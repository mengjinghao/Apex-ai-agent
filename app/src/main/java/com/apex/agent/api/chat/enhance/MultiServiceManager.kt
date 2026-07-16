package com.apex.api.chat.enhance

import android.content.Context
import com.apex.util.AppLogger
import com.apex.api.chat.llmprovider.AIService
import com.apex.api.chat.llmprovider.AIServiceFactory
import com.apex.api.chat.llmprovider.RateLimitedAIService
import com.apex.api.chat.llmprovider.RateLimiterRegistry
import com.apex.api.chat.llmprovider.RequestConcurrencyRegistry
import com.apex.core.config.ModelConfigService
import com.apex.data.model.FunctionType
import com.apex.data.model.ModelConfigData
import com.apex.data.model.getModelByIndex
import com.apex.data.model.getValidModelIndex
import com.apex.data.preferences.ModelConfigManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 管理多个AIService实例，根据功能类型提供不同的服务配置 */
class MultiServiceManager(private val context: Context) {
    companion object {
        private const val TAG = "MultiServiceManager"
    }

    // 配置管理服务
    private val modelConfigService = ModelConfigService.getInstance(context)
    private val modelConfigManager = ModelConfigManager(context)

    // 服务实例缓存
    private val serviceInstances = mutableMapOf<FunctionType, AIService>()
    private val customServiceInstances = mutableMapOf<String, AIService>()
    private val serviceMutex = Mutex()

    private val initMutex = Mutex()
    @Volatile private var isInitialized = false

    // 默认AIService，用于兼容现有代?   private var defaultService: AIService? = null

    /** 初始化服务管理器，确保配置已经准备好 */
    suspend fun initialize() {
        ensureInitialized()
    }

    private suspend fun ensureInitialized() {
        if (isInitialized) return
        initMutex.withLock {
            if (isInitialized) return
            // 确保ModelConfigService已初始化
            modelConfigService.getCurrentConfig()
            isInitialized = true
        }
    }

    /** 获取指定功能类型的AIService */
    suspend fun getServiceForFunction(functionType: FunctionType): AIService {
        ensureInitialized()
        return serviceMutex.withLock {
            // 如果缓存中已有该服务实例，直接返?           serviceInstances[functionType]?.let {
                return@withLock it
            }

            // 否则，创建新的服务实?           // 所有功能类型都使用统一的活跃配?
            val config = modelConfigService.getCurrentConfig() ?: throw IllegalStateException("No active model config found")
            val service = createServiceFromConfig(config, 0) // 使用默认模型索引
            serviceInstances[functionType] = service

            // 如果是CHAT功能类型，也设置为默认服?           if (functionType == FunctionType.CHAT) {
                defaultService = service
            }

