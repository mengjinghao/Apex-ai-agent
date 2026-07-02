package com.apex.sdk.bridge

import android.os.Parcel
import android.os.Parcelable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 跨 APK 调用的通用载体 — Kotlin 侧实现。
 *
 * AIDL 自动生成的 BuildConfig 类只有 getter/setter，业务代码应使用本扩展函数。
 */
data class BridgeParcel(
    var method: String = "",
    var traceId: String = "",
    var args: ByteArray = ByteArray(0),
    var result: ByteArray = ByteArray(0),
    var errorCode: Int = 0,
    var errorMessage: String? = null
) : Parcelable {

    constructor(source: Parcel) : this(
        method = source.readString().orEmpty(),
        traceId = source.readString().orEmpty(),
        args = source.createByteArray() ?: ByteArray(0),
        result = source.createByteArray() ?: ByteArray(0),
        errorCode = source.readInt(),
        errorMessage = source.readString()
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(method)
        dest.writeString(traceId)
        dest.writeByteArray(args)
        dest.writeByteArray(result)
        dest.writeInt(errorCode)
        dest.writeString(errorMessage)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BridgeParcel) return false
        return method == other.method &&
            traceId == other.traceId &&
            args.contentEquals(other.args) &&
            result.contentEquals(other.result) &&
            errorCode == other.errorCode &&
            errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var r = method.hashCode()
        r = 31 * r + traceId.hashCode()
        r = 31 * r + args.contentHashCode()
        r = 31 * r + result.contentHashCode()
        r = 31 * r + errorCode
        r = 31 * r + (errorMessage?.hashCode() ?: 0)
        return r
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<BridgeParcel> {
            override fun createFromParcel(source: Parcel) = BridgeParcel(source)
            override fun newArray(size: Int) = arrayOfNulls<BridgeParcel>(size)
        }

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun success(method: String, traceId: String, resultBytes: ByteArray): BridgeParcel =
            BridgeParcel(
                method = method,
                traceId = traceId,
                result = resultBytes,
                errorCode = 0
            )

        fun successJson(method: String, traceId: String, jsonElement: JsonElement): BridgeParcel =
            success(method, traceId, json.encodeToString(JsonElement.serializer(), jsonElement).toByteArray(Charsets.UTF_8))

        fun failure(method: String, traceId: String, errorCode: Int, message: String): BridgeParcel =
            BridgeParcel(
                method = method,
                traceId = traceId,
                errorCode = errorCode,
                errorMessage = message
            )

        fun request(method: String, traceId: String, argsBytes: ByteArray): BridgeParcel =
            BridgeParcel(
                method = method,
                traceId = traceId,
                args = argsBytes
            )

        fun requestJson(method: String, traceId: String, jsonElement: JsonElement): BridgeParcel =
            request(method, traceId, json.encodeToString(JsonElement.serializer(), jsonElement).toByteArray(Charsets.UTF_8))
    }
}
