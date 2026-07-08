package com.apex.sdk.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * BridgeParcel 工厂工具 — 为 AIDL 生成的 BridgeParcel 提供便捷构造方法。
 *
 * AIDL 自动生成的 BridgeParcel 类只有 getter/setter，
 * 这里提供 success/failure/request 等工厂方法。
 */
object BridgeParcelExt {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun success(method: String, traceId: String, resultBytes: ByteArray): BridgeParcel {
        val parcel = BridgeParcel()
        parcel.method = method
        parcel.traceId = traceId
        parcel.result = resultBytes
        parcel.errorCode = 0
        return parcel
    }

    fun successJson(method: String, traceId: String, jsonElement: JsonElement): BridgeParcel =
        success(method, traceId, json.encodeToString(JsonElement.serializer(), jsonElement).toByteArray(Charsets.UTF_8))

    fun failure(method: String, traceId: String, errorCode: Int, message: String): BridgeParcel {
        val parcel = BridgeParcel()
        parcel.method = method
        parcel.traceId = traceId
        parcel.errorCode = errorCode
        parcel.errorMessage = message
        return parcel
    }

    fun request(method: String, traceId: String, argsBytes: ByteArray): BridgeParcel {
        val parcel = BridgeParcel()
        parcel.method = method
        parcel.traceId = traceId
        parcel.args = argsBytes
        return parcel
    }

    fun requestJson(method: String, traceId: String, jsonElement: JsonElement): BridgeParcel =
        request(method, traceId, json.encodeToString(JsonElement.serializer(), jsonElement).toByteArray(Charsets.UTF_8))
}
