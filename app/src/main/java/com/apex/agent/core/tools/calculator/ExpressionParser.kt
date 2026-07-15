package com.apex.core.tools.calculator

/**
 * è¡¨è¾¾å¼è§£æå¨
 *
 * å°è¡¨è¾¾å¼å­ç¬¦ä¸²è§£æä¸ºè¯­æ³ï¼?*/
class ExpressionParser(private val expression: String) {
    private var position = 0
    private var currentToken = ""
        private var currentTokenType = TokenType.NONE

    /** è¯æ³ååç±»å */
    enum class TokenType {
        NONE,
        NUMBER,
        IDENTIFIER,
        OPERATOR,
        LEFT_PAREN,
        RIGHT_PAREN,
        LEFT_BRACKET,
        RIGHT_BRACKET,
        COMMA,
        STRING,
        TEMPLATE_START,
        TEMPLATE_MIDDLE,
        TEMPLATE_END,
        EOF
    }

    /** è§£æè¡¨è¾¾ï¼?/
    fun parse(): ExpressionNode {
        nextToken()
        val result = parseExpression()
        if (currentTokenType != TokenType.EOF) {
            throw IllegalArgumentException("Unexpected token: ${currentToken}")
        }
        return result
    }

    /** è§£æè¡¨è¾¾ï¼?/
    private fun parseExpression(): ExpressionNode {
        return parseTernary()
    }

    /** è§£æä¸åè¿ç®ï¼?/
    private fun parseTernary(): ExpressionNode {
        val condition = parseAssignment()
        if (currentToken == "?") {
            nextToken()
        val trueExpr = parseAssignment()
        if (currentToken != ":") {
                throw IllegalArgumentException("Expected ':' in ternary operator")
            }
            nextToken()
        val falseExpr = parseAssignment()
        return TernaryOperationNode(condition, trueExpr, falseExpr)
        }
        return condition
    }

    /** è§£æèµå¼è¡¨è¾¾å¼ */
    private fun parseAssignment(): ExpressionNode {
        if (currentTokenType == TokenType.IDENTIFIER) {
            val variableName = currentToken
            val nextPos = position
            val nextChar = if (position < expression.length) expression[position] else ' '

            if (nextChar == '=') {
                val followingChar =
                        if (position + 1 < expression.length) expression[position + 1] else ' '

                if (followingChar == '=') {
                    // è¿æ¯==è¿ç®ç¬¦ï¼ä¸æ¯èµå¼ï¼åºè¯¥èµ°é»è¾æè¡¨è¾¾å¼è·¯å¾
    return parseLogicalOr()
                }

                // ç®åèµï¼?x = expr
                nextToken() // è·³è¿=
                nextToken() // è·åä¸ä¸ä¸ªtoken
    val valueExpr = parseAssignment() // éå½è§£æå³ä¾§è¡¨è¾¾ï¼?
    return AssignmentNode(variableName, valueExpr)
            } else if (nextChar == '+' || nextChar == '-' || nextChar == '*' || nextChar == '/') {
                if (position + 1 < expression.length && expression[position + 1] == '=') {
                    // å¤åèµï¼ x += expr, x -= expr, etc.
    val operator = nextChar.toString() + "="
                    position += 2 // è·³è¿æä½ï¼?                   nextToken()
        val valueExpr = parseAssignment()
        return CompoundAssignmentNode(variableName, operator, valueExpr)
                }
            }
        }
        return parseLogicalOr()
    }

    /** è§£æé»è¾ORè¡¨è¾¾ï¼?/
    private fun parseLogicalOr(): ExpressionNode {
        var left = parseLogicalAnd()

        while (currentToken == "||") {
            val operator = currentToken
            nextToken()
        val right = parseLogicalAnd()
            left = BinaryOperationNode(left, operator, right)
        }
        return left
    }

    /** è§£æé»è¾ANDè¡¨è¾¾ï¼?/
    private fun parseLogicalAnd(): ExpressionNode {
        var left = parseEquality()

        while (currentToken == "&&") {
            val operator = currentToken
            nextToken()
        val right = parseEquality()
            left = BinaryOperationNode(left, operator, right)
        }
        return left
    }

    /** è§£æç¸ç­æ§è¡¨è¾¾å¼ */
    private fun parseEquality(): ExpressionNode {
        var left = parseComparison()

        while (currentToken == "==" || currentToken == "!=") {
            val operator = currentToken
            nextToken()
        val right = parseComparison()
            left = BinaryOperationNode(left, operator, right)
        }
        return left
    }

