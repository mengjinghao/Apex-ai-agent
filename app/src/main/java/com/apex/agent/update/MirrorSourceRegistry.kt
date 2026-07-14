package com.apex.agent.update

import android.content.Context
import com.apex.util.AppLogger
import com.apex.sdk.storage.ApexDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 镜像源注册表。
 *
 * 维护两类镜像：
 * 1. **内置镜像** — 经过社区验证的免费 GitHub 加速镜像，用户不可删除但可禁用；
 * 2. **自定义镜像** — 用户自行添加的镜像，持久化到 [ApexDataStore]。
 *
 * 镜像顺序即下载回退顺序。下载时按顺序尝试每个启用的镜像，首个成功即用。
 *
 * 使用方式：
 * ```kotlin
 * val registry = MirrorSourceRegistry.getInstance(context)
 * registry.mirrorsFlow.collect { list -> ... }
 * registry.addCustom(MirrorSource(...))
 * registry.remove("custom-id")
 * ```
 */
class MirrorSourceRegistry private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MirrorSourceRegistry"
        private const val DATA_KEY = "update.mirror.custom_list"

        @Volatile private var INSTANCE: MirrorSourceRegistry? = null
        fun getInstance(context: Context): MirrorSourceRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MirrorSourceRegistry(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** 内置免费 GitHub 加速镜像。模板中 `{url}` 会被替换为原始 GitHub 下载地址。 */
        val BUILTIN_MIRRORS: List<MirrorSource> = listOf(
            MirrorSource(
                id = "direct",
                name = "GitHub 直连",
                urlTemplate = "{url}",
                builtin = true,
                description = "不经过任何代理，原始 GitHub 地址"
            ),
            MirrorSource(
                id = "ghproxy",
                name = "ghproxy.com",
                urlTemplate = "https://ghproxy.com/{url}",
                builtin = true,
                description = "老牌 GitHub 加速，国内可用"
            ),
            MirrorSource(
                id = "ghproxy-net",
                name = "mirror.ghproxy.com",
                urlTemplate = "https://mirror.ghproxy.com/{url}",
                builtin = true,
                description = "ghproxy 备用节点"
            ),
            MirrorSource(
                id = "ghps",
                name = "ghps.cc",
                urlTemplate = "https://ghps.cc/{url}",
                builtin = true,
                description = "Free CDN mirror, fast in mainland China"
            ),
            MirrorSource(
                id = "moeyy",
                name = "github.moeyy.xyz",
                urlTemplate = "https://github.moeyy.xyz/{url}",
                builtin = true,
                description = "moeyy 加速镜像"
            ),
            MirrorSource(
                id = "gh-proxy",
                name = "gh-proxy.com",
                urlTemplate = "https://gh-proxy.com/{url}",
                builtin = true,
                description = "公益 GitHub 代理"
            ),
            MirrorSource(
                id = "kkgithub",
                name = "kkgithub.com",
                urlTemplate = "https://kkgithub.com/{url}",
                builtin = true,
                description = "通过替换域名的镜像（仅 github.com 路径有效）"
            ),
            MirrorSource(
                id = "gcore",
                name = "gh.api.99988866.xyz",
                urlTemplate = "https://gh.api.99988866.xyz/{url}",
                builtin = true,
                description = "另一公益加速节点"
            )
        )

        /** kkgithub 实际上是替换 host，需要单独处理。 */
        internal fun applyKkGithub(originalUrl: String): String {
            return originalUrl
                .replace("https://github.com/", "https://kkgithub.com/")
                .replace("http://github.com/", "http://kkgithub.com/")
        }
    }

    private val _mirrorsFlow = MutableStateFlow<List<MirrorSource>>(emptyList())
    val mirrorsFlow: StateFlow<List<MirrorSource>> = _mirrorsFlow.asStateFlow()

    /** 当前镜像快照（内置 + 自定义）。 */
    val mirrors: List<MirrorSource> get() = _mirrorsFlow.value

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    init {
        // 立即填充内置镜像，让 UI 先有数据可用；自定义镜像异步加载后会覆盖。
        _mirrorsFlow.value = BUILTIN_MIRRORS
    }

    /**
     * 从 [ApexDataStore] 加载自定义镜像并合并到当前列表。
     * 应在 Application 启动或设置页打开时调用一次。
     */
    suspend fun load() {
        try {
            val raw = ApexDataStore.getStringSync(context, DATA_KEY, default = "")
            val custom = if (raw.isBlank()) {
                emptyList()
            } else {
                runCatching {
                    json.decodeFromString(ListSerializer(MirrorSource.serializer()), raw)
                }.getOrElse {
                    AppLogger.w(TAG, "解析自定义镜像失败，重置为空: ${it.message}")
                    emptyList()
                }
            }
            _mirrorsFlow.value = (BUILTIN_MIRRORS + custom)
            AppLogger.i(TAG, "镜像源加载完成：内置 ${BUILTIN_MIRRORS.size} + 自定义 ${custom.size}")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "加载镜像源失败", t)
            _mirrorsFlow.value = BUILTIN_MIRRORS
        }
    }

    /** 返回所有启用的镜像（按列表顺序，用于下载回退）。 */
    suspend fun enabledMirrors(): List<MirrorSource> {
        return mirrors.filter { it.enabled }
    }

    /** 添加一个自定义镜像。若 id 已存在则替换。 */
    suspend fun addCustom(mirror: MirrorSource) {
        val current = _mirrorsFlow.value
        val customOnly = current.filter { !it.builtin && it.id != mirror.id } + mirror.copy(builtin = false)
        persistCustom(customOnly)
        _mirrorsFlow.value = BUILTIN_MIRRORS + customOnly
        AppLogger.i(TAG, "已添加自定义镜像：${mirror.name} (${mirror.id})")
    }

    /** 删除一个自定义镜像（内置镜像不可删除）。 */
    suspend fun remove(id: String) {
        val current = _mirrorsFlow.value
        val target = current.firstOrNull { it.id == id }
        if (target == null) {
            AppLogger.w(TAG, "删除镜像失败：未找到 id=$id")
            return
        }
        if (target.builtin) {
            AppLogger.w(TAG, "内置镜像不可删除：$id")
            return
        }
        val customOnly = current.filter { !it.builtin && it.id != id }
        persistCustom(customOnly)
        _mirrorsFlow.value = BUILTIN_MIRRORS + customOnly
        AppLogger.i(TAG, "已删除自定义镜像：$id")
    }

    /** 启用/禁用镜像。 */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        val current = _mirrorsFlow.value
        val updated = current.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        val customOnly = updated.filter { !it.builtin }
        persistCustom(customOnly)
        _mirrorsFlow.value = BUILTIN_MIRRORS + customOnly
        AppLogger.i(TAG, "镜像 $id enabled=$enabled")
    }

    /** 移动自定义镜像顺序（仅对自定义镜像生效，内置镜像顺序固定在前）。 */
    suspend fun moveCustom(fromIndex: Int, toIndex: Int) {
        val current = _mirrorsFlow.value
        val customOnly = current.filter { !it.builtin }.toMutableList()
        if (fromIndex !in customOnly.indices || toIndex !in customOnly.indices) return
        val item = customOnly.removeAt(fromIndex)
        customOnly.add(toIndex, item)
        persistCustom(customOnly)
        _mirrorsFlow.value = BUILTIN_MIRRORS + customOnly
    }

    private suspend fun persistCustom(custom: List<MirrorSource>) {
        val raw = json.encodeToString(ListSerializer(MirrorSource.serializer()), custom)
        ApexDataStore.putString(context, DATA_KEY, raw)
    }
}
