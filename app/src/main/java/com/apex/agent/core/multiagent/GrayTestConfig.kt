package com.apex.agent.core.multiagent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class GrayTestConfig {

    data class GrayUser(
        val userId: String,
        val joinTime: Long,
        val isActive: Boolean
    )

    private val grayUsers = ConcurrentHashMap<String, GrayUser>()
    private val isGrayEnabled = AtomicBoolean(false)
    private var grayPercentage = 5 // 默认5%用户
    private val featureFlags = ConcurrentHashMap<String, Boolean>()

    fun enableGrayTest() {
        isGrayEnabled.set(true)
    }

    fun disableGrayTest() {
        isGrayEnabled.set(false)
    }

    fun isGrayTestEnabled(): Boolean = isGrayEnabled.get()

    fun setGrayPercentage(percentage: Int) {
        grayPercentage = percentage.coerceIn(1, 100)
    }

    fun getGrayPercentage(): Int = grayPercentage

    fun addGrayUser(userId: String): Boolean {
        if (grayUsers.size >= getMaxGrayUsers()) {
            return false
        }

        val user = GrayUser(
            userId = userId,
            joinTime = System.currentTimeMillis(),
            isActive = true
        )
        grayUsers[userId] = user
        return true
    }

    fun removeGrayUser(userId: String): Boolean {
        return grayUsers.remove(userId) != null
    }

    fun isGrayUser(userId: String): Boolean {
        return grayUsers.containsKey(userId) && grayUsers[userId]?.isActive ?: false
    }

    fun getGrayUsers(): List<GrayUser> {
        return grayUsers.values.toList()
    }

    fun getGrayUserCount(): Int {
        return grayUsers.size
    }

    fun getMaxGrayUsers(): Int {
        // 假设总用户数�?000，根据灰度比例计算最大灰度用户数
        val totalUsers = 10000
        return (totalUsers * grayPercentage / 100).coerceAtLeast(1)
    }

    fun toggleFeature(featureName: String, enabled: Boolean) {
        featureFlags[featureName] = enabled
    }

    fun isFeatureEnabled(featureName: String): Boolean {
        return featureFlags.getOrDefault(featureName, false)
    }

    fun getFeatureFlags(): Map<String, Boolean> {
        return featureFlags.toMap()
    }

    fun clearGrayUsers() {
        grayUsers.clear()
    }

    fun clearFeatureFlags() {
        featureFlags.clear()
    }

    fun reset() {
        disableGrayTest()
        setGrayPercentage(5)
        clearGrayUsers()
        clearFeatureFlags()
    }

    // 检查用户是否应该进入灰度测�?   fun shouldUserEnterGrayTest(userId: String): Boolean {
        if (!isGrayTestEnabled()) {
            return false
        }

        // 如果用户已经在灰度列表中，直接返回true
        if (isGrayUser(userId)) {
            return true
        }

        // 如果灰度用户数还没达到上限，加入灰度列表
        if (grayUsers.size < getMaxGrayUsers()) {
            return addGrayUser(userId)
        }

        // 否则，根据用户ID的哈希值和灰度比例决定
        val hash = userId.hashCode() % 100
        return hash < grayPercentage
    }
}
