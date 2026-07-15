package com.apex.agent.common.extensions

import kotlin.random.Random

/**
 * 高级集合扩展函数集合，提供分区、聚合、统计、排序等进阶操作。
 */

/**
 * 根据谓词将集合分为多个连续块，每块中所有元素都满足或都不满足谓词。
 *
 * @param predicate 分组谓词
 * @return 连续块的列表
 */
fun <T> Iterable<T>.chunkedWhile(predicate: (T) -> Boolean): List<List<T>> {
    if (this is Collection && isEmpty()) return emptyList()
        val result = mutableListOf<List<T>>()
        val current = mutableListOf<T>()
        var lastState: Boolean? = null
    for (element in this) {
        val state = predicate(element)
        if (lastState != null && state != lastState && current.isNotEmpty()) {
            result.add(current.toList())
            current.clear()
        }
        current.add(element)
        lastState = state
    }
        if (current.isNotEmpty()) result.add(current)
        return result
}

/**
 * 取集合中元素直到第一个不满足谓词的元素为止（包含该元素）。
 *
 * @param predicate 条件谓词
 * @return 满足条件的头部元素列表
 */
fun <T> Iterable<T>.takeUntil(predicate: (T) -> Boolean): List<T> {
    val result = mutableListOf<T>()
        for (element in this) {
        result.add(element)
        if (predicate(element)) break
    }
        return result
}

/**
 * 丢弃集合中元素直到第一个满足谓词的元素为止（包含该元素）。
 *
 * @param predicate 条件谓词
 * @return 丢弃头部后的剩余元素列表
 */
fun <T> Iterable<T>.dropUntil(predicate: (T) -> Boolean): List<T> {
    val list = this.toList()
        val index = list.indexOfFirst(predicate)
        return if (index >= 0) list.drop(index) else emptyList()
}

/**
 * 在每两个元素之间插入指定元素。
 *
 * @param element 待插入的元素
 * @return 插入后的列表
 */
fun <T> Iterable<T>.intersperse(element: T): List<T> {
    val result = mutableListOf<T>()
        var first = true
    for (item in this) {
        if (!first) result.add(element)
        result.add(item)
        first = false
    }
        return result
}

/**
 * 查找所有重复出现的元素（出现次数 > 1）。
 *
 * @return 重复元素列表
 */
fun <T> Iterable<T>.findDuplicates(): List<T> {
    val seen = mutableSetOf<T>()
        val duplicates = mutableListOf<T>()
        for (element in this) {
        if (!seen.add(element)) {
            duplicates.add(element)
        }
    }
        return duplicates.distinct()
}

/**
 * 获取所有元素的索引位置映射。
 *
 * @param element 待查找的元素
 * @return 索引位置列表
 */
fun <T> Iterable<T>.indexesOf(element: T): List<Int> {
    val indexes = mutableListOf<Int>()
        for ((index, value) in this.withIndex()) {
        if (value == element) indexes.add(index)
    }
        return indexes
}

/**
 * 查找第一个重复元素的索引。
 *
 * @return 第一个重复元素的索引，无重复返回 -1
 */
fun <T> Iterable<T>.indexOfFirstDuplicate(): Int {
    val seen = mutableSetOf<T>()
        for ((index, element) in this.withIndex()) {
        if (!seen.add(element)) return index
    }
        return -1
}

/**
 * 判断集合中是否包含循环依赖（需要元素可标识唯一性）。
 * 对于存在自引用的结构，通过 identityHashCode 检测循环。
 *
 * @param nextExtractor 提取下一个元素的函数
 * @return 是否存在循环
 */
fun <T> Iterable<T>.hasCycle(nextExtractor: (T) -> T?): Boolean {
    val visited = mutableSetOf<Int>()
        for (element in this) {
        var current: T? = element
        val path = mutableSetOf<Int>()
        while (current != null) {
            val id = System.identityHashCode(current)
        if (!path.add(id)) return true
            if (!visited.add(id)) break
            current = nextExtractor(current)
        }
    }
        return false
}

/**
 * 对集合进行拓扑排序。
 *
 * @param dependencyExtractor 提取依赖项的函数
 * @return 拓扑排序后的列表，若存在循环依赖则返回 null
 */
fun <T> Iterable<T>.topologicalSort(dependencyExtractor: (T) -> Iterable<T>): List<T>? {
    val sorted = mutableListOf<T>()
        val visited = mutableSetOf<Int>()
        val visiting = mutableSetOf<Int>()
        fun dfs(node: T): Boolean {
        val id = System.identityHashCode(node)
        if (id in visiting) return false
        if (id in visited) return true
        visiting.add(id)
        for (dep in dependencyExtractor(node)) {
            if (!dfs(dep)) return false
        }
        visiting.remove(id)
        visited.add(id)
        sorted.add(node)
        return true
    }
        for (element in this) {
        if (System.identityHashCode(element) !in visited) {
            if (!dfs(element)) return null
        }
    }
        return sorted
}

