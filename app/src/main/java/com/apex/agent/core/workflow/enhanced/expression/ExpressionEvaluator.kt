package com.apex.agent.core.workflow.enhanced.expression

import java.util.Stack

/**
 * 工作流表达式引擎
 *
 * 支持复杂条件表达式，用于 CONDITION 节点和连接条件
 *
 * 参照 Dify 的条件表达式、Coze 的 If/Else 节点、SpEL（Spring Expression Language）
 *
 * 支持特性：
 * - 变量引用：`${nodeId.field}` 或 `$nodeId.field`
 * - 字符串字面量：'hello' 或 "hello"
 * - 数字字面量：123, 3.14
 * - 布尔字面量：true, false
 * - 算术运算：+ - * / % 
 * - 比较运算：== != > < >= <=
 * - 逻辑运算：&& || ! 
 * - 字符串运算：contains startsWith endsWith matches
 * - 三元运算：condition ? a : b
 * - 括号分组：(expr)
 * - 空值合并：a ?? b
 *
 * 示例：
 * - `${user.age} > 18 && ${user.country} == 'CN'`
 * - `${status} == 'success' ? '完成' : '失败'`
 * - `${message} contains 'error' || ${code} >= 500`
 * - `${value} ?? ${default}`
 */
class ExpressionEvaluator {

    private val tokenizer = Tokenizer()
        private val parser = Parser()

    /**
     * 求值表达式
     * @param expression 表达式字符串
     * @param context 变量上下文
     */
    fun evaluate(expression: String, context: Map<String, Any> = emptyMap()): Any? {
        val tokens = tokenizer.tokenize(expression)
        val ast = parser.parse(tokens)
        return ast.eval(context)
    }

    /**
     * 求值为布尔
     */
    fun evaluateBoolean(expression: String, context: Map<String, Any> = emptyMap()): Boolean {
        return when (val r = evaluate(expression, context)) {
            is Boolean -> r
            is String -> r.lowercase() in setOf("true", "1", "yes", "on")
        is Number -> r.toDouble() != 0.0
            null -> false
            else -> true
        }
    }

    /**
     * 求值为字符串
     */
    fun evaluateString(expression: String, context: Map<String, Any> = emptyMap()): String {
        return evaluate(expression, context)?.toString() ?: ""
    }

    /**
     * 求值为数字
     */
    fun evaluateNumber(expression: String, context: Map<String, Any> = emptyMap()): Double? {
        return when (val r = evaluate(expression, context)) {
            is Number -> r.toDouble()
        is String -> r.toDoubleOrNull()
        else -> null
        }
    }

    /**
     * 替换变量占位符（简单字符串模板）
     */
    fun interpolate(template: String, context: Map<String, Any>): String {
        var result = template
        val regex = Regex("\\$\\{([^}]+)}|\\\$([a-zA-Z_][a-zA-Z0-9_.]*)")
        regex.findAll(template).forEach { m ->
            val ref = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
        val value = resolveVariable(ref, context)?.toString() ?: ""
        result = result.replace(m.value, value)
        }
        return result
    }
        private fun resolveVariable(path: String, context: Map<String, Any>): Any? {
        val parts = path.split(".")
        var current: Any? = context[parts[0]] ?: context["__node_${parts[0]}_output"]
        for (i in 1 until parts.size) {
            current = when (current) {
                is Map<*, *> -> current[parts[i]]
                else -> return null
            }
        }
        return current
    }

