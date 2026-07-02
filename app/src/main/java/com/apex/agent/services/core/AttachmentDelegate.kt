package com.apex.services.core

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.apex.agent.R
import com.apex.util.AppLogger
import com.apex.util.ApexPaths
import com.apex.core.tools.AIToolHandler
import com.apex.data.model.AITool
import com.apex.data.model.AttachmentInfo
import com.apex.data.model.ToolParameter
import com.apex.util.OCRUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages attachment operations for the chat feature Handles adding, removing, and referencing
 * attachments
 */
class AttachmentDelegate(private val context: Context, private val toolHandler: AIToolHandler) {
    companion object {
        private const val TAG = "AttachmentDelegate"
        private const val OCR_INLINE_INSTRUCTION = "Do not read the file, answer the user\'s question directly based on the attachment content and the user\'s question."
    }

    // State for attachments
    private val _attachments = MutableStateFlow<List<AttachmentInfo>>(emptyList())
    val attachments: StateFlow<List<AttachmentInfo>> = _attachments

    // Events
    private val _toastEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<String> = _toastEvent

    /** Adds multiple attachments in one shot (dedup by filePath) */
    fun addAttachments(attachments: List<AttachmentInfo>) {
        if (attachments.isEmpty()) return
        val currentList = _attachments.value
        val toAdd = attachments.filterNot { incoming ->
            currentList.any { existing -> existing.filePath == incoming.filePath }
        }
        if (toAdd.isNotEmpty()) {
            _attachments.value = currentList + toAdd
        }
    }

    /**
     * Inserts a reference to an attachment at the current cursor position in the user's message
     * @return the formatted reference string
     */
    fun createAttachmentReference(attachment: AttachmentInfo): String {
        // Generate XML reference for the attachment
        val attachmentRef = StringBuilder("<attachment ")
        attachmentRef.append("id=\"${attachment.filePath}\" ")
        attachmentRef.append("filename=\"${attachment.fileName}\" ")
        attachmentRef.append("type=\"${attachment.mimeType}\" ")

        // Add size property
        if (attachment.fileSize > 0) {
            attachmentRef.append("size=\"${attachment.fileSize}\" ")
        }

        // Add content property (if exists)
        if (attachment.content.isNotEmpty()) {
            attachmentRef.append("content=\"${attachment.content}\" ")
        }

        attachmentRef.append("/>")

        return attachmentRef.toString()
    }

    /** Handles a photo taken by the camera */
    suspend fun handleTakenPhoto(uri: Uri) =
            withContext(Dispatchers.IO) {
                try {
                    val fileName = "camera_${System.currentTimeMillis()}.jpg"
                    val tempFile = createTempFileFromUri(uri, fileName)

                    if (tempFile != null) {
                        AppLogger.d(TAG, "Successfully created temp file from camera URI: ${tempFile.absolutePath}")

                        val attachmentInfo =
                                AttachmentInfo(
                                        filePath = tempFile.absolutePath,
                                        fileName = fileName,
                                        mimeType = "image/jpeg",
                                        fileSize = tempFile.length()
                                )

                        val currentList = _attachments.value
                        if (!currentList.any { it.filePath == tempFile.absolutePath }) {
                            _attachments.value = currentList + attachmentInfo
                        }

                        _toastEvent.emit(context.getString(R.string.attachment_photo_added, fileName))
                    } else {
                        AppLogger.e(TAG, "Failed to create temp file from camera URI")
                        _toastEvent.emit(context.getString(R.string.attachment_photo_process_failed))
                    }
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_photo_add_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error adding photo attachment", e)
                }
            }

