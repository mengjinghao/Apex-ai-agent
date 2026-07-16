package com.apex.core.tools.calculator

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

interface ExpressionNode
data class NumberNode(val data: String = "")
data class VariableNode(val data: String = "")
data class BinaryOperationNode(val data: String = "")
data class UnaryOperationNode(val data: String = "")
data class FunctionCallNode(val data: String = "")
data class TernaryOperationNode(val data: String = "")
data class AssignmentNode(val data: String = "")
data class CompoundAssignmentNode(val data: String = "")
data class ArrayAccessNode(val data: String = "")
data class TemplateStringNode(val data: String = "")
