package com.apex.api.chat.enhance

import com.apex.data.model.ModelParameter
import com.apex.data.model.ParameterCategory
import com.apex.data.model.ParameterValueType
import com.apex.util.AppLogger
import com.apex.agent.core.tools.defaultTool.debugger.name

/**
 * 增强功能集成?* 统一管理三个核心增强功能的集成点
 */
object EnhancedFeatureIntegrator {
    private const val TAG = "EnhancedIntegrator"

    /**
     * 应用动态模型参数到现有的参数列?    * @param existingParams 现有的模型参数列?    * @param userInput 用户输入
     * @return 更新后的模型参数列表
     */
    fun applyDynamicParams(
        existingParams: List<ModelParameter<*>>,
        userInput: String
    ): List<ModelParameter<*>> {
        // 获取动态参?       val dynamicParams = DynamicModelParamsAdapter.getDynamicModelParams(userInput)
        val scenario = DynamicModelParamsAdapter.getScenarioDescription(userInput)
        
        AppLogger.d(TAG, "应用动态参场景=${scenario}, temp=${dynamicParams.temperature}")
        
        // 创建可修改的副本
        val mutableParams = existingParams.toMutableList()
        
        // 更新或添加temperature参数
        updateOrAddParam(
            params = mutableParams,
            id = "dynamic_temperature",
            name = "温度 (动，",
            apiName = "temperature",
            description = "根据输入自动调整的温度参?${scenario}",
            defaultValue = 0.6,
            currentValue = dynamicParams.temperature,
            valueType = ParameterValueType.FLOAT,
            category = ParameterCategory.CREATIVITY,
            minValue = 0.0,
            maxValue = 2.0
        )
        
        // 更新或添加top_p参数
        updateOrAddParam(
            params = mutableParams,
            id = "dynamic_top_p",
            name = "Top P (动，",
            apiName = "top_p",
            description = "根据输入自动调整的top_p参数: ${scenario}",
            defaultValue = 0.9,
            currentValue = dynamicParams.top_p,
            valueType = ParameterValueType.FLOAT,
            category = ParameterCategory.CREATIVITY,
            minValue = 0.0,
            maxValue = 1.0
        )
        
        // 更新或添加frequency_penalty参数
        updateOrAddParam(
            params = mutableParams,
            id = "dynamic_frequency_penalty",
            name = "频率惩罚 (动，",
            apiName = "frequency_penalty",
            description = "根据输入自动调整的频率惩?${scenario}",
            defaultValue = 0.2,
            currentValue = dynamicParams.frequency_penalty,
            valueType = ParameterValueType.FLOAT,
            category = ParameterCategory.REPETITION,
            minValue = 0.0,
            maxValue = 2.0
        )
        
        // 更新或添加presence_penalty参数
        updateOrAddParam(
            params = mutableParams,
            id = "dynamic_presence_penalty",
            name = "存在惩罚 (动，",
            apiName = "presence_penalty",
            description = "根据输入自动调整的存在惩?${scenario}",
            defaultValue = 0.1,
            currentValue = dynamicParams.presence_penalty,
            valueType = ParameterValueType.FLOAT,
            category = ParameterCategory.REPETITION,
            minValue = 0.0,
            maxValue = 2.0
        )
        
        return mutableParams.toList()
    }

    /**
     * 更新或添加参?    */
    private fun <T> updateOrAddParam(
        params: MutableList<ModelParameter<*>>,
        id: String,
        name: String,
        apiName: String,
        description: String,
        defaultValue: T,
        currentValue: T,
        valueType: ParameterValueType,
        category: ParameterCategory,
        minValue: Any? = null,
        maxValue: Any? = null
    ) {
        // 检查是否已存在该参数（通过apiName匹配?       val existingIndex = params.indexOfFirst { 
            it.apiName == apiName || it.id == id
        }
        
        if (existingIndex >= 0) {
            // 更新现有参数 - 注意：这里我们创建一个新的参数对?           val existing = params[existingIndex]
            
            @Suppress("UNCHECKED_CAST")
            val updatedParam = when (valueType) {
                ParameterValueType.FLOAT -> (existing as ModelParameter<Double>).copy(
                    name = name,
                    description = description,
                    currentValue = currentValue as Double,
                    isEnabled = true
                )
                else -> existing.copy(
                    name = name,
                    description = description,
                    isEnabled = true
                )
            }
            
            params[existingIndex] = updatedParam
        } else {
            // 添加新参?           val newParam = createParameter(
                id = id,
                name = name,
                apiName = apiName,
                description = description,
                defaultValue = defaultValue,
                currentValue = currentValue,
                valueType = valueType,
                category = category,
                minValue = minValue,
                maxValue = maxValue
            )
            params.add(newParam)
        }
    }

    /**
     * 创建参数对象
     */
    private fun <T> createParameter(
        id: String,
        name: String,
        apiName: String,
        description: String,
        defaultValue: T,
        currentValue: T,
        valueType: ParameterValueType,
        category: ParameterCategory,
        minValue: Any? = null,
        maxValue: Any? = null
    ): ModelParameter<T> {
        return ModelParameter(
            id = id,
            name = name,
            apiName = apiName,
            description = description,
            defaultValue = defaultValue,
            currentValue = currentValue,
            isEnabled = true,
            valueType = valueType,
            minValue = minValue,
            maxValue = maxValue,
            category = category,
            isCustom = true
        )
    }

    /**
     * 获取当前使用的场景（用于UI显示?    */
    fun getCurrentScenario(userInput: String): String {
        return DynamicModelParamsAdapter.getScenarioDescription(userInput)
    }
}
