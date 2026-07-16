package com.apex.agent.core.tools.skill

import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class BreakpointManager {

    companion object {
        private const val TAG = "BreakpointManager"
    }

    private val breakpoints = ConcurrentHashMap<String, SkillDebugger.Breakpoint>()
    private val breakpointIdCounter = AtomicLong(0)

    fun addBreakpoint(type: SkillDebugger.BreakpointType, target: String, condition: String? = null): SkillDebugger.Breakpoint {
        val id = "bp_${breakpointIdCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        val breakpoint = SkillDebugger.Breakpoint(
            id = id,
            type = type,
            target = target,
            condition = condition
        )
        breakpoints[id] = breakpoint
        AppLogger.d(TAG, "Breakpoint added: id=${id} type=${type} target=${target} condition=${condition}")
        return breakpoint
    }

    fun removeBreakpoint(id: String): Boolean {
        val removed = breakpoints.remove(id) != null
        if (removed) {
            AppLogger.d(TAG, "Breakpoint removed: id=${id}")
        }
        return removed
    }

    fun getBreakpoint(id: String): SkillDebugger.Breakpoint? {
        return breakpoints[id]
    }

    fun getAllBreakpoints(): List<SkillDebugger.Breakpoint> {
        return breakpoints.values.toList()
    }

    fun getBreakpointsByType(type: SkillDebugger.BreakpointType): List<SkillDebugger.Breakpoint> {
        return breakpoints.values.filter { it.type == type }
    }

    fun getBreakpointsByTarget(target: String): List<SkillDebugger.Breakpoint> {
        return breakpoints.values.filter { it.target == target }
    }

    fun setBreakpointEnabled(id: String, enabled: Boolean): Boolean {
        val breakpoint = breakpoints[id] ?: return false
        breakpoints[id] = breakpoint.copy(enabled = enabled)
        AppLogger.d(TAG, "Breakpoint ${id} enabled=${enabled}")
        return true
    }

    fun setBreakpointCondition(id: String, condition: String): Boolean {
        val breakpoint = breakpoints[id] ?: return false
        breakpoints[id] = breakpoint.copy(condition = condition)
        AppLogger.d(TAG, "Breakpoint ${id} condition=${condition}")
        return true
    }

    fun clearAllBreakpoints() {
        breakpoints.clear()
        AppLogger.d(TAG, "All breakpoints cleared")
    }

    fun removeBreakpointsByType(type: SkillDebugger.BreakpointType): Int {
        val toRemove = breakpoints.values.filter { it.type == type }.map { it.id }
        toRemove.forEach { breakpoints.remove(it) }
        AppLogger.d(TAG, "Removed ${toRemove.size} breakpoints of type ${type}")
        return toRemove.size
    }

    fun removeBreakpointsByTarget(target: String): Int {
        val toRemove = breakpoints.values.filter { it.target == target }.map { it.id }
        toRemove.forEach { breakpoints.remove(it) }
        AppLogger.d(TAG, "Removed ${toRemove.size} breakpoints targeting ${target}")
        return toRemove.size
    }

    fun getEnabledBreakpoints(): List<SkillDebugger.Breakpoint> {
        return breakpoints.values.filter { it.enabled }
    }

    fun getDisabledBreakpoints(): List<SkillDebugger.Breakpoint> {
        return breakpoints.values.filter { !it.enabled }
    }

    fun getTotalHitCount(): Long {
        return breakpoints.values.sumOf { it.hitCount.get() }
    }

    fun getBreakpointStats(): BreakpointStats {
        return BreakpointStats(
            totalBreakpoints = breakpoints.size,
            enabledBreakpoints = getEnabledBreakpoints().size,
            disabledBreakpoints = getDisabledBreakpoints().size,
            toolCallBreakpoints = getBreakpointsByType(SkillDebugger.BreakpointType.TOOL_CALL).size,
            lineNumberBreakpoints = getBreakpointsByType(SkillDebugger.BreakpointType.LINE_NUMBER).size,
            conditionBreakpoints = getBreakpointsByType(SkillDebugger.BreakpointType.CONDITION).size,
            totalHits = getTotalHitCount()
        )
    }

    fun exportBreakpoints(): List<BreakpointExport> {
        return breakpoints.values.map { bp ->
            BreakpointExport(
                type = bp.type.name,
                target = bp.target,
                condition = bp.condition,
                enabled = bp.enabled,
                hitCount = bp.hitCount.get()
            )
        }
    }

    fun importBreakpoints(breakpoints: List<BreakpointExport>) {
        clearAllBreakpoints()
        breakpoints.forEach { bp ->
            val type = SkillDebugger.BreakpointType.valueOf(bp.type)
            addBreakpoint(type, bp.target, bp.condition)
            if (!bp.enabled) {
                val created = breakpoints.lastOrNull { it.target == bp.target && it.type == bp.type }
                created?.let { setBreakpointEnabled(it.target, false) }
            }
        }
        AppLogger.d(TAG, "Imported ${breakpoints.size} breakpoints")
    }

    data class BreakpointStats(
        val totalBreakpoints: Int,
        val enabledBreakpoints: Int,
        val disabledBreakpoints: Int,
        val toolCallBreakpoints: Int,
        val lineNumberBreakpoints: Int,
        val conditionBreakpoints: Int,
        val totalHits: Long
    )

    data class BreakpointExport(
        val type: String,
        val target: String,
        val condition: String?,
        val enabled: Boolean,
        val hitCount: Long
    )
}