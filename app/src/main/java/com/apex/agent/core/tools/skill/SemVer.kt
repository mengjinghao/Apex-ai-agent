package com.apex.agent.core.tools.skill

/**
 * SemVer语义化版本号
 *
 * 支持解析和比较 "major.minor.patch" 格式的版本号，
 * 以及带预发布标签的版本号如 "1.2.3-alpha"。
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null
) : Comparable<SemVer> {

    override operator fun compareTo(other: SemVer): Int {
        val majorCmp = this.major.compareTo(other.major)
        if (majorCmp != 0) return majorCmp
        val minorCmp = this.minor.compareTo(other.minor)
        if (minorCmp != 0) return minorCmp
        val patchCmp = this.patch.compareTo(other.patch)
        if (patchCmp != 0) return patchCmp
        return when {
            this.preRelease == null && other.preRelease != null -> 1
            this.preRelease != null && other.preRelease == null -> -1
            this.preRelease == null && other.preRelease == null -> 0
            else -> this.preRelease!!.compareTo(other.preRelease!!)
        }
    }

    override fun toString(): String {
        val base = "$major.$minor.$patch"
        return if (preRelease != null) "$base-$preRelease" else base
    }

    companion object {
        /**
         * 解析版本字符串为SemVer实例
         * @param version 版本字符串，如 "1.2.3" 或 "1.2.3-beta"
         */
        fun parse(version: String): SemVer? {
            val cleaned = version.trim().trimStart('v', 'V')
            val parts = cleaned.split("-", limit = 2)
            val numeric = parts[0]
            val pre = if (parts.size > 1) parts[1] else null

            val numParts = numeric.split(".")
            if (numParts.size !in 1..3) return null

            val major = numParts[0].toIntOrNull() ?: return null
            val minor = if (numParts.size > 1) numParts[1].toIntOrNull() ?: 0 else 0
            val patch = if (numParts.size > 2) numParts[2].toIntOrNull() ?: 0 else 0

            if (major < 0 || minor < 0 || patch < 0) return null

            return SemVer(major, minor, patch, pre?.takeIf { it.isNotBlank() })
        }

        /**
         * 比较两个版本字符串
         * @return 正数表示v1 > v2，负数表示v1 < v2，0表示相等
         */
        fun compare(v1: String, v2: String): Int {
            val sv1 = parse(v1) ?: return 0
            val sv2 = parse(v2) ?: return 0
            return sv1.compareTo(sv2)
        }

        /**
         * 检查版本是否满足约束
         * 支持的运算符: >=, <=, >, <, =, ~>, ^
         */
        fun satisfies(constraint: String, version: String): Boolean {
            val cleaned = constraint.trim()
            val operator = when {
                cleaned.startsWith(">=") -> ">="
                cleaned.startsWith("<=") -> "<="
                cleaned.startsWith(">") -> ">"
                cleaned.startsWith("<") -> "<"
                cleaned.startsWith("=") -> "="
                cleaned.startsWith("~>") -> "~>"
                cleaned.startsWith("^") -> "^"
                else -> "="
            }

            val target = cleaned.removePrefix(operator).trim()
            val semVer = parse(version) ?: return false
            val targetVer = parse(target) ?: return false

            return when (operator) {
                ">=" -> semVer >= targetVer
                "<=" -> semVer <= targetVer
                ">" -> semVer > targetVer
                "<" -> semVer < targetVer
                "=" -> semVer == targetVer
                "~>" -> semVer.major == targetVer.major && semVer.minor >= targetVer.minor && semVer >= targetVer
                "^" -> semVer.major == targetVer.major && semVer >= targetVer
                else -> semVer == targetVer
            }
        }
    }
}
