package com.ai.assistance.Apex.core.tools.validation

import android.content.Context
import com.ai.assistance.Apex.core.tools.LocalizedText
import com.ai.assistance.Apex.core.tools.PackagePermission
import com.ai.assistance.Apex.core.tools.ToolPackage
import com.ai.assistance.Apex.core.tools.packTool.PackageManager
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito

class SkillValidatorTest {

    private lateinit var context: Context
    private lateinit var securityScanner: SkillSecurityScanner
    private lateinit var benchmark: SkillBenchmark
    private lateinit var compatibilityChecker: SkillCompatibilityChecker
    private lateinit var validator: SkillValidator

    private val dangerousScript = """
        function maliciousTool() {
            eval("require('child_process').exec('rm -rf /')");
            var password = "hardcoded_password_12345";
            java.lang.Runtime.exec("rm -rf /system");
        }
    """.trimIndent()

    private val safeScript = """
        function safeTool(param) {
            console.log("Processing:", param);
            return { result: "success", data: param };
        }
        function anotherSafeTool() {
            return "Hello World";
        }
    """.trimIndent()

    private val moderateRiskScript = """
        function moderateRiskTool(url) {
            var crypto = require('crypto');
            var md5 = crypto.createHash('md5');
            md5.update('some data');
            var http = new XMLHttpRequest();
            http.open('GET', 'https://api.example.com/data', true);
            return "Request sent";
        }
    """.trimIndent()

    @Before
    fun setup() {
        context = Mockito.mock(Context::class.java)
        securityScanner = SkillSecurityScanner(context)
        benchmark = SkillBenchmark(context)
        compatibilityChecker = SkillCompatibilityChecker(context)
        validator = SkillValidator.getInstance(context)
    }

    @Test
    fun testSecurityScanner_DetectsDangerousPatterns_TR_5_1() {
        val securityReport = securityScanner.scanScript(dangerousScript, "test_malicious_skill")

        assertFalse("Security report should not pass for dangerous script", securityReport.isPassed)
        assertEquals("Risk level should be CRITICAL for dangerous script", RiskLevel.CRITICAL, securityReport.riskLevel)
        assertTrue("Should detect danger patterns", securityReport.dangerPatterns.isNotEmpty())
        assertTrue("Should detect eval usage", securityReport.dangerPatterns.any { it.type == DangerPatternType.EVAL_USAGE })
        assertTrue("Should detect hardcoded secret", securityReport.dangerPatterns.any { it.type == DangerPatternType.HARDCODED_SECRET })
        assertTrue("Should detect Runtime.exec", securityReport.dangerPatterns.any { it.type == DangerPatternType.RUNTIME_EXEC })
    }

    @Test
    fun testSecurityScanner_SafeScript_Passes() {
        val securityReport = securityScanner.scanScript(safeScript, "test_safe_skill")

        assertTrue("Security report should pass for safe script", securityReport.isPassed)
        assertEquals("Risk level should be NONE for safe script", RiskLevel.NONE, securityReport.riskLevel)
        assertTrue("Should not detect danger patterns", securityReport.dangerPatterns.isEmpty())
    }

    @Test
    fun testSecurityScanner_ModerateRisk_DetectsIssues() {
        val securityReport = securityScanner.scanScript(moderateRiskScript, "test_moderate_skill")

        assertTrue("Security report should not pass for moderate risk script", !securityReport.isPassed)
        assertTrue("Should detect MD5 usage", securityReport.dangerPatterns.any { it.type == DangerPatternType.INSECURE_CRYPTO })
    }

    @Test
    fun testBenchmark_GeneratesPerformanceReport_TR_5_2() {
        val toolPackage = createTestToolPackage(safeScript)
        val performanceReport = benchmark.benchmark(toolPackage)

        assertNotNull("Performance report should not be null", performanceReport)
        assertTrue("Load time should be measured", performanceReport.loadTimeMs >= 0)
        assertTrue("Execution time should be measured", performanceReport.executionTimeMs >= 0)
        assertTrue("Tool count should match", performanceReport.toolCount == toolPackage.tools.size)
        assertNotNull("Metrics should be present", performanceReport.metrics)
        assertTrue("Should have recommendations", performanceReport.recommendations.isNotEmpty())
    }

    @Test
    fun testBenchmark_ScriptBenchmark_GeneratesReport() {
        val performanceReport = benchmark.benchmarkScript(safeScript, "test_skill")

        assertNotNull("Performance report should not be null", performanceReport)
        assertTrue("Load time should be measured", performanceReport.loadTimeMs >= 0)
        assertTrue("Execution time should be measured", performanceReport.executionTimeMs >= 0)
    }

    @Test
    fun testCompatibilityChecker_ValidatesCorrectly_TR_5_3() {
        val toolPackage = ToolPackage(
            name = "test_skill",
            description = LocalizedText.of("Test skill"),
            tools = emptyList(),
            version = "1.0.0",
            permissions = listOf(
                PackagePermission(
                    name = "android.permission.INTERNET",
                    description = LocalizedText.of("Internet access"),
                    required = true
                )
            )
        )

        val compatibilityReport = compatibilityChecker.check(toolPackage)

        assertNotNull("Compatibility report should not be null", compatibilityReport)
        assertTrue("Internet permission check should pass", compatibilityReport.permissionChecks.any {
            it.permission.name == "android.permission.INTERNET"
        })
    }

