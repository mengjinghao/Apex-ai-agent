package com.apex.core.tools.calculator

import com.apex.agent.core.tools.skill.Date

/** Calculator类，作为对JsCalculator的适配器，保持API兼容?/
class Calculator {
    companion object {
        /** 计算表达?/
        fun evalExpression(expression: String): Double {
            return JsCalculator.evaluate(expression)
        }

        /** 获取变量?/
        fun getVariable(name: String): Double? {
            return try {
                JsCalculator.getVariable(name)
            } catch (e: Exception) {
                null
            }
        }

        /** 设置变量?/
        fun setVariable(name: String, value: Double) {
            JsCalculator.setVariable(name, value)
        }

        /** 清除所有变?/
        fun clearVariables() {
            JsCalculator.clearVariables()
        }

        /** 格式化日?/
        fun formatDate(date: Date, format: String): String {
            return JsCalculator.formatDate(date, format)
        }

        /** 格式化结?/
        fun formatResult(result: Double): String {
            return JsCalculator.formatResult(result)
        }

        /** 获取支持的单位列?/
        fun getSupportedUnits(): Map<String, List<String>> {
            return JsCalculator.getSupportedUnits()
        }

        /** 获取支持的日期函?/
        fun getSupportedDateFunctions(): List<String> {
            return JsCalculator.getSupportedDateFunctions()
        }

        /** 获取支持的统计函?/
        fun getSupportedStatFunctions(): List<String> {
            return JsCalculator.getSupportedStatFunctions()
        }

        /** 获取支持的JavaScript特，*/
        fun getSupportedJsFeatures(): List<String> {
            return JsCalculator.getSupportedJsFeatures()
        }
    }
}
