package com.apex.core.tools.adapters

import com.apex.agent.core.security.InputSanitizer
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 智能网页正文爬取引擎
 * 轻量无浏览器，自动提纯正文内?
 */
object SmartFetchEngine {
    private const val TAG = "SmartFetchEngine"
    private val inputSanitizer = InputSanitizer()

    /**
     * 爬取结果
     */
    data class FetchResult(
        val url: String,
        val pureContent: String,
        val fetchTime: String,
        val mode: String,
        val success: Boolean
    )

    // 内容缓存
    private val contentCache = ConcurrentHashMap<String, CachedContent>()
    private const val CACHE_EXPIRE = 20 * 60 * 1000L // 20分钟
    private const val MAX_CACHE_SIZE = 60
    private const val CLEANUP_BATCH_SIZE = 20
    private const val MAX_CONTENT_LENGTH = 15000

    private data class CachedContent(
        val data: FetchResult,
        val timestamp: Long
    )

    // OkHttp客户?
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 清理过期缓存
     */
    private fun cleanExpiredCache() {
        val currentTime = System.currentTimeMillis()
        val expiredKeys = contentCache.filterValues { 
            currentTime - it.timestamp >= CACHE_EXPIRE
        }.keys
        expiredKeys.forEach { contentCache.remove(it) }
        if (expiredKeys.isNotEmpty()) {
            AppLogger.d(TAG, "清理?${expiredKeys.size} 个过期缓?)
        }
    }

    /**
     * 清理超过大小限制的缓?
     */
    private fun cleanOldCacheIfNeeded() {
        if (contentCache.size >= MAX_CACHE_SIZE) {
            cleanExpiredCache()
            if (contentCache.size >= MAX_CACHE_SIZE) {
                // 按时间排序，删除最旧的缓存
                val sortedKeys = contentCache.entries
                    .sortedBy { it.value.timestamp }
                    .take(CLEANUP_BATCH_SIZE)
                    .map { it.key }
                sortedKeys.forEach { contentCache.remove(it) }
                AppLogger.d(TAG, "清理?${sortedKeys.size} 个旧缓存")
            }
        }
    }

    /**
     * 验证URL格式
     */
    private fun isValidUrl(url: String): Boolean {
        return try {
            val cleanUrl = url.trim()
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                false
            } else {
                val uri = java.net.URI.create(cleanUrl)
                uri.host != null && uri.host.isNotEmpty()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 爬取网页正文
     * @param url 网页链接
     * @return 爬取结果
     */
    suspend fun fetch(url: String): FetchResult = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        
        // 边界情况：空URL
        if (cleanUrl.isEmpty()) {
            return@withContext FetchResult(
                url = cleanUrl,
                pureContent = "URL不能为空",
                fetchTime = getCurrentTime(),
                mode = "失败",
                success = false
            )
        }
        
        // 边界情况：URL格式验证
        if (!isValidUrl(cleanUrl)) {
            return@withContext FetchResult(
                url = cleanUrl,
                pureContent = "无效的URL格式，请输入有效的http或https链接",
                fetchTime = getCurrentTime(),
                mode = "失败",
                success = false
            )
        }

        // 检查缓?
        contentCache[cleanUrl]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < CACHE_EXPIRE) {
                AppLogger.d(TAG, "使用缓存: ${cleanUrl}")
                return@withContext cached.data
            } else {
                contentCache.remove(cleanUrl)
            }
        }

        AppLogger.d(TAG, "开始爬? ${cleanUrl}")

        try {
            // 构建请求
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            // 执行请求
            val response = client.newCall(request).execute()
            
            // 检查响应状?
            if (!response.isSuccessful) {
                return@withContext FetchResult(
                    url = cleanUrl,
                    pureContent = "请求失败，状态码: ${response.code}",
                    fetchTime = getCurrentTime(),
                    mode = "失败",
                    success = false
                )
            }
            
            // 检查Content-Type
            val contentType = response.header("Content-Type") ?: ""
            if (!contentType.contains("text/html") && !contentType.contains("application/xhtml")) {
                return@withContext FetchResult(
                    url = cleanUrl,
                    pureContent = "该链接不是HTML网页，无法提取内?,
                    fetchTime = getCurrentTime(),
                    mode = "失败",
                    success = false
                )
            }
            
            val html = response.body?.string() ?: ""
            
            // 检查HTML内容
            if (html.isEmpty()) {
                return@withContext FetchResult(
                    url = cleanUrl,
                    pureContent = "无法获取网页内容，请检查网络连?,
                    fetchTime = getCurrentTime(),
                    mode = "失败",
                    success = false
                )
            }
            
            // 检查HTML大小
            if (html.length > 5 * 1024 * 1024) {
                return@withContext FetchResult(
                    url = cleanUrl,
                    pureContent = "网页过大，无法处?,
                    fetchTime = getCurrentTime(),
                    mode = "失败",
                    success = false
                )
            }

            // 提纯正文内容
            val rawContent = extractPureContent(html)

            // 对提取的内容进行安全消毒
            val pureContent = try {
                val sanitizeResult = inputSanitizer.sanitize(rawContent)
                if (sanitizeResult.findings.isNotEmpty()) {
                    AppLogger.d(TAG, "网页内容消毒完成: 发现${sanitizeResult.findings.size}个安全问?)
                }
                sanitizeResult.sanitizedText
            } catch (e: Exception) {
                AppLogger.e(TAG, "网页内容消毒失败，使用原始内?, e)
                // 消毒失败时使用原始内容，不阻断流?
                rawContent
            }

            val result = FetchResult(
                url = cleanUrl,
                pureContent = pureContent,
                fetchTime = getCurrentTime(),
                mode = "轻量无浏览器抓取",
                success = true
            )

            // 清理旧缓存并存储新结?
            cleanOldCacheIfNeeded()
            contentCache[cleanUrl] = CachedContent(result, System.currentTimeMillis())

            AppLogger.d(TAG, "爬取完成，内容长? ${pureContent.length}")
            return@withContext result

        } catch (e: java.net.SocketTimeoutException) {
            AppLogger.e(TAG, "爬取超时", e)
            return@withContext FetchResult(
                url = cleanUrl,
                pureContent = "请求超时，请检查网络连接后重试",
                fetchTime = getCurrentTime(),
                mode = "失败",
                success = false
            )
        } catch (e: java.net.UnknownHostException) {
            AppLogger.e(TAG, "域名解析失败", e)
            return@withContext FetchResult(
                url = cleanUrl,
                pureContent = "无法解析域名，请检查URL是否正确",
                fetchTime = getCurrentTime(),
                mode = "失败",
                success = false
            )
        } catch (e: java.net.MalformedURLException) {
            AppLogger.e(TAG, "URL格式错误", e)
            return@withContext FetchResult(
                url = cleanUrl,
                pureContent = "URL格式错误，请输入有效的链?,
                fetchTime = getCurrentTime(),
                mode = "失败",
                success = false
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "爬取失败", e)
            return@withContext FetchResult(
                url = cleanUrl,
                pureContent = "网页读取失败: ${e.message ?: "未知错误"}",
                fetchTime = getCurrentTime(),
                mode = "失败",
                success = false
            )
        }
    }

    /**
     * 提取网页正文内容（极简版）
     */
    private fun extractPureContent(html: String): String {
        // 安全检查：输入为空
        if (html.isEmpty()) {
            return ""
        }
        
        var text = html

        // 步骤1：移除脚本和样式（带异常保护?
        try {
            text = text.replace(Regex("""<script[\s\S]*?<\/script>""", RegexOption.IGNORE_CASE), "")
            text = text.replace(Regex("""<style[\s\S]*?<\/style>""", RegexOption.IGNORE_CASE), "")
            text = text.replace(Regex("""<noscript[\s\S]*?<\/noscript>""", RegexOption.IGNORE_CASE), "")
            text = text.replace(Regex("""<iframe[\s\S]*?<\/iframe>""", RegexOption.IGNORE_CASE), "")
        } catch (e: Exception) {
            AppLogger.w(TAG, "移除脚本样式时出?, e)
        }

        // 步骤2：尝试找到主要内容区?
        val mainContent = tryExtractMainContent(text)
        if (mainContent.isNotEmpty()) {
            text = mainContent
        }

        // 步骤3：移除所有HTML标签
        try {
            text = text.replace(Regex("""<[^>]+>"""), " ")
        } catch (e: Exception) {
            AppLogger.w(TAG, "移除HTML标签时出?, e)
        }

        // 步骤4：HTML实体解码
        try {
            text = text.replace(Regex("""&nbsp;"""), " ")
            text = text.replace(Regex("""&lt;"""), "<")
            text = text.replace(Regex("""&gt;"""), ">")
            text = text.replace(Regex("""&amp;"""), "&")
            text = text.replace(Regex("""&quot;"""), "\"")
            text = text.replace(Regex("""&apos;"""), "'")
        } catch (e: Exception) {
            AppLogger.w(TAG, "HTML解码时出?, e)
        }

        // 步骤5：清理空白字?
        try {
            text = text.replace(Regex("""[ \t]+"""), " ")
            text = text.replace(Regex("""\n\s*"""), "\n")
            text = text.replace(Regex("""\n{3,}"""), "\n\n")
        } catch (e: Exception) {
            AppLogger.w(TAG, "清理空白时出?, e)
        }

        // 步骤6：保留有效内?
        val lines = text.split("\n")
            .map { it.trim() }
            .filter { it.length > 10 } // 过滤太短的行
            .take(300) // 限制行数

        text = lines.joinToString("\n")

        // 步骤7：截断冗?
        if (text.length > MAX_CONTENT_LENGTH) {
            text = text.take(MAX_CONTENT_LENGTH)
            // 尝试在完整句子处截断
            val lastPeriod = text.lastIndexOf("?)
            val lastComma = text.lastIndexOf("?)
            val lastDot = text.lastIndexOf(".")
            val cutPoint = listOf(lastPeriod, lastComma, lastDot).maxOrNull() ?: MAX_CONTENT_LENGTH
            if (cutPoint > MAX_CONTENT_LENGTH * 0.7) {
                text = text.take(cutPoint + 1)
            }
            text += "......(内容已截断）"
        }

        return text.trim()
    }

    /**
     * 尝试提取主要内容区域
     */
    private fun tryExtractMainContent(html: String): String {
        // 常见的主要内容区域标?
        val contentSelectors = listOf(
            """<article[\s\S]*?<\/article>""",
            """<main[\s\S]*?<\/main>""",
            """<div[^>]*class="[^"]*content[^"]*"[\s\S]*?<\/div>""",
            """<div[^>]*class="[^"]*main[^"]*"[\s\S]*?<\/div>""",
            """<div[^>]*id="[^"]*content[^"]*"[\s\S]*?<\/div>""",
            """<div[^>]*id="[^"]*main[^"]*"[\s\S]*?<\/div>""",
            """<div[^>]*class="[^"]*post[^"]*"[\s\S]*?<\/div>""",
            """<div[^>]*class="[^"]*article[^"]*"[\s\S]*?<\/div>""",
            """<section[\s\S]*?<\/section>"""
        )

        for (selector in contentSelectors) {
            try {
                val match = Regex(selector, RegexOption.IGNORE_CASE).find(html)
                if (match != null) {
                    val content = match.value
                    // 检查内容长度，确保是有效内容区?
                    if (content.length > 500) {
                        return content
                    }
                }
            } catch (e: Exception) {
                // 忽略
            }
        }

        return ""
    }

    /**
     * 获取当前时间
     */
    private fun getCurrentTime(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())
    }

    /**
     * 格式化爬取结?
     */
    fun formatResult(result: FetchResult): String {
        if (!result.success) {
            return "网页抓取失败: ${result.pureContent}"
        }

        return buildString {
            append("📄 网页内容?{result.fetchTime}）：\n")
            append("🔗 ${result.url}\n")
            append("───────────────────────\n\n")
            append(result.pureContent)
            append("\n\n───────────────────────\n")
            append("内容长度: ${result.pureContent.length} 字符")
        }
    }
}