    /** è§£ææ¯è¾è¡¨è¾¾ï¼?/
    private fun parseComparison(): ExpressionNode {
        var left = parseAdditive()

        while (currentToken == ">" ||
                currentToken == ">=" ||
                currentToken == "<" ||
                currentToken == "<=") {
            val operator = currentToken
            nextToken()
        val right = parseAdditive()
            left = BinaryOperationNode(left, operator, right)
        }
        return left
    }

    /** è§£æå æ³ååï¼?/
    private fun parseAdditive(): ExpressionNode {
        var left = parseMultiplicative()

        while (currentToken == "+" || currentToken == "-") {
            val operator = currentToken
            nextToken()
        val right = parseMultiplicative()
            left = BinaryOperationNode(left, operator, right)
        }
        return left
    }

    /** è§£æä¹æ³åé¤ï¼?/
    private fun parseMultiplicative(): ExpressionNode {
        var left = parseExponential()

        while (currentToken == "*" || currentToken == "/" || currentToken == "%") {
            val operator = currentToken
            nextToken()
        val right = parseExponential()
            left = BinaryOperationNode(left, operator, right)
        }
        return left
    }

    /** è§£æææ°è¿ç® */
    private fun parseExponential(): ExpressionNode {
        var left = parseUnary()

        while (currentToken == "**" || currentToken == "^") {
            val operator = currentToken
            nextToken()
        val right = parseUnary()
            left = BinaryOperationNode(left, operator, right)
        }
        return left
    }

    /** è§£æä¸åæä½ç¬¦ */
    private fun parseUnary(): ExpressionNode {
        if (currentToken == "+" || currentToken == "-" || currentToken == "!") {
            val operator = currentToken
            nextToken()
        val operand = parseUnary()
        return UnaryOperationNode(operator, operand)
        }
        return parseArrayAccess()
    }

    /** è§£ææ°ç»è®¿é® */
    private fun parseArrayAccess(): ExpressionNode {
        var expr = parsePrimary()

        while (true) {
            if (currentToken == "[") {
                nextToken() // è·³è¿[
    val index = parseExpression()
        if (currentToken != "]") {
                    throw IllegalArgumentException("Expected ']' in array access")
                }
                nextToken() // è·³è¿]

                expr = ArrayAccessNode(expr, index)
            } else if (currentToken == "." && peekNextToken() == "length") {
                // ç¹æ®å¤ç .length å±æ§è®¿ï¼?               nextToken() // è·³è¿.
                nextToken() // è·³è¿length

                expr = FunctionCallNode("length", listOf(expr))
            } else {
                break
            }
        }
        return expr
    }