/**
 * 计算集合的累积和。
 *
 * @return 累积和列表
 */
fun Iterable<Number>.cumulativeSum(): List<Double> {
    val result = mutableListOf<Double>()
        var sum = 0.0
    for (element in this) {
        sum += element.toDouble()
        result.add(sum)
    }
        return result
}

/**
 * 检查是否有任何元素相等（用于检测重复）。
 *
 * @return 如果有重复则返回 true
 */
fun <T> Iterable<T>.allUnique(): Boolean {
    val set = mutableSetOf<T>()
        for (element in this) {
        if (!set.add(element)) return false
    }
        return true
}

/**
 * 判断集合是否已按自然顺序排序。
 *
 * @return 如果已排序返回 true
 */
fun <T : Comparable<T>> Iterable<T>.isSorted(): Boolean {
    val iterator = this.iterator()
        if (!iterator.hasNext()) return true
    var previous = iterator.next()
    while (iterator.hasNext()) {
        val current = iterator.next()
        if (previous > current) return false
        previous = current
    }
        return true
}

/**
 * 判断集合中的所有元素是否都唯一（无重复）。
 *
 * @return 如果所有元素唯一返回 true
 */
fun <T> Iterable<T>.isDistinct(): Boolean = allUnique()

/**
 * 计算集合的平均值，集合为空时返回 null。
 *
 * @return 平均值或 null
 */
fun Iterable<Number>.averageOrNull(): Double? {
    val list = this.toList()
        if (list.isEmpty()) return null
    return list.sumOf { it.toDouble() } / list.size
}

/**
 * 计算集合的中位数，集合为空时返回 null。
 *
 * @return 中位数或 null
 */
