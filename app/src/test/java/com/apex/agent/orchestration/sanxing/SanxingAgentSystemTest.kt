package com.apex.agent.orchestration.sanxing

import android.content.Context
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class SanxingAgentSystemTest {

    private lateinit var context: Context
    private lateinit var system: SanxingAgentSystem

    @Before
    fun setup() {
        context = Mockito.mock(Context::class.java)
        system = SanxingAgentSystem(
            context = context,
            zhongshuSheng = ZhongshuSheng(),
            menxiaSheng = MenxiaSheng(),
            shangshuSheng = ShangshuSheng(),
            libuPersonnel = LibuPersonnel(),
            hubu = Hubu(),
            libuRitual = LibuRitual(),
            bingbu = Bingbu(),
            xingbu = Xingbu(),
            gongbu = Gongbu(),
            yushitai = Yushitai()
        )
    }

    @Test
    fun getRoles_returnsAllTenRoles() {
        val roles = system.getRoles()

        assertEquals(10, roles.size)
        assertTrue(roles.any { it.roleId == "sanxing_zhongshu" })
        assertTrue(roles.any { it.roleId == "sanxing_yushitai" })
    }

    @Test
    fun createStandardAgents_returnsActiveAgentForEachRole() {
        val agents = system.createStandardAgents()

        assertEquals(10, agents.size)
        assertTrue(agents.all { it.isActive })
        assertTrue(agents.all { it.agent.id.isNotEmpty() })
    }

    @Test
    fun findRole_returnsConfiguredRole() {
        val role = system.findRole("sanxing_zhongshu")

        assertNotNull(role)
        assertEquals("中书省", role?.roleName)
        assertEquals("决策中枢", role?.getAgent()?.role?.substringAfter("・"))
    }

    @Test
    fun createAgentWithGlobalConfig_appliesGlobalConfig() {
        val role = system.findRole("sanxing_shangshu")!!

        val sanxingAgent = system.createAgentWithGlobalConfig(role, useGlobalConfig = true, configId = "global-1")

        assertTrue(sanxingAgent.agent.useGlobalConfig)
        assertEquals("global-1", sanxingAgent.agent.configId)
    }

    @Test
    fun getThreeProvinceAgents_returnsThreeRoles() {
        val provinceAgents = system.getThreeProvinceAgents()

        assertEquals(3, provinceAgents.size)
        assertTrue(provinceAgents.map { it.roleId }.containsAll(
            listOf("sanxing_zhongshu", "sanxing_menxia", "sanxing_shangshu")
        ))
    }
}
