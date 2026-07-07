package com.apex.util

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 反射工具类，提供字段访问、方法调用、类信息查询等 Java 反射操作
 */
object ReflectionUtils {

    /**
     * 获取对象的指定字段值（支持私有和继承字段）
     *
     * @param obj 目标对象
     * @param fieldName 字段名
     * @return 字段值，如果未找到返回 null
     */
    fun getFieldValue(obj: Any, fieldName: String): Any? {
        var currentClass: Class<*>? = obj.javaClass
        while (currentClass != null) {
            try {
                val field = currentClass.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            } catch (e: NoSuchFieldException) {
                currentClass = currentClass.superclass
            }
        }
        return null
    }

    /**
     * 设置对象的指定字段值（支持私有和继承字段）
     *
     * @param obj 目标对象
     * @param fieldName 字段名
     * @param value 要设置的值
     */
    fun setFieldValue(obj: Any, fieldName: String, value: Any) {
        var currentClass: Class<*>? = obj.javaClass
        while (currentClass != null) {
            try {
                val field = currentClass.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(obj, value)
                return
            } catch (e: NoSuchFieldException) {
                currentClass = currentClass.superclass
            }
        }
    }

    /**
     * 调用对象的指定方法（支持私有和继承方法）
     *
     * @param obj 目标对象
     * @param methodName 方法名
     * @param args 方法参数
     * @return 方法返回值（如果方法返回 void 则返回 null）
     */
    fun invokeMethod(obj: Any, methodName: String, vararg args: Any?): Any? {
        val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        var currentClass: Class<*>? = obj.javaClass
        while (currentClass != null) {
            try {
                val method = currentClass.getDeclaredMethod(methodName, *paramTypes)
                method.isAccessible = true
                return method.invoke(obj, *args)
            } catch (e: NoSuchMethodException) {
                currentClass = currentClass.superclass
            }
        }
        return null
    }

    /**
     * 获取类的静态字段值
     *
     * @param className 完整类名
     * @param fieldName 字段名
     * @return 静态字段值，如果未找到返回 null
     */
    fun getStaticFieldValue(className: String, fieldName: String): Any? {
        return try {
            val clazz = Class.forName(className)
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            val modifiers = Field::class.java.getDeclaredField("modifiers")
            modifiers.isAccessible = true
            modifiers.setInt(field, field.modifiers and Modifier.FINAL.inv())
            field.get(null)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 设置类的静态字段值
     *
     * @param className 完整类名
     * @param fieldName 字段名
     * @param value 要设置的值
     */
    fun setStaticFieldValue(className: String, fieldName: String, value: Any) {
        try {
            val clazz = Class.forName(className)
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            val modifiers = Field::class.java.getDeclaredField("modifiers")
            modifiers.isAccessible = true
            modifiers.setInt(field, field.modifiers and Modifier.FINAL.inv())
            field.set(null, value)
        } catch (ignored: Exception) {
        }
    }

    /**
     * 调用类的静态方法
     *
     * @param className 完整类名
     * @param methodName 方法名
     * @param args 方法参数
     * @return 方法返回值（如果方法返回 void 则返回 null）
     */
    fun invokeStaticMethod(className: String, methodName: String, vararg args: Any?): Any? {
        val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        return try {
            val clazz = Class.forName(className)
            val method = clazz.getDeclaredMethod(methodName, *paramTypes)
            method.isAccessible = true
            method.invoke(null, *args)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取类中声明的所有字段名（包含私有字段，不包含继承字段）
     *
     * @param className 完整类名
     * @return 字段名列表
     */
    fun getAllFields(className: String): List<String> {
        return try {
            val clazz = Class.forName(className)
            clazz.declaredFields.map { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取类中声明的所有方法名（包含私有方法，不包含继承方法）
     *
     * @param className 完整类名
     * @return 方法名列表
     */
    fun getAllMethods(className: String): List<String> {
        return try {
            val clazz = Class.forName(className)
            clazz.declaredMethods.map { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 检查类是否包含指定字段
     *
     * @param className 完整类名
     * @param fieldName 字段名
     * @return 如果存在该字段返回 true
     */
    fun hasField(className: String, fieldName: String): Boolean {
        return try {
            val clazz = Class.forName(className)
            var currentClass: Class<*>? = clazz
            while (currentClass != null) {
                try {
                    currentClass.getDeclaredField(fieldName)
                    return true
                } catch (e: NoSuchFieldException) {
                    currentClass = currentClass.superclass
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查类是否包含指定方法
     *
     * @param className 完整类名
     * @param methodName 方法名
     * @return 如果存在该方法返回 true
     */
    fun hasMethod(className: String, methodName: String): Boolean {
        return try {
            val clazz = Class.forName(className)
            var currentClass: Class<*>? = clazz
            while (currentClass != null) {
                try {
                    currentClass.getDeclaredMethod(methodName)
                    return true
                } catch (e: NoSuchMethodException) {
                    currentClass = currentClass.superclass
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通过反射创建类的实例（调用匹配参数的构造方法）
     *
     * @param className 完整类名
     * @param args 构造方法参数
     * @return 新创建的实例，失败返回 null
     */
    fun newInstance(className: String, vararg args: Any?): Any? {
        val paramTypes = args.map { it?.javaClass ?: Any::class.java }.toTypedArray()
        return try {
            val clazz = Class.forName(className)
            val constructor = clazz.getDeclaredConstructor(*paramTypes)
            constructor.isAccessible = true
            constructor.newInstance(*args)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取类的父类名
     *
     * @param className 完整类名
     * @return 父类名，如果没有父类返回 null
     */
    fun getSuperclass(className: String): String? {
        return try {
            val clazz = Class.forName(className)
            clazz.superclass?.name
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取类实现的接口名列表
     *
     * @param className 完整类名
     * @return 接口名数组
     */
    fun getInterfaces(className: String): Array<String> {
        return try {
            val clazz = Class.forName(className)
            clazz.interfaces.map { it.name }.toTypedArray()
        } catch (e: Exception) {
            emptyArray()
        }
    }
}