    /** Handles a file or image attachment selected by the user 确保在IO线程执行所有文件操�?/
    suspend fun handleAttachment(filePath: String) =
            withContext(Dispatchers.IO) {
                try {
                    when (filePath) {
                        "screen_capture" -> {
                            captureScreenContent()
                            return@withContext
                        }
                        "notifications_capture" -> {
                            captureNotifications()
                            return@withContext
                        }
                        "location_capture" -> {
                            captureLocation()
                            return@withContext
                        }
                    }

                    // 检查是否是媒体选择器特殊路�?                   if (filePath.contains("/sdcard/.transforms/synthetic/picker/") ||
                                    filePath.contains("/com.android.providers.media.photopicker/")
                    ) {
                        AppLogger.d(TAG, "Detected media picker special path: ${filePath}")

                        try {
                            // 尝试从特殊路径提取实际URI
                            val actualUri = extractMediaStoreUri(filePath)
                            if (actualUri != null) {
                                // 使用提取出的URI创建临时文件
                                val fileName = filePath.substringAfterLast('/')
                                val tempFile = createTempFileFromUri(actualUri, fileName)

                                if (tempFile != null) {
                                    AppLogger.d(TAG, "Successfully created temp file from media picker path: ${tempFile.absolutePath}")

                                    // 创建附件对象
                                    val mimeType =
                                            getMimeTypeFromPath(tempFile.name) ?: "image/jpeg"
                                    val attachmentInfo =
                                            AttachmentInfo(
                                                    filePath = tempFile.absolutePath,
                                                    fileName = fileName,
                                                    mimeType = mimeType,
                                                    fileSize = tempFile.length()
                                            )

                                    // 添加到附件列�?                                   val currentList = _attachments.value
                                    if (!currentList.any { it.filePath == tempFile.absolutePath }) {
                                        _attachments.value = currentList + attachmentInfo
                                    }

                                    _toastEvent.emit(context.getString(R.string.attachment_added, fileName))
                                    return@withContext
                                } else {
                                    AppLogger.e(TAG, "Failed to create temp file from media path")
                                    _toastEvent.emit(context.getString(R.string.attachment_media_process_failed))
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Error handling media picker path", e)
                            // 继续尝试常规处理方法
                        }
                    }

                    // Check if it's a content URI path
                    if (filePath.startsWith("content://")) {
                        val uri = Uri.parse(filePath)
                        AppLogger.d(TAG, "Handling content URI: ${uri}")

                        // Get file metadata from ContentResolver
                        val fileName = getFileNameFromUri(uri)
                        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

                        // Always create a temporary file for content URIs to ensure persistent access
                        AppLogger.d(TAG, "Copying content URI to a local temporary file.")
                        val tempFile = createTempFileFromUri(uri, fileName)

                        if (tempFile != null && tempFile.exists()) {
                            AppLogger.d(TAG, "Successfully created temp file: ${tempFile.absolutePath}")
                            val attachmentInfo =
                                AttachmentInfo(
                                    filePath = tempFile.absolutePath,
                                    fileName = fileName,
                                    mimeType = mimeType,
                                    fileSize = tempFile.length()
                                )

                            // Add to attachment list
                            val currentList = _attachments.value
                            if (!currentList.any { it.filePath == tempFile.absolutePath }) {
                                _attachments.value = currentList + attachmentInfo
                            }
                            _toastEvent.emit(context.getString(R.string.attachment_added, fileName))
                        } else {
                            AppLogger.e(TAG, "Failed to create temp file from URI: ${uri}")
                            _toastEvent.emit(context.getString(R.string.attachment_cannot_attach, fileName))
                        }
                    } else {
                        // Handle as regular file path
                        val file = java.io.File(filePath)
                        if (!file.exists()) {
                            _toastEvent.emit(context.getString(R.string.attachment_file_not_exist))
                            return@withContext
                        }

                        val fileName = file.name
                        val fileSize = file.length()
                        val mimeType = getMimeTypeFromPath(filePath) ?: "application/octet-stream"

                        // 图片文件使用绝对路径
                        val actualFilePath =
                                if (mimeType.startsWith("image/")) {
                                    file.absolutePath
                                } else {
                                    filePath
                                }

                        val attachmentInfo =
                                AttachmentInfo(
                                        filePath = actualFilePath,
                                        fileName = fileName,
                                        mimeType = mimeType,
                                        fileSize = fileSize
                                )

                        // Add to attachment list
                        val currentList = _attachments.value
                        if (!currentList.any { it.filePath == actualFilePath }) {
                            _attachments.value = currentList + attachmentInfo
                        }

                        _toastEvent.emit(context.getString(R.string.attachment_added, fileName))
                    }
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_add_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error adding attachment", e)
                }
            }

    /** 从媒体选择器路径提取真实的MediaStore URI */
    private fun extractMediaStoreUri(filePath: String): Uri? {
        try {
            // 从文件名中提取媒体ID
            val mediaId = filePath.substringAfterLast('/').substringBefore('.')
            if (mediaId.toLongOrNull() != null) {
                // 构造MediaStore URI
                return Uri.parse("content://media/external/images/media/${mediaId}")
            }

            // 尝试通过直接构造content URI
            if (filePath.contains("com.android.providers.media.photopicker")) {
                val path = "content://com.android.providers.media.photopicker/media/${mediaId}"
                return Uri.parse(path)
            }

            // 最后尝试直接将路径转为URI
            return Uri.parse("file://${filePath}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to extract media URI: ${filePath}", e)
            return null
        }
    }

