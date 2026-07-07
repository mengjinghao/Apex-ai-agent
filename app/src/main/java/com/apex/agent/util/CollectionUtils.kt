package com.apex.util

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 集合工具类，提供集合和数组的常用操作方法
 */
object CollectionUtils {

    /**
     * 将列表分割成指定大小的多个子列表
     *
     * @param list 原始列表
     * @param size 每个子列表的最大大小
     * @return 子列表的列表
     */
    fun <T> chunked(list: List<T>, size: Int): List<List<T>> {
        if (size <= 0) throw IllegalArgumentException("size must be positive, got $size")
        if (list.isEmpty()) return emptyList()
        val result = mutableListOf<List<T>>()
        var start = 0
        while (start < list.size) {
            val end = minOf(start + size, list.size)
            result.add(list.subList(start, end))
            start = end
        }
        return result
    }

    /**
     * 根据谓词将列表分为满足条件和不满足条件的两部分
     *
     * @param list 原始列表
     * @param predicate 分区谓词
     * @return 包含两个列表的 Pair，第一个为匹配谓词的元素，第二个为不匹配的元素
     */
    fun <T> partitionBy(list: List<T>, predicate: (T) -> Boolean): Pair<List<T>, List<T>> {
        val matched = mutableListOf<T>()
        val unmatched = mutableListOf<T>()
        for (element in list) {
            if (predicate(element)) {
                matched.add(element)
            } else {
                unmatched.add(element)
            }
        }
        return Pair(matched, unmatched)
    }

    /**
     * 将列表按指定大小分块（不抛出异常，size <= 0 时返回单块）。
     *
     * @param list 原始列表
     * @param size 每块大小
     * @return 分块后的列表
     */
    fun <T> partitionBySize(list: List<T>, size: Int): List<List<T>> {
        if (size <= 0) return listOf(list)
        return chunked(list, size)
    }

    /**
     * 查找列表中出现频率最高的元素
     *
     * @param list 元素列表
     * @return 出现频率最高的元素，如果列表为空则返回 null
     */
    fun <T> modeOf(list: List<T>): T? {
        if (list.isEmpty()) return null
        val freq = frequencies(list)
        return freq.maxByOrNull { it.value }?.key
    }

    /**
     * 计算列表中每个元素出现的频率
     *
     * @param list 元素列表
     * @return 元素到出现次数的映射
     */
    fun <T> frequencies(list: List<T>): Map<T, Int> {
        val freq = linkedMapOf<T, Int>()
        for (element in list) {
            freq[element] = (freq[element] ?: 0) + 1
        }
        return freq
    }

    /**
     * 计算列表中每个元素根据变换函数归类的计数。
     *
     * @param list 原始列表
     * @param transform 变换函数
     * @return 变换结果到出现次数的映射
     */
    fun <T, K> countBy(list: List<T>, transform: (T) -> K): Map<K, Int> {
        return list.groupBy(transform).mapValues { it.value.size }
    }

    /**
     * 根据变换函数分组后统计每组元素个数。
     *
     * @param list 原始列表
     * @param transform 变换函数
     * @return 变换结果到计数的映射
     */
    fun <T, K> groupByCount(list: List<T>, transform: (T) -> K): Map<K, Int> {
        return countBy(list, transform)
    }

    /**
     * 获取列表中前 N 个最大元素（根据比较器）
     *
     * @param list 原始列表
     * @param n 需要获取的元素数量
     * @param comparator 比较器
     * @return 前 N 个元素的列表
     */
    fun <T> topN(list: List<T>, n: Int, comparator: Comparator<T>): List<T> {
        if (n <= 0) return emptyList()
        if (n >= list.size) return list.sortedWith(comparator.reversed())
        return list.sortedWith(comparator.reversed()).take(n)
    }

