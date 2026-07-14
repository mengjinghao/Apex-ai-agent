package com.apex.agent.core.patterns

/**
 * 解释器模式 - Agent 指令语言解释器
 * 定义脚本语言的文法，解释执行简单的 Agent 指令
 */

/** 表达式接口 */
interface Expression {
    fun interpret(context: ScriptContext): Any?
    fun accept(visitor: ExpressionVisitor): Any?
}

/** 脚本上下文 */
class ScriptContext {
    private val variables = mutableMapOf<String, Any?>()
        fun setVariable(name: String, value: Any?) { variables[name] = value }
        fun getVariable(name: String): Any? = variables[name]
    fun hasVariable(name: String): Boolean = name in variables
    fun clear() = variables.clear()
}

/** 值表达式（终端） */
class ValueExpression(private val value: Any?) : Expression {
    override fun interpret(context: ScriptContext): Any? = value
    override fun accept(visitor: ExpressionVisitor): Any? = visitor.visitValue(this)
        fun getValue(): Any? = value
}

/** 变量表达式（终端） */
class VariableExpression(private val name: String) : Expression {
    override fun interpret(context: ScriptContext): Any? {
        return context.getVariable(name) ?: throw IllegalStateException("Undefined variable: $name")
    }
        override fun accept(visitor: ExpressionVisitor): Any? = visitor.visitVariable(this)
        fun getName(): String = name
}

/** 函数表达式（终端） */
class FunctionExpression(private val functionName: String, private val args: List<Expression>) : Expression {
    override fun interpret(context: ScriptContext): Any? {
        val resolvedArgs = args.map { it.interpret(context) }
        return when (functionName.lowercase()) {
            "len" -> (resolvedArgs[0] as? String)?.length
            "upper" -> (resolvedArgs[0] as? String)?.uppercase()
            "lower" -> (resolvedArgs[0] as? String)?.lowercase()
            "concat" -> resolvedArgs.joinToString("")
            "trim" -> (resolvedArgs[0] as? String)?.trim()
        else -> throw IllegalStateException("Unknown function: $functionName")
        }
    }
        override fun accept(visitor: ExpressionVisitor): Any? = visitor.visitFunction(this)
}

/** 二元表达式（非终端） */
class BinaryExpression(
    private val left: Expression,
    private val operator: String,
    private val right: Expression
) : Expression {
    override fun interpret(context: ScriptContext): Any? {
        val l = left.interpret(context)
        val r = right.interpret(context)
        return when (operator) {
            "+" -> when { l is Number && r is Number -> l.toDouble() + r.toDouble(); l is String -> l + r; else -> null }
            "-" -> if (l is Number && r is Number) l.toDouble() - r.toDouble() else null
            "*" -> if (l is Number && r is Number) l.toDouble() * r.toDouble() else null
            "/" -> if (l is Number && r is Number) l.toDouble() / r.toDouble() else null
            "==" -> l == r
            "!=" -> l != r
            ">" -> if (l is Comparable<*> && r is Comparable<*>) @Suppress("UNCHECKED_CAST") (l as Comparable<Any>) > (r as Comparable<Any>) else null
            "<" -> if (l is Comparable<*> && r is Comparable<*>) @Suppress("UNCHECKED_CAST") (l as Comparable<Any>) < (r as Comparable<Any>) else null
            "and" -> l == true && r == true
            "or" -> l == true || r == true
            else -> throw IllegalStateException("Unknown operator: $operator")
        }
    }
        override fun accept(visitor: ExpressionVisitor): Any? = visitor.visitBinary(this)
}

/** 一元表达式（非终端） */
class UnaryExpression(private val operator: String, private val operand: Expression) : Expression {
    override fun interpret(context: ScriptContext): Any? {
        val value = operand.interpret(context)
        return when (operator) {
            "not" -> value != true
            "-" -> if (value is Number) -value.toDouble() else null
            else -> throw IllegalStateException("Unknown unary operator: $operator")
        }
    }
        override fun accept(visitor: ExpressionVisitor): Any? = visitor.visitUnary(this)
}

/** 条件表达式（非终端） */
class ConditionalExpression(
    private val condition: Expression,
    private val thenBranch: Expression,
    private val elseBranch: Expression? = null
) : Expression {
    override fun interpret(context: ScriptContext): Any? {
        return if (condition.interpret(context) == true) thenBranch.interpret(context)
        else elseBranch?.interpret(context)
    }
        override fun accept(visitor: ExpressionVisitor): Any? = visitor.visitConditional(this)
}

/** 复合表达式（非终端） */
class CompoundExpression(private val expressions: List<Expression>) : Expression {
    override fun interpret(context: ScriptContext): Any? {
        var result: Any? = null
        for (expr in expressions) result = expr.interpret(context)
        return result
    }
        override fun accept(visitor: ExpressionVisitor): Any? = visitor.visitCompound(this)
}

/** 表达式访问者 */
interface ExpressionVisitor {
    fun visitValue(expr: ValueExpression): Any?
    fun visitVariable(expr: VariableExpression): Any?
    fun visitFunction(expr: FunctionExpression): Any?
    fun visitBinary(expr: BinaryExpression): Any?
    fun visitUnary(expr: UnaryExpression): Any?
    fun visitConditional(expr: ConditionalExpression): Any?
    fun visitCompound(expr: CompoundExpression): Any?
}

