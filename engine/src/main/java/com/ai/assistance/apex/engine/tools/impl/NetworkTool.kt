package com.ai.assistance.apex.engine.tools.impl

import android.content.Context
import com.ai.assistance.apex.engine.model.ExecutionResult
import com.ai.assistance.apex.engine.tools.Tool
import com.ai.assistance.apex.engine.tools.errorResult
import com.ai.assistance.apex.engine.tools.parseArgs
import com.ai.assistance.apex.engine.tools.successResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit

class NetworkTool(private val context: Context? = null) : Tool {
    override val name = "network"
    override val description = "HTTP network operations tool for GET, POST requests and file downloads"
    override val category = "network"
    override val parameters = arrayOf("operation", "url", "body", "headers", "path")
    override val requiresRoot = false

    /**
     * Body size limit: 10 MiB. Prevents unbounded memory consumption from a
     * malicious / oversized remote response (H-4: resource exhaustion).
     */
    private val maxBodyBytes = 10L * 1024L * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Security (H-1/H-2): disable auto-redirects so we cannot be silently
        // bounced to an internal/private host. Callers must opt in to following
        // redirects and each hop will be re-validated.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Returns true if the host resolves to a private / loopback / link-local /
     * any-local address, OR is a well-known cloud-metadata endpoint.
     *
     * Security (H-1/H-2): SSRF protection — blocks AI tools from reaching
     * 127.0.0.1, 10/8, 172.16/12, 192.168/16, 169.254/16, link-local v6,
     * and cloud-metadata IPs (169.254.169.254, metadata.google.internal).
     */
    private fun isPrivateIp(host: String): Boolean {
        return try {
            val addr = InetAddress.getByName(host)
            addr.isLoopbackAddress ||
                addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress ||
                addr.isAnyLocalAddress ||
                host == "169.254.169.254" ||
                host == "metadata.google.internal" ||
                host == "metadata" ||
                host.endsWith(".internal")
        } catch (e: Exception) {
            // Be conservative: if we cannot resolve, treat as private to avoid
            // SSRF via deliberately-unresolvable-but-still-routable tricks.
            // (OkHttp will independently fail on connect.)
            true
        }
    }

    /**
     * Validates a URL against the SSRF allowlist. Returns null if the URL is
     * safe to fetch, or an error message string if it must be rejected.
     */
    private fun validateUrl(urlStr: String): String? {
        return try {
            val parsed = URL(urlStr)
            val proto = parsed.protocol?.lowercase()
            if (proto != "http" && proto != "https") {
                return "Blocked: only http/https URLs are allowed (got '$proto')"
            }
            val host = parsed.host ?: return "Blocked: URL has no host"
            if (isPrivateIp(host)) {
                return "Blocked: SSRF protection — cannot access private/internal IPs (host='$host')"
            }
            null
        } catch (e: Exception) {
            "Blocked: invalid URL — ${e.message}"
        }
    }

    /** Reads at most [maxBodyBytes] from the response body, appending "[...truncated]" if cut. */
    private fun readBodyCapped(response: okhttp3.Response): String {
        return response.body?.byteStream()?.use { stream ->
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            var truncated = false
            while (total < maxBodyBytes) {
                val n = stream.read(buf)
                if (n < 0) break
                baos.write(buf, 0, n)
                total += n
            }
            if (stream.read() >= 0) truncated = true
            val text = baos.toString(Charsets.UTF_8.name())
            if (truncated) "$text\n[...truncated at ${maxBodyBytes} bytes]" else text
        } ?: ""
    }

    override fun execute(args: String): ExecutionResult {
        val params = parseArgs(args)
        val operation = params["operation"]?.lowercase() ?: "get"
        val url = params["url"]

        if (url.isNullOrEmpty()) {
            return errorResult("URL is required")
        }

        return when (operation) {
            "get", "http_get" -> httpGet(url, params["headers"])
            "post", "http_post" -> httpPost(url, params["body"], params["headers"])
            "download" -> downloadFile(url, params["path"])
            else -> errorResult("Unknown operation: $operation")
        }
    }

    private fun httpGet(url: String, headers: String?): ExecutionResult {
        val blockReason = validateUrl(url)
        if (blockReason != null) return errorResult(blockReason)

        return try {
            val requestBuilder = Request.Builder().url(url).get()

            headers?.split(";")?.forEach { header ->
                val parts = header.split(":", limit = 2)
                if (parts.size == 2) {
                    requestBuilder.addHeader(parts[0].trim(), parts[1].trim())
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = readBodyCapped(response)

            ExecutionResult().apply {
                exitCode = if (response.isSuccessful) 0 else response.code
                output = body
                error = if (!response.isSuccessful) response.message else ""
                success = response.isSuccessful
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "HTTP GET failed")
        }
    }

    private fun httpPost(url: String, body: String?, headers: String?): ExecutionResult {
        val blockReason = validateUrl(url)
        if (blockReason != null) return errorResult(blockReason)

        return try {
            val requestBuilder = Request.Builder().url(url)
                .post((body ?: "").toRequestBody())

            headers?.split(";")?.forEach { header ->
                val parts = header.split(":", limit = 2)
                if (parts.size == 2) {
                    requestBuilder.addHeader(parts[0].trim(), parts[1].trim())
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = readBodyCapped(response)

            ExecutionResult().apply {
                exitCode = if (response.isSuccessful) 0 else response.code
                output = responseBody
                error = if (!response.isSuccessful) response.message else ""
                success = response.isSuccessful
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "HTTP POST failed")
        }
    }

    private fun downloadFile(url: String, path: String?): ExecutionResult {
        if (path.isNullOrEmpty()) {
            return errorResult("Path is required for download")
        }

        val blockReason = validateUrl(url)
        if (blockReason != null) return errorResult(blockReason)

        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return ExecutionResult().apply {
                    exitCode = response.code
                    error = response.message
                    success = false
                }
            }

            val file = File(path)
            file.parentFile?.mkdirs()

            // H-4: cap downloaded file size at 10 MiB as well.
            response.body?.byteStream()?.use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (total < maxBodyBytes) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        total += n
                    }
                    if (input.read() >= 0) {
                        return@use // discard remainder — caller sees file truncated at cap
                    }
                }
            }

            ExecutionResult().apply {
                exitCode = 0
                output = "Downloaded to $path (${file.length()} bytes)"
                success = true
            }
        } catch (e: Exception) {
            errorResult(e.message ?: "Download failed")
        }
    }

}
