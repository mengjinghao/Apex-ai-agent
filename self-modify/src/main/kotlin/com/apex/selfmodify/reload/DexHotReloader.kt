package com.apex.selfmodify.reload

import com.apex.selfmodify.workspace.FileChange
import dalvik.system.DexClassLoader
import java.io.File

/**
 * Hot-reloads Kotlin classes via [dalvik.system.DexClassLoader].
 *
 * Per AGENT_SELF_MODIFY_SPEC §4.3: Kotlin classes (no new signatures) can be
 * hot-swapped by compiling the changed .kt → .class → .dex, then loading the
 * new dex via DexClassLoader and replacing instances.
 *
 * **Phase 5b status — Option D (framework with documented limitation).**
 *
 * A stock Android device does NOT ship `kotlinc` or `d8` on PATH, so the full
 * .kt → .class → .dex → DexClassLoader pipeline cannot run end-to-end without
 * one of the following (deferred to a future phase):
 *   - **Option A**: ship `kotlinc` as a native binary (~50 MB — too heavy).
 *   - **Option B**: remote compile service (send .kt, receive .dex).
 *   - **Option C**: Android's `javac` + `d8` (not standard on device).
 *
 * This implementation:
 *   1. Auto-detects `kotlinc` (and an optional explicit [kotlincPath]).
 *   2. If available: runs the full compile → d8 → DexClassLoader chain.
 *   3. If NOT available: returns [ReloadResult.Partial] with a clear message
 *      explaining the limitation and suggesting an app restart.
 *
 * Constructor is backward-compatible with the Phase 3 call site
 * `DexHotReloader(workspace.config.indexDir)` — [kotlincPath] defaults to null
 * (auto-detect via `which kotlinc`).
 *
 * @param cacheDir     writable cache directory for .class / .dex output.
 *                     Typically `workspace.config.indexDir`.
 * @param kotlincPath  explicit path to a `kotlinc` binary, or null to auto-detect.
 */
class DexHotReloader(
    private val cacheDir: File,
    private val kotlincPath: String? = null
) : HotReloader {

    override fun canHotReload(file: File): Boolean = file.extension == "kt"

    override suspend fun reload(files: List<FileChange>): ReloadResult {
        val ktFiles = files.filter { canHotReload(File(it.path)) }
        if (ktFiles.isEmpty()) return ReloadResult.Failure("No hot-reloadable files")

        val compiler = findKotlinc()
        if (compiler == null) {
            // Option D fallback: kotlinc not on device. Report Partial so the
            // PlanExecutor flow can proceed without blocking on reload; the new
            // code will take effect on the next app restart.
            return ReloadResult.Partial(
                reloaded = emptyList(),
                failed = ktFiles.map { it.path },
                reason = "kotlinc not available on device — cannot compile .kt to .dex. " +
                    "Options: (1) restart app to load new code, (2) ship kotlinc binary, " +
                    "(3) use remote compile service. See spec §4.3 / §8.2."
            )
        }

        val reloaded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val dexOutputDir = File(cacheDir, "dex_${System.currentTimeMillis()}").apply { mkdirs() }
        val d8Path = findD8()

        for (change in ktFiles) {
            try {
                val sourceFile = File(change.path)
                if (!sourceFile.exists()) {
                    failed.add(change.path)
                    continue
                }

                // 1. Compile .kt → .class
                val classOutputDir = File(dexOutputDir, "classes").apply { mkdirs() }
                val compileProc = ProcessBuilder(
                    compiler, "-classpath", androidClasspath(),
                    "-d", classOutputDir.absolutePath, sourceFile.absolutePath
                ).redirectErrorStream(true).start()
                val compileOut = compileProc.inputStream.bufferedReader().use { it.readText() }
                val compileExit = compileProc.waitFor()
                if (compileExit != 0) {
                    failed.add(change.path)
                    continue
                }

                // 2. .class → .dex via d8 (if available)
                if (d8Path == null) {
                    failed.add(change.path)
                    continue
                }
                val classFiles = classOutputDir.walkTopDown().filter { it.extension == "class" }.toList()
                if (classFiles.isEmpty()) {
                    failed.add(change.path)
                    continue
                }
                val dexFile = File(dexOutputDir, "classes.dex")
                val d8Args = mutableListOf(d8Path, "--output", dexFile.absolutePath)
                d8Args.addAll(classFiles.map { it.absolutePath })
                val d8Proc = ProcessBuilder(d8Args).redirectErrorStream(true).start()
                val d8Out = d8Proc.inputStream.bufferedReader().use { it.readText() }
                val d8Exit = d8Proc.waitFor()
                if (d8Exit != 0 || !dexFile.exists()) {
                    failed.add(change.path)
                    continue
                }

                // 3. DexClassLoader — load the class by deriving FQN from path
                val classLoader = DexClassLoader(
                    dexFile.absolutePath, dexOutputDir.absolutePath,
                    null, javaClass.classLoader
                )
                val className = deriveClassName(change.path)
                if (className != null) {
                    classLoader.loadClass(className)
                    reloaded.add(className)
                } else {
                    failed.add(change.path)
                }
            } catch (e: Exception) {
                failed.add(change.path)
            }
        }

        return if (failed.isEmpty() && reloaded.isNotEmpty()) {
            ReloadResult.Success(reloaded)
        } else if (reloaded.isNotEmpty()) {
            ReloadResult.Partial(reloaded, failed, "Partial reload: ${reloaded.size} ok, ${failed.size} failed")
        } else {
            ReloadResult.Partial(reloaded, failed, "All ${failed.size} file(s) failed to reload")
        }
    }

    /**
     * Locate a `kotlinc` binary: explicit [kotlincPath] first, else `which kotlinc`.
     * Returns null if not found (the common case on stock Android).
     */
    private fun findKotlinc(): String? {
        kotlincPath?.let { return it }
        return try {
            val p = ProcessBuilder("which", "kotlinc").start()
            val out = p.inputStream.bufferedReader().use { it.readText().trim() }
            if (p.waitFor() == 0 && out.isNotEmpty()) out else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Locate the `d8` (or `dx`) dexer binary on PATH. Returns null if unavailable.
     */
    private fun findD8(): String? {
        for (cmd in listOf("d8", "dx")) {
            try {
                val p = ProcessBuilder("which", cmd).start()
                val out = p.inputStream.bufferedReader().use { it.readText().trim() }
                if (p.waitFor() == 0 && out.isNotEmpty()) return out
            } catch (e: Exception) {
                // try next
            }
        }
        return null
    }

    /**
     * Best-effort classpath for kotlinc. On Android this is typically the app's
     * runtime classpath; a real implementation would assemble the SDK + dependency
     * jars. Left as a stub — only relevant when kotlinc IS present.
     */
    private fun androidClasspath(): String {
        return System.getProperty("java.class.path") ?: ""
    }

    /**
     * Derive the fully-qualified class name from a source path.
     * `/workspace/src/com/apex/Foo.kt` → `com.apex.Foo`.
     * Returns null if the path doesn't contain a `/src/` segment.
     */
    private fun deriveClassName(path: String): String? {
        val srcIndex = path.indexOf("/src/")
        if (srcIndex < 0) return null
        val rel = path.substring(srcIndex + 5) // after "/src/"
        return rel.removeSuffix(".kt").replace("/", ".")
    }
}
