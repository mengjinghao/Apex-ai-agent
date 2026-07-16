package com.apex.core.tools.validation

import android.content.Context
import android.os.Build
import com.apex.core.tools.PackagePermission
import com.apex.core.tools.ToolPackage
import com.apex.util.AppLogger
import java.util.regex.Pattern

class SkillSecurityScanner(private val context: Context) {

    companion object {
        private const val TAG = "SkillSecurityScanner"

        private val DANGEROUS_PATTERNS = listOf(
            DangerPatternInfo(
                DangerPatternType.CODE_INJECTION,
            RiskLevel.CRITICAL,
                "eval\\s*\\([^)]+\\)",
                "Use of eval() - code injection risk"
            ),
            DangerPatternInfo(
                DangerPatternType.COMMAND_INJECTION,
                RiskLevel.CRITICAL,
                "(?:exec|spawn|execSync)\\s*\\([^)]*(?:\\+|\\$\\{)",
                "Potential command injection through string concatenation"
            ),
            DangerPatternInfo(
                DangerPatternType.RUNTIME_EXEC,
                RiskLevel.HIGH,
                "Runtime\\s*\\.\\s*exec\\s*\\(",
                "Runtime.exec() detected - shell command execution"
            ),
            DangerPatternInfo(
                DangerPatternType.REFLECTION_ABUSE,
                RiskLevel.MEDIUM,
                "(?:reflect|reflection)\\s*\\.[\\s\\S]{0,50}getDeclaredMethod|getMethod\\s*\\(",
                "Reflection API usage - potential method invocation"
            ),
            DangerPatternInfo(
                DangerPatternType.DANGEROUS_PERMISSION,
                RiskLevel.HIGH,
                "RECEIVE_BOOT_COMPLETED|SYSTEM_ALERT_WINDOW",
                "Dangerous permission request detected"
            ),
            DangerPatternInfo(
                DangerPatternType.HARDCODED_SECRET,
                RiskLevel.MEDIUM,
                "(?:password|secret|api[_-]?key|token)\\s*[:=]\\s*['\"][^'\"]{8,}['\"]",
                "Potential hardcoded secret detected"
            ),
            DangerPatternInfo(
                DangerPatternType.INSECURE_CRYPTO,
                RiskLevel.MEDIUM,
                "DES\\s*\\(|MD5\\s*\\(|SHA1\\s*\\(",
                "Insecure cryptographic algorithm"
            ),
            DangerPatternInfo(
                DangerPatternType.LOCAL_FILE_INCLUSION,
                RiskLevel.HIGH,
                "fs\\s*\\.\\s*read(?:File|Sync)\\s*\\([^)]*(?:\\+|\\$\\{)",
                "Potential local file inclusion via string concatenation"
            ),
            DangerPatternInfo(
                DangerPatternType.DYNAMIC_CODE_LOADING,
                RiskLevel.CRITICAL,
                "new\\s+Function\\s*\\(|Function\\s*\\([^)]*\\)",
                "Dynamic code creation via new Function()"
            ),
            DangerPatternInfo(
                DangerPatternType.NATIVE_CODE_ACCESS,
                RiskLevel.HIGH,
                "\\.\\s*exec\\s*\\(|\\.\\s*spawn\\s*\\(|child_process",
                "Native process execution capability"
            ),
            DangerPatternInfo(
                DangerPatternType.PROCESS_INFO_LEAK,
                RiskLevel.LOW,
                "process\\s*.\\s*(?:env|argv|title)",
                "Process information access"
            ),
            DangerPatternInfo(
                DangerPatternType.EVAL_USAGE,
                RiskLevel.CRITICAL,
                "\\beval\\b",
                "Direct eval() usage detected"
            ),
            DangerPatternInfo(
                DangerPatternType.OBFUSCATED_CODE,
                RiskLevel.MEDIUM,
                "(?:atob\\s*\\(|btoa\\s*\\(|String\\s*\\.\\s*fromCharCode)",
                "Potential code obfuscation using base64 or char codes"
            ),
            DangerPatternInfo(
                DangerPatternType.SUSPICIOUS_FUNCTION,
                RiskLevel.LOW,
                "setTimeout\\s*\\(\\s*(?:function|\\(|\\d)|setInterval\\s*\\(\\s*(?:function|\\(|\\d)",
                "Dynamic timeout/interval with suspicious content"
            )
        )

        private val SENSITIVE_APIS = listOf(
            SensitiveApiInfo("java.lang.Runtime.exec", RiskLevel.HIGH, "Shell command execution"),
            SensitiveApiInfo("java.lang.reflect.Method.invoke", RiskLevel.MEDIUM, "Dynamic method invocation"),
            SensitiveApiInfo("java.lang.Class.forName", RiskLevel.MEDIUM, "Dynamic class loading"),
            SensitiveApiInfo("android.content.pm.PackageManager.getInstalledPackages", RiskLevel.LOW, "Package enumeration"),
            SensitiveApiInfo("android.app.ActivityManager.killBackgroundProcesses", RiskLevel.HIGH, "Process termination"),
            SensitiveApiInfo("android.os.Process.killProcess", RiskLevel.HIGH, "Process kill"),
            SensitiveApiInfo("dalvik.system.VMStack.getThreadStackTrace", RiskLevel.LOW, "Stack trace access"),
            SensitiveApiInfo("java.lang.System.exit", RiskLevel.MEDIUM, "VM termination"),
            SensitiveApiInfo("android.net.ConnectivityManager.getActiveNetworkInfo", RiskLevel.LOW, "Network state access"),
            SensitiveApiInfo("java.net.HttpURLConnection", RiskLevel.LOW, "HTTP connections"),
            SensitiveApiInfo("okhttp3.OkHttpClient", RiskLevel.LOW, "Network requests via OkHttp")
        )

        private val SUSPICIOUS_URL_PATTERNS = listOf(
            Pattern.compile("https?://[\\w.-]+(?:\\.[\\w.-]+)+[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=]+(?:\\.[a-z]{2,})?/"),
            Pattern.compile("https?://(?:\\d{1,3}\\.){3}\\d{1,3}"),
            Pattern.compile("https?://localhost(?:\\:\\d+)?"),
            Pattern.compile("https?://(?:10\\.|172\\.(?:1[6-9]|2[0-9]|3[01])\\.|192\\.168\\.)"),
            Pattern.compile("file://"),
            Pattern.compile("content://")
        )

        private val DANGEROUS_FILE_OPERATIONS = listOf(
            Pair(Pattern.compile("(?:readFile|readFileSync|readdir|readdirSync)\\s*\\(['\"](?:/system|/data|/proc)"), "Reading sensitive system directories"),
            Pair(Pattern.compile("(?:writeFile|writeFileSync|mkdir|rmdir|unlink)\\s*\\(['\"](?:/system|/data/data|/data/local)"), "Writing to sensitive directories"),
            Pair(Pattern.compile("(?:chmod|chown)\\s*\\([^)]*(?:0[0-7]{3}|7[0-7][0-7])"), "Dangerous file permission modification")
        )
    }