    /** è§£æåºæ¬è¡¨è¾¾ï¼?/
    private fun parsePrimary(): ExpressionNode {
        when (currentTokenType) {
            TokenType.NUMBER -> {
                val value = currentToken.toDouble()
                nextToken()
        return NumberNode(value)
            }
            TokenType.IDENTIFIER -> {
                val identifier = currentToken
                nextToken()

                // å½æ°è°ç¨
    if (currentToken == "(") {
                    nextToken() // è·³è¿(
    val args = mutableListOf<ExpressionNode>()
        if (currentToken != ")") {
                        args.add(parseExpression())

                        while (currentToken == ",") {
                            nextToken() // è·³è¿,
                            args.add(parseExpression())
                        }
                    }
        if (currentToken != ")") {
                        throw IllegalArgumentException("Expected ')' in function call")
                    }
                    nextToken() // è·³è¿ï¼?

                    // ç¹æ®å¤ç convert å½æ°ï¼å®éè¦ä¸ä¸ªåæ°ï¼ä½ç¬¬2åç¬¬3ä¸ªæ¯å­ç¬¦ä¸?
    if (identifier.equals("convert", ignoreCase = true) && args.size >= 3) {
                        val fromUnit =
                                (args[1] as? VariableNode)?.name ?: args[1].evaluate().toString()
        val toUnit =
                                (args[2] as? VariableNode)?.name ?: args[2].evaluate().toString()

                        // å°åä½å­å¨ä¸ºä¸´æ¶åéä¾å½æ°ä½¿ï¼?                       ExpressionContext.setVariable("_convert_from", 0.0) // ä¼è¢«ç±»åè½¬æ¢ä¸ºå­ç¬¦ä¸²
                        ExpressionContext.setVariable("_convert_to", 0.0) // åä¸
    return FunctionCallNode(identifier, listOf(args[0]))
                    }
        return FunctionCallNode(identifier, args)
                }

                // æ°å­¦å¯¹è±¡æ¹æ³è°ç¨
    if (identifier == "Math" && currentToken == ".") {
                    nextToken() // è·³è¿.
    val methodName = currentToken
                    nextToken()
        if (currentToken != "(") {
                        throw IllegalArgumentException("Expected '(' after Math.${methodName}")
                    }
                    nextToken() // è·³è¿(
    val args = mutableListOf<ExpressionNode>()
        if (currentToken != ")") {
                        args.add(parseExpression())

                        while (currentToken == ",") {
                            nextToken() // è·³è¿,
                            args.add(parseExpression())
                        }
                    }
        if (currentToken != ")") {
                        throw IllegalArgumentException("Expected ')' in Math.${methodName} call")
                    }
                    nextToken() // è·³è¿ï¼?
    return FunctionCallNode("Math.${methodName}", args)
                }

                // åéå¼ç¨
    return VariableNode(identifier)
            }
            TokenType.LEFT_PAREN -> {
                nextToken() // è·³è¿(
    val expr = parseExpression()
        if (currentToken != ")") {
                    throw IllegalArgumentException("Expected ')'")
                }
                nextToken() // è·³è¿ï¼?
    return expr
            }
            TokenType.LEFT_BRACKET -> {
                nextToken() // è·³è¿[
    val elements = mutableListOf<ExpressionNode>()
        if (currentToken != "]") {
                    elements.add(parseExpression())

                    while (currentToken == ",") {
                        nextToken() // è·³è¿,
                        elements.add(parseExpression())
                    }
                }
        if (currentToken != "]") {
                    throw IllegalArgumentException("Expected ']'")
                }
                nextToken() // è·³è¿]

                // åå»ºä¸ä¸ªä»£è¡¨æ°ç»çèç¹
    return FunctionCallNode("array", elements)
            }
            TokenType.STRING -> {
                val value = currentToken
                nextToken()
                // å­ç¬¦ä¸²èç¹å¤çä¸ºä¸ä¸ªåéèï¼?
    return VariableNode(value)
            }
            TokenType.TEMPLATE_START -> {
                return parseTemplate()
            }
            else -> {
                throw IllegalArgumentException("Unexpected token: ${currentToken}")
            }
        }
    }

    /** è§£ææ¨¡æ¿å­ç¬¦ä¸?/
    private fun parseTemplate(): ExpressionNode {
        val parts = mutableListOf<Any>()

        // æ·»å æ¨¡æ¿èµ·å§é¨å
        parts.add(currentToken.substring(1)) // å»æå¼å§ç"
        nextToken()

        while (currentTokenType == TokenType.TEMPLATE_MIDDLE ||
                currentTokenType == TokenType.TEMPLATE_END) {
            if (currentTokenType == TokenType.TEMPLATE_MIDDLE) {
                val expr = parseExpression()
                parts.add(expr)
            } else { // TEMPLATE_END
                parts.add(currentToken.substring(0, currentToken.length - 1)) // å»æç»æï¼?
                nextToken()
                break
            }
        }
        return TemplateStringNode(parts)
    }

    /** è·åä¸ä¸ä¸ªè¯æ³åï¼?/
    private fun nextToken() {
        // è·³è¿ç©ºç½å­ç¬¦
        while (position < expression.length && Character.isWhitespace(expression[position])) {
            position++
        }
        if (position >= expression.length) {
            currentToken = ""
            currentTokenType = TokenType.EOF
            return
        }
        val c = expression[position]

        when {
            c.isDigit() ||
                    (c == '.' &&
                            position + 1 < expression.length &&
                            expression[position + 1].isDigit()) -> {
                scanNumber()
            }
            c.isLetter() || c == '_' -> {
                scanIdentifier()
            }
            c == '"' || c == '\'' -> {
                scanString(c)
            }
            c == '`' -> {
                scanTemplateString()
            }
            c == '(' -> {
                currentToken = "("
                currentTokenType = TokenType.LEFT_PAREN
                position++
            }
            c == ')' -> {
                currentToken = ")"
                currentTokenType = TokenType.RIGHT_PAREN
                position++
            }
            c == '[' -> {
                currentToken = "["
                currentTokenType = TokenType.LEFT_BRACKET
                position++
            }
            c == ']' -> {
                currentToken = "]"
                currentTokenType = TokenType.RIGHT_BRACKET
                position++
            }
            c == ',' -> {
                currentToken = ","
                currentTokenType = TokenType.COMMA
                position++
            }
            c == '+' ||
                    c == '-' ||
                    c == '*' ||
                    c == '/' ||
                    c == '%' ||
                    c == '^' ||
                    c == '=' ||
                    c == '!' ||
                    c == '>' ||
                    c == '<' ||
                    c == '&' ||
                    c == '|' ||
                    c == '?' ||
                    c == ':' ||
                    c == '.' -> {
                scanOperator()
            }
            else -> {
                throw IllegalArgumentException("Invalid character: ${c}")
            }
        }
    }

    /** æ«ææ°å­ */
    private fun scanNumber() {
        val start = position
        var hasDot = false

        while (position < expression.length) {
            val c = expression[position]
            if (c.isDigit()) {
                position++
            } else if (c == '.' && !hasDot) {
                hasDot = true
                position++
            } else {
                break
            }
        }

        currentToken = expression.substring(start, position)
        currentTokenType = TokenType.NUMBER
    }

