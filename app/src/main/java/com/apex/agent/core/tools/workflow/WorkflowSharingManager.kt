package com.apex.core.tools.workflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.apex.data.model.ShareMetadata
import com.apex.data.model.Workflow
import com.apex.data.model.WorkflowImportResult
import com.apex.data.model.WorkflowShare
import com.apex.data.model.WorkflowTemplate
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.EnumMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkflowSharingManager(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val shareDir = File(context.filesDir, "workflow_shares").apply {
        if (!exists()) mkdirs()
    }

    private val appScheme = "Apex"
    private val appHost = "workflow"

    suspend fun createShare(workflow: Workflow, expiresInDays: Int? = null): WorkflowShare = withContext(Dispatchers.IO) {
        val shareCode = generateShareCode()
        val expiresAt = expiresInDays?.let { System.currentTimeMillis() + it * 24 * 60 * 60 * 1000L }

        val share = WorkflowShare(
            workflowId = workflow.id,
            shareCode = shareCode,
            expiresAt = expiresAt
        )

        val workflowJson = json.encodeToString(workflow)
        saveShareData(share, workflowJson)

        share
    }

    suspend fun getShareByCode(shareCode: String): WorkflowShare? = withContext(Dispatchers.IO) {
        val shareFile = File(shareDir, "${shareCode}.json")
        if (!shareFile.exists()) return@withContext null

        try {
            val shareData = loadShareData(shareCode) ?: return@withContext null
            val (share, _) = shareData

            if (share.expiresAt != null && System.currentTimeMillis() > share.expiresAt) {
                shareFile.delete()
                return@withContext null
            }

            if (share.maxUses != null && share.useCount >= share.maxUses) {
                return@withContext null
            }

            share
        } catch (e: Exception) {
            null
        }
    }

    suspend fun importFromShare(shareCode: String): WorkflowImportResult = withContext(Dispatchers.IO) {
        try {
            val share = getShareByCode(shareCode)
            if (share == null) {
                return@withContext WorkflowImportResult(
                    success = false,
                    error = "Share not found or expired"
                )
            }

            val shareData = loadShareData(shareCode)
            if (shareData == null) {
                return@withContext WorkflowImportResult(
                    success = false,
                    error = "Failed to load share data"
                )
            }

            val (_, workflowJson) = shareData
            val workflow = json.decodeFromString<Workflow>(workflowJson)

            val newWorkflow = workflow.copy(
                id = java.util.UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            return@withContext WorkflowImportResult(
                success = true,
                workflow = newWorkflow
            )
        } catch (e: Exception) {
            WorkflowImportResult(
                success = false,
                error = "Import failed: ${e.message}"
            )
        }
    }

    suspend fun exportToJson(workflow: Workflow): String = withContext(Dispatchers.IO) {
        json.encodeToString(workflow)
    }

    suspend fun importFromJson(jsonString: String): WorkflowImportResult = withContext(Dispatchers.IO) {
        try {
            val workflow = json.decodeFromString<Workflow>(jsonString)

            val newWorkflow = workflow.copy(
                id = java.util.UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            WorkflowImportResult(
                success = true,
                workflow = newWorkflow
            )
        } catch (e: Exception) {
            WorkflowImportResult(
                success = false,
                error = "Invalid JSON format: ${e.message}"
            )
        }
    }

    suspend fun generateQRCode(shareCode: String, size: Int = 512): Bitmap = withContext(Dispatchers.IO) {
        val shareUrl = "${appScheme}://${appHost}/share/${shareCode}"
        generateQRCodeBitmap(shareUrl, size, size)
    }

    suspend fun generateShareUrl(share: WorkflowShare): String = withContext(Dispatchers.IO) {
        "${appScheme}://${appHost}/share/${share.shareCode}"
    }

    suspend fun createTemplate(
        workflow: Workflow,
        metadata: ShareMetadata
    ): WorkflowTemplate = withContext(Dispatchers.IO) {
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

    private fun generateQRCodeBitmap(content: String, width: Int, height: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.MARGIN, 2)
            put(EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
        }

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints)

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun generateShareCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }

    private fun saveShareData(share: WorkflowShare, workflowJson: String) {
        val shareFile = File(shareDir, "${share.shareCode}.json")
        val shareJson = json.encodeToString(share)
        val combined = mapOf(
            "share" to json.decodeFromString<Map<String, String>>(shareJson),
            "workflow" to json.decodeFromString<Map<String, String>>(workflowJson)
        )
        FileOutputStream(shareFile).use { fos ->
            fos.write(json.encodeToString(combined).toByteArray(Charsets.UTF_8))
        }
    }

    private fun loadShareData(shareCode: String): Pair<WorkflowShare, String>? {
        val shareFile = File(shareDir, "${shareCode}.json")
        if (!shareFile.exists()) return null

        return try {
            FileInputStream(shareFile).use { fis ->
                val content = fis.readBytes().toString(Charsets.UTF_8)
                @Suppress("UNCHECKED_CAST")
                val combined = json.decodeFromString<Map<String, Map<String, Any>>>(content)
                val shareJson = json.encodeToString(combined["share"])
                val workflowJson = json.encodeToString(combined["workflow"])
                val share = json.decodeFromString<WorkflowShare>(shareJson)
                Pair(share, workflowJson)
            }
        } catch (e: Exception) {
            try {
                FileInputStream(shareFile).use { fis ->
                    val content = fis.readBytes().toString(Charsets.UTF_8)
                    val share = json.decodeFromString<WorkflowShare>(content)
                    val workflowJson = "{}"
                    Pair(share, workflowJson)
                }
            } catch (e2: Exception) {
                null
            }
        }
    }
}

suspend fun exportWorkflowToFile(workflow: Workflow, file: File): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        val workflowJson = json.encodeToString(workflow)
        FileOutputStream(file).use { fos ->
            fos.write(workflowJson.toByteArray(Charsets.UTF_8))
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

suspend fun importWorkflowFromFile(file: File): Result<Workflow> = withContext(Dispatchers.IO) {
    try {
        if (!file.exists()) {
            return@withContext Result.failure(Exception("File not found"))
        }

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        FileInputStream(file).use { fis ->
            val content = fis.readBytes().toString(Charsets.UTF_8)
            val workflow = json.decodeFromString<Workflow>(content)
            Result.success(workflow)
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}