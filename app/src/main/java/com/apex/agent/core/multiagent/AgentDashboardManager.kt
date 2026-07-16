package com.apex.agent.core.multiagent

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

data class SystemSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val overallHealth: Float,
    val agentCount: Int,
    val activeTasks: Int,
    val completedTasks: Int,
    val avgResponseTime: Float,
    val memoryUsage: Float,
    val topPerformingAgents: List<Pair<String, Float>>,
    val pendingCapacity: Float
)

data class SystemAlert(
    val id: String = java.util.UUID.randomUUID().toString(),
    val level: AlertLevel,
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val resolved: Boolean = false
)

enum class AlertLevel {
    INFO, WARNING, ERROR, CRITICAL
}

class AgentDashboardManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val snapshots = mutableListOf<SystemSnapshot>()
    private val alerts = mutableListOf<SystemAlert>()
    private val maxSnapshots = 100
    private val maxAlerts = 50

    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState

    data class DashboardState(
        val currentSnapshot: SystemSnapshot? = null,
        val recentAlerts: List<SystemAlert> = emptyList(),
        val historySize: Int = 0,
        val isMonitoring: Boolean = false
    )

    init { startMonitoring() }

    private fun startMonitoring() {
        scope.launch {
            _dashboardState.value = _dashboardState.value.copy(isMonitoring = true)
            while (isActive) {
                updateSnapshot()
                checkForAlerts()
                delay(5000)
            }
        }
    }

    private fun updateSnapshot() {
        val snapshot = SystemSnapshot(overallHealth = calculateOverallHealth(), agentCount = 3, activeTasks = 5, completedTasks = 128, avgResponseTime = 1.8f, memoryUsage = 0.45f, topPerformingAgents = listOf("agent_1" to 0.95f, "agent_2" to 0.88f, "agent_3" to 0.82f), pendingCapacity = 0.7f)
        snapshots.add(snapshot)
        while (snapshots.size > maxSnapshots) { snapshots.removeAt(0) }
        _dashboardState.value = _dashboardState.value.copy(currentSnapshot = snapshot, historySize = snapshots.size)
    }

    private fun calculateOverallHealth(): Float = 0.92f

    private fun checkForAlerts() {
        val snapshot = _dashboardState.value.currentSnapshot ?: return
        val newAlerts = mutableListOf<SystemAlert>()

        if (snapshot.memoryUsage > 0.8f) {
            newAlerts.add(SystemAlert(level = AlertLevel.WARNING, title = "内存使用过高", message = "当前内存使用�? ${(snapshot.memoryUsage * 100).toInt()}%"))
        }
        if (snapshot.overallHealth < 0.7f) {
            newAlerts.add(SystemAlert(level = AlertLevel.CRITICAL, title = "系统健康度下�?, message = "系统健康�? ${(snapshot.overallHealth * 100).toInt()}%"))
        }

        if (newAlerts.isNotEmpty()) {
            alerts.addAll(0, newAlerts)
            while (alerts.size > maxAlerts) { alerts.removeAt(maxAlerts) }
            _dashboardState.value = _dashboardState.value.copy(recentAlerts = alerts.take(10))
        }
    }

    fun getHistory(lastMinutes: Int = 30): List<SystemSnapshot> {
        val cutoffTime = System.currentTimeMillis() - (lastMinutes * 60 * 1000L)
        return snapshots.filter { it.timestamp >= cutoffTime }
    }

    fun getCriticalAlerts(): List<SystemAlert> = alerts.filter { !it.resolved && it.level.ordinal >= AlertLevel.ERROR.ordinal }
    fun getAlertsForComponent(component: String): List<SystemAlert> = alerts.filter { it.title.contains(component, ignoreCase = true) }

    fun resolveAlert(alertId: String) {
        alerts.find { it.id == alertId }?.let { alert ->
            val index = alerts.indexOf(alert)
            if (index != -1) {
                alerts[index] = alert.copy(resolved = true)
                _dashboardState.value = _dashboardState.value.copy(recentAlerts = alerts.take(10))
            }
        }
    }

    fun generateReport(): SystemReport {
        return SystemReport(periodStart = snapshots.firstOrNull()?.timestamp, periodEnd = snapshots.lastOrNull()?.timestamp, avgHealth = snapshots.map { it.overallHealth }.average().toFloat(), totalTasksCompleted = snapshots.lastOrNull()?.completedTasks ?: 0, alertsGenerated = alerts.size, criticalEvents = alerts.count { it.level == AlertLevel.CRITICAL })
    }

    data class SystemReport(
        val periodStart: Long?,
        val periodEnd: Long?,
        val avgHealth: Float,
        val totalTasksCompleted: Int,
        val alertsGenerated: Int,
        val criticalEvents: Int
    )

    fun shutdown() {
        scope.cancel()
        _dashboardState.value = _dashboardState.value.copy(isMonitoring = false)
    }
}
