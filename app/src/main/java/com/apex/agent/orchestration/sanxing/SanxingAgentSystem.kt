package com.apex.agent.orchestration.sanxing

import android.content.Context
import com.apex.agent.common.result.Result
import com.apex.agent.core.permissions.rbac.PermissionDeniedException
import com.apex.agent.core.permissions.rbac.RbacManager
import com.apex.agent.domain.entity.AgentMessage
import com.apex.agent.orchestration.agent.model.Agent
import com.apex.agent.orchestration.sanxing.roles.Bingbu
import com.apex.agent.orchestration.sanxing.roles.Gongbu
import com.apex.agent.orchestration.sanxing.roles.Hubu
import com.apex.agent.orchestration.sanxing.roles.LibuPersonnel
import com.apex.agent.orchestration.sanxing.roles.LibuRitual
import com.apex.agent.orchestration.sanxing.roles.MenxiaSheng
import com.apex.agent.orchestration.sanxing.roles.ShangshuSheng
import com.apex.agent.orchestration.sanxing.roles.Xingbu
import com.apex.agent.orchestration.sanxing.roles.Yushitai
import com.apex.agent.orchestration.sanxing.roles.ZhongshuSheng
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull

/**
 * 三星�?Agent 系统的编排入口，管理三省六部一台的全部角色�? */
@Singleton
class SanxingAgentSystem constructor(
    @ApplicationContext private val context: Context,
    private val zhongshuSheng: ZhongshuSheng,
    private val menxiaSheng: MenxiaSheng,
    private val shangshuSheng: ShangshuSheng,
    private val libuPersonnel: LibuPersonnel,
    private val hubu: Hubu,
    private val libuRitual: LibuRitual,
    private val bingbu: Bingbu,
    private val xingbu: Xingbu,
    private val gongbu: Gongbu,
    private val yushitai: Yushitai
) {

    private val allRoles: List<SanxingRole> by lazy {
        listOf(
            zhongshuSheng,
            menxiaSheng,
            shangshuSheng,
            libuPersonnel,
            hubu,
            libuRitual,
            bingbu,
            xingbu,
            gongbu,
            yushitai
        )
    }

    fun getRoles(): List<SanxingRole> = allRoles

    fun getAgents(): List<Agent> = allRoles.map { it.getAgent() }

    fun createStandardAgents(): List<SanxingAgent> = allRoles.map { createAgent(it) }

    fun getThreeProvinceAgents(): List<SanxingAgent> = listOf(
        createAgent(zhongshuSheng),
        createAgent(menxiaSheng),
        createAgent(shangshuSheng)
    )

    fun findRole(roleId: String): SanxingRole? = allRoles.find { it.roleId == roleId }

    fun findAgent(roleId: String): Agent? = findRole(roleId)?.getAgent()

    fun createAgent(role: SanxingRole): SanxingAgent {
        val agent = role.getAgent()
        return SanxingAgent(
            agent = agent,
            roleId = role.roleId,
            isActive = true,
            apiConfig = ApiEndpointConfig(
                endpoint = getEndpointForRole(role),
                apiKey = "",
                timeout = 60,
                retryCount = 3
            )
        )
    }

    fun createAgentWithGlobalConfig(
        role: SanxingRole,
        useGlobalConfig: Boolean = true,
        configId: String? = null
    ): SanxingAgent {
        val agent = role.getAgent().copy(useGlobalConfig = useGlobalConfig, configId = configId)
        return SanxingAgent(
            agent = agent,
            roleId = role.roleId,
            isActive = true,
            apiConfig = ApiEndpointConfig(
                endpoint = getEndpointForRole(role),
                apiKey = "",
                timeout = 60,
                retryCount = 3
            )
        )
    }

    fun getAvailableProviders(): List<com.apex.data.model.ApiProviderType> {
        return com.apex.data.model.ApiProviderType.values().toList()
    }

    fun getAvailableConfigs(): List<com.apex.core.config.ModelConfigService.ModelConfigTemplate> {
        return com.apex.core.config.ModelConfigService.getInstance(context).getConfigTemplates()
    }

    /**
     * 激活三星制系统并处理用户输入�?     * 当前为最小占位实现，将输入路由给中书省角色处理�?     */
    suspend fun activate(input: String): Result<String> {
        val message = AgentMessage(
            id = UUID.randomUUID().toString(),
            senderId = "user",
            receiverId = zhongshuSheng.roleId,
            content = input,
            timestamp = System.currentTimeMillis()
        )
        val response = zhongshuSheng.handleMessage(message).firstOrNull()
        return when (response) {
            is Result.Success -> Result.Success(response.data.content)
            else -> Result.Success("三星制系统已激�?)
        }
    }

    fun createAgentForUser(
        role: SanxingRole,
        userId: Long,
        rbacManager: RbacManager
    ): SanxingAgent {
        val rbacPerms = SanxingRbacBridge.toRbacPermissions(role.permissions)
        val missing = rbacPerms.filter { permName ->
            kotlinx.coroutines.runBlocking { !rbacManager.hasPermission(userId, permName) }
        }
        if (missing.isNotEmpty()) {
            throw PermissionDeniedException(
                "用户缺少创建 ${role.roleName} Agent 所需的权�? ${missing.joinToString(", ")}"
            )
        }
        return createAgent(role)
    }

    suspend fun validateUserPermissions(
        userId: Long,
        rbacManager: RbacManager
    ): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        for (role in allRoles) {
            val missing = SanxingRbacBridge.getMissingPermissions(rbacManager, userId, role)
            if (missing.isNotEmpty()) {
                result[role.roleId] = missing
            }
        }
        return result
    }

    private fun getEndpointForRole(role: SanxingRole): String {
        return when (role.roleId) {
            "sanxing_menxia" -> "https://api.anthropic.com/v1/messages"
            "sanxing_xingbu" -> "https://api.anthropic.com/v1/messages"
            "sanxing_bingbu" -> "https://api.deepseek.com/v1/chat/completions"
            else -> "https://api.openai.com/v1/chat/completions"
        }
    }
}

data class SanxingAgent(
    val agent: Agent,
    val roleId: String,
    var isActive: Boolean = true,
    var apiConfig: ApiEndpointConfig = ApiEndpointConfig()
)

data class ApiEndpointConfig(
    var endpoint: String = "https://api.openai.com/v1/chat/completions",
    var apiKey: String = "",
    var timeout: Int = 60,
    var retryCount: Int = 3,
    var rateLimit: Int = 100
)

data class AgentUsageStats(
    val agentId: String,
    val callCount: Int = 0,
    val tokenUsage: Int = 0,
    val errorCount: Int = 0,
    val lastCallTime: Long = 0
)