    // ============ Tokenizer ============
        private class Tokenizer {
        fun tokenize(input: String): List<Token> {
            val tokens = mutableListOf<Token>()
        var i = 0
            val s = input.trim()
        while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c == '$' && i + 1 < s.length && s[i + 1] == '{' -> {
                        // ${var.path}
        val end = s.indexOf('}', i + 2)
        require(end > 0) { "未闭合的 \${" }
        val path = s.substring(i + 2, end)
        tokens.add(Token.Variable(path))
        i = end + 1
                    }
        c == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_') -> {
                        // $var.path
        var j = i + 1
                        while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '.')) j++
                        tokens.add(Token.Variable(s.substring(i + 1, j)))
        i = j
                    }
        c == '\'' || c == '"' -> { // 字符串字面量 val quote = c val sb = StringBuilder() var j = i + 1 while (j < s.length && s[j] != quote) { if (s[j] == '\\' && j + 1 < s.length) { sb.append(when (s[j + 1]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r' '\\' -> '\\'; quote -> quote else -> s[j + 1] }) j += 2 } else { sb.append(s[j]) j++ } } require(j < s.length) { "未闭合的字符串" } tokens.add(Token.StringLiteral(sb.toString())) i = j + 1 } c.isDigit() || (c == '-' && i + 1 < s.length && s[i + 1].isDigit() && (tokens.isEmpty() || tokens.last() is Token.Operator)) -> { // 数字 var j = if (c == '-') i + 1 else i var hasDot = false while (j < s.length && (s[j].isDigit() || s[j] == '.')) { if (s[j] == '.') hasDot = true j++ } val numStr = s.substring(i, j) tokens.add(Token.NumberLiteral(if (hasDot) numStr.toDouble() else numStr.toLong().toDouble())) i = j } c.isLetter() || c == '_' -> { // 标识符 / 关键字 var j = i while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++ val word = s.substring(i, j) tokens.add(when (word.lowercase()) { "true" -> Token.BooleanLiteral(true) "false" -> Token.BooleanLiteral(false) "null" -> Token.NullLiteral "and" -> Token.Operator("&&") "or" -> Token.Operator("||") "not" -> Token.Operator("!") else -> Token.Identifier(word) }) i = j } c in "+-*/%" -> { tokens.add(Token.Operator(c.toString())) i++ } c == '=' && i + 1 < s.length && s[i + 1] == '=' -> { tokens.add(Token.Operator("==")); i += 2 } c == '!' && i + 1 < s.length && s[i + 1] == '=' -> { tokens.add(Token.Operator("!=")); i += 2 } c == '>' && i + 1 < s.length && s[i + 1] == '=' -> { tokens.add(Token.Operator(">=")); i += 2 } c == '<' && i + 1 < s.length && s[i + 1] == '=' -> { tokens.add(Token.Operator("<=")); i += 2 } c == '>' -> { tokens.add(Token.Operator(">")); i++ } c == '<' -> { tokens.add(Token.Operator("<")); i++ } c == '&' && i + 1 < s.length && s[i + 1] == '&' -> { tokens.add(Token.Operator("&&")); i += 2 } c == '|' && i + 1 < s.length && s[i + 1] == '|' -> { tokens.add(Token.Operator("||")); i += 2 } c == '!' -> { tokens.add(Token.Operator("!")); i++ } c == '?' && i + 1 < s.length && s[i + 1] == '?' -> { tokens.add(Token.Operator("??")); i += 2 } c == '?' -> { tokens.add(Token.QuestionMark); i++ } c == ':' -> { tokens.add(Token.Colon); i++ } c == '(' -> { tokens.add(Token.LParen); i++ } c == ')' -> { tokens.add(Token.RParen); i++ } c == ',' -> { tokens.add(Token.Comma); i++ } c == '.' -> { tokens.add(Token.Dot); i++ } else -> throw IllegalArgumentException("无法识别的字符: $c (位置 $i)") } } return tokens } }  // ============ Token ============ sealed class Token { data class Variable(val path: String) : Token() data class StringLiteral(val value: String) : Token() data class NumberLiteral(val value: Double) : Token() data class BooleanLiteral(val value: Boolean) : Token() object NullLiteral : Token() data class Identifier(val name: String) : Token() data class Operator(val op: String) : Token() object QuestionMark : Token() object Colon : Token() object LParen : Token() object RParen : Token() object Comma : Token() object Dot : Token() }  // ============ AST ============ sealed class ASTNode { abstract fun eval(context: Map<String, Any>): Any?  data class Literal(val value: Any?) : ASTNode() { override fun eval(context: Map<String, Any>) = value } data class VariableRef(val path: String) : ASTNode() { override fun eval(context: Map<String, Any>): Any? { val parts = path.split(".") var current: Any? = context[parts[0]] ?: context["__node_${parts[0]}_output"] for (i in 1 until parts.size) { current = when (current) { is Map<*, *> -> current[parts[i]] else -> return null } } return current } } data class BinaryOp(val op: String, val left: ASTNode, val right: ASTNode) : ASTNode() { override fun eval(context: Map<String, Any>): Any? { val l = left.eval(context) val r = right.eval(context) return when (op) { "+" -> when { l is Number && r is Number -> l.toDouble() + r.toDouble() else -> (l?.toString() ?: "") + (r?.toString() ?: "") } "-" -> (l as Number).toDouble() - (r as Number).toDouble() "*" -> (l as Number).toDouble() * (r as Number).toDouble() "/" -> (l as Number).toDouble() / (r as Number).toDouble() "%" -> (l as Number).toDouble() % (r as Number).toDouble() "==" -> l == r "!=" -> l != r ">" -> cmp(l, r) > 0 "<" -> cmp(l, r) < 0 ">=" -> cmp(l, r) >= 0 "<=" -> cmp(l, r) <= 0 "&&" -> toBool(l) && toBool(r) "||" -> toBool(l) || toBool(r) "??" -> l ?: r else -> throw IllegalStateException("未知运算符: $op") } } private fun cmp(l: Any?, r: Any?): Int { return when { l is Number && r is Number -> l.toDouble().compareTo(r.toDouble()) else -> (l?.toString() ?: "").compareTo(r?.toString() ?: "") } } private fun toBool(v: Any?): Boolean = when (v) { is Boolean -> v is Number -> v.toDouble() != 0.0 is String -> v.lowercase() in setOf("true", "1", "yes", "on") null -> false else -> true } } data class UnaryOp(val op: String, val operand: ASTNode) : ASTNode() { override fun eval(context: Map<String, Any>): Any? { val v = operand.eval(context) return when (op) { "!" -> when (v) { is Boolean -> !v is Number -> v.toDouble() == 0.0 is String -> v.lowercase() !in setOf("true", "1", "yes", "on") null -> true else -> false } "-" -> -(v as Number).toDouble() else -> throw IllegalStateException("未知一元运算符: $op") } } } data class MethodCall(val receiver: ASTNode, val method: String, val args: List<ASTNode>) : ASTNode() { override fun eval(context: Map<String, Any>): Any? { val recv = receiver.eval(context)?.toString() ?: "" val argVals = args.map { it.eval(context) } return when (method.lowercase()) { "contains" -> recv.contains(argVals.firstOrNull()?.toString() ?: "") "startswith" -> recv.startsWith(argVals.firstOrNull()?.toString() ?: "") "endswith" -> recv.endsWith(argVals.firstOrNull()?.toString() ?: "") "matches" -> recv.matches(Regex(argVals.firstOrNull()?.toString() ?: "")) "length", "size" -> recv.length "uppercase", "upper" -> recv.uppercase() "lowercase", "lower" -> recv.lowercase() "trim" -> recv.trim() "replace" -> recv.replace(argVals[0]?.toString() ?: "", argVals.getOrNull(1)?.toString() ?: "") "substring" -> { val start = (argVals[0] as Number).toInt() val end = (argVals.getOrNull(1) as? Number)?.toInt() ?: recv.length recv.substring(start, end) } "indexof" -> recv.indexOf(argVals.firstOrNull()?.toString() ?: "") else -> throw IllegalStateException("未知方法: $method") } } } data class Ternary(val condition: ASTNode, val thenBranch: ASTNode, val elseBranch: ASTNode) : ASTNode() { override fun eval(context: Map<String, Any>): Any? { val cond = condition.eval(context) val isTrue = when (cond) { is Boolean -> cond is Number -> cond.toDouble() != 0.0 is String -> cond.lowercase() in setOf("true", "1", "yes", "on") null -> false else -> true } return if (isTrue) thenBranch.eval(context) else elseBranch.eval(context) } } }  // ============ Parser ============ private class Parser { private var tokens: List<Token> = emptyList() private var pos = 0  fun parse(tokens: List<Token>): ASTNode { this.tokens = tokens this.pos = 0 val node = parseTernary() require(pos == tokens.size) { "未消费的 token: ${tokens.drop(pos)}" } return node } private fun peek(): Token? = tokens.getOrNull(pos) private fun next(): Token = tokens[pos++] private fun match(vararg types: Token): Boolean { val t = peek() ?: return false return types.any { it == t || it::class == t::class } } private fun parseTernary(): ASTNode { val cond = parseNullCoalesce() if (peek() is Token.QuestionMark) { next() val thenBranch = parseTernary() require(peek() is Token.Colon) { "三元运算符缺少 :" } next() val elseBranch = parseTernary() return ASTNode.Ternary(cond, thenBranch, elseBranch) } return cond } private fun parseNullCoalesce(): ASTNode { var left = parseLogicalOr() while (peek() is Token.Operator && (peek() as Token.Operator).op == "??") { next() val right = parseLogicalOr() left = ASTNode.BinaryOp("??", left, right) } return left } private fun parseLogicalOr(): ASTNode { var left = parseLogicalAnd() while (peek() is Token.Operator && (peek() as Token.Operator).op == "||") { next() val right = parseLogicalAnd() left = ASTNode.BinaryOp("||", left, right) } return left } private fun parseLogicalAnd(): ASTNode { var left = parseEquality() while (peek() is Token.Operator && (peek() as Token.Operator).op == "&&") { next() val right = parseEquality() left = ASTNode.BinaryOp("&&", left, right) } return left } private fun parseEquality(): ASTNode { var left = parseComparison() while (peek() is Token.Operator && (peek() as Token.Operator).op in setOf("==", "!=")) { val op = (next() as Token.Operator).op val right = parseComparison() left = ASTNode.BinaryOp(op, left, right) } return left } private fun parseComparison(): ASTNode { var left = parseAdditive() while (peek() is Token.Operator && (peek() as Token.Operator).op in setOf(">", "<", ">=", "<=")) { val op = (next() as Token.Operator).op val right = parseAdditive() left = ASTNode.BinaryOp(op, left, right) } return left } private fun parseAdditive(): ASTNode { var left = parseMultiplicative() while (peek() is Token.Operator && (peek() as Token.Operator).op in setOf("+", "-")) { val op = (next() as Token.Operator).op val right = parseMultiplicative() left = ASTNode.BinaryOp(op, left, right) } return left } private fun parseMultiplicative(): ASTNode { var left = parseUnary() while (peek() is Token.Operator && (peek() as Token.Operator).op in setOf("*", "/", "%")) { val op = (next() as Token.Operator).op val right = parseUnary() left = ASTNode.BinaryOp(op, left, right) } return left } private fun parseUnary(): ASTNode { if (peek() is Token.Operator && (peek() as Token.Operator).op in setOf("!", "-")) { val op = (next() as Token.Operator).op val operand = parseUnary() return ASTNode.UnaryOp(op, operand) } return parsePostfix() } private fun parsePostfix(): ASTNode { var node = parsePrimary() while (true) { when { peek() is Token.Dot -> { next() val ident = next() as Token.Identifier if (peek() is Token.LParen) { next() val args = mutableListOf<ASTNode>() if (peek() !is Token.RParen) { args.add(parseTernary()) while (peek() is Token.Comma) { next() args.add(parseTernary()) } } require(peek() is Token.RParen) { "方法调用缺少 )" } next() node = ASTNode.MethodCall(node, ident.name, args) } else { // 属性访问 - 视为变量路径的一部分 val path = when (node) { is ASTNode.VariableRef -> node.path + "." + ident.name else -> ident.name } node = ASTNode.VariableRef(path) } } else -> break } } return node } private fun parsePrimary(): ASTNode { return when (val t = peek()) { is Token.Variable -> { next(); ASTNode.VariableRef(t.path) } is Token.StringLiteral -> { next(); ASTNode.Literal(t.value) } is Token.NumberLiteral -> { next(); ASTNode.Literal(t.value) } is Token.BooleanLiteral -> { next(); ASTNode.Literal(t.value) } Token.NullLiteral -> { next(); ASTNode.Literal(null) } is Token.LParen -> { next() val node = parseTernary() require(peek() is Token.RParen) { "缺少 )" } next() node } is Token.Operator -> { require(t.op == "-") { "意外的运算符: ${t.op}" } next() ASTNode.UnaryOp("-", parsePrimary()) } else -> throw IllegalStateException("意外的 token: $t") } } } companion object { @Volatile private var instance: ExpressionEvaluator? = null  fun getInstance(): ExpressionEvaluator { return instance ?: synchronized(this) { instance ?: ExpressionEvaluator().also { instance = it } } } } }
