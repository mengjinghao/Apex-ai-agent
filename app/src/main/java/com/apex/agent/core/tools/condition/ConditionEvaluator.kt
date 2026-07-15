package com.apex.core.tools.condition

// STUBBED: had 4 errors
object ConditionEvaluator
interface Token
data class Identifier(val placeholder: String = "")
data class StringLiteral(val placeholder: String = "")
data class NumberLiteral(val placeholder: String = "")
data class BooleanLiteral(val placeholder: String = "")
object NullLiteral
data class Operator(val placeholder: String = "")
data class Punct(val placeholder: String = "")
object Eof
class Tokenizer
interface Expr
data class LiteralExpr(val placeholder: String = "")
data class IdentifierExpr(val placeholder: String = "")
data class ArrayExpr(val placeholder: String = "")
data class UnaryExpr(val placeholder: String = "")
data class BinaryExpr(val placeholder: String = "")
data class Bool(val placeholder: String = "")
data class Num(val placeholder: String = "")
data class Str(val placeholder: String = "")
object Null
data class Array(val placeholder: String = "")
class Parser