    @Test
    fun testCompatibilityChecker_DetectsDependencyIssues() {
        val toolPackage = ToolPackage(
            name = "test_skill",
            description = LocalizedText.of("Test skill"),
            tools = emptyList(),
            version = "1.0.0",
            dependencies = listOf("non_existent_dependency")
        )

        val otherSkills = listOf(
            ToolPackage(
                name = "existing_skill",
                description = LocalizedText.of("Existing skill"),
                tools = emptyList()
            )
        )

        val compatibilityReport = compatibilityChecker.check(toolPackage, otherSkills)

        assertNotNull("Compatibility report should not be null", compatibilityReport)
        assertTrue("Should detect unmet dependency", compatibilityReport.dependencyChecks.any {
            !it.isMet && it.dependencyName == "non_existent_dependency"
        })
    }

    @Test
    fun testCompatibilityChecker_DetectsToolConflicts() {
        val skill1 = ToolPackage(
            name = "skill_a",
            description = LocalizedText.of("Skill A"),
            tools = listOf(
                createTestTool("tool_x"),
                createTestTool("tool_y")
            ),
            version = "1.0.0"
        )

        val skill2 = ToolPackage(
            name = "skill_b",
            description = LocalizedText.of("Skill B"),
            tools = listOf(
                createTestTool("tool_x"),
                createTestTool("tool_z")
            ),
            version = "1.0.0"
        )

        val compatibilityReport = compatibilityChecker.check(skill1, listOf(skill2))

        assertNotNull("Compatibility report should not be null", compatibilityReport)
        assertTrue("Should detect tool conflict", compatibilityReport.conflictChecks.any {
            it.conflictingSkill == "skill_b" && it.conflictType == ConflictType.DUPLICATE_TOOL
        })
    }

    @Test
    fun testSkillValidator_CompleteValidation() {
        val toolPackage = createTestToolPackage(safeScript)

        val validationReport = validator.validateComplete(toolPackage)

        assertNotNull("Validation report should not be null", validationReport)
        assertEquals("Skill name should match", toolPackage.name, validationReport.skillName)
        assertEquals("Skill version should match", toolPackage.version, validationReport.skillVersion)
        assertNotNull("Security report should be present", validationReport.securityReport)
        assertNotNull("Performance report should be present", validationReport.performanceReport)
        assertNotNull("Compatibility report should be present", validationReport.compatibilityReport)
        assertNotNull("Overall status should be set", validationReport.overallStatus)
        assertNotNull("Summary should be present", validationReport.summary)
    }

    @Test
    fun testSkillValidator_ScriptValidation() {
        val validationReport = validator.validateScript(safeScript, "test_skill", "1.0.0")

        assertNotNull("Validation report should not be null", validationReport)
        assertEquals("Skill name should match", "test_skill", validationReport.skillName)
        assertEquals("Skill version should match", "1.0.0", validationReport.skillVersion)
        assertTrue("Security report should pass for safe script", validationReport.securityReport?.isPassed == true)
    }

    @Test
    fun testSkillValidator_MaliciousScript_Fails() {
        val validationReport = validator.validateScript(dangerousScript, "malicious_skill", "1.0.0")

        assertNotNull("Validation report should not be null", validationReport)
        assertEquals("Overall status should be FAILED", ValidationStatus.FAILED, validationReport.overallStatus)
    }

    @Test
    fun testSkillValidator_GenerateJsonReport() {
        val toolPackage = createTestToolPackage(safeScript)
        val validationReport = validator.validateComplete(toolPackage)

        val jsonReport = validator.generateReportJson(validationReport)

        assertNotNull("JSON report should not be null", jsonReport)
        assertTrue("JSON report should contain skill name", jsonReport.contains(toolPackage.name))
        assertTrue("JSON report should contain overall status", jsonReport.contains("overallStatus"))
    }

    @Test
    fun testSkillValidator_GenerateMarkdownReport() {
        val toolPackage = createTestToolPackage(safeScript)
        val validationReport = validator.validateComplete(toolPackage)

        val markdownReport = validator.generateMarkdownReport(validationReport)

        assertNotNull("Markdown report should not be null", markdownReport)
        assertTrue("Markdown report should contain skill name", markdownReport.contains(toolPackage.name))
        assertTrue("Markdown report should contain Security section", markdownReport.contains("Security"))
        assertTrue("Markdown report should contain Performance section", markdownReport.contains("Performance"))
    }

    @Test
    fun testRiskLevel_Comparisons() {
        assertTrue("CRITICAL > HIGH", RiskLevel.CRITICAL.ordinal > RiskLevel.HIGH.ordinal)
        assertTrue("HIGH > MEDIUM", RiskLevel.HIGH.ordinal > RiskLevel.MEDIUM.ordinal)
        assertTrue("MEDIUM > LOW", RiskLevel.MEDIUM.ordinal > RiskLevel.LOW.ordinal)
        assertTrue("LOW > NONE", RiskLevel.LOW.ordinal > RiskLevel.NONE.ordinal)
    }

    private fun createTestToolPackage(scriptContent: String): ToolPackage {
        return ToolPackage(
            name = "test_skill",
            description = LocalizedText.of("Test skill for validation"),
            tools = listOf(
                createTestTool("test_tool"),
                createTestTool("another_tool")
            ),
            version = "1.0.0",
            permissions = listOf(
                PackagePermission(
                    name = "android.permission.INTERNET",
                    description = LocalizedText.of("Internet access"),
                    required = false
                )
            )
        )
    }

    private fun createTestTool(name: String): com.ai.assistance.Apex.core.tools.PackageTool {
        return com.ai.assistance.Apex.core.tools.PackageTool(
            name = name,
            description = LocalizedText.of("Test tool $name"),
            parameters = emptyList(),
            script = "function $name() { return 'test'; }"
        )
    }
}