package com.apex.agent.core.tools.selector

/**
 * UI元素选择?
 * 
 * 用于精确定位UI元素，支持多种选择条件
 */
data class ElementSelector(
    /** 资源ID（完整ID或后缀?*/
    val resourceId: String? = null,
    
    /** 类名（完整类名或简单类名） */
    val className: String? = null,
    
    /** 内容描述 */
    val contentDesc: String? = null,
    
    /** 文本内容 */
    val text: String? = null,
    
    /** 边界坐标 "[left,top][right,bottom]" */
    val bounds: String? = null,
    
    /** 匹配索引（当有多个匹配时?*/
    val index: Int = 0,
    
    /** 是否必须可点?*/
    val clickable: Boolean? = null,
    
    /** 是否必须可见 */
    val visible: Boolean? = null
) {
    companion object {
        /**
         * 通过资源ID创建选择?
         */
        fun byResourceId(resourceId: String, index: Int = 0): ElementSelector {
            return ElementSelector(resourceId = resourceId, index = index)
        }
        
        /**
         * 通过类名创建选择?
         */
        fun byClassName(className: String, index: Int = 0): ElementSelector {
            return ElementSelector(className = className, index = index)
        }
        
        /**
         * 通过文本创建选择?
         */
        fun byText(text: String, exact: Boolean = true, index: Int = 0): ElementSelector {
            return ElementSelector(text = text, index = index)
        }
        
        /**
         * 通过内容描述创建选择?
         */
        fun byContentDesc(contentDesc: String, index: Int = 0): ElementSelector {
            return ElementSelector(contentDesc = contentDesc, index = index)
        }
        
        /**
         * 通过边界坐标创建选择?
         */
        fun byBounds(bounds: String): ElementSelector {
            return ElementSelector(bounds = bounds)
        }
        
        /**
         * 组合选择器（AND逻辑?
         */
        fun combine(
            resourceId: String? = null,
            className: String? = null,
            text: String? = null,
            contentDesc: String? = null,
            index: Int = 0
        ): ElementSelector {
            return ElementSelector(
                resourceId = resourceId,
                className = className,
                text = text,
                contentDesc = contentDesc,
                index = index
            )
        }
    }
    
    /**
     * 验证选择器是否有?
     * 
     * @return 至少有一个非空的选择条件
     */
    fun isValid(): Boolean {
        return resourceId != null || 
               className != null || 
               contentDesc != null || 
               text != null || 
               bounds != null
    }
    
    /**
     * 获取选择器的描述字符串（用于日志?
     */
    fun getDescription(): String {
        val conditions = mutableListOf<String>()
        
        resourceId?.let { conditions.add("resourceId=${it}") }
        className?.let { conditions.add("className=${it}") }
        contentDesc?.let { conditions.add("contentDesc=${it}") }
        text?.let { conditions.add("text=${it}") }
        bounds?.let { conditions.add("bounds=${it}") }
        clickable?.let { conditions.add("clickable=${it}") }
        visible?.let { conditions.add("visible=${it}") }
        
        if (index > 0) {
            conditions.add("index=${index}")
        }
        
        return conditions.joinToString(", ")
    }
    
    /**
     * 检查另一个选择器是否与当前选择器兼?
     * （用于合并多个选择条件?
     */
    fun isCompatibleWith(other: ElementSelector): Boolean {
        // 如果两个选择器都指定了相同的字段但值不同，则不兼容
        if (resourceId != null && other.resourceId != null && resourceId != other.resourceId) {
            return false
        }
        if (className != null && other.className != null && className != other.className) {
            return false
        }
        if (contentDesc != null && other.contentDesc != null && contentDesc != other.contentDesc) {
            return false
        }
        if (text != null && other.text != null && text != other.text) {
            return false
        }
        if (bounds != null && other.bounds != null && bounds != other.bounds) {
            return false
        }
        
        return true
    }
    
    /**
     * 合并两个选择?
     * 
     * @param other 另一个选择?
     * @return 合并后的选择器，如果不兼容返回null
     */
    fun merge(other: ElementSelector): ElementSelector? {
        if (!isCompatibleWith(other)) {
            return null
        }
        
        return ElementSelector(
            resourceId = resourceId ?: other.resourceId,
            className = className ?: other.className,
            contentDesc = contentDesc ?: other.contentDesc,
            text = text ?: other.text,
            bounds = bounds ?: other.bounds,
            index = if (index != 0) index else other.index,
            clickable = clickable ?: other.clickable,
            visible = visible ?: other.visible
        )
    }
}