    /** æ«ææ è¯ï¼?/
    private fun scanIdentifier() {
        val start = position

        while (position < expression.length) {
            val c = expression[position]
            if (c.isLetterOrDigit() || c == '_') {
                position++
            } else {
                break
            }
        }

        currentToken = expression.substring(start, position)
        currentTokenType = TokenType.IDENTIFIER
    }

    /** æ«æå­ç¬¦ä¸²å­é¢é */
    private fun scanString(quoteChar: Char) {
        val start = position
        position++ // è·³è¿å¼å§çå¼å·

        while (position < expression.length) {
            val c = expression[position]
            position++

            if (c == quoteChar) {
                break
            } else if (c == '\\' && position < expression.length) {
                // å¤çè½¬ä¹å­ç¬¦
                position++
            }
        }

        currentToken = expression.substring(start, position)
        currentTokenType = TokenType.STRING
    }

    /** æ«ææ¨¡æ¿å­ç¬¦ä¸?/
    private fun scanTemplateString() {
        val start = position
        position++ // è·³è¿å¼å§ç `

        // æ¥æ¾${æèç»æç`
        while (position < expression.length) {
            if (position + 1 < expression.length &&
                            expression[position] == '$' &&
                            expression[position + 1] == '{'
            ) {
                currentToken = expression.substring(start, position)
                currentTokenType = TokenType.TEMPLATE_START
                position += 2 // è·³è¿ ${
    return
            } else if (expression[position] == '`') {
                currentToken = expression.substring(start, position + 1)
                currentTokenType = TokenType.TEMPLATE_END
                position++ // è·³è¿ç»æï¼`
    return
            }
            position++
        }
        throw IllegalArgumentException("Unclosed template string")
    }

    /** æ«ææä½ï¼?/
    private fun scanOperator() {
        val start = position
        val c = expression[position]
        position++

        // å¤çå¤å­ç¬¦æä½ç¬¦
    if (position < expression.length) {
            val nextChar = expression[position]

            if ((c == '+' ||
                            c == '-' ||
                            c == '*' ||
                            c == '/' ||
                            c == '=' ||
                            c == '!' ||
                            c == '>' ||
                            c == '<') && nextChar == '='
            ) {
                position++
            } else if (c == '*' && nextChar == '*') {
                position++
            } else if (c == '&' && nextChar == '&') {
                position++
            } else if (c == '|' && nextChar == '|') {
                position++
            }
        }

        currentToken = expression.substring(start, position)
        currentTokenType = TokenType.OPERATOR
    }

    /** æ¥çä¸ä¸ä¸ªè¯æ³ååä½ä¸æ¶è´¹å® */
    private fun peekNextToken(): String {
        val savedPosition = position
        val savedToken = currentToken
        val savedType = currentTokenType

        nextToken()
        val nextToken = currentToken

        // æ¢å¤ç¶æ?       position = savedPosition
        currentToken = savedToken
        currentTokenType = savedType

        return nextToken
    }
}