fun Iterable<Number>.medianOrNull(): Double? {
    val sorted = this.map { it.toDouble() }.sorted()
        if (sorted.isEmpty()) return null
    val size = sorted.size
    return if (size % 2 == 1) sorted[size / 2]
    else (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0
}

/**
 * 计算集合的众数（出现频率最高的元素）。
 *
 * @return 众数，集合为空时返回 null
 */
fun <T> Iterable<T>.modeOrNull(): T? {
    val list = this.toList()
        if (list.isEmpty()) return null
    return list.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

/**
 * 使用分隔符和转换函数将集合连接为字符串。
 *
 * @param delimiter 分隔符
 * @param transform 元素转换函数
 * @return 连接后的字符串
 */
fun <T> Iterable<T>.toDelimitedString(delimiter: String = ",", transform: ((T) -> String)? = null): String {
    val fn = transform ?: { it.toString() }
        return this.joinToString(delimiter) { fn(it) }
}

/**
 * 获取集合中前 N 个最常见的元素。
 *
 * @param n 返回的元素数量
 * @return 最常见元素的列表
 */
fun <T> Iterable<T>.mostCommon(n: Int): List<T> {
    return this.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .take(n)
        .map { it.key }
}

/**
 * 获取集合中后 N 个最不常见的元素。
 *
 * @param n 返回的元素数量
 * @return 最不常见元素的列表
 */
fun <T> Iterable<T>.leastCommon(n: Int): List<T> {
    return this.groupingBy { it }.eachCount()
        .entries.sortedBy { it.value }
        .take(n)
        .map { it.key }
}

/**
 * 根据变换函数对元素进行计数分组。
 *
 * @param transform 变换函数
 * @return 变换结果到计数的映射
 */
fun <T, K> Iterable<T>.countBy(transform: (T) -> K): Map<K, Int> {
    return this.groupingBy(transform).eachCount()
}

/**
 * 根据变换函数分组后再统计每组元素数量。
 *
 * @param transform 变换函数
 * @return 变换结果到元素列表大小的映射
 */
fun <T, K> Iterable<T>.groupByCount(transform: (T) -> K): Map<K, Int> {
    return this.groupBy(transform).mapValues { it.value.size }
}

/**
 * 计算当前集合与另一集合的对称差集（在任一集合中但不在交集中的元素）。
 *
 * @param other 另一集合
 * @return 对称差集
 */
fun <T> Iterable<T>.symmetricDifference(other: Iterable<T>): Set<T> {
    val setA = this.toSet()
        val setB = other.toSet()
        return (setA - setB) union (setB - setA)
}

/**
 * 判断当前集合是否与另一集合有差异（元素不同或顺序不同）。
 *
 * @param other 另一集合
 * @return 如果有差异返回 true
 */
fun <T> Iterable<T>.differsFrom(other: Iterable<T>): Boolean {
    return this.toList() != other.toList()
}

/**
 * 返回此集合的一个随机打乱副本。
 *
 * @return 打乱后的列表
 */
fun <T> Iterable<T>.shuffled(): List<T> {
    return this.toList().apply { shuffle() }
}

/**
 * 返回集合中的一个随机元素，集合为空时返回 null。
 *
 * @return 随机元素或 null
 */
fun <T> Iterable<T>.randomElement(): T? {
    val list = this.toList()
        return if (list.isEmpty()) null else list[Random.nextInt(list.size)]
}

/**
 * 返回集合中的 N 个随机元素（可重复）。
 *
 * @param n 要返回的元素数量
 * @return 随机元素列表
 */
fun <T> Iterable<T>.randomElements(n: Int): List<T> {
    val list = this.toList()
        if (list.isEmpty() || n <= 0) return emptyList()
        return List(n) { list[Random.nextInt(list.size)] }
}

/**
 * 使用指定前缀、分隔符和后缀将集合连接为字符串。
 *
 * @param prefix 前缀
 * @param separator 分隔符
 * @param suffix 后缀
 * @return 格式化后的字符串
 */
fun <T> Iterable<T>.joinTo(prefix: String = "", separator: String = ", ", suffix: String = ""): String {
    return prefix + this.joinToString(separator) + suffix
}

/**
 * 将两个集合压缩为映射，第一个集合为键，第二个集合为值。
 *
 * @param keys 键集合
 * @param values 值集合
 * @return 键值对映射
 */
fun <K, V> toMap(keys: Iterable<K>, values: Iterable<V>): Map<K, V> {
    val keyList = keys.toList()
        val valueList = values.toList()
        val size = minOf(keyList.size, valueList.size)
        val map = mutableMapOf<K, V>()
        for (i in 0 until size) {
        map[keyList[i]] = valueList[i]
    }
        return map
}

/**
 * 将两个列表压缩为列表（处理不同长度，使用默认值填充）。
 *
 * @param other 第二个列表
 * @param defaultLeft 第一个列表的默认填充值
 * @param defaultRight 第二个列表的默认填充值
 * @return 成对列表
 */
fun <T, U> Iterable<T>.zipAll(other: Iterable<U>, defaultLeft: T, defaultRight: U): List<Pair<T, U>> {
    val listA = this.toList()
        val listB = other.toList()
        val maxSize = maxOf(listA.size, listB.size)
        val result = mutableListOf<Pair<T, U>>()
        for (i in 0 until maxSize) {
        val a = if (i < listA.size) listA[i] else defaultLeft
        val b = if (i < listB.size) listB[i] else defaultRight
        result.add(Pair(a, b))
    }
        return result
}

/**
 * 对可变集合添加元素（仅当元素不为 null 时）。
 *
 * @param element 待添加的元素
 * @return 如果添加成功返回 true
 */
fun <T> MutableCollection<T>.addIfNotNull(element: T?): Boolean {
    if (element != null) return add(element)
        return false
}

/**
 * 对可变集合添加元素（仅当元素尚不存在时）。
 *
 * @param element 待添加的元素
 * @return 如果添加成功返回 true
 */
fun <T> MutableCollection<T>.addIfAbsent(element: T): Boolean {
    if (element !in this) return add(element)
        return false
}

/**
 * 根据变换函数去除重复项。
 *
 * @param transform 变换函数
 * @return 去重后的列表
 */
fun <T, K> Iterable<T>.distinctBy(transform: (T) -> K): List<T> {
    val seen = mutableSetOf<K>()
        val result = mutableListOf<T>()
        for (element in this) {
        val key = transform(element)
        if (key in seen) continue
        seen.add(key)
        result.add(element)
    }
        return result
}

/**
 * 去除连续重复的元素。
 *
 * @return 去除连续重复后的列表
 */
fun <T> Iterable<T>.distinctConsecutive(): List<T> {
    val result = mutableListOf<T>()
        var previous: Any? = null
    for (element in this) {
        if (element != previous) {
            result.add(element)
            previous = element
        }
    }
        return result
}

/**
 * 将集合聚合为单个值（使用初始值和操作函数）。
 *
 * @param initial 初始值
 * @param operation 聚合操作
 * @return 聚合结果
 */
fun <T, R> Iterable<T>.aggregate(initial: R, operation: (R, T) -> R): R {
    var result = initial
    for (element in this) {
        result = operation(result, element)
    }
        return result
}
