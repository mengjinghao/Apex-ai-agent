package com.ai.assistance.`Apex agent`.core.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class UnifiedAITest {
    private lateinit var unifiedAI: UnifiedAIInterface
    private lateinit var modelSwitcher: ModelSwitcher
    
    @Before
    fun setUp() {
        unifiedAI = UnifiedAIFactory.create()
        modelSwitcher = ModelSwitcherFactory.create(unifiedAI)
    }
    
    @Test
    fun `test get available models`() {
        val models = unifiedAI.getAvailableModels()
        Assert.assertTrue(models.isNotEmpty())
        models.forEach {
            println("Model: ${it.name}, Provider: ${it.provider}, Type: ${it.type}")
        }
    }
    
    @Test
    fun `test generate text with different models`() = runBlocking {
        val prompt = "Hello, how are you?"
        
        // Test OpenAI model
        val openAIResult = unifiedAI.generateText(prompt, "gpt-4o")
        Assert.assertTrue(openAIResult.isNotEmpty())
        println("OpenAI result: $openAIResult")
        
        // Test Claude model
        val claudeResult = unifiedAI.generateText(prompt, "claude-3-opus-20240229")
        Assert.assertTrue(claudeResult.isNotEmpty())
        println("Claude result: $claudeResult")
        
        // Test Gemini model
        val geminiResult = unifiedAI.generateText(prompt, "gemini-1.5-pro")
        Assert.assertTrue(geminiResult.isNotEmpty())
        println("Gemini result: $geminiResult")
        
        // Test Nvidia model
        val nvidiaResult = unifiedAI.generateText(prompt, "nvidia-tensorrt")
        Assert.assertTrue(nvidiaResult.isNotEmpty())
        println("Nvidia result: $nvidiaResult")
        
        // Test local model
        val localResult = unifiedAI.generateText(prompt, "llama.cpp")
        Assert.assertTrue(localResult.isNotEmpty())
        println("Local result: $localResult")
    }
    
    @Test
    fun `test model switcher selects appropriate model`() {
        // Test text generation task
        val textModel = modelSwitcher.selectModel(TaskType.TEXT_GENERATION)
        Assert.assertNotNull(textModel)
        println("Selected text model: $textModel")
        
        // Test image generation task
        val imageModel = modelSwitcher.selectModel(TaskType.IMAGE_GENERATION)
        Assert.assertNotNull(imageModel)
        println("Selected image model: $imageModel")
        
        // Test speech recognition task
        val speechModel = modelSwitcher.selectModel(TaskType.SPEECH_RECOGNITION)
        Assert.assertNotNull(speechModel)
        println("Selected speech model: $speechModel")
    }
    
    @Test
    fun `test model switcher with fallback`() = runBlocking {
        val result = modelSwitcher.executeWithFallback(TaskType.TEXT_GENERATION) {
            unifiedAI.generateText("Test fallback mechanism", it)
        }
        Assert.assertTrue(result.isNotEmpty())
        println("Fallback result: $result")
    }
    
    @Test
    fun `test model status monitoring`() {
        val modelStatuses = modelSwitcher.getAllModelStatuses()
        Assert.assertTrue(modelStatuses.isNotEmpty())
        modelStatuses.forEach {
            println("Model: ${it.modelName}, Health: ${it.health.healthy}, Priority: ${it.priority}")
        }
    }
    
    @Test
    fun `test model priority setting`() {
        val testModel = "gpt-4o"
        val originalPriority = modelSwitcher.getModelStatus(testModel).priority
        
        // Set new priority
        modelSwitcher.setModelPriority(testModel, 20)
        val newPriority = modelSwitcher.getModelStatus(testModel).priority
        
        Assert.assertNotEquals(originalPriority, newPriority)
        Assert.assertEquals(20, newPriority)
        println("Priority changed from $originalPriority to $newPriority")
    }
    
    @Test
    fun `test model reset`() {
        val testModel = "gpt-4o"
        modelSwitcher.resetModelStatus(testModel)
        
        val status = modelSwitcher.getModelStatus(testModel)
        Assert.assertTrue(status.health.healthy)
        Assert.assertEquals(0, status.callCount)
        println("Model reset successfully: ${status.modelName}")
    }
}