    private data class DangerPatternInfo(
        val type: DangerPatternType,
        val severity: RiskLevel,
        val regex: String,
        val suggestion: String
    )

    private data class SensitiveApiInfo(
        val apiName: String,
        val severity: RiskLevel,
        val description: String
    )

    fun scan(toolPackage: ToolPackage): SecurityReport {
        val scriptContent = extractScriptContent(toolPackage)
        val dangerPatterns = mutableListOf<DangerPattern>()
        val sensitiveApiCalls = mutableListOf<SensitiveApiCall>()
        val networkRequests = mutableListOf<NetworkRequest>()
        val fileOperations = mutableListOf<FileOperation>()
        val warnings = mutableListOf<String>()

        DANGEROUS_PATTERNS.forEach { patternInfo ->
            val pattern = Pattern.compile(patternInfo.regex, Pattern.CASE_INSENSITIVE or Pattern.MULTILINE)
            val matcher = pattern.matcher(scriptContent)
            while (matcher.find()) {
                val snippet = matcher.group().take(100)
                dangerPatterns.add(
                    DangerPattern(
                        type = patternInfo.type,
                        severity = patternInfo.severity,
                        description = "Detected ${patternInfo.type.name.lowercase().replace("_", " ")}",
                        codeSnippet = snippet,
                        lineNumber = getLineNumber(scriptContent, matcher.start()),
                        suggestion = patternInfo.suggestion
                    )
                )
            }
        }

        SENSITIVE_APIS.forEach { apiInfo ->
            val pattern = Pattern.compile(Pattern.quote(apiInfo.apiName), Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(scriptContent)
            while (matcher.find()) {
                val lineNum = getLineNumber(scriptContent, matcher.start())
                val contextStart = maxOf(0, matcher.start() - 30)
                val contextEnd = minOf(scriptContent.length, matcher.end() + 30)
                sensitiveApiCalls.add(
                    SensitiveApiCall(
                        apiName = apiInfo.apiName,
                        severity = apiInfo.severity,
                        description = apiInfo.description,
                        lineNumber = lineNum,
                        context = scriptContent.substring(contextStart, contextEnd)
                    )
                )
            }
        }

        SUSPICIOUS_URL_PATTERNS.forEach { urlPattern ->
            val matcher = urlPattern.matcher(scriptContent)
            while (matcher.find()) {
                val url = matcher.group()
                val isSuspicious = isSuspiciousUrl(url)
                networkRequests.add(
                    NetworkRequest(
                        url = url,
                        isSuspicious = isSuspicious,
                        reason = if (isSuspicious) "Suspicious network destination" else "Normal network request",
                        lineNumber = getLineNumber(scriptContent, matcher.start())
                    )
                )
            }
        }

        DANGEROUS_FILE_OPERATIONS.forEach { (pattern, reason) ->
            val matcher = pattern.matcher(scriptContent)
            while (matcher.find()) {
                val operation = matcher.group().take(50)
                fileOperations.add(
                    FileOperation(
                        operation = operation,
                        path = extractPathFromOperation(matcher.group()),
                        isDangerous = true,
                        reason = reason,
                        lineNumber = getLineNumber(scriptContent, matcher.start())
                    )
                )
            }
        }

        if (dangerPatterns.isEmpty() && sensitiveApiCalls.isEmpty()) {
            warnings.add("No obvious malicious patterns detected, but manual review is still recommended")
        }

        val criticalCount = dangerPatterns.count { it.severity == RiskLevel.CRITICAL }
        val highCount = dangerPatterns.count { it.severity == RiskLevel.HIGH }

        val riskLevel = when {
            criticalCount > 0 -> RiskLevel.CRITICAL
            highCount > 0 -> RiskLevel.HIGH
            dangerPatterns.isNotEmpty() -> RiskLevel.MEDIUM
            sensitiveApiCalls.isNotEmpty() -> RiskLevel.LOW
            else -> RiskLevel.NONE
        }

        val isPassed = riskLevel != RiskLevel.CRITICAL && riskLevel != RiskLevel.HIGH

        val recommendations = mutableListOf<String>()
        if (dangerPatterns.any { it.type == DangerPatternType.EVAL_USAGE }) {
            recommendations.add("Avoid using eval(). Consider using safer alternatives like JSON.parse for data or functions for logic.")
        }
        if (dangerPatterns.any { it.type == DangerPatternType.RUNTIME_EXEC }) {
            recommendations.add("Avoid Runtime.exec(). If shell commands are necessary, validate and sanitize all inputs carefully.")
        }
        if (dangerPatterns.any { it.type == DangerPatternType.HARDCODED_SECRET }) {
            recommendations.add("Remove hardcoded secrets. Use environment variables or secure storage instead.")
        }
        if (networkRequests.any { it.isSuspicious }) {
            recommendations.add("Review network requests to external URLs. Ensure all network communication uses HTTPS and trusted endpoints.")
        }

        AppLogger.d(TAG, "Security scan completed for ${toolPackage.name}: riskLevel=${riskLevel}, dangerPatterns=${dangerPatterns.size}")

        return SecurityReport(
            isPassed = isPassed,
            riskLevel = riskLevel,
            dangerPatterns = dangerPatterns,
            sensitiveApiCalls = sensitiveApiCalls,
            networkRequests = networkRequests,
            fileOperations = fileOperations,
            warnings = warnings,
            recommendations = recommendations
        )
    }

    fun scanScript(scriptContent: String, skillName: String = "unknown"): SecurityReport {
        val dangerPatterns = mutableListOf<DangerPattern>()
        val sensitiveApiCalls = mutableListOf<SensitiveApiCall>()
        val networkRequests = mutableListOf<NetworkRequest>()
        val fileOperations = mutableListOf<FileOperation>()
        val warnings = mutableListOf<String>()

        DANGEROUS_PATTERNS.forEach { patternInfo ->
            val pattern = Pattern.compile(patternInfo.regex, Pattern.CASE_INSENSITIVE or Pattern.MULTILINE)
            val matcher = pattern.matcher(scriptContent)
            while (matcher.find()) {
                val snippet = matcher.group().take(100)
                dangerPatterns.add(
                    DangerPattern(
                        type = patternInfo.type,
                        severity = patternInfo.severity,
                        description = "Detected ${patternInfo.type.name.lowercase().replace("_", " ")}",
                        codeSnippet = snippet,
                        lineNumber = getLineNumber(scriptContent, matcher.start()),
                        suggestion = patternInfo.suggestion
                    )
                )
            }
        }

        SENSITIVE_APIS.forEach { apiInfo ->
            val pattern = Pattern.compile(Pattern.quote(apiInfo.apiName), Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(scriptContent)
            while (matcher.find()) {
                val lineNum = getLineNumber(scriptContent, matcher.start())
                val contextStart = maxOf(0, matcher.start() - 30)
                val contextEnd = minOf(scriptContent.length, matcher.end() + 30)
                sensitiveApiCalls.add(
                    SensitiveApiCall(
                        apiName = apiInfo.apiName,
                        severity = apiInfo.severity,
                        description = apiInfo.description,
                        lineNumber = lineNum,
                        context = scriptContent.substring(contextStart, contextEnd)
                    )
                )
            }
        }

        SUSPICIOUS_URL_PATTERNS.forEach { urlPattern ->
            val matcher = urlPattern.matcher(scriptContent)
            while (matcher.find()) {
                val url = matcher.group()
                val isSuspicious = isSuspiciousUrl(url)
                networkRequests.add(
                    NetworkRequest(
                        url = url,
                        isSuspicious = isSuspicious,
                        reason = if (isSuspicious) "Suspicious network destination" else "Normal network request",
                        lineNumber = getLineNumber(scriptContent, matcher.start())
                    )
                )
            }
        }

        DANGEROUS_FILE_OPERATIONS.forEach { (pattern, reason) ->
            val matcher = pattern.matcher(scriptContent)
            while (matcher.find()) {
                val operation = matcher.group().take(50)
                fileOperations.add(
                    FileOperation(
                        operation = operation,
                        path = extractPathFromOperation(matcher.group()),
                        isDangerous = true,
                        reason = reason,
                        lineNumber = getLineNumber(scriptContent, matcher.start())
                    )
                )
            }
        }

        val criticalCount = dangerPatterns.count { it.severity == RiskLevel.CRITICAL }
        val highCount = dangerPatterns.count { it.severity == RiskLevel.HIGH }

        val riskLevel = when {
            criticalCount > 0 -> RiskLevel.CRITICAL
            highCount > 0 -> RiskLevel.HIGH
            dangerPatterns.isNotEmpty() -> RiskLevel.MEDIUM
            sensitiveApiCalls.isNotEmpty() -> RiskLevel.LOW
            else -> RiskLevel.NONE
        }

        val isPassed = riskLevel != RiskLevel.CRITICAL && riskLevel != RiskLevel.HIGH

        return SecurityReport(
            isPassed = isPassed,
            riskLevel = riskLevel,
            dangerPatterns = dangerPatterns,
            sensitiveApiCalls = sensitiveApiCalls,
            networkRequests = networkRequests,
            fileOperations = fileOperations,
            warnings = warnings,
            recommendations = emptyList()
        )
    }

    private fun extractScriptContent(toolPackage: ToolPackage): String {
        val sb = StringBuilder()
        toolPackage.tools.forEach { tool ->
            if (tool.script.isNotBlank()) {
                sb.append(tool.script)
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    private fun getLineNumber(content: String, position: Int): Int {
        var lineNumber = 1
        for (i in 0 until minOf(position, content.length)) {
            if (content[i] == '\n') {
                lineNumber++
            }
        }
        return lineNumber
    }

    private fun isSuspiciousUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return lowerUrl.contains("localhost") ||
                lowerUrl.contains("127.0.0.1") ||
                lowerUrl.contains("file://") ||
                lowerUrl.contains("10.") ||
                lowerUrl.contains("172.16") ||
                lowerUrl.contains("192.168.") ||
                lowerUrl.matches(Regex(".*\\.(ru|cn|xyz|tk|ml|ga|cf|gq)/?$"))
    }

    private fun extractPathFromOperation(operation: String): String {
        val pathPattern = Pattern.compile("['\"]([^'\"]+)['\"]")
        val matcher = pathPattern.matcher(operation)
        return if (matcher.find()) matcher.group(1) else "unknown"
    }
}