    /**
     * 获取列表中出现频率最高的前 N 个元素。
     *
     * @param list 原始列表
     * @param n 返回元素数量
     * @return 最常见元素列表
     */
    fun <T> mostCommon(list: List<T>, n: Int): List<T> {
        if (list.isEmpty() || n <= 0) return emptyList()
        return frequencies(list).entries
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key }
    }

    /**
     * 获取列表中出现频率最低的后 N 个元素。
     *
     * @param list 原始列表
     * @param n 返回元素数量
     * @return 最不常见元素列表
     */
    fun <T> leastCommon(list: List<T>, n: Int): List<T> {
        if (list.isEmpty() || n <= 0) return emptyList()
        return frequencies(list).entries
            .sortedBy { it.value }
            .take(n)
            .map { it.key }
    }

    /**
     * 根据指定的键提取器去重，保留第一个出现的元素
     *
     * @param list 原始列表
     * @param keyExtractor 键提取函数
     * @return 去重后的列表
     */
    fun <T, K> distinctByKey(list: List<T>, keyExtractor: (T) -> K): List<T> {
        val seen = mutableSetOf<K>()
        val result = mutableListOf<T>()
        for (element in list) {
            val key = keyExtractor(element)
            if (key !in seen) {
                seen.add(key)
                result.add(element)
            }
        }
        return result
    }

    /**
     * 根据变换函数去除重复项。
     *
     * @param list 原始列表
     * @param transform 变换函数
     * @return 去重后的列表
     */
    fun <T, K> distinctBy(list: List<T>, transform: (T) -> K): List<T> {
        return distinctByKey(list, transform)
    }

    /**
     * 去除连续重复的元素，只保留第一个。
     *
     * @param list 原始列表
     * @return 去除连续重复后的列表
     */
    fun <T> distinctConsecutive(list: List<T>): List<T> {
        if (list.isEmpty()) return emptyList()
        val result = mutableListOf<T>()
        var previous = list[0]
        result.add(previous)
        for (i in 1 until list.size) {
            if (list[i] != previous) {
                result.add(list[i])
                previous = list[i]
            }
        }
        return result
    }

    /**
     * 计算移动平均值
     *
     * @param values 数值列表
     * @param windowSize 滑动窗口大小
     * @return 移动平均值列表
     */
    fun movingAverage(values: List<Double>, windowSize: Int): List<Double> {
        if (windowSize <= 0) throw IllegalArgumentException("windowSize must be positive, got $windowSize")
        if (values.isEmpty()) return emptyList()
        if (values.size < windowSize) return emptyList()

        val result = mutableListOf<Double>()
        var windowSum = values.take(windowSize).sum()
        result.add(windowSum / windowSize)

        for (i in windowSize until values.size) {
            windowSum += values[i] - values[i - windowSize]
            result.add(windowSum / windowSize)
        }
        return result
    }

    /**
     * 计算列表的中位数
     *
     * @param values 数值列表
     * @return 中位数
     */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) throw IllegalArgumentException("list must not be empty")
        val sorted = values.sorted()
        val size = sorted.size
        return if (size % 2 == 1) {
            sorted[size / 2]
        } else {
            (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0
        }
    }

    /**
     * 计算列表的方差
     *
     * @param values 数值列表
     * @return 方差值
     */
    fun variance(values: List<Double>): Double {
        if (values.isEmpty()) throw IllegalArgumentException("list must not be empty")
        if (values.size == 1) return 0.0
        val mean = values.sum() / values.size
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }

    /**
     * 计算列表的标准差
     *
     * @param values 数值列表
     * @return 标准差
     */
    fun stdDev(values: List<Double>): Double {
        return sqrt(variance(values))
    }

    /**
     * 计算两个列表的笛卡尔积
     *
     * @param list1 第一个列表
     * @param list2 第二个列表
     * @return 笛卡尔积的 Pair 列表
     */
    fun <T, U> cartesianProduct(list1: List<T>, list2: List<U>): List<Pair<T, U>> {
        if (list1.isEmpty() || list2.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<T, U>>()
        for (t in list1) {
            for (u in list2) {
                result.add(Pair(t, u))
            }
        }
        return result
    }

    /**
     * 生成列表的幂集（所有子集）。
     *
     * @param list 原始列表
     * @return 幂集列表
     */
    fun <T> powerSet(list: List<T>): List<List<T>> {
        if (list.isEmpty()) return listOf(emptyList())
        val result = mutableListOf<List<T>>()
        val n = list.size
        for (i in 0 until (1 shl n)) {
            val subset = mutableListOf<T>()
            for (j in 0 until n) {
                if (i and (1 shl j) != 0) {
                    subset.add(list[j])
                }
            }
            result.add(subset)
        }
        return result
    }

    /**
     * 使用确定性种子对列表进行洗牌（相同种子产生相同结果）
     *
     * @param list 原始列表
     * @param seed 随机种子，默认为 42
     * @return 洗牌后的新列表
     */
    fun <T> shufflePreserveOrder(list: List<T>, seed: Long = 42L): List<T> {
        val mutable = list.toMutableList()
        val random = Random(seed)
        for (i in mutable.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val temp = mutable[i]
            mutable[i] = mutable[j]
            mutable[j] = temp
        }
        return mutable
    }

    /**
     * 返回列表的随机打乱副本。
     *
     * @param list 原始列表
     * @return 打乱后的列表
     */
    fun <T> shuffled(list: List<T>): List<T> {
        return list.toMutableList().apply { shuffle() }
    }

    /**
     * 返回列表中的一个随机元素，列表为空时返回 null。
     *
     * @param list 原始列表
     * @return 随机元素或 null
     */
    fun <T> randomElement(list: List<T>): T? {
        if (list.isEmpty()) return null
        return list[Random.nextInt(list.size)]
    }

    /**
     * 返回列表中的 N 个随机元素（可重复）。
     *
     * @param list 原始列表
     * @param n 返回的元素数量
     * @return 随机元素列表
     */
    fun <T> randomElements(list: List<T>, n: Int): List<T> {
        if (list.isEmpty() || n <= 0) return emptyList()
        return List(n) { list[Random.nextInt(list.size)] }
    }

    /**
     * 生成列表的滑动窗口序列
     *
     * @param list 原始列表
     * @param windowSize 窗口大小
     * @param step 步长，默认为 1
     * @return 滑动窗口列表的列表
     */
    fun <T> windowedSliding(list: List<T>, windowSize: Int, step: Int = 1): List<List<T>> {
        if (windowSize <= 0) throw IllegalArgumentException("windowSize must be positive, got $windowSize")
        if (step <= 0) throw IllegalArgumentException("step must be positive, got $step")
        if (list.size < windowSize) return emptyList()

        val result = mutableListOf<List<T>>()
        var start = 0
        while (start + windowSize <= list.size) {
            result.add(list.subList(start, start + windowSize))
            start += step
        }
        return result
    }

    /**
     * 生成列表的滑动窗口序列（返回新列表，不引用原列表）。
     *
     * @param list 原始列表
     * @param size 窗口大小
     * @param step 步长，默认 1
     * @return 滑动窗口列表
     */
    fun <T> slidingWindow(list: List<T>, size: Int, step: Int = 1): List<List<T>> {
        return windowedSliding(list, size, step)
    }

    /**
     * 将列表元素与其索引配对
     *
     * @param list 原始列表
     * @return 索引和元素的 Pair 列表
     */
    fun <T> zipWithIndex(list: List<T>): List<Pair<Int, T>> {
        return list.mapIndexed { index, element -> Pair(index, element) }
    }

    /**
     * 将列表向左循环移动指定位置数
     *
     * @param list 原始列表
     * @param positions 移动的位置数
     * @return 移动后的新列表
     */
    fun <T> rotateLeft(list: List<T>, positions: Int): List<T> {
        if (list.isEmpty() || list.size == 1) return list.toList()
        val effectivePositions = positions.mod(list.size)
        if (effectivePositions == 0) return list.toList()
        return list.drop(effectivePositions) + list.take(effectivePositions)
    }

    /**
     * 将列表向右循环移动指定位置数
     *
     * @param list 原始列表
     * @param positions 移动的位置数
     * @return 移动后的新列表
     */
    fun <T> rotateRight(list: List<T>, positions: Int): List<T> {
        if (list.isEmpty() || list.size == 1) return list.toList()
        val effectivePositions = positions.mod(list.size)
        if (effectivePositions == 0) return list.toList()
        return list.takeLast(effectivePositions) + list.dropLast(effectivePositions)
    }

    /**
     * 交错合并多个列表（从每个列表中依次取一个元素）
     *
     * @param lists 需要合并的多个列表
     * @return 交错合并后的列表
     */
    fun <T> interleave(vararg lists: List<T>): List<T> {
        if (lists.isEmpty()) return emptyList()
        val result = mutableListOf<T>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxSize) {
            for (list in lists) {
                if (i < list.size) {
                    result.add(list[i])
                }
            }
        }
        return result
    }

    /**
     * 检查列表是否已按比较器排序
     *
     * @param list 待检查的列表
     * @param comparator 比较器
     * @return 如果已排序则返回 true
     */
    fun <T> isSorted(list: List<T>, comparator: Comparator<T>): Boolean {
        if (list.size < 2) return true
        for (i in 0 until list.size - 1) {
            if (comparator.compare(list[i], list[i + 1]) > 0) return false
        }
        return true
    }

    /**
     * 检查列表是否已按自然顺序排序。
     *
     * @param list 待检查的列表
     * @return 如果已排序返回 true
     */
    fun <T : Comparable<T>> isSorted(list: List<T>): Boolean {
        if (list.size < 2) return true
        for (i in 0 until list.size - 1) {
            if (list[i] > list[i + 1]) return false
        }
        return true
    }

    /**
     * 检查列表中所有元素是否唯一（无重复）
     *
     * @param list 待检查的列表
     * @return 如果所有元素唯一则返回 true
     */
    fun <T> allUnique(list: List<T>): Boolean {
        return list.size == list.toSet().size
    }

    /**
     * 检查列表中的所有元素是否都不同（无重复）。
     *
     * @param list 待检查的列表
     * @return 如果所有元素唯一返回 true
     */
    fun <T> isDistinct(list: List<T>): Boolean {
        return allUnique(list)
    }

    /**
     * 将列表元素连接为字符串。
     *
     * @param list 原始列表
     * @param separator 分隔符
     * @return 连接后的字符串
     */
    fun <T> asString(list: List<T>, separator: String = ", "): String {
        return list.joinToString(separator)
    }

    /**
     * 将列表元素以指定前缀、分隔符和后缀格式化为字符串。
     *
     * @param list 原始列表
     * @param prefix 前缀
     * @param separator 分隔符
     * @param suffix 后缀
     * @return 格式化后的字符串
     */
    fun <T> joinTo(list: List<T>, prefix: String = "", separator: String = ", ", suffix: String = ""): String {
        return prefix + list.joinToString(separator) + suffix
    }

    /**
     * 判断两个列表是否不完全相同。
     *
     * @param a 第一个列表
     * @param b 第二个列表
     * @return 如果不同返回 true
     */
    fun <T> differsFrom(a: List<T>, b: List<T>): Boolean {
        return a != b
    }

    /**
     * 计算两个列表的对称差集。
     *
     * @param a 第一个列表
     * @param b 第二个列表
     * @return 对称差集列表
     */
    fun <T> symmetricDifference(a: List<T>, b: List<T>): List<T> {
        val setA = a.toSet()
        val setB = b.toSet()
        return (setA - setB).union(setB - setA).toList()
    }

    /**
     * 批量处理列表（按固定大小分批返回）。
     *
     * @param list 原始列表
     * @param size 每批大小
     * @return 批次列表
     */
    fun <T> batched(list: List<T>, size: Int): List<List<T>> {
        return chunked(list, size)
    }

    /**
     * 查找列表中的所有重复元素。
     *
     * @param list 原始列表
     * @return 重复元素列表
     */
    fun <T> findDuplicates(list: List<T>): List<T> {
        val seen = mutableSetOf<T>()
        val duplicates = mutableSetOf<T>()
        for (element in list) {
            if (!seen.add(element)) {
                duplicates.add(element)
            }
        }
        return duplicates.toList()
    }

    /**
     * 移除列表中的所有重复元素，保留首次出现的顺序。
     *
     * @param list 原始列表
     * @return 去重后的列表
     */
    fun <T> removeDuplicates(list: List<T>): List<T> {
        val seen = mutableSetOf<T>()
        val result = mutableListOf<T>()
        for (element in list) {
            if (seen.add(element)) {
                result.add(element)
            }
        }
        return result
    }

    /**
     * 计算列表的平均值，列表为空时返回 null。
     *
     * @param list 数值列表
     * @return 平均值或 null
     */
    fun averageOrNull(list: List<Number>): Double? {
        if (list.isEmpty()) return null
        return list.sumOf { it.toDouble() } / list.size
    }

    /**
     * 计算列表的中位数，列表为空时返回 null。
     *
     * @param list 数值列表
     * @return 中位数或 null
     */
    fun medianOrNull(list: List<Double>): Double? {
        if (list.isEmpty()) return null
        return median(list)
    }

    /**
     * 计算列表的众数（出现最频繁的值），列表为空时返回 null。
     *
     * @param list 数值列表
     * @return 众数或 null
     */
    fun modeOrNull(list: List<Double>): Double? {
        if (list.isEmpty()) return null
        return modeOf(list)
    }

    /**
     * 取列表中元素直到第一个满足谓词的元素为止（包含该元素）。
     *
     * @param list 原始列表
     * @param predicate 条件谓词
     * @return 符合条件的头部元素列表
     */
    fun <T> takeUntil(list: List<T>, predicate: (T) -> Boolean): List<T> {
        val result = mutableListOf<T>()
        for (element in list) {
            result.add(element)
            if (predicate(element)) break
        }
        return result
    }

    /**
     * 丢弃列表中元素直到第一个满足谓词的元素为止（包含该元素）。
     *
     * @param list 原始列表
     * @param predicate 条件谓词
     * @return 丢弃头部后的剩余元素列表
     */
    fun <T> dropUntil(list: List<T>, predicate: (T) -> Boolean): List<T> {
        val index = list.indexOfFirst(predicate)
        return if (index >= 0) list.drop(index) else emptyList()
    }

    /**
     * 在每两个元素之间插入指定元素。
     *
     * @param list 原始列表
     * @param element 待插入的元素
     * @return 插入后的列表
     */
    fun <T> intersperse(list: List<T>, element: T): List<T> {
        val result = mutableListOf<T>()
        var first = true
        for (item in list) {
            if (!first) result.add(element)
            result.add(item)
            first = false
        }
        return result
    }

    /**
     * 查找元素在列表中的所有索引位置。
     *
     * @param list 原始列表
     * @param element 待查找的元素
     * @return 索引位置列表
     */
    fun <T> indexesOf(list: List<T>, element: T): List<Int> {
        val indexes = mutableListOf<Int>()
        for ((index, value) in list.withIndex()) {
            if (value == element) indexes.add(index)
        }
        return indexes
    }

    /**
     * 查找第一个重复元素的索引。
     *
     * @param list 原始列表
     * @return 第一个重复元素的索引，无重复返回 -1
     */
    fun <T> indexOfFirstDuplicate(list: List<T>): Int {
        val seen = mutableSetOf<T>()
        for ((index, element) in list.withIndex()) {
            if (!seen.add(element)) return index
        }
        return -1
    }

    /**
     * 计算列表的累积和。
     *
     * @param values 数值列表
     * @return 累积和列表
     */
    fun cumulativeSum(values: List<Number>): List<Double> {
        val result = mutableListOf<Double>()
        var sum = 0.0
        for (v in values) {
            sum += v.toDouble()
            result.add(sum)
        }
        return result
    }
}