            AppLogger.d(TAG, "已为功能${functionType}创建服务实例，使用配置{config.name}")
            service
        }
    }

    /** 根据配置ID和模型索引获取AIService（不会修改功能映射） */
    suspend fun getServiceForConfig(configId: String, modelIndex: Int): AIService {
        ensureInitialized()
        return serviceMutex.withLock {
            val normalizedIndex = modelIndex.coerceAtLeast(0)
            val cacheKey = "${configId}#${normalizedIndex}"
            customServiceInstances[cacheKey]?.let { return@withLock it }

            val config = modelConfigManager.getModelConfigFlow(configId).first()
            val service = createServiceFromConfig(config, normalizedIndex)
            customServiceInstances[cacheKey] = service

            AppLogger.d(TAG, "已为自定义配置创建服务实例，配置=${configId}，模型索，的${normalizedIndex}")
            service
        }
    }

    /** 获取默认服务（通常是CHAT功能的服务） */
    suspend fun getDefaultService(): AIService {
        ensureInitialized()
        return serviceMutex.withLock {
            defaultService ?: getServiceForFunction(FunctionType.CHAT).also { defaultService = it }
        }
    }

    suspend fun cancelAllStreaming() {
        serviceMutex.withLock {
            val services = mutableSetOf<AIService>()
            services.addAll(serviceInstances.values)
            services.addAll(customServiceInstances.values)
            defaultService?.let { services.add(it) }

            services.forEach { service ->
                try {
                    service.cancelStreaming()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "取消服务流式传输时出? e)
                }
            }
        }
    }

    suspend fun resetAllTokenCounters() {
        serviceMutex.withLock {
            val services = mutableSetOf<AIService>()
            services.addAll(serviceInstances.values)
            services.addAll(customServiceInstances.values)
            defaultService?.let { services.add(it) }

            services.forEach { service ->
                try {
                    service.resetTokenCounts()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "重置服务token计数器时出错", e)
                }
            }
        }
    }

    suspend fun resetTokenCountersForFunction(functionType: FunctionType) {
        val service = getServiceForFunction(functionType)
        try {
            service.resetTokenCounts()
        } catch (e: Exception) {
            AppLogger.e(TAG, "重置功能${functionType}的token计数器时出错", e)
        }
    }

    /** 刷新指定功能类型的服务实例当配置更改时调用此方法*/
    suspend fun refreshServiceForFunction(functionType: FunctionType) {
        ensureInitialized()
        serviceMutex.withLock {
            // 释放旧实例的资源（对于本地模型如MNN，这很重要）
            serviceInstances[functionType]?.let { oldService ->
                try {
                    oldService.cancelStreaming()
                    oldService.release()
                    AppLogger.d(TAG, "已释放功能{functionType}的服务资?
                } catch (e: Exception) {
                    AppLogger.e(TAG, "释放服务资源时出? e)
                }
            }

            // 移除旧实?           serviceInstances.remove(functionType)

            // 如果是默认服务，也清除默认服务缓?           if (functionType == FunctionType.CHAT) {
                defaultService = null
                customServiceInstances.values.forEach { service ->
                    try {
                        service.cancelStreaming()
                        service.release()
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "释放自定义CHAT服务资源时出? e)
                    }
                }
                customServiceInstances.clear()
            }

            // 不立即创建新实例，而是等到需要时再创?           AppLogger.d(TAG, "已移除功能{functionType}的服务实例缓存）
        }
    }

    /** 刷新所有服务实例当全局设置更改时调用此方法 */
    suspend fun refreshAllServices() {
        ensureInitialized()
        serviceMutex.withLock {
            // 释放所有服务实例的资源
            serviceInstances.values.forEach { service ->
                try {
                    service.cancelStreaming()
                    service.release()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "释放服务资源时出? e)
                }
            }
            customServiceInstances.values.forEach { service ->
                try {
                    service.cancelStreaming()
                    service.release()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "释放自定义服务资源时出错", e)
                }
            }

            serviceInstances.clear()
            customServiceInstances.clear()
            defaultService = null
            AppLogger.d(TAG, "已清除所有服务实例缓存并释放资源")
        }
    }

    /** 根据配置创建AIService实例 */
    private suspend fun createServiceFromConfig(config: ModelConfigData, modelIndex: Int): AIService {
        // 使用公共函数计算有效索引
        val actualIndex = getValidModelIndex(config.modelName, modelIndex)
        
        // 记录越界警告
        if (actualIndex != modelIndex && modelIndex != 0) {
            val modelList = config.modelName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            AppLogger.w(TAG, "模型索引 ${modelIndex} 超出范围(0-${modelList.size - 1})，自动使用第一个模型）
        }
        
        // 根据实际索引选择具体模型
        val selectedModelName = getModelByIndex(config.modelName, actualIndex)
        
        // 创建一个临时配置，使用选中的模型名?       val configWithSelectedModel = config.copy(modelName = selectedModelName)
        
        AppLogger.d(TAG, "创建服务: 原始模型='${config.modelName}', 选中模型='${selectedModelName}' (请求索引=${modelIndex}, 实际索引=${actualIndex})")

        val rawService = AIServiceFactory.createService(
            config = configWithSelectedModel,
            modelConfigManager = modelConfigManager,
            context = context
        )

        val requestLimitPerMinute = config.requestLimitPerMinute.coerceAtLeast(0)
        val maxConcurrentRequests = config.maxConcurrentRequests.coerceAtLeast(0)

        if (requestLimitPerMinute == 0 && maxConcurrentRequests == 0) {
            return rawService
        }

        val limiter =
            if (requestLimitPerMinute > 0) {
                RateLimiterRegistry.getOrCreate(
                    key = config.id,
                    maxRequestsPerMinute = requestLimitPerMinute
                )
            } else {
                null
            }

        val concurrencySemaphore =
            if (maxConcurrentRequests > 0) {
                RequestConcurrencyRegistry.getOrCreate(
                    key = config.id,
                    maxConcurrentRequests = maxConcurrentRequests
                )
            } else {
                null
            }

        return RateLimitedAIService(
            delegate = rawService,
            rateLimiter = limiter,
            concurrencySemaphore = concurrencySemaphore
        )
    }

    /**
     * 获取指定功能类型的模型参数列?    * @param functionType 功能类型
     * @return 模型参数列表
     */
    suspend fun getModelParametersForFunction(
            functionType: FunctionType
    ): List<com.apex.data.model.ModelParameter<*>> {
        ensureInitialized()
        // 所有功能类型都使用统一的活跃配?
        val config = modelConfigService.getCurrentConfig() ?: throw IllegalStateException("No active model config found")
        return modelConfigManager.getModelParametersForConfig(config.id)
    }

    /**
     * 获取指定功能类型的模型配?    * @param functionType 功能类型
     * @return 模型配置数据
     */
    suspend fun getModelConfigForFunction(functionType: FunctionType): ModelConfigData {
        ensureInitialized()
        // 所有功能类型都使用统一的活跃配?
        return modelConfigService.getCurrentConfig() ?: throw IllegalStateException("No active model config found")
    }

    /** 获取指定配置ID的模型配?/
    suspend fun getModelConfigForConfig(configId: String): ModelConfigData {
        ensureInitialized()
        return modelConfigManager.getModelConfigFlow(configId).first()
    }

    /** 获取指定配置ID的模型参?/
    suspend fun getModelParametersForConfig(
        configId: String
    ): List<com.apex.data.model.ModelParameter<*>> {
        ensureInitialized()
        return modelConfigManager.getModelParametersForConfig(configId)
    }

    /**
     * 检查识图功能是否已配置
     * @return 如果识图功能配置启用了直接图片处理则返回true
     */
    suspend fun hasImageRecognitionConfigured(): Boolean {
        ensureInitialized()
        val config = modelConfigService.getCurrentConfig() ?: return false
        // 检查模型配置是否启用了直接图片处理
        return config.enableDirectImageProcessing
    }

    suspend fun hasAudioRecognitionConfigured(): Boolean {
        ensureInitialized()
        val config = modelConfigService.getCurrentConfig() ?: return false
        return config.enableDirectAudioProcessing
    }

    suspend fun hasVideoRecognitionConfigured(): Boolean {
        ensureInitialized()
        val config = modelConfigService.getCurrentConfig() ?: return false
        return config.enableDirectVideoProcessing
    }

}
