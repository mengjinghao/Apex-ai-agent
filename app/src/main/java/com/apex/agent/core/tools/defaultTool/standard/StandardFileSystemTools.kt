package com.apex.agent.core.tools.defaultTool.standard

import android.content.Context
import com.apex.agent.util.AppLogger
import com.apex.agent.core.tools.DirectoryListingData
import com.apex.agent.core.tools.FileContentData
import com.apex.agent.core.tools.FileApplyResultData
import com.apex.agent.core.tools.BinaryFileContentData
import com.apex.agent.core.tools.FileExistsData
import com.apex.agent.core.tools.FileInfoData
import com.apex.agent.core.tools.FileOperationData
import com.apex.agent.core.tools.FilePartContentData
import com.apex.agent.core.tools.FindFilesResultData
import com.apex.agent.core.tools.ToolProgressBus
import com.apex.agent.core.tools.GrepResultData
import com.apex.agent.core.tools.StringResultData
import com.apex.data.model.AITool
import com.apex.data.model.ToolParameter
import com.apex.data.model.ToolResult
import com.apex.agent.core.tools.defaultTool.PathValidator
import org.json.JSONObject

/**
 * Collection of file system operation tools for the AI assistant These tools use Java File APIs for
 * file operations
 */
open class StandardFileSystemTools(context: Context) : FileSystemBaseTools(context), FileSystemAdvancedTools by FileSystemAdvancedTools(context) {
    private val enhancedTools = FileSystemEnhancedTools(context)
    companion object {
        protected const val TAG = "FileSystemTools"
    }

    // 保持原有方法的兼容的
    override suspend fun listFiles(tool: AITool): ToolResult {
        return super.listFiles(tool)
    }

    override suspend fun readFile(tool: AITool): ToolResult {
        return super.readFile(tool)
    }

    override suspend fun writeFile(tool: AITool): ToolResult {
        return super.writeFile(tool)
    }

    override suspend fun appendFile(tool: AITool): ToolResult {
        return super.appendFile(tool)
    }

    override suspend fun fileExists(tool: AITool): ToolResult {
        return super.fileExists(tool)
    }

    override suspend fun fileInfo(tool: AITool): ToolResult {
        return super.fileInfo(tool)
    }

    override suspend fun readFilePart(tool: AITool): ToolResult {
        return super.readFilePart(tool)
    }

    override suspend fun findFiles(tool: AITool): ToolResult {
        return super.findFiles(tool)
    }

    override suspend fun grepCode(tool: AITool): ToolResult {
        return super.grepCode(tool)
    }

    override suspend fun grepContext(tool: AITool): ToolResult {
        return super.grepContext(tool)
    }

    /** Search files with content search */
    open suspend fun searchFiles(tool: AITool): ToolResult {
        return enhancedTools.searchFiles(tool)
    }

    /** Batch file operations */
    open suspend fun batchFileOperation(tool: AITool): ToolResult {
        return enhancedTools.batchFileOperation(tool)
    }

    /** Compress files */
    open suspend fun compressFiles(tool: AITool): ToolResult {
        return enhancedTools.compressFiles(tool)
    }

    /** Extract files */
    open suspend fun extractFiles(tool: AITool): ToolResult {
        return enhancedTools.extractFiles(tool)
    }

    /** Sync directories */
    open suspend fun syncDirectories(tool: AITool): ToolResult {
        return enhancedTools.syncDirectories(tool)
    }

    /** Git operations */
    open suspend fun gitOperation(tool: AITool): ToolResult {
        return enhancedTools.gitOperation(tool)
    }
}
