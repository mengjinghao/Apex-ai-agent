package com.apex.util

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * 颜色工具类，提供颜色格式转换、亮度计算、对比度分析、颜色混合等实用功能
 *
 * 支持 HEX、RGB、ARGB、HSL 等多种颜色空间的互相转换，
 * 并提供 WCAG 无障碍标准的对比度计算和文本颜色自动适配。
 */
object ColorUtils {

    /**
     * HSL 颜色模型数据类
     *
     * @property hue 色相（0..360）
     * @property saturation 饱和度（0..1）
     * @property lightness 明度（0..1）
     */
    data class HSL(val hue: Float, val saturation: Float, val lightness: Float)

    /**
     * 将颜色 int 值转换为 HEX 格式字符串
     *
     * 输出格式为 "#AARRGGBB"，如白色返回 "#FFFFFFFF"。
     *
     * @param color 颜色 int 值
     * @return HEX 格式颜色字符串
     */
    fun intToHex(color: Int): String {
        return String.format("#%08X", color)
    }

    /**
     * 将 HEX 格式颜色字符串解析为 int 值
     *
     * 支持以下格式：
     * - "#RGB"
     * - "#RRGGBB"
     * - "#AARRGGBB"
     * - "RGB"
     * - "RRGGBB"
     * 解析失败时返回 null。
     *
     * @param hex HEX 格式颜色字符串
     * @return 颜色 int 值，解析失败返回 null
     */
    fun hexToInt(hex: String): Int? {
        return try {
            val cleanHex = hex.trimStart('#')
            when (cleanHex.length) {
                3 -> {
                    val r = cleanHex[0].toString().repeat(2)
                    val g = cleanHex[1].toString().repeat(2)
                    val b = cleanHex[2].toString().repeat(2)
                    Color.parseColor("#FF$r$g$b")
                }
                6 -> Color.parseColor("#FF$cleanHex")
                8 -> Color.parseColor("#$cleanHex")
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将颜色 int 值拆分为 RGB 分量
     *
     * @param color 颜色 int 值
     * @return Triple 包含 (R, G, B) 三个分量，取值范围 0..255
     */
    fun intToRgb(color: Int): Triple<Int, Int, Int> {
        return Triple(
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    /**
     * 将 RGB 分量组合为颜色 int 值（不透明）
     *
     * @param r 红色分量（0..255）
     * @param g 绿色分量（0..255）
     * @param b 蓝色分量（0..255）
     * @return 组合后的颜色 int 值（Alpha 为 255）
     */
    fun rgbToInt(r: Int, g: Int, b: Int): Int {
        return Color.rgb(r, g, b)
    }

    /**
     * 将 ARGB 分量组合为颜色 int 值
     *
     * @param a Alpha 分量（0..255）
     * @param r 红色分量（0..255）
     * @param g 绿色分量（0..255）
     * @param b 蓝色分量（0..255）
     * @return 组合后的颜色 int 值
     */
    fun argbToInt(a: Int, r: Int, g: Int, b: Int): Int {
        return Color.argb(a, r, g, b)
    }

    /**
     * 计算颜色的相对亮度
     *
     * 遵循 WCAG 2.1 标准的公式计算相对亮度，
     * 返回值范围为 0（最暗）到 1（最亮）。
     *
     * @param color 颜色 int 值
     * @return 相对亮度值（0..1）
     */
    fun luminance(color: Int): Double {
        val linearize = { c: Double ->
            if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = linearize(Color.red(color) / 255.0)
        val g = linearize(Color.green(color) / 255.0)
        val b = linearize(Color.blue(color) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * 计算两种颜色之间的 WCAG 对比度
     *
     * 对比度范围从 1:1 到 21:1。
     * WCAG AA 标准要求普通文本对比度 ≥ 4.5:1，大文本 ≥ 3:1。
     * WCAG AAA 标准要求普通文本对比度 ≥ 7:1，大文本 ≥ 4.5:1。
     *
     * @param color1 第一种颜色
     * @param color2 第二种颜色
     * @return 对比度比值
     */
    fun contrastRatio(color1: Int, color2: Int): Double {
        val l1 = luminance(color1)
        val l2 = luminance(color2)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * 判断颜色是否为亮色
     *
     * 基于相对亮度判断，亮度 > 0.5 视为亮色。
     *
     * @param color 颜色 int 值
     * @return true 为亮色，false 为暗色
     */
    fun isLightColor(color: Int): Boolean {
        return luminance(color) > 0.5
    }

    /**
     * 判断颜色是否为暗色
     *
     * 基于相对亮度判断，亮度 <= 0.5 视为暗色。
     *
     * @param color 颜色 int 值
     * @return true 为暗色，false 为亮色
     */
    fun isDarkColor(color: Int): Boolean {
        return luminance(color) <= 0.5
    }

    /**
     * 混合两种颜色
     *
     * 根据 ratio 比例线性插值混合两种颜色。
     * ratio = 0.0 时完全返回 color1，ratio = 1.0 时完全返回 color2。
     *
     * @param color1 第一种颜色
     * @param color2 第二种颜色
     * @param ratio 混合比例（0..1）
     * @return 混合后的颜色 int 值
     */
    fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val clampedRatio = ratio.coerceIn(0f, 1f)
        val alpha = (Color.alpha(color1) * (1 - clampedRatio) + Color.alpha(color2) * clampedRatio).toInt()
        val red = (Color.red(color1) * (1 - clampedRatio) + Color.red(color2) * clampedRatio).toInt()
        val green = (Color.green(color1) * (1 - clampedRatio) + Color.green(color2) * clampedRatio).toInt()
        val blue = (Color.blue(color1) * (1 - clampedRatio) + Color.blue(color2) * clampedRatio).toInt()
        return argbToInt(alpha, red, green, blue)
    }

    /**
     * 调整颜色的亮度
     *
     * 通过乘数因子调整 RGB 分量值。
     * factor > 1.0 使颜色变亮，factor < 1.0 使颜色变暗。
     *
     * @param color 原始颜色
     * @param factor 亮度调整因子
     * @return 调整后的颜色 int 值
     */
    fun adjustBrightness(color: Int, factor: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return argbToInt(a, r, g, b)
    }

    /**
     * 将颜色 int 值转换为 HSL 颜色模型
     *
     * 基于标准 RGB 到 HSL 的转换算法。
     *
     * @param color 颜色 int 值
     * @return HSL 数据类实例
     */
    fun intToHSL(color: Int): HSL {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        val lightness = (max + min) / 2f

        val hue = if (delta == 0f) 0f else {
            val h = when (max) {
                r -> ((g - b) / delta) % 6f
                g -> (b - r) / delta + 2f
                b -> (r - g) / delta + 4f
                else -> 0f
            }
            (h * 60f + 360f) % 360f
        }

        val saturation = if (delta == 0f) 0f else delta / (1f - kotlin.math.abs(2f * lightness - 1f))

        return HSL(hue, saturation.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f))
    }

    /**
     * 将 HSL 颜色模型转换为颜色 int 值
     *
     * 基于标准 HSL 到 RGB 的转换算法，Alpha 值固定为 255（不透明）。
     *
     * @param hsl HSL 颜色数据类
     * @return 颜色 int 值
     */
    fun hslToInt(hsl: HSL): Int {
        val hue = hsl.hue / 360f
        val saturation = hsl.saturation
        val lightness = hsl.lightness

        if (saturation == 0f) {
            val gray = (lightness * 255f).toInt().coerceIn(0, 255)
            return argbToInt(255, gray, gray, gray)
        }

        val hueToRgb = { p: Float, q: Float, t: Float ->
            var ht = t
            if (ht < 0f) ht += 1f
            if (ht > 1f) ht -= 1f
            when {
                ht < 1f / 6f -> p + (q - p) * 6f * ht
                ht < 1f / 2f -> q
                ht < 2f / 3f -> p + (q - p) * (2f / 3f - ht) * 6f
                else -> p
            }
        }

        val q = if (lightness < 0.5f) lightness * (1f + saturation) else lightness + saturation - lightness * saturation
        val p = 2f * lightness - q

        val r = (hueToRgb(p, q, hue + 1f / 3f) * 255f).toInt().coerceIn(0, 255)
        val g = (hueToRgb(p, q, hue) * 255f).toInt().coerceIn(0, 255)
        val b = (hueToRgb(p, q, hue - 1f / 3f) * 255f).toInt().coerceIn(0, 255)

        return argbToInt(255, r, g, b)
    }

    /**
     * 根据背景色返回合适的文本颜色（白色或黑色）
     *
     * 使用 WCAG 对比度算法判断，选择与背景色对比度更高的文本颜色，
     * 确保文字清晰可读。
     *
     * @param bgColor 背景颜色
     * @return 白色（Color.WHITE）或黑色（Color.BLACK）
     */
    fun getTextColorForBackground(bgColor: Int): Int {
        return if (isLightColor(bgColor)) Color.BLACK else Color.WHITE
    }

    /**
     * 生成随机颜色
     *
     * 使用可选的种子值生成可复现的随机颜色。
     * 种子相同时每次生成的随机颜色相同，适用于需要一致性的场景。
     *
     * @param seed 随机种子（可选），相同种子生成相同颜色
     * @return 随机生成的颜色 int 值（不透明）
     */
    fun randomColor(seed: Long? = null): Int {
        val random = if (seed != null) Random(seed) else Random
        val r = random.nextInt(256)
        val g = random.nextInt(256)
        val b = random.nextInt(256)
        return rgbToInt(r, g, b)
    }

    /**
     * 设置颜色的 Alpha 透明度
     *
     * @param color 原始颜色
     * @param alpha 透明度值（0.0 完全透明，1.0 完全不透明）
     * @return 设置了透明度后的颜色 int 值
     */
    fun alpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return argbToInt(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
