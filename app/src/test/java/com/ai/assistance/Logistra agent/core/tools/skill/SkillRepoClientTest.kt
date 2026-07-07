package com.ai.assistance.`Apex agent`.core.tools.skill

import androidx.test.core.app.ApplicationProvider
import com.ai.assistance.`Apex agent`.core.tools.skill.SkillRepoClient
import com.ai.assistance.`Apex agent`.core.tools.skill.SkillUpdateManager
import com.ai.assistance.`Apex agent`.core.tools.skill.SkillVersionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(JUnit4::class)
class SkillRepoClientTest {

    private lateinit var repoClient: SkillRepoClient

    @Before
    fun setUp() {
        repoClient = SkillRepoClient.getInstance("https://skill-repo.Apex.ai/api/v1")
    }

    @After
    fun tearDown() {
        SkillRepoClient.resetInstance()
    }

    @Test
    fun testConnection() = runBlocking {
        val result = repoClient.testConnection()
        assertNotNull(result)
    }

    @Test
    fun testGetSkillList() = runBlocking {
        val result = repoClient.getSkillList(1, 20)
        assertNotNull(result)
        result.onSuccess { skills ->
            assertNotNull(skills)
        }.onFailure {
            assertTrue(it.message?.contains("HTTP") == true || it.message?.contains("connection") == true)
        }
    }

    @Test
    fun testGetSkillDetail() = runBlocking {
        val result = repoClient.getSkillDetail("test-skill")
        assertNotNull(result)
        result.onSuccess { detail ->
            assertNotNull(detail)
        }.onFailure {
            assertTrue(it.message?.contains("HTTP") == true || it.message?.contains("connection") == true)
        }
    }

    @Test
    fun testSearchSkills() = runBlocking {
        val result = repoClient.searchSkills("test", 1, 10)
        assertNotNull(result)
        result.onSuccess { searchResult ->
            assertNotNull(searchResult)
            assertNotNull(searchResult.skills)
        }.onFailure {
            assertTrue(it.message?.contains("HTTP") == true || it.message?.contains("connection") == true)
        }
    }

    @Test
    fun testCheckForUpdate() = runBlocking {
        val result = repoClient.checkForUpdate("test-skill", "1.0.0")
        assertNotNull(result)
        result.onSuccess { updateCheck ->
            assertNotNull(updateCheck)
        }.onFailure {
            assertTrue(it.message?.contains("HTTP") == true || it.message?.contains("connection") == true)
        }
    }

    @Test
    fun testGetCategories() = runBlocking {
        val result = repoClient.getCategories()
        assertNotNull(result)
        result.onSuccess { categories ->
            assertNotNull(categories)
        }.onFailure {
            assertTrue(it.message?.contains("HTTP") == true || it.message?.contains("connection") == true)
        }
    }
}

@RunWith(JUnit4::class)
class SkillUpdateManagerTest {

    private lateinit var context: Context
    private lateinit var updateManager: SkillUpdateManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        updateManager = SkillUpdateManager.getInstance(context)
    }

    @After
    fun tearDown() {
        SkillUpdateManager.resetInstance()
    }

    @Test
    fun testUpdateStateFlow() {
        val stateFlow = updateManager.updateState
        assertNotNull(stateFlow)
        assertTrue(stateFlow.value.isEmpty())
    }

    @Test
    fun testAvailableUpdatesFlow() {
        val updatesFlow = updateManager.availableUpdates
        assertNotNull(updatesFlow)
        assertTrue(updatesFlow.value.isEmpty())
    }

    @Test
    fun testGetUpdateState() {
        val state = updateManager.getUpdateState("non-existent-skill")
        assertTrue(state == null)
    }

    @Test
    fun testClearUpdateState() {
        updateManager.clearUpdateState("non-existent-skill")
        assertTrue(updateManager.updateState.value.isEmpty())
    }

    @Test
    fun testClearAllUpdateStates() {
        updateManager.clearAllUpdateStates()
        assertTrue(updateManager.updateState.value.isEmpty())
    }

    @Test
    fun testGetCacheSize() {
        val cacheSize = updateManager.getCacheSize()
        assertTrue(cacheSize >= 0)
    }
}

@RunWith(JUnit4::class)
class SkillVersionManagerTest {

    private lateinit var context: Context
    private lateinit var versionManager: SkillVersionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        versionManager = SkillVersionManager.getInstance(context)
    }

    @After
    fun tearDown() {
        SkillVersionManager.resetInstance()
    }

    @Test
    fun testGetVersionHistory() = runBlocking {
        val history = versionManager.getVersionHistory("non-existent-skill")
        assertNotNull(history)
    }

    @Test
    fun testGetRollbackStatus() = runBlocking {
        val status = versionManager.getRollbackStatus("non-existent-skill")
        assertNotNull(status)
        assertFalse(status.canRollback)
        assertNotNull(status.currentVersion)
    }

    @Test
    fun testGetVersionStorageSize() = runBlocking {
        val size = versionManager.getVersionStorageSize()
        assertTrue(size >= 0)
    }
}