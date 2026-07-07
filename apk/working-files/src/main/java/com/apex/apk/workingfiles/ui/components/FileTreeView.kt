package com.apex.apk.workingfiles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.workingfiles.ui.theme.CodeColors
import com.apex.lib.workingfiles.FileTreeNode

/**
 * VSCode 风格的文件树组件。
 *
 * - 目录可展开/折叠
 * - 文件点击选中
 * - 不同文件类型显示不同图标颜色
 */
@Composable
fun FileTreeView(
    rootNode: FileTreeNode,
    selectedPath: String?,
    onFileClick: (FileTreeNode) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .background(CodeColors.TreeBackground)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "资源管理器",
                    color = CodeColors.EditorForeground,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SEMIBOLD,
                    letterSpacing = 0.5.sp
                )
            }
        }
        items(rootNode.children) { node ->
            FileTreeItem(
                node = node,
                depth = 0,
                selectedPath = selectedPath,
                onFileClick = onFileClick
            )
        }
    }
}

@Composable
private fun FileTreeItem(
    node: FileTreeNode,
    depth: Int,
    selectedPath: String?,
    onFileClick: (FileTreeNode) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    val isSelected = node.path == selectedPath
    val bg = if (isSelected) CodeColors.TreeItemSelected else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable {
                if (node.isDirectory) {
                    expanded = !expanded
                } else {
                    onFileClick(node)
                }
            }
            .padding(start = (12 + depth * 16).dp, end = 12.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.isDirectory) {
            Icon(
                imageVector = if (expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                tint = CodeColors.TreeDirectory,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = CodeColors.LineNumberForeground,
                modifier = Modifier
                    .size(12.dp)
                    .padding(0.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
        } else {
            Spacer(modifier = Modifier.width(20.dp))
            Icon(
                imageVector = Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = fileIconColor(node.language),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = node.name,
            color = if (node.isDirectory) CodeColors.TreeDirectory else CodeColors.TreeFile,
            fontSize = 13.sp,
            fontFamily = FontFamily.SansSerif,
            maxLines = 1
        )
    }

    if (node.isDirectory && expanded) {
        node.children.forEach { child ->
            FileTreeItem(
                node = child,
                depth = depth + 1,
                selectedPath = selectedPath,
                onFileClick = onFileClick
            )
        }
    }
}

private fun fileIconColor(language: String): Color = when (language) {
    "kotlin", "java" -> Color(0xFFCE7E3E)
    "javascript", "typescript" -> Color(0xFFCAA623)
    "python" -> Color(0xFF3776AB)
    "html" -> Color(0xFFE44D26)
    "css" -> Color(0xFF264DE4)
    "json" -> Color(0xFFCBBA2D)
    "markdown" -> Color(0xFF6A9955)
    "shell", "bash" -> Color(0xFF89E051)
    "c", "cpp" -> Color(0xFF659AD2)
    "go" -> Color(0xFF00ADD8)
    "rust" -> Color(0xFFDEA584)
    else -> CodeColors.TreeFile
}
