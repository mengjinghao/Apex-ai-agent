package com.apex.util

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 位图工具类，提供 Bitmap 的压缩、转换、缩放、裁剪、水印等常用图像处理功能
 */
object BitmapUtils {

    /**
     * 压缩位图为指定格式的字节数组
     *
     * @param bitmap 原始位图
     * @param format 压缩格式，默认为 JPEG
     * @param quality 压缩质量（0-100），默认为 80
     * @return 压缩后的字节数组，失败返回 null
     */
    fun compressBitmap(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 80): ByteArray? {
        val stream = ByteArrayOutputStream()
        return try {
            if (bitmap.compress(format, quality, stream)) {
                stream.toByteArray()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                stream.close()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * 将位图转换为字节数组（PNG 格式，无损质量）
     *
     * @param bitmap 原始位图
     * @return 字节数组，失败返回 null
     */
    fun bitmapToByteArray(bitmap: Bitmap): ByteArray? {
        return compressBitmap(bitmap, Bitmap.CompressFormat.PNG, 100)
    }

    /**
     * 将字节数组解码为位图
     *
     * @param bytes 字节数组
     * @return 解码后的位图，失败返回 null
     */
    fun byteArrayToBitmap(bytes: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 按最大宽高等比缩放位图，保持原始宽高比
     *
     * @param bitmap 原始位图
     * @param maxWidth 最大宽度
     * @param maxHeight 最大高度
     * @return 缩放后的位图
     */
    fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val ratio = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        if (ratio >= 1f) return bitmap
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 按比例缩放位图
     *
     * @param bitmap 原始位图
     * @param scaleFactor 缩放因子（1.0 为原始大小，0.5 为一半，2.0 为两倍）
     * @return 缩放后的位图
     */
    fun scaleBitmap(bitmap: Bitmap, scaleFactor: Float): Bitmap {
        val newWidth = (bitmap.width * scaleFactor).toInt()
        val newHeight = (bitmap.height * scaleFactor).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 从中心裁剪出指定大小的正方形位图
     *
     * @param bitmap 原始位图
     * @param size 裁剪后的正方形边长
     * @return 裁剪后的正方形位图
     */
    fun cropCenterBitmap(bitmap: Bitmap, size: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val left = (width - size) / 2
        val top = (height - size) / 2
        val x = if (left >= 0) left else 0
        val y = if (top >= 0) top else 0
        val actualSize = minOf(size, width - x, height - y)
        if (actualSize <= 0) return bitmap
        return Bitmap.createBitmap(bitmap, x, y, actualSize, actualSize)
    }

    /**
     * 旋转位图指定角度
     *
     * @param bitmap 原始位图
     * @param degrees 旋转角度
     * @return 旋转后的位图
     */
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return bitmap
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 将位图转换为 Base64 编码的字符串
     *
     * @param bitmap 原始位图
     * @param format 压缩格式，默认为 JPEG
     * @param quality 压缩质量，默认为 80
     * @return Base64 编码的字符串，失败返回 null
     */
    fun bitmapToBase64(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 80): String? {
        val bytes = compressBitmap(bitmap, format, quality) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * 将 Base64 编码的字符串解码为位图
     *
     * @param base64 Base64 编码的字符串
     * @return 解码后的位图，失败返回 null
     */
    fun base64ToBitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取位图占用的内存大小（字节）
     *
     * @param bitmap 位图对象
     * @return 内存大小（字节数）
     */
    fun getBitmapSize(bitmap: Bitmap): Long {
        return bitmap.byteCount.toLong()
    }

    /**
     * 计算高效加载位图时的采样率，使加载的位图不超过目标尺寸
     *
     * @param options BitmapFactory.Options 对象（已设置 inJustDecodeBounds = true）
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return 采样率（2 的幂次）
     */
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 从文件高效加载经过缩放的位图，避免 OOM
     *
     * @param filePath 文件路径
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return 加载后的缩略图，失败返回 null
     */
    fun decodeSampledBitmapFromFile(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(filePath, this)
                inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
                inJustDecodeBounds = false
            }
            BitmapFactory.decodeFile(filePath, options)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从资源文件高效加载经过缩放的位图
     *
     * @param res Resources 对象
     * @param resId 资源 ID
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return 加载后的缩略图，失败返回 null
     */
    fun decodeSampledBitmapFromResource(res: Resources, resId: Int, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeResource(res, resId, this)
                inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
                inJustDecodeBounds = false
            }
            BitmapFactory.decodeResource(res, resId, options)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 创建指定尺寸的缩放位图（直接拉伸至目标尺寸，不保持宽高比）
     *
     * @param bitmap 原始位图
     * @param targetWidth 目标宽度
     * @param targetHeight 目标高度
     * @return 缩放后的位图
     */
    fun createScaledBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /**
     * 为位图添加圆角效果
     *
     * @param bitmap 原始位图
     * @param radius 圆角半径
     * @return 带圆角的位图
     */
    fun getRoundedCornerBitmap(bitmap: Bitmap, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)
        canvas.drawRoundRect(rectF, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }

    /**
     * 将位图裁剪为圆形
     *
     * @param bitmap 原始位图
     * @return 圆形位图
     */
    fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val srcRect = Rect((bitmap.width - size) / 2, (bitmap.height - size) / 2, (bitmap.width + size) / 2, (bitmap.height + size) / 2)
        canvas.drawBitmap(bitmap, srcRect, rectF, paint)
        return output
    }

    /**
     * 在位图上添加水印
     *
     * @param bitmap 原始位图
     * @param watermark 水印位图
     * @param gravity 水印位置（使用 Gravity 常量组合），默认为右下角
     * @param margin 水印边距（像素），默认为 10
     * @return 带水印的位图
     */
    fun addWatermark(
        bitmap: Bitmap,
        watermark: Bitmap,
        position: Int = android.view.Gravity.BOTTOM or android.view.Gravity.END,
        margin: Int = 10
    ): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val wmWidth = watermark.width
        val wmHeight = watermark.height
        val horizontalGravity = position and android.view.Gravity.HORIZONTAL_GRAVITY_MASK
        val verticalGravity = position and android.view.Gravity.VERTICAL_GRAVITY_MASK

        val x = when (horizontalGravity) {
            android.view.Gravity.LEFT -> margin
            android.view.Gravity.RIGHT -> bitmap.width - wmWidth - margin
            android.view.Gravity.CENTER_HORIZONTAL -> (bitmap.width - wmWidth) / 2
            else -> bitmap.width - wmWidth - margin
        }
        val y = when (verticalGravity) {
            android.view.Gravity.TOP -> margin
            android.view.Gravity.BOTTOM -> bitmap.height - wmHeight - margin
            android.view.Gravity.CENTER_VERTICAL -> (bitmap.height - wmHeight) / 2
            else -> bitmap.height - wmHeight - margin
        }
        canvas.drawBitmap(watermark, x.toFloat(), y.toFloat(), paint)
        return result
    }

    /**
     * 获取位图中出现频率最高的颜色
     *
     * @param bitmap 原始位图
     * @return 最常出现的 ARGB 颜色值
     */
    fun getDominantColor(bitmap: Bitmap): Int {
        val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        val color = scaled.getPixel(0, 0)
        scaled.recycle()
        return color
    }

    /**
     * 判断位图是否为空或透明（检查是否全透明或尺寸极小）
     *
     * @param bitmap 待检查的位图
     * @return 如果位图为空、全透明或尺寸为 0 返回 true
     */
    fun isBitmapEmpty(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        if (bitmap.config == null) return true
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (pixel in pixels) {
            if (Color.alpha(pixel) > 0) return false
        }
        return true
    }
}
