package com.apex.agent.core.tools.parser

import com.apex.agent.core.tools.SimplifiedUINode
import com.apex.util.AppLogger
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * XML布局解析�?
 * 
 * 负责解析Android UI层次结构的XML，提取节点信息和窗口信息
 */
class XmlLayoutParser {
    
    companion object {
        private const val TAG = "XmlLayoutParser"
    }
    
    /**
     * UI节点数据�?
     */
    data class UINode(
        val className: String?,
        val text: String?,
        val contentDesc: String?,
        val resourceId: String?,
        val bounds: String?,
        val isClickable: Boolean,
        val children: MutableList<UINode> = mutableListOf()
    ) {
        /**
         * 转换为SimplifiedUINode
         */
        fun toSimplifiedNode(): SimplifiedUINode {
            return SimplifiedUINode(
                className = className,
                text = text,
                contentDesc = contentDesc,
                resourceId = resourceId,
                bounds = bounds,
                isClickable = isClickable,
                children = children.map { it.toSimplifiedNode() }
            )
        }
    }
    
    /**
     * 节点信息（用于查找）
     */
    data class NodeInfo(
        val bounds: String?,
        val text: String?,
        val className: String?,
        val resourceId: String?,
        val contentDesc: String?
    )
    
    /**
     * 解析XML布局为节点树
     * 
     * @param xml XML字符�?
     * @return 简化的UI节点�?
     */
    fun parse(xml: String): SimplifiedUINode {
        try {
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = false
            }
            val parser = factory.newPullParser().apply {
                setInput(StringReader(xml))
            }
            
            val nodeStack = mutableListOf<UINode>()
            var rootNode: UINode? = null
            
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "node") {
                            val newNode = createNode(parser)
                            if (rootNode == null) {
                                rootNode = newNode
                                nodeStack.add(newNode)
                            } else {
                                nodeStack.lastOrNull()?.children?.add(newNode)
                                nodeStack.add(newNode)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "node") {
                            nodeStack.removeLastOrNull()
                        }
                    }
                }
                parser.next()
            }
            
            return rootNode?.toSimplifiedNode() ?: SimplifiedUINode(
                className = null,
                text = null,
                contentDesc = null,
                resourceId = null,
                bounds = null,
                isClickable = false,
                children = emptyList()
            )
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析XML布局失败", e)
            return SimplifiedUINode(
                className = null,
                text = null,
                contentDesc = null,
                resourceId = null,
                bounds = null,
                isClickable = false,
                children = emptyList()
            )
        }
    }
    
    /**
     * 在XML中查找匹配的节点
     * 
     * @param xml XML字符�?
     * @param predicate 匹配条件
     * @return 匹配的节点列�?
     */
    fun findNodes(xml: String, predicate: (XmlPullParser) -> Boolean): List<NodeInfo> {
        val matchedNodes = mutableListOf<NodeInfo>()
        
        try {
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = false
            }
            val parser = factory.newPullParser().apply {
                setInput(StringReader(xml))
            }
            
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                    if (predicate(parser)) {
                        matchedNodes.add(
                            NodeInfo(
                                bounds = parser.getAttributeValue(null, "bounds"),
                                text = parser.getAttributeValue(null, "text"),
                                className = parser.getAttributeValue(null, "class"),
                                resourceId = parser.getAttributeValue(null, "resource-id"),
                                contentDesc = parser.getAttributeValue(null, "content-desc")
                            )
                        )
                    }
                }
                parser.next()
            }
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "查找节点失败", e)
        }
        
        return matchedNodes
    }
    
    /**
     * 从XML中提取窗口信�?
     * 
     * @param xml XML字符�?
     * @return Pair<包名, Activity�?
     */
    fun extractWindowInfo(xml: String): Pair<String?, String?> {
        try {
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = false
            }
            val parser = factory.newPullParser().apply {
                setInput(StringReader(xml))
            }
            
            var packageName: String? = null
            var activityName: String? = null
            
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    // 尝试从hierarchy标签获取包名
                    if (parser.name == "hierarchy") {
                        packageName = parser.getAttributeValue(null, "rotation")?.let { 
                            // rotation属性通常不包含包名，需要从其他位置获取
                            null 
                        }
                    }
                    
                    // 从第一个node标签获取包名
                    if (parser.name == "node" && packageName == null) {
                        packageName = parser.getAttributeValue(null, "package")
                    }
                }
                parser.next()
            }
            
            return Pair(packageName, activityName)
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "提取窗口信息失败", e)
            return Pair(null, null)
        }
    }
    
    /**
     * 解析边界坐标字符�?
     * 
     * @param boundsString 边界字符串，格式: "[left,top][right,bottom]"
     * @return Rect对象
     */
    fun parseBounds(boundsString: String): android.graphics.Rect {
        val rect = android.graphics.Rect()
        
        try {
            // 解析 "[left,top][right,bottom]" 格式
            val cleaned = boundsString.replace("[", "").replace("]", ",")
            val parts = cleaned.split(",")
            
            if (parts.size >= 4) {
                rect.left = parts[0].trim().toInt()
                rect.top = parts[1].trim().toInt()
                rect.right = parts[2].trim().toInt()
                rect.bottom = parts[3].trim().toInt()
            }
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析边界坐标失败: ${boundsString}", e)
        }
        
        return rect
    }
    
    /**
     * 计算边界中心�?
     * 
     * @param boundsString 边界字符�?
     * @return Pair<x, y> 中心点坐�?
     */
    fun getBoundsCenter(boundsString: String): Pair<Int, Int>? {
        val rect = parseBounds(boundsString)
        
        if (rect.isEmpty) {
            return null
        }
        
        return Pair(rect.centerX(), rect.centerY())
    }
    
    /**
     * 检查边界是否在屏幕范围�?
     * 
     * @param boundsString 边界字符�?
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 是否在屏幕范围内
     */
    fun isBoundsInScreen(boundsString: String, screenWidth: Int, screenHeight: Int): Boolean {
        val rect = parseBounds(boundsString)
        
        if (rect.isEmpty) {
            return false
        }
        
        return rect.left >= 0 && rect.top >= 0 &&
               rect.right <= screenWidth && rect.bottom <= screenHeight
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 从XmlPullParser创建UINode
     * 
     * @param parser XML解析�?
     * @return UINode对象
     */
    private fun createNode(parser: XmlPullParser): UINode {
        // 解析关键属�?
        val className = parser.getAttributeValue(null, "class")?.substringAfterLast('.')
        val text = parser.getAttributeValue(null, "text")?.replace("&#10;", "\n")
        val contentDesc = parser.getAttributeValue(null, "content-desc")
        val resourceId = parser.getAttributeValue(null, "resource-id")
        val bounds = parser.getAttributeValue(null, "bounds")
        val isClickable = parser.getAttributeValue(null, "clickable") == "true"
        
        return UINode(
            className = className,
            text = text,
            contentDesc = contentDesc,
            resourceId = resourceId,
            bounds = bounds,
            isClickable = isClickable
        )
    }
}