/** 表达式优化器 */
class ExpressionOptimizer : ExpressionVisitor {
    override fun visitValue(expr: ValueExpression): Any? = expr
    override fun visitVariable(expr: VariableExpression): Any? = expr
    override fun visitFunction(expr: FunctionExpression): Any? = expr
    override fun visitBinary(expr: BinaryExpression): Any? = expr
    override fun visitUnary(expr: UnaryExpression): Any? = expr
    override fun visitConditional(expr: ConditionalExpression): Any? = expr
    override fun visitCompound(expr: CompoundExpression): Any? = expr
}

/** 表达式解析器 */
class ExpressionParser(private val input: String) {
    private var pos = 0

    fun parse(): Expression {
        return parseCompound()
    }
        private fun parseCompound(): CompoundExpression {
        val exprs = mutableListOf<Expression>()
        while (pos < input.length) {
            skipWhitespace()
        if (pos >= input.length) break
            exprs.add(parseConditional())
        skipWhitespace()
        if (pos < input.length && input[pos] == ';') pos++
        }
        return CompoundExpression(exprs)
    }
        private fun parseConditional(): Expression {
        val expr = parseOr()
        skipWhitespace()
        if (pos + 2 < input.length && input.substring(pos, pos + 3) == "if ") {
            val condition = parseConditional()
        return ConditionalExpression(condition, expr)
        }
        return expr
    }
        private fun parseOr(): Expression {
        var left = parseAnd()
        skipWhitespace()
        while (pos + 2 < input.length && input.substring(pos, pos + 3) == "or ") {
            pos += 3
            val right = parseAnd()
        left = BinaryExpression(left, "or", right)
        skipWhitespace()
        }
        return left
    }
        private fun parseAnd(): Expression {
        var left = parseComparison()
        skipWhitespace()
        while (pos + 3 < input.length && input.substring(pos, pos + 4) == "and ") {
            pos += 4
            val right = parseComparison()
        left = BinaryExpression(left, "and", right)
        skipWhitespace()
        }
        return left
    }
        private fun parseComparison(): Expression {
        var left = parseTerm()
        skipWhitespace()
        while (pos < input.length) {
            val op = when {
                input.substring(pos).startsWith("==") -> { pos += 2; "==" }
        input.substring(pos).startsWith("!=") -> { pos += 2; "!=" }
        input.substring(pos).startsWith(">=") -> { pos += 2; ">=" }
        input.substring(pos).startsWith("<=") -> { pos += 2; "<=" }
        input[pos] == '>' -> { pos++; ">" }
        input[pos] == '<' -> { pos++; "<" }
        else -> null
            }
        if (op != null) {
                skipWhitespace()
        left = BinaryExpression(left, op, parseTerm())
            } else break
            skipWhitespace()
        }
        return left
    }
        private fun parseTerm(): Expression {
        var left = parseFactor()
        skipWhitespace()
        while (pos < input.length && (input[pos] == '+' || input[pos] == '-')) {
            val op = input[pos].toString(); pos++
            skipWhitespace()
        left = BinaryExpression(left, op, parseFactor())
        skipWhitespace()
        }
        return left
    }
        private fun parseFactor(): Expression {
        var left = parseUnary()
        skipWhitespace()
        while (pos < input.length && (input[pos] == '*' || input[pos] == '/')) {
            val op = input[pos].toString(); pos++
            skipWhitespace()
        left = BinaryExpression(left, op, parseUnary())
        skipWhitespace()
        }
        return left
    }
        private fun parseUnary(): Expression {
        skipWhitespace()
        if (pos < input.length && input[pos] == '!') {
            pos++; skipWhitespace()
        return UnaryExpression("not", parsePrimary())
        }
        if (pos < input.length && input[pos] == '-') {
            pos++; skipWhitespace()
        return UnaryExpression("-", parsePrimary())
        }
        return parsePrimary()
    }
        private fun parsePrimary(): Expression {
        skipWhitespace()
        if (pos >= input.length) throw IllegalStateException("Unexpected end of input")
        return when {
            input[pos] == '(' -> {
                pos++; val expr = parseConditional()
        if (pos >= input.length || input[pos] != ')') throw IllegalStateException("Missing )")
        pos++; expr
            }
        input[pos] == '"' || input[pos] == '\'' -> { val quote = input[pos]; pos++ val start = pos while (pos < input.length && input[pos] != quote) pos++ val str = input.substring(start, pos) if (pos < input.length) pos++ ValueExpression(str) } input[pos].isDigit() -> { val start = pos while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++ val num = input.substring(start, pos) ValueExpression(if (num.contains('.')) num.toDouble() else num.toInt()) } input[pos] == 't' && input.substring(pos).startsWith("true") -> { pos += 4; ValueExpression(true) } input[pos] == 'f' && input.substring(pos).startsWith("false") -> { pos += 5; ValueExpression(false) } else -> { val start = pos while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) pos++ val name = input.substring(start, pos) skipWhitespace() if (pos < input.length && input[pos] == '(') { pos++; val args = mutableListOf<Expression>() while (pos < input.length && input[pos] != ')') { skipWhitespace() if (args.isNotEmpty()) { if (input[pos] == ',') pos++; skipWhitespace() } args.add(parseConditional()) skipWhitespace() } if (pos < input.length) pos++ FunctionExpression(name, args) } else { VariableExpression(name) } } } } private fun skipWhitespace() { while (pos < input.length && input[pos].isWhitespace()) pos++ } }  /** Agent 脚本解释器 */ class AgentScriptInterpreter { private val context = ScriptContext() private val parser: ExpressionParser? = null  fun execute(script: String): Any? { val parser = ExpressionParser(script) val ast = parser.parse() return ast.interpret(context) } fun setVariable(name: String, value: Any?) { context.setVariable(name, value) } fun getContext(): ScriptContext = context fun clearContext() = context.clear() }
