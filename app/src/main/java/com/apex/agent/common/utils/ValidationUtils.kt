package com.apex.agent.common.utils

/**
 * 数据验证工具类，提供常见格式的验证方法。
 */
object ValidationUtils {

    /**
     * 检查字符串是否为非空且非空白。
     *
     * @param input 待检查的字符串
     * @return 如果非空且包含非空白字符返回 true
     */
    fun isNotBlank(input: CharSequence?): Boolean = !input.isNullOrBlank()

    /**
     * 验证邮箱格式是否有效。
     * 使用基本正则表达式验证邮箱地址格式。
     *
     * @param email 待验证的邮箱地址
     * @return 格式有效返回 true，否则返回 false
     */
    fun isValidEmail(email: String): Boolean {
        if (email.isBlank()) return false
        val regex = Regex(
            "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
        )
        return regex.matches(email)
    }

    /**
     * 验证手机号格式是否基本有效。
     * 支持 11 位中国大陆手机号格式（以 1 开头）。
     *
     * @param phone 待验证的手机号
     * @return 格式有效返回 true，否则返回 false
     */
    fun isValidPhone(phone: String): Boolean {
        if (phone.isBlank()) return false
        val cleaned = phone.replace(Regex("[\\s\\-()]"), "")
        return cleaned.length >= 10 && cleaned.all { it.isDigit() }
    }

    /**
     * 验证 URL 格式是否有效。
     * 支持 http、https、ftp 等常见协议。
     *
     * @param url 待验证的 URL 地址
     * @return 格式有效返回 true，否则返回 false
     */
    fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val regex = Regex(
            "^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]"
        )
        return regex.matches(url)
    }

    /**
     * 验证 IPv4 地址格式是否有效。
     *
     * @param ip 待验证的 IP 地址字符串
     * @return 有效的 IPv4 地址返回 true，否则返回 false
     */
    fun isValidIpAddress(ip: String): Boolean {
        if (ip.isBlank()) return false
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255 && part == num.toString()
        }
    }

    /**
     * 验证端口号是否在有效范围内（1-65535）。
     *
     * @param port 待验证的端口号
     * @return 在有效范围内返回 true，否则返回 false
     */
    fun isValidPort(port: Int): Boolean = port in 1..65535

    /**
     * 验证十六进制颜色值格式是否有效。
     * 支持 #RGB、#RRGGBB、#RRGGBBAA 格式。
     *
     * @param color 待验证的颜色字符串（如 "#FF0000"）
     * @return 格式有效返回 true，否则返回 false
     */
    fun isValidHexColor(color: String): Boolean {
        if (color.isBlank()) return false
        val regex = Regex("^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
        return regex.matches(color)
    }

    /**
     * 验证密码强度是否满足要求。
     *
     * @param password      待验证的密码
     * @param minLength     最小长度，默认 8
     * @param requireDigit  是否需要包含数字，默认 true
     * @param requireSpecial 是否需要包含特殊字符，默认 true
     * @return 满足所有规则返回 true，否则返回 false
     */
    fun isValidPassword(
        password: String,
        minLength: Int = 8,
        requireDigit: Boolean = true,
        requireSpecial: Boolean = true
    ): Boolean {
        if (password.length < minLength) return false
        if (requireDigit && !password.any { it.isDigit() }) return false
        if (requireSpecial && !password.any { !it.isLetterOrDigit() }) return false
        return true
    }

    /**
     * 检查字符串是否为纯数字。
     *
     * @param input 待检查的字符串
     * @return 全部为数字字符返回 true
     */
    fun isNumeric(input: String): Boolean {
        if (input.isBlank()) return false
        return input.all { it.isDigit() }
    }

    /**
     * 检查字符串是否为字母数字组合（仅包含字母和数字）。
     *
     * @param input 待检查的字符串
     * @return 仅包含字母和数字返回 true
     */
    fun isAlphanumeric(input: String): Boolean {
        if (input.isBlank()) return false
        return input.all { it.isLetterOrDigit() }
    }

    /**
     * 检查字符串长度是否在指定范围内（包含边界）。
     *
     * @param input 待检查的字符串
     * @param min   最小长度
     * @param max   最大长度
     * @return 长度在 [min, max] 范围内返回 true
     */
    fun isValidLength(input: String, min: Int, max: Int): Boolean {
        return input.length in min..max
    }

    /**
     * 检查字符串是否匹配给定的正则表达式。
     *
     * @param input 待检查的字符串
     * @param regex 正则表达式
     * @return 匹配成功返回 true，否则返回 false
     */
    fun matchesRegex(input: String, regex: String): Boolean {
        return try {
            Regex(regex).matches(input)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查整数是否在指定范围内（包含边界）。
     *
     * @param value 待检查的整数值
     * @param min   最小值
     * @param max   最大值
     * @return 在 [min, max] 范围内返回 true
     */
    fun isInRange(value: Int, min: Int, max: Int): Boolean = value in min..max

    /**
     * 检查数字是否为正数（大于 0）。
     *
     * @param value 待检查的数字
     * @return 大于 0 返回 true，否则返回 false
     */
    fun isPositive(value: Number): Boolean = value.toDouble() > 0.0

    /**
     * 检查字符串是否为有效的 JSON 格式。
     *
     * @param json 待验证的 JSON 字符串
     * @return 有效的 JSON 返回 true，否则返回 false
     */
    fun isValidJson(json: String): Boolean = JsonUtils.isValidJson(json)
}
