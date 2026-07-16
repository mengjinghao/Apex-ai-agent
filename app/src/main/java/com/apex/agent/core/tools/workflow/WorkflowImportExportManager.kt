package com.apex.core.tools.workflow

import com.apex.data.model.ShareMetadata
import com.apex.data.model.Workflow
import com.apex.data.model.WorkflowImportResult
import com.apex.data.model.WorkflowTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WorkflowImportExportManager {

    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    suspend fun exportWorkflow(workflow: Workflow, exportDir: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val fileName = generateExportFileName(workflow.name, isTemplate = false)
            val exportFile = File(exportDir, fileName)

            val exportInfo = WorkflowExportInfo(
                version = "1.0",
                type = "workflow",
                exportedAt = System.currentTimeMillis(),
                workflow = workflow
            )

            val jsonContent = json.encodeToString(exportInfo)
            exportFile.writeText(jsonContent)

            Result.success(exportFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportTemplate(template: WorkflowTemplate, exportDir: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val fileName = generateExportFileName(template.name, isTemplate = true)
            val exportFile = File(exportDir, fileName)

            val exportInfo = TemplateExportInfo(
                version = "1.0",
                type = "template",
                exportedAt = System.currentTimeMillis(),
                template = template
            )

            val jsonContent = json.encodeToString(exportInfo)
            exportFile.writeText(jsonContent)

            Result.success(exportFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importWorkflow(importFile: File): Result<WorkflowImportResult> = withContext(Dispatchers.IO) {
        try {
            if (!importFile.exists()) {
                return@withContext Result.failure(Exception("Import file not found"))
            }

            val content = importFile.readText()

            val workflowImportResult = parseAndValidateImport(content, isTemplate = false)
            Result.success(workflowImportResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importTemplate(importFile: File): Result<WorkflowImportResult> = withContext(Dispatchers.IO) {
        try {
            if (!importFile.exists()) {
                return@withContext Result.failure(Exception("Import file not found"))
            }

            val content = importFile.readText()

            val templateImportResult = parseAndValidateImport(content, isTemplate = true)
            Result.success(templateImportResult)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun validateWorkflowJson(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val workflow = json.decodeFromString<Workflow>(jsonString)
            workflow.id.isNotBlank() && workflow.nodes.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun convertToTemplate(workflow: Workflow, metadata: ShareMetadata): WorkflowTemplate = withContext(Dispatchers.IO) {
        WorkflowTemplate(
            name = metadata.title,
            description = metadata.description,
            author = metadata.author,
            tags = metadata.tags,
            category = metadata.category,
            workflow = workflow,
            version = metadata.version,
            previewImage = metadata.previewImage,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun parseAndValidateImport(content: String, isTemplate: Boolean): WorkflowImportResult {
        return try {
            val warnings = mutableListOf<String>()

            val exportInfo = try {
                json.decodeFromString<WorkflowExportInfo>(content)
            } catch (e: Exception) {
                null
            }

            val templateExportInfo = if (exportInfo == null) {
                try {
                    json.decodeFromString<TemplateExportInfo>(content)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            when {
                exportInfo != null && exportInfo.type == "workflow" -> {
                    if (exportInfo.version != "1.0") {
                        warnings.add("Unsupported export version: ${exportInfo.version}")
                    }

                    val newWorkflow = exportInfo.workflow.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )

                    WorkflowImportResult(
                        success = true,
                        workflow = newWorkflow,
                        warnings = warnings
                    )
                }

                templateExportInfo != null && templateExportInfo.type == "template" -> {
                    if (templateExportInfo.version != "1.0") {
                        warnings.add("Unsupported export version: ${templateExportInfo.version}")
                    }

                    val newTemplate = templateExportInfo.template.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        workflow = templateExportInfo.template.workflow.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        ),
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )

                    WorkflowImportResult(
                        success = true,
                        template = newTemplate,
                        warnings = warnings
                    )
                }

                else -> {
                    val directWorkflow = try {
                        json.decodeFromString<Workflow>(content)
                    } catch (e: Exception) {
                        null
                    }

                    if (directWorkflow != null) {
                        val newWorkflow = directWorkflow.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )

                        WorkflowImportResult(
                            success = true,
                            workflow = newWorkflow,
                            warnings = listOf("Imported as raw workflow, not wrapped in export format")
                        )
                    } else {
                        WorkflowImportResult(
                            success = false,
                            error = "Unrecognized file format"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            WorkflowImportResult(
                success = false,
                error = "Parse error: ${e.message}"
            )
        }
    }

    private fun generateExportFileName(name: String, isTemplate: Boolean): String {
        val timestamp = dateFormat.format(Date())
        val sanitizedName = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(50)
        val extension = if (isTemplate) "_template.json" else ".json"
        return "${sanitizedName}_${timestamp}${extension}"
    }
}

@Serializable
data class WorkflowExportInfo(
    val version: String = "1.0",
    val type: String = "workflow",
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val workflow: Workflow
)

@Serializable
data class TemplateExportInfo(
    val version: String = "1.0",
    val type: String = "template",
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val template: WorkflowTemplate
)