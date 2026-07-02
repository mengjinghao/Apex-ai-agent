package com.apex.agent.common.extensions

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 数值类型的扩展函数集合，提供范围限制、范围判断、百分比计算、精度控制等便捷方法。
 */

/**
 * 将 [Int] 值限制在 [min] 和 [max] 之间（包含边界）。
 *
 * @param min 最小值
 * @param max 最大值
 * @return 限制后的值
 */
fun Int.clamp(min: Int, max: Int): Int {
    require(min <= max) { "最小值 $min 不能大于最大值 $max" }
    return when {
        this < min -> min
        this > max -> max
        else -> this
    }
}

/**
 * 将 [Float] 值限制在 [min] 和 [max] 之间（包含边界）。
 *
 * @param min 最小值
 * @param max 最大值
 * @return 限制后的值
 */
fun Float.clamp(min: Float, max: Float): Float {
    require(min <= max) { "最小值 $min 不能大于最大值 $max" }
    return when {
        this < min -> min
        this > max -> max
        else -> this
    }
}

/**
 * 将 [Double] 值限制在 [min] 和 [max] 之间（包含边界）。
 *
 * @param min 最小值
 * @param max 最大值
 * @return 限制后的值
 */
fun Double.clamp(min: Double, max: Double): Double {
    require(min <= max) { "最小值 $min 不能大于最大值 $max" }
    return when {
        this < min -> min
        this > max -> max
        else -> this
    }
}

/**
 * 判断 [Int] 值是否在 [min] 和 [max] 之间（包含边界）。
 *
 * @param min 最小值
 * @param max 最大值
 * @return 在范围内返回 true
 */
fun Int.between(min: Int, max: Int): Boolean = this in min..max

/**
 * 判断 [Float] 值是否在 [min] 和 [max] 之间（包含边界）。
 *
 * @param min 最小值
 * @param max 最大值
 * @return 在范围内返回 true
 */
fun Float.between(min: Float, max: Float): Boolean = this in min..max

/**
 * 计算当前 [Int] 值占总量的百分比。
 *
 * @param total 总量
 * @return 百分比值（0f ~ 100f）
 */
fun Int.percentOf(total: Int): Float {
    if (total == 0) return 0f
    return (this.toFloat() / total.toFloat()) * 100f
}

/**
 * 将 [Float] 四舍五入到指定小数位数。
 *
 * @param decimals 保留的小数位数
 * @return 四舍五入后的值
 */
fun Float.roundTo(decimals: Int): Float {
    val factor = 10.0.pow(decimals).toFloat()
    return (this * factor).roundToInt() / factor
}

/**
 * 将 [Double] 四舍五入到指定小数位数。
 *
 * @param decimals 保留的小数位数
 * @return 四舍五入后的值
 */
fun Double.roundTo(decimals: Int): Double {
    val factor = 10.0.pow(decimals)
    return (this * factor).roundToInt() / factor
}