    /** 从URI创建临时文件 */
    private suspend fun createTempFileFromUri(uri: Uri, fileName: String): java.io.File? =
            withContext(Dispatchers.IO) {
                try {
                    val fileExtension = fileName.substringAfterLast('.', "jpg")

                    // 使用外部存储Download/Apex/cleanOnExit目录，而不是缓存目�?                   val externalDir = ApexPaths.cleanOnExitDir()

                    // 确保目录存在
                    if (!externalDir.exists()) {
                        externalDir.mkdirs()
                    }

                    // 确保.nomedia文件存在，防止媒体扫�?                   val noMediaFile = java.io.File(externalDir, ".nomedia")
                    if (!noMediaFile.exists()) {
                        noMediaFile.createNewFile()
                    }

                    val tempFile =
                            java.io.File(
                                    externalDir,
                                    "img_${System.currentTimeMillis()}.${fileExtension}"
                            )

                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }

                    if (tempFile.exists() && tempFile.length() > 0) {
                        AppLogger.d(TAG, "Successfully created temp image file: ${tempFile.absolutePath}")
                        return@withContext tempFile
                    }

                    return@withContext null
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to create temp file", e)
                    return@withContext null
                }
            }

    /** Removes an attachment by its file path */
    fun removeAttachment(filePath: String) {
        val currentList = _attachments.value
        _attachments.value = currentList.filter { it.filePath != filePath }
    }

    /** Clear all attachments */
    fun clearAttachments() {
        _attachments.value = emptyList()
    }

    /** Update attachments with a new list */
    fun updateAttachments(newAttachments: List<AttachmentInfo>) {
        _attachments.value = newAttachments
    }

    /**
     * Captures the current screen content and attaches it to the message Uses the get_page_info
     * AITool to retrieve UI structure 确保在IO线程中执�?    */
    suspend fun captureScreenContent() =
            withContext(Dispatchers.IO) {
                try {
                    val screenshotTool = AITool(name = "capture_screenshot", parameters = emptyList())
                    val screenshotResult = toolHandler.executeTool(screenshotTool)
                    if (!screenshotResult.success) {
                        _toastEvent.emit(context.getString(R.string.attachment_screen_content_failed, screenshotResult.error ?: context.getString(R.string.attachment_screenshot_failed)))
                        return@withContext
                    }

                    val screenshotPath = screenshotResult.result.toString().trim()
                    if (screenshotPath.isBlank()) {
                        _toastEvent.emit(context.getString(R.string.attachment_screen_content_failed, context.getString(R.string.attachment_screenshot_failed)))
                        return@withContext
                    }

                    val imageOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(screenshotPath, imageOptions)
                    val screenshotWidth = imageOptions.outWidth
                    val screenshotHeight = imageOptions.outHeight
                    val positionInfo =
                        if (screenshotWidth > 0 && screenshotHeight > 0) {
                            context.getString(R.string.attachment_location_full_screen, screenshotWidth, screenshotHeight)
                        } else {
                            context.getString(R.string.attachment_location_full_screen_simple)
                        }

                    val ocrText = OCRUtils.recognizeText(
                        context = context,
                        uri = Uri.fromFile(File(screenshotPath)),
                        quality = OCRUtils.Quality.HIGH
                    ).trim()

                    if (ocrText.isBlank()) {
                        _toastEvent.emit(context.getString(R.string.attachment_no_screen_text))
                        return@withContext
                    }

                    val captureId = "screen_ocr_${System.currentTimeMillis()}"
                    val content =
                        buildString {
                            append(context.getString(R.string.attachment_screen_content))
                            append(positionInfo)
                            append("\n\n")
                            append(ocrText)
                            append("\n\n")
                            append(OCR_INLINE_INSTRUCTION)
                        }
                    val attachmentInfo =
                        AttachmentInfo(
                            filePath = captureId,
                            fileName = "screen_content.txt",
                            mimeType = "text/plain",
                            fileSize = content.length.toLong(),
                            content = content
                        )

                    val currentList = _attachments.value
                    _attachments.value = currentList + attachmentInfo

                    _toastEvent.emit(context.getString(R.string.attachment_screen_content_added))

                    // 清理临时截图文件
                    try {
                        File(screenshotPath).delete()
                    } catch (_: Exception) {}
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_screen_content_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error capturing screen content", e)
                }
            }

    /** 获取设备当前通知并作为附件添加到消息 使用get_notifications AITool获取通知数据 确保在IO线程中执�?/
    suspend fun captureNotifications(limit: Int = 10) =
            withContext(Dispatchers.IO) {
                try {
                    // 创建工具参数
                    val toolParams =
                            listOf(
                                    ToolParameter("limit", limit.toString()),
                                    ToolParameter("include_ongoing", "true")
                            )

                    // 创建工具
                    val notificationsToolTask =
                            AITool(name = "get_notifications", parameters = toolParams)

                    // 执行工具
                    val result = toolHandler.executeTool(notificationsToolTask)

                    if (result.success) {
                        // 生成唯一ID
                        val captureId = "notifications_${System.currentTimeMillis()}"
                        val notificationsContent = result.result.toString()

                        // 创建附件信息
                        val attachmentInfo =
                                AttachmentInfo(
                                        filePath = captureId,
                                        fileName = "notifications.json",
                                        mimeType = "application/json",
                                        fileSize = notificationsContent.length.toLong(),
                                        content = notificationsContent
                                )

                        // 添加到附件列�?                       val currentList = _attachments.value
                        _attachments.value = currentList + attachmentInfo

                        _toastEvent.emit(context.getString(R.string.attachment_notifications_added))
                    } else {
                        _toastEvent.emit(context.getString(R.string.attachment_notifications_failed, result.error ?: context.getString(R.string.attachment_unknown_error)))
                    }
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_notifications_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error capturing notifications", e)
                }
            }

    /** 获取设备当前位置并作为附件添加到消息 使用get_device_location AITool获取位置数据 确保在IO线程中执�?/
    suspend fun captureLocation(highAccuracy: Boolean = true) =
            withContext(Dispatchers.IO) {
                try {
                    // 创建工具参数
                    val toolParams =
                            listOf(
                                    ToolParameter("high_accuracy", highAccuracy.toString()),
                                    ToolParameter("timeout", "10") // 10秒超�?                           )

                    // 创建工具
                    val locationToolTask =
                            AITool(name = "get_device_location", parameters = toolParams)

                    // 执行工具
                    val result = toolHandler.executeTool(locationToolTask)

                    if (result.success) {
                        // 生成唯一ID
                        val captureId = "location_${System.currentTimeMillis()}"
                        val locationContent = result.result.toString()

                        // 创建附件信息
                        val attachmentInfo =
                                AttachmentInfo(
                                        filePath = captureId,
                                        fileName = "location.json",
                                        mimeType = "application/json",
                                        fileSize = locationContent.length.toLong(),
                                        content = locationContent
                                )

                        // 添加到附件列�?                       val currentList = _attachments.value
                        _attachments.value = currentList + attachmentInfo

                        _toastEvent.emit(context.getString(R.string.attachment_location_added))
                    } else {
                        _toastEvent.emit(context.getString(R.string.attachment_location_failed, result.error ?: context.getString(R.string.attachment_unknown_error)))
                    }
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_location_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error capturing location", e)
                }
            }

    /** 获取当前时间并作为附件添加到消息 */
    suspend fun captureCurrentTime() =
            withContext(Dispatchers.IO) {
                try {
                    val captureId = "time_${System.currentTimeMillis()}"
                    val timeText =
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                    val content = context.getString(R.string.attachment_current_time, timeText)
                    val attachmentInfo =
                        AttachmentInfo(
                            filePath = captureId,
                            fileName = "time.txt",
                            mimeType = "text/plain",
                            fileSize = content.length.toLong(),
                            content = content
                        )

                    val currentList = _attachments.value
                    _attachments.value = currentList + attachmentInfo

                    _toastEvent.emit(context.getString(R.string.attachment_time_added))
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_time_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error capturing current time", e)
                }
            }

    /**
     * 捕获记忆文件夹并作为附件添加到消�?    * @param folderPaths 选中的记忆文件夹路径列表
     */
    suspend fun captureMemoryFolders(folderPaths: List<String>) =
            withContext(Dispatchers.IO) {
                try {
                    if (folderPaths.isEmpty()) {
                        _toastEvent.emit(context.getString(R.string.attachment_no_memory_folder))
                        return@withContext
                    }

                    // 生成唯一ID
                    val captureId = "memory_context_${System.currentTimeMillis()}"

                    // 构建XML格式的记忆上下文
                    val memoryContext = buildMemoryContextXml(folderPaths)

                    // 创建附件信息
                    val attachmentInfo =
                            AttachmentInfo(
                                    filePath = captureId,
                                    fileName = "memory_context.xml",
                                    mimeType = "application/xml",
                                    fileSize = memoryContext.length.toLong(),
                                    content = memoryContext
                            )

                    // 添加到附件列�?                   val currentList = _attachments.value
                    _attachments.value = currentList + attachmentInfo

                    val folderCountText = if (folderPaths.size == 1) {
                        context.getString(R.string.attachment_memory_folder_added, folderPaths[0])
                    } else {
                        context.getString(R.string.attachment_memory_folders_added, folderPaths.size)
                    }
                    _toastEvent.emit(folderCountText)
                } catch (e: Exception) {
                    _toastEvent.emit(context.getString(R.string.attachment_memory_folder_failed, e.message ?: ""))
                    AppLogger.e(TAG, "Error capturing memory folders", e)
                }
            }

    /**
     * 构建记忆上下文XML字符�?    */
    private fun buildMemoryContextXml(folderPaths: List<String>): String {
        val foldersText = folderPaths.joinToString("\n") { "  - ${it}" }
        val examplePath = folderPaths.firstOrNull() ?: "some/folder/path"

        return """
<memory_context>
 <available_folders>
${foldersText}
 </available_folders>
 <instruction>
- **CRITICAL**: To search within the folders listed above, you **MUST** use the `query_memory` tool and provide the `folder_path` parameter.
- The `folder_path` parameter's value **MUST** be one of the paths from the `<available_folders>` list.
- Autonomously decide whether a search is needed and what query to use based on the user's question.
- Example: `<tool name="query_memory"><param name="query">search query</param><param name="folder_path">${examplePath}</param></tool>`
 </instruction>
</memory_context>""".trimIndent()
    }

    /** 从Content URI获取文件的实际路径，不复制文件内�?/
    private fun getFilePathFromUri(uri: Uri): String? {
        try {
            // 尝试直接从URI获取路径
            if (uri.scheme == "file") {
                return uri.path
            }

            // 对于content URI，使用不同的方法尝试获取实际路径
            if (uri.scheme == "content") {
                // 特殊处理: Downloads提供程序URI
                if (uri.authority == "com.android.providers.downloads.documents") {
                    val id = android.provider.DocumentsContract.getDocumentId(uri)

                    // 处理raw:前缀，直接解码路�?                   if (id.startsWith("raw:")) {
                        val decodedPath = java.net.URLDecoder.decode(id.substring(4), "UTF-8")
                        AppLogger.d(TAG, "Downloads document URI resolved to: ${decodedPath}")
                        return decodedPath
                    }

                    // 处理msf:前缀
                    else if (id.startsWith("msf:")) {
                        // MediaStore format.
                        val mediaId = id.substring(4)
                        // We can't know from the URI alone if it's an image, video, or audio file.
                        // So we'll use the generic files table.
                        val contentUri = android.provider.MediaStore.Files.getContentUri("external")
                        val selection = "_id=?"
                        val selectionArgs = arrayOf(mediaId)
                        return getDataColumn(contentUri, selection, selectionArgs)
                    }

                    // 普通ID，使用下载内容URI
                    else {
                        val contentUri = android.content.ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"),
                            id.toLong()
                        )
                        return getDataColumn(contentUri, null, null)
                    }
                }

                // 方法1: 通过DocumentsContract获取路径 (API 19+)
                try {
                    val docId = android.provider.DocumentsContract.getDocumentId(uri)
                    val split = docId.split(":")
                    val type = split[0]

                    // 对于外部存储文件
                    if ("primary".equals(type, ignoreCase = true)) {
                        return "/sdcard/${split[1]}"
                    }

                    // 对于SD�?                   if ("sdcard".equals(type, ignoreCase = true) && split.size > 1) {
                        return "/storage/sdcard1/${split[1]}"
                    }
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Failed to get path through DocumentsContract", e)
                }

                // 方法2: 通过MediaStore查询
                return getDataColumn(uri, null, null)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get actual file path: ${e.message}", e)
        }

        return null
    }

    /** 从URI获取数据，DATA)的，*/
    private fun getDataColumn(uri: Uri, selection: String?, selectionArgs: Array<String>): String? {
        val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA)

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATA)
                    return cursor.getString(columnIndex)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to query URI data column: ${e.message}", e)
        }

        return null
    }

    /** Get file name from content URI */
    private suspend fun getFileNameFromUri(uri: Uri): String =
            withContext(Dispatchers.IO) {
                val contentResolver = context.contentResolver
                var fileName = context.getString(R.string.attachment_unknown_file)

                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val displayNameIndex =
                                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (displayNameIndex != -1) {
                                fileName = cursor.getString(displayNameIndex)
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error getting file name from URI", e)
                }

                return@withContext fileName
            }

    /** Get file size from content URI */
    private suspend fun getFileSizeFromUri(uri: Uri): Long =
            withContext(Dispatchers.IO) {
                val contentResolver = context.contentResolver
                var fileSize = 0L

                try {
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex != -1) {
                                fileSize = cursor.getLong(sizeIndex)
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error getting file size from URI", e)
                }

                return@withContext fileSize
            }

    /** Get MIME type from file path */
    private fun getMimeTypeFromPath(filePath: String): String? {
        val extension = filePath.substringAfterLast('.', "").lowercase()
        if (extension.isBlank()) return null
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "zip" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
    }
}
