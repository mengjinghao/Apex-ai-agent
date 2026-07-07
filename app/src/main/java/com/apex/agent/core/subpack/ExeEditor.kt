package com.apex.core.subpack

import android.content.Context
import com.apex.agent.R
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.apex.util.AssetCopyUtils
import java.io.File
import java.io.InputStream

/** EXE编辑�? 提供链式调用API 支持EXE图标更换功能 */
class ExeEditor
private constructor(
        private val context: Context,
        private val exeFile: File,
        private val exeIconChanger: ExeIconChanger
) {
    companion object {
        private const val TAG = "ExeEditor"

        /**
         * 从资产文件创建EXE编辑�?        * @param context 上下�?        * @param assetPath 资产路径
         * @return EXE编辑器实�?        */
        @JvmStatic
        fun fromAsset(context: Context, assetPath: String): ExeEditor {
            val fileName = assetPath.substringAfterLast('/')
            val outputFile = File(context.cacheDir, "exe_editor_${fileName}")
            val exeFile = AssetCopyUtils.copyAssetToFile(context, assetPath, outputFile, overwrite = true)
            val exeIconChanger = ExeIconChanger(context)
            return ExeEditor(context, exeFile, exeIconChanger)
        }

        /**
         * 从文件创建EXE编辑�?        * @param context 上下�?        * @param exeFile EXE文件
         * @return EXE编辑器实�?        */
        @JvmStatic
        fun fromFile(context: Context, exeFile: File): ExeEditor {
            val exeIconChanger = ExeIconChanger(context)
            return ExeEditor(context, exeFile, exeIconChanger)
        }

        /**
         * 从文件路径创建EXE编辑�?        * @param context 上下�?        * @param exeFilePath EXE文件路径
         * @return EXE编辑器实�?        */
        @JvmStatic
        fun fromPath(context: Context, exeFilePath: String): ExeEditor {
            val exeFile = File(exeFilePath)
            return fromFile(context, exeFile)
        }

        /**
         * 复制资产文件到缓存目�?        * @param context 上下�?        * @param assetPath 资产路径
         * @return 缓存文件
         */
    }

    private var newIconBitmap: Bitmap? = null
    private var outputFile: File? = null

    /**
     * 更改图标（从位图�?    * @param iconBitmap 图标位图
     * @return 当前EXE编辑器实�?    */
    fun changeIcon(iconBitmap: Bitmap): ExeEditor {
        this.newIconBitmap = iconBitmap
        return this
    }

    /**
     * 更改图标（从输入流）
     * @param iconInputStream 图标输入�?    * @return 当前EXE编辑器实�?    */
    fun changeIcon(iconInputStream: InputStream): ExeEditor {
        val bitmap = BitmapFactory.decodeStream(iconInputStream)
        return changeIcon(bitmap)
    }

    /**
     * 更改图标（从资产文件�?    * @param iconAssetPath 图标资产路径
     * @return 当前EXE编辑器实�?    */
    fun changeIconFromAsset(iconAssetPath: String): ExeEditor {
        context.assets.open(iconAssetPath).use { input ->
            return changeIcon(input)
        }
    }

    /**
     * 设置输出文件
     * @param outputFile 输出文件
     * @return 当前EXE编辑器实�?    */
    fun setOutput(outputFile: File): ExeEditor {
        this.outputFile = outputFile
        return this
    }

    /**
     * 设置输出文件路径
     * @param outputPath 输出文件路径
     * @return 当前EXE编辑器实�?    */
    fun setOutput(outputPath: String): ExeEditor {
        return setOutput(File(outputPath))
    }

    /**
     * 处理并保存修改后的EXE文件
     * @return 修改后的EXE文件
     */
    fun process(): File {
        if (newIconBitmap == null) {
            throw IllegalStateException(context.getString(R.string.exe_editor_change_icon_first))
        }

        // 确定输出文件
        val outputExeFile =
                if (outputFile != null) {
                    outputFile!!
                } else {
                    File(context.cacheDir, "modified_${exeFile.name}")
                }

        // 更换图标
        if (!exeIconChanger.changeIcon(exeFile, newIconBitmap!!, outputExeFile)) {
            throw RuntimeException(context.getString(R.string.exe_editor_change_icon_failed))
        }

        return outputExeFile
    }

    /** 清理临时文件 */
    fun cleanup() {
        exeIconChanger.cleanup()
        newIconBitmap?.recycle()
        newIconBitmap = null
    }
}
