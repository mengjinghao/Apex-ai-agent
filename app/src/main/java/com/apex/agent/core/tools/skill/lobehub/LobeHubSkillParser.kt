package com.apex.agent.core.tools.skill.lobehub

import com.apex.util.AppLogger
import java.io.File

/**
 * LobeHub SKILL.md Parser
 * 
 * Parses LobeHub Skills Marketplace format SKILL.md files
 * Reference: https://lobehub.com/skills/skill.md
 * 
 * LobeHub SKILL.md Format:
 * ---
 * name: skill-name
 * description: Skill description
 * ---
 * # Skill Title
 * 
 * ## Description
 * ...
 * 
 * ## Inputs
 * ...
 * 
 * ## Usage
 * ...
 */
class LobeHubSkillParser {

    companion object {
        private const val TAG = "LobeHubSkillParser"
        private const val FRONTMATTER_DELIMITER = "---"
    }

    /**
     * Parse SKILL.md content into LobeHubSkillSpec
     */
    fun parseSkillMd(content: String): LobeHubSkillSpec {
        val spec = LobeHubSkillSpec()
        if (content.isBlank()) {
            return spec
        }
        val lines = content.lines()
        
        // Check for frontmatter
        if (lines.isNotEmpty() && lines[0].trim() == FRONTMATTER_DELIMITER) {
            val endIndex = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
        if (endIndex >= 0) {
                val frontmatter = lines.subList(1, endIndex + 1)
        parseFrontmatter(frontmatter, spec)
                
                // Parse body content
        val bodyStart = endIndex + 2
                if (bodyStart < lines.size) {
                    parseBody(lines.subList(bodyStart, lines.size), spec)
                }
            }
        } else {
            // No frontmatter, try to parse from body
        parseBody(lines, spec)
        }
        return spec
    }

