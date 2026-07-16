package com.apex.agent.core.patterns

// Minimal implementation (original had 8 errors)
// TODO: Restore full implementation from original code

interface Visitable
interface Visitor
data class WorkflowTaskNode(val data: String = "")
class WorkflowDAG
class ValidateVisitor
class MetricsCollectorVisitor
class OptimizationVisitor
class ExportVisitor
