package com.apex.agent.common.extensions

/**
 * [List] 和 [List?] 的扩展函数集合，提供空安全处理、安全索引访问、分块等便捷方法。
 */

/**
 * 当列表为 null 时返回空列表。
 *
 * @return 非 null 的列表
 */
fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

/**
 * 将列表按指定大小分块。
 *
 * @param size 每块的大小，必须大于 0
 * @return 分块后的列表
 */
fun <T> List<T>.chunked(size: Int): List<List<T>> {
    require(size > 0) { "分块大小必须大于 0，实际为 $size" }
    if (isEmpty()) return emptyList()
    val result = mutableListOf<List<T>>()
    var i = 0
    while (i < size) {
        if (i >= this.size) break
        val end = (i + size).coerceAtMost(this.size)
        result.add(subList(i, end))
        i += size
    }
    return result
}

/**
 * 获取列表的第二个元素。
 *
 * @return 第二个元素
 * @throws IndexOutOfBoundsException 列表长度小于 2 时抛出
 */
fun <T> List<T>.second(): T {
    if (size < 2) throw IndexOutOfBoundsException("列表长度 $size 小于 2")
    return this[1]
}

/**
 * 获取列表的第三个元素。
 *
 * @return 第三个元素
 * @throws IndexOutOfBoundsException 列表长度小于 3 时抛出
 */
fun <T> List<T>.third(): T {
    if (size < 3) throw IndexOutOfBoundsException("列表长度 $size 小于 3")
    return this[2]
}

/**
 * 安全地获取列表的第二个元素，不存在时返回 null。
 *
 * @return 第二个元素，不存在返回 null
 */
fun <T> List<T>.secondOrNull(): T? {
    return if (size >= 2) this[1] else null
}

/**
 * 安全地获取列表的第三个元素，不存在时返回 null。
 *
 * @return 第三个元素，不存在返回 null
 */
fun <T> List<T>.thirdOrNull(): T? {
    return if (size >= 3) this[2] else null
}

/**
 * 过滤掉列表中的 null 元素，返回非空元素列表。
 *
 * @return 不含 null 的列表
 */
fun <T> List<T?>.filterNotNull(): List<T> {
    val result = mutableListOf<T>()
    for (element in this) {
        if (element != null) {
            result.add(element)
        }
    }
    return result
}

/**
 * 当列表为 null 时返回空列表（保留向后兼容性）。
 *
 * @return 非 null 的列表
 */
fun <T> List<T>?.orEmptyList(): List<T> = this.orEmpty()