    /**
     * Parse SKILL.md file
     */
    fun parseSkillFile(file: File): LobeHubSkillSpec {
        return try {
            val content = file.readText()
        val spec = parseSkillMd(content)
            // Use filename as identifier if not set
        if (spec.identifier.isBlank()) {
                spec.copy(identifier = file.nameWithoutExtension)
            }
        spec
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse skill file: ${file.absolutePath}", e)
        LobeHubSkillSpec()
        }
    }
        private fun parseFrontmatter(lines: List<String>, spec: LobeHubSkillSpec) {
        var identifier = ""
        var name = ""
        var description = ""
        var version = "1.0.0"
        var author = ""
        var homepage = ""
        var license = "MIT"
        var tags = emptyList<String>()
        var agent = emptyList<String>()
        var extends = emptyList<String>()
        var inCapabilities = false
        var inInstall = false
        var inInputs = false
        
        var currentBlock = ""
        var blockContent = StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
        when {
                trimmed == "capabilities:" -> {
                    inCapabilities = true
                    inInstall = false
                    inInputs = false
                    currentBlock = "capabilities"
        blockContent = StringBuilder()
                }
        trimmed == "install:" -> {
                    inCapabilities = false
                    inInstall = true
                    inInputs = false
                    currentBlock = "install"
        blockContent = StringBuilder()
                }
        trimmed.startsWith("inputs:") -> {
                    inCapabilities = false
                    inInstall = false
                    inInputs = true
                    currentBlock = "inputs"
        blockContent = StringBuilder()
                }
        trimmed.startsWith("tags:") -> {
                    tags = parseListField(trimmed)
                }
        trimmed.startsWith("agent:") -> {
                    agent = parseListField(trimmed)
                }
        trimmed.startsWith("extends:") -> {
                    extends = parseListField(trimmed)
                }
        trimmed.startsWith("identifier:") || trimmed.startsWith("name:") -> {
                    val key = if (trimmed.startsWith("identifier:")) "identifier" else "name"
        val value = trimmed.substringAfter(":").trim().unquote()
        if (key == "identifier") identifier = value else name = value
                }
        trimmed.startsWith("description:") -> {
                    description = trimmed.substringAfter(":").trim().unquote()
                }
        trimmed.startsWith("version:") -> {
                    version = trimmed.substringAfter(":").trim().unquote()
                }
        trimmed.startsWith("author:") -> {
                    author = trimmed.substringAfter(":").trim().unquote()
                }
        trimmed.startsWith("homepage:") -> {
                    homepage = trimmed.substringAfter(":").trim().unquote()
                }
        trimmed.startsWith("license:") -> {
                    license = trimmed.substringAfter(":").trim().unquote()
                }
            }
        }
        spec.copy(
            identifier = identifier.ifBlank { name },
            name = name,
            description = description,
            version = version,
            author = author,
            homepage = homepage,
            license = license,
            tags = tags,
            agent = agent,
            capabilities = LobeHubCapabilities(extends = extends)
        )
    }
        private fun parseBody(lines: List<String>, spec: LobeHubSkillSpec) {
        // Extract title (first # heading)
        val title = lines.find { it.startsWith("# ") }?.substringAfter("# ") ?: ""
        
        // Extract sections
        var currentSection = ""
        var sectionContent = StringBuilder()
        val sections = mutableMapOf<String, String>()
        for (line in lines) {
            when {
                line.startsWith("## ") -> {
                    if (currentSection.isNotEmpty() && sectionContent.isNotEmpty()) {
                        sections[currentSection] = sectionContent.toString().trim()
                    }
        currentSection = line.substringAfter("## ").trim().lowercase()
        sectionContent = StringBuilder()
                }
        line.startsWith("# ") -> {
                    // Skip main title
                }
        else -> {
                    sectionContent.appendLine(line)
                }
            }
        }
        if (currentSection.isNotEmpty() && sectionContent.isNotEmpty()) {
            sections[currentSection] = sectionContent.toString().trim()
        }

        // Update spec with parsed sections
        val description = sections["description"] ?: sections["概述"] ?: ""
        val usage = sections["usage"] ?: sections["使用方法"] ?: sections["使用者輸具] ?: "" val inputs = sections["inputs"] ?: sections["輸入"] ?: ""  // If no description in frontmatter, use body if (spec.description.isBlank() && description.isNotBlank()) { spec.description = description } }
private fun parseListField(line: String): List<String> { val value = line.substringAfter(":").trim() if (value.isBlank()) return emptyList() return when { value.startsWith("[") && value.endsWith("]") -> { // JSON array format: [item1, item2] value.trim('[', ']') .split(",") .map { it.trim().unquote() } .filter { it.isNotBlank() } }
value.contains(",") -> { // Comma-separated: item1, item2 value.split(",").map { it.trim().unquote() }.filter { it.isNotBlank() } }
else -> { // Single item listOf(value.unquote()) } } }
private fun String.unquote(): String { var value = this if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\''))) { if (value.length >= 2) value = value.substring(1, value.length - 1) }
return value.trim() }  /** * Convert LobeHubSkillSpec to Apex-compatible skill metadata */ fun toApexMetadata(spec: LobeHubSkillSpec): Map<String, Any> { return mapOf( "name" to spec.identifier.ifBlank { spec.name }, "display_name" to mapOf( "zh" to spec.name, "en" to spec.name ), "description" to mapOf( "zh" to spec.description, "en" to spec.description ), "version" to spec.version, "author" to spec.author, "category" to "LobeHub", "tags" to spec.tags, "supported_agents" to spec.agent, "license" to spec.license, "homepage" to spec.homepage ) }  /** * Generate SKILL.md from LobeHubSkillSpec */ fun generateSkillMd(spec: LobeHubSkillSpec): String { val sb = StringBuilder() sb.appendLine("---") sb.appendLine("identifier: ${spec.identifier}") sb.appendLine("name: ${spec.name}") sb.appendLine("description: ${spec.description}") sb.appendLine("version: ${spec.version}") if (spec.author.isNotBlank()) { sb.appendLine("author: ${spec.author}") }
if (spec.homepage.isNotBlank()) { sb.appendLine("homepage: ${spec.homepage}") }
sb.appendLine("license: ${spec.license}") if (spec.tags.isNotEmpty()) { sb.appendLine("tags: [${spec.tags.joinToString(", ")}]") }
if (spec.agent.isNotEmpty()) { sb.appendLine("agent: [${spec.agent.joinToString(", ")}]") }
sb.appendLine("---") sb.appendLine() sb.appendLine("# ${spec.name}") sb.appendLine() sb.appendLine("## Description") sb.appendLine() sb.appendLine(spec.description) sb.appendLine() if (spec.inputs.isNotEmpty()) { sb.appendLine("## Inputs") sb.appendLine() sb.appendLine("| Parameter | Description | Type | Required |") sb.appendLine("|-----------|-------------|------|----------|") for (input in spec.inputs) { sb.appendLine("| ${input.name} | ${input.description} | ${input.type} | ${if (input.required) "Yes" else "No"} |") }
sb.appendLine() }
sb.appendLine("## Usage") sb.appendLine() sb.appendLine("Install using LobeHub Market CLI:") sb.appendLine() sb.appendLine("```bash") sb.appendLine("npx -y @lobehub/market-cli skills install ${spec.identifier}") sb.appendLine("```") return sb.toString() } }
