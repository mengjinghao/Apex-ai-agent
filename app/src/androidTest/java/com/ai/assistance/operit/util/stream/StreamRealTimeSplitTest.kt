package com.ai.assistance.Apex.util.stream

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.Apex.util.stream.plugins.StreamXmlPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class StreamRealTimeSplitTest {

    @Before
    fun setup() {
        // 启用日志
        StreamLogger.setEnabled(true)
        // 只保留高级别日志，禁用逐字符的详细（verbose）日志，使输出更清晰
        StreamLogger.setVerboseEnabled(false)
    }

    /**
     * 生成混合内容的大文本
     * 包含扁平的XML块和普通文本，重复多次以创建足够大的数据量
     */
    private fun generateLargeTestText(sizeMultiplier: Int = 20): String {
        val block = """
            <user id="1">
                <name>用户1</name>
                <email>user1@example.com</email>
            </user>
            这是第一段普通文本，它不属于任何XML块。
            <message read="true">
                这是一个消息内容。
            </message>
            这是第二段普通文本，用于分隔XML块。
        """.trimIndent()

        // 重复内容多次以创建大文本
        return (1..sizeMultiplier).joinToString("\n\n") {
            "--- 第 $it 部分 ---\n$block"
        }
    }

    /**
     * 测试splitBy对大文本的实时流式处理能力
     * 通过打印发送和接收的时间戳和内容，验证处理是否同步流式进行
     */
    @Test
    fun testRealTimeSplitByProcessing() = runBlocking {
        println("\n===== 开始测试实时流式处理 =====")
        
        // 准备测试数据
        val largeText = generateLargeTestText()
        println("生成的测试文本大小: ${largeText.length} 字符")
        
        // XML插件
        val xmlPlugin = StreamXmlPlugin()
        
        // 计时
        val totalTime = measureTimeMillis {
            var charCount = 0
            var textGroupCount = 0
            var xmlGroupCount = 0
            var totalReceivedChars = 0
            
            // 从字符串创建字符流
            val charStream = largeText.asCharStream()
            
            // 使用splitBy处理流
            val groupedStream = charStream.splitBy(listOf(xmlPlugin))
            
            // 创建一个异步任务来收集结果
            val collectJob = async(Dispatchers.IO) {
                groupedStream.collect { group ->
                    when (group.tag) {
                        is StreamXmlPlugin -> {
                            xmlGroupCount++
                            println("➡️ 开始接收 XML 组 #$xmlGroupCount")
                            
                            var groupCharCount = 0
                            val groupStartTime = System.currentTimeMillis()
                            
                            group.stream.collect { char ->
                                groupCharCount++
                                totalReceivedChars++
                                
                                // 每100个字符打印一次进度，避免日志过多
                                if (groupCharCount % 100 == 0) {
                                    val now = System.currentTimeMillis()
                                    println("   ⏱️ XML组收到第 $groupCharCount 个字符，总接收 $totalReceivedChars，耗时 ${now - groupStartTime}ms")
                                }
                            }
                            
                            println("⬅️ 完成接收 XML 组 #$xmlGroupCount，共 $groupCharCount 字符")
                        }
                        null -> {
                            textGroupCount++
                            println("➡️ 开始接收文本组 #$textGroupCount")
                            
                            var groupCharCount = 0
                            val groupStartTime = System.currentTimeMillis()
                            
                            group.stream.collect { char ->
                                groupCharCount++
                                totalReceivedChars++
                                
                                // 每100个字符打印一次进度
                                if (groupCharCount % 100 == 0) {
                                    val now = System.currentTimeMillis()
                                    println("   ⏱️ 文本组收到第 $groupCharCount 个字符，总接收 $totalReceivedChars，耗时 ${now - groupStartTime}ms")
                                }
                            }
                            
                            println("⬅️ 完成接收文本组 #$textGroupCount，共 $groupCharCount 字符")
                        }
                    }
                }
                
                totalReceivedChars
            }
            
            // 等待收集完成
            val totalProcessed = collectJob.await()
            
            println("\n🔍 处理统计:")
            println("总输入字符: ${largeText.length}")
            println("总处理字符: $totalProcessed")
            println("XML组数量: $xmlGroupCount")
            println("文本组数量: $textGroupCount")
        }
        
        println("\n⏱️ 总处理时间: ${totalTime}ms")
        println("===== 测试完成 =====\n")
    }
    
    /**
     * 测试在发送方添加延迟的情况下，是否仍然保持流式处理
     * 这有助于更清晰地观察流式处理的行为
     */
    @Test
    fun testRealTimeSplitByWithSlowProducer() = runBlocking {
        println("\n===== 开始测试慢速生产者的实时流式处理 =====")
        
        // 使用较小的测试数据，因为我们会添加延迟
        val testText = generateLargeTestText(2)
        println("生成的测试文本大小: ${testText.length} 字符")
        
        val xmlPlugin = StreamXmlPlugin()
        
        // 创建一个带延迟的字符流
        val slowCharStream = stream<Char> {
            testText.forEach { char ->
                emit(char)
                // 每发射10个字符添加一个小延迟，模拟网络或文件读取的不均匀速度
                if (testText.indexOf(char) % 10 == 0) {
                    delay(5)
                }
            }
        }
        
        // 分割流并收集结果
        val groupedStream = slowCharStream.splitBy(listOf(xmlPlugin))
        
        var textGroupCount = 0
        var xmlGroupCount = 0
        
        groupedStream.collect { group ->
            val isXml = group.tag is StreamXmlPlugin
            val groupId = if (isXml) {
                xmlGroupCount++
                "XML #$xmlGroupCount"
            } else {
                textGroupCount++
                "文本 #$textGroupCount"
            }
            
            println("\n🆕 开始接收 $groupId 组")
            val startTime = System.currentTimeMillis()
            var charCount = 0
            
            // 使用另一个延迟来模拟处理时间，验证生产和消费的异步性
            group.stream.collect { char ->
                charCount++
                if (charCount % 20 == 0) {
                    val now = System.currentTimeMillis()
                    val elapsed = now - startTime
                    println("   📊 $groupId 组已接收 $charCount 字符，耗时 ${elapsed}ms")
                    
                    // 随机添加一个短暂的处理延迟
                    if (Math.random() > 0.7) {
                        delay(10)
                    }
                }
            }
            
            val endTime = System.currentTimeMillis()
            println("✅ 完成接收 $groupId 组，共 $charCount 字符，总耗时 ${endTime - startTime}ms")
        }
        
        println("\n📈 处理统计:")
        println("XML组数量: $xmlGroupCount")
        println("文本组数量: $textGroupCount")
        println("===== 测试完成 =====\n")
    }
} 