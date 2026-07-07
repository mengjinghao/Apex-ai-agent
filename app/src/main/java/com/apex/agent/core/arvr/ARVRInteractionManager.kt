package com.apex.agent.core.arvr

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ARVRInteractionManager(private val context: Context) {

    private val TAG = "ARVRManager"

    enum class SessionType {
        AR,
        VR,
        MIXED_REALITY
    }

    enum class InteractionMode {
        GESTURE,
        VOICE,
        EYE_TRACKING,
        CONTROLLER,
        TOUCH,
        GRAVITY
    }

    enum class SpatialType {
        SURFACE,
        MARKER,
        ANCHOR,
        WORLD_MAP,
        GEOLOCATION
    }

    data class ARVRSession(
        val id: String,
        val type: SessionType,
        val mode: InteractionMode,
        val startTime: Long,
        val endTime: Long? = null,
        val isActive: Boolean = false,
        val sceneObjects: List<SceneObject> = emptyList(),
        val interactions: List<Interaction> = emptyList(),
        val spatialAnchors: List<SpatialAnchor> = emptyList()
    )

    data class SceneObject(
        val id: String,
        val name: String,
        val type: ObjectType,
        val position: Vector3,
        val rotation: Quaternion,
        val scale: Vector3,
        val properties: Map<String, Any>,
        val isVisible: Boolean = true,
        val isInteractive: Boolean = true
    )

    enum class ObjectType {
        MESH,
        SPHERE,
        CUBE,
        CYLINDER,
        PLANE,
        TEXT,
        IMAGE,
        VIDEO,
        PARTICLE_SYSTEM,
        LIGHT,
        CAMERA,
        CUSTOM_MODEL
    }

    data class Vector3(
        val x: Float,
        val y: Float,
        val z: Float
    ) {
        companion object {
            val zero = Vector3(0f, 0f, 0f)
            val one = Vector3(1f, 1f, 1f)
        }
    }

    data class Quaternion(
        val x: Float,
        val y: Float,
        val z: Float,
        val w: Float
    ) {
        companion object {
            val identity = Quaternion(0f, 0f, 0f, 1f)
        }
    }

    data class Interaction(
        val id: String,
        val type: InteractionType,
        val timestamp: Long,
        val targetObjectId: String?,
        val source: InteractionSource,
        val parameters: Map<String, Any>,
        val success: Boolean
    )

    enum class InteractionType {
        SELECT,
        DRAG,
        ROTATE,
        SCALE,
        TAP,
        DOUBLE_TAP,
        LONG_PRESS,
        SWIPE,
        PINCH,
        SPREAD,
        VOICE_COMMAND,
        GAZE,
        BUTTON_PRESS,
        GESTURE_RECOGNIZED
    }

    enum class InteractionSource {
        USER,
        SYSTEM,
        AI,
        ANIMATION
    }

    data class SpatialAnchor(
        val id: String,
        val name: String,
        val type: SpatialType,
        val position: Vector3,
        val rotation: Quaternion,
        val worldCoordinates: GeoLocation? = null,
        val isPersistent: Boolean = false,
        val createdAt: Long,
        val lastUpdatedAt: Long
    )

    data class GeoLocation(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Float
    )

    data class Gesture(
        val id: String,
        val type: GestureType,
        val confidence: Float,
        val duration: Long,
        val startPosition: Vector3,
        val endPosition: Vector3,
        val timestamp: Long
    )

    enum class GestureType {
        WAVE,
        PINCH,
        POINT,
        THUMBS_UP,
        THUMBS_DOWN,
        VICTORY,
        FIST,
        OPEN_HAND,
        SWIPE_LEFT,
        SWIPE_RIGHT,
        SWIPE_UP,
        SWIPE_DOWN,
        CUSTOM
    }

    data class VoiceCommand(
        val id: String,
        val text: String,
        val confidence: Float,
        val intent: String,
        val entities: Map<String, String>,
        val timestamp: Long
    )

    private val sessionsDir: File
        get() = File(context.filesDir, "arvr_sessions").also {
            if (!it.exists()) it.mkdirs()
        }

    private val objectsDir: File
        get() = File(context.filesDir, "arvr_objects").also {
            if (!it.exists()) it.mkdirs()
        }

    private val anchorsDir: File
        get() = File(context.filesDir, "arvr_anchors").also {
            if (!it.exists()) it.mkdirs()
        }

    private val activeSessions = mutableMapOf<String, ARVRSession>()
    private val sceneObjects = mutableMapOf<String, SceneObject>()
    private val spatialAnchors = mutableMapOf<String, SpatialAnchor>()

    suspend fun startSession(
        type: SessionType,
        mode: InteractionMode = InteractionMode.GESTURE
    ): ARVRSession = withContext(Dispatchers.IO) {
        val session = ARVRSession(
            id = UUID.randomUUID().toString(),
            type = type,
            mode = mode,
            startTime = System.currentTimeMillis(),
            isActive = true
        )

        saveSession(session)
        activeSessions[session.id] = session
        session
    }

    private suspend fun saveSession(session: ARVRSession) = withContext(Dispatchers.IO) {
        val sessionFile = File(sessionsDir, "${session.id}.json")

        val objectsJson = JSONArray()
        session.sceneObjects.forEach { obj ->
            objectsJson.put(serializeSceneObject(obj))
        }

        val interactionsJson = JSONArray()
        session.interactions.forEach { interaction ->
            interactionsJson.put(serializeInteraction(interaction))
        }

        val anchorsJson = JSONArray()
        session.spatialAnchors.forEach { anchor ->
            anchorsJson.put(serializeAnchor(anchor))
        }

        val json = JSONObject().apply {
            put("id", session.id)
            put("type", session.type.name)
            put("mode", session.mode.name)
            put("startTime", session.startTime)
            put("endTime", session.endTime ?: JSONObject.NULL)
            put("isActive", session.isActive)
            put("sceneObjects", objectsJson)
            put("interactions", interactionsJson)
            put("spatialAnchors", anchorsJson)
        }

        sessionFile.writeText(json.toString(2))
    }

    private fun serializeSceneObject(obj: SceneObject): JSONObject {
        val propsJson = JSONObject()
        obj.properties.forEach { (key, value) ->
            when (value) {
                is String -> propsJson.put(key, value)
                is Number -> propsJson.put(key, value)
                is Boolean -> propsJson.put(key, value)
            }
        }

        return JSONObject().apply {
            put("id", obj.id)
            put("name", obj.name)
            put("type", obj.type.name)
            put("position", serializeVector3(obj.position))
            put("rotation", serializeQuaternion(obj.rotation))
            put("scale", serializeVector3(obj.scale))
            put("properties", propsJson)
            put("isVisible", obj.isVisible)
            put("isInteractive", obj.isInteractive)
        }
    }

    private fun serializeInteraction(interaction: Interaction): JSONObject {
        val paramsJson = JSONObject()
        interaction.parameters.forEach { (key, value) ->
            when (value) {
                is String -> paramsJson.put(key, value)
                is Number -> paramsJson.put(key, value)
                is Boolean -> paramsJson.put(key, value)
            }
        }

        return JSONObject().apply {
            put("id", interaction.id)
            put("type", interaction.type.name)
            put("timestamp", interaction.timestamp)
            put("targetObjectId", interaction.targetObjectId ?: JSONObject.NULL)
            put("source", interaction.source.name)
            put("parameters", paramsJson)
            put("success", interaction.success)
        }
    }

    private fun serializeAnchor(anchor: SpatialAnchor): JSONObject {
        val geoJson = anchor.worldCoordinates?.let {
            JSONObject().apply {
                put("latitude", it.latitude)
                put("longitude", it.longitude)
                put("altitude", it.altitude)
                put("accuracy", it.accuracy.toDouble())
            }
        } ?: JSONObject.NULL

        return JSONObject().apply {
            put("id", anchor.id)
            put("name", anchor.name)
            put("type", anchor.type.name)
            put("position", serializeVector3(anchor.position))
            put("rotation", serializeQuaternion(anchor.rotation))
            put("worldCoordinates", geoJson)
            put("isPersistent", anchor.isPersistent)
            put("createdAt", anchor.createdAt)
            put("lastUpdatedAt", anchor.lastUpdatedAt)
        }
    }

    private fun serializeVector3(vector: Vector3): JSONObject {
        return JSONObject().apply {
            put("x", vector.x.toDouble())
            put("y", vector.y.toDouble())
            put("z", vector.z.toDouble())
        }
    }

    private fun serializeQuaternion(quaternion: Quaternion): JSONObject {
        return JSONObject().apply {
            put("x", quaternion.x.toDouble())
            put("y", quaternion.y.toDouble())
            put("z", quaternion.z.toDouble())
            put("w", quaternion.w.toDouble())
        }
    }

    suspend fun endSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val session = activeSessions[sessionId] ?: return@withContext false

        val updatedSession = session.copy(
            isActive = false,
            endTime = System.currentTimeMillis()
        )

        saveSession(updatedSession)
        activeSessions.remove(sessionId)
        true
    }

    suspend fun addObject(
        sessionId: String,
        name: String,
        type: ObjectType,
        position: Vector3 = Vector3.zero,
        rotation: Quaternion = Quaternion.identity,
        scale: Vector3 = Vector3.one
    ): SceneObject = withContext(Dispatchers.IO) {
        val obj = SceneObject(
            id = UUID.randomUUID().toString(),
            name = name,
            type = type,
            position = position,
            rotation = rotation,
            scale = scale,
            properties = emptyMap()
        )

        sceneObjects[obj.id] = obj
        saveObject(obj)

        val session = activeSessions[sessionId]
        session?.let {
            val updatedSession = it.copy(
                sceneObjects = it.sceneObjects + obj
            )
            saveSession(updatedSession)
            activeSessions[sessionId] = updatedSession
        }

        obj
    }

    private suspend fun saveObject(obj: SceneObject) = withContext(Dispatchers.IO) {
        val objFile = File(objectsDir, "${obj.id}.json")
        objFile.writeText(serializeSceneObject(obj).toString(2))
    }

    suspend fun updateObjectPosition(
        objectId: String,
        newPosition: Vector3
    ): Boolean = withContext(Dispatchers.IO) {
        val obj = sceneObjects[objectId] ?: return@withContext false

        val updatedObj = obj.copy(
            position = newPosition
        )

        sceneObjects[objectId] = updatedObj
        saveObject(updatedObj)
        true
    }

    suspend fun rotateObject(
        objectId: String,
        deltaRotation: Quaternion
    ): Boolean = withContext(Dispatchers.IO) {
        val obj = sceneObjects[objectId] ?: return@withContext false

        val updatedObj = obj.copy(
            rotation = Quaternion(
                x = obj.rotation.x * deltaRotation.w + obj.rotation.w * deltaRotation.x,
                y = obj.rotation.y * deltaRotation.w + obj.rotation.w * deltaRotation.y,
                z = obj.rotation.z * deltaRotation.w + obj.rotation.w * deltaRotation.z,
                w = obj.rotation.w * deltaRotation.w - obj.rotation.x * deltaRotation.x
            )
        )

        sceneObjects[objectId] = updatedObj
        saveObject(updatedObj)
        true
    }

    suspend fun removeObject(objectId: String): Boolean = withContext(Dispatchers.IO) {
        val removed = sceneObjects.remove(objectId) ?: return@withContext false

        File(objectsDir, "${objectId}.json").delete()

        activeSessions.values.forEach { session ->
            if (session.sceneObjects.any { it.id == objectId }) {
                val updatedSession = session.copy(
                    sceneObjects = session.sceneObjects.filter { it.id != objectId }
                )
                saveSession(updatedSession)
                activeSessions[session.id] = updatedSession
            }
        }

        true
    }

    suspend fun recordInteraction(
        sessionId: String,
        type: InteractionType,
        targetObjectId: String? = null,
        source: InteractionSource = InteractionSource.USER,
        parameters: Map<String, Any> = emptyMap(),
        success: Boolean = true
    ): Interaction = withContext(Dispatchers.IO) {
        val interaction = Interaction(
            id = UUID.randomUUID().toString(),
            type = type,
            timestamp = System.currentTimeMillis(),
            targetObjectId = targetObjectId,
            source = source,
            parameters = parameters,
            success = success
        )

        val session = activeSessions[sessionId]
        session?.let {
            val updatedSession = it.copy(
                interactions = it.interactions + interaction
            )
            saveSession(updatedSession)
            activeSessions[sessionId] = updatedSession
        }

        interaction
    }

    suspend fun createAnchor(
        sessionId: String,
        name: String,
        type: SpatialType,
        position: Vector3,
        rotation: Quaternion = Quaternion.identity,
        isPersistent: Boolean = false
    ): SpatialAnchor = withContext(Dispatchers.IO) {
        val anchor = SpatialAnchor(
            id = UUID.randomUUID().toString(),
            name = name,
            type = type,
            position = position,
            rotation = rotation,
            isPersistent = isPersistent,
            createdAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis()
        )

        spatialAnchors[anchor.id] = anchor
        saveAnchor(anchor)

        val session = activeSessions[sessionId]
        session?.let {
            val updatedSession = it.copy(
                spatialAnchors = it.spatialAnchors + anchor
            )
            saveSession(updatedSession)
            activeSessions[sessionId] = updatedSession
        }

        anchor
    }

    private suspend fun saveAnchor(anchor: SpatialAnchor) = withContext(Dispatchers.IO) {
        val anchorFile = File(anchorsDir, "${anchor.id}.json")
        anchorFile.writeText(serializeAnchor(anchor).toString(2))
    }

    suspend fun getSessions(activeOnly: Boolean = false): List<ARVRSession> = withContext(Dispatchers.IO) {
        val sessions = mutableListOf<ARVRSession>()

        sessionsDir.listFiles { _, name -> name.endsWith(".json") }
            ?.forEach { file ->
                try {
                    val session = deserializeSession(file.readText())
                    sessions.add(session)
                    activeSessions[session.id] = session
                } catch (e: Exception) {
                    AppLogger.w(TAG, "解析会话配置失败: ${file.name}", e)
                }
            }

        if (activeOnly) {
            sessions.filter { it.isActive }
        } else {
            sessions
        }
    }

    private fun deserializeSession(jsonString: String): ARVRSession {
        val json = JSONObject(jsonString)

        val objects = mutableListOf<SceneObject>()
        val objectsJson = json.getJSONArray("sceneObjects")
        for (i in 0 until objectsJson.length()) {
            objects.add(deserializeSceneObject(objectsJson.getJSONObject(i)))
        }

        val interactions = mutableListOf<Interaction>()
        val interactionsJson = json.getJSONArray("interactions")
        for (i in 0 until interactionsJson.length()) {
            interactions.add(deserializeInteraction(interactionsJson.getJSONObject(i)))
        }

        val anchors = mutableListOf<SpatialAnchor>()
        val anchorsJson = json.getJSONArray("spatialAnchors")
        for (i in 0 until anchorsJson.length()) {
            anchors.add(deserializeAnchor(anchorsJson.getJSONObject(i)))
        }

        return ARVRSession(
            id = json.getString("id"),
            type = SessionType.valueOf(json.getString("type")),
            mode = InteractionMode.valueOf(json.getString("mode")),
            startTime = json.getLong("startTime"),
            endTime = if (json.isNull("endTime")) null else json.getLong("endTime"),
            isActive = json.getBoolean("isActive"),
            sceneObjects = objects,
            interactions = interactions,
            spatialAnchors = anchors
        )
    }

    private fun deserializeSceneObject(json: JSONObject): SceneObject {
        val props = mutableMapOf<String, Any>()
        val propsJson = json.getJSONObject("properties")
        propsJson.keys().forEach { key ->
            val value = propsJson.get(key)
            when (value) {
                is String -> props[key] = value
                is Number -> props[key] = value.toFloat()
                is Boolean -> props[key] = value
            }
        }

        return SceneObject(
            id = json.getString("id"),
            name = json.getString("name"),
            type = ObjectType.valueOf(json.getString("type")),
            position = deserializeVector3(json.getJSONObject("position")),
            rotation = deserializeQuaternion(json.getJSONObject("rotation")),
            scale = deserializeVector3(json.getJSONObject("scale")),
            properties = props,
            isVisible = json.getBoolean("isVisible"),
            isInteractive = json.getBoolean("isInteractive")
        )
    }

    private fun deserializeInteraction(json: JSONObject): Interaction {
        val params = mutableMapOf<String, Any>()
        val paramsJson = json.getJSONObject("parameters")
        paramsJson.keys().forEach { key ->
            val value = paramsJson.get(key)
            when (value) {
                is String -> params[key] = value
                is Number -> params[key] = value.toFloat()
                is Boolean -> params[key] = value
            }
        }

        return Interaction(
            id = json.getString("id"),
            type = InteractionType.valueOf(json.getString("type")),
            timestamp = json.getLong("timestamp"),
            targetObjectId = if (json.isNull("targetObjectId")) null else json.getString("targetObjectId"),
            source = InteractionSource.valueOf(json.getString("source")),
            parameters = params,
            success = json.getBoolean("success")
        )
    }

    private fun deserializeAnchor(json: JSONObject): SpatialAnchor {
        val geoJson = if (json.isNull("worldCoordinates")) null else json.getJSONObject("worldCoordinates")

        return SpatialAnchor(
            id = json.getString("id"),
            name = json.getString("name"),
            type = SpatialType.valueOf(json.getString("type")),
            position = deserializeVector3(json.getJSONObject("position")),
            rotation = deserializeQuaternion(json.getJSONObject("rotation")),
            worldCoordinates = geoJson?.let {
                GeoLocation(
                    latitude = it.getDouble("latitude"),
                    longitude = it.getDouble("longitude"),
                    altitude = it.getDouble("altitude"),
                    accuracy = it.getDouble("accuracy").toFloat()
                )
            },
            isPersistent = json.getBoolean("isPersistent"),
            createdAt = json.getLong("createdAt"),
            lastUpdatedAt = json.getLong("lastUpdatedAt")
        )
    }

    private fun deserializeVector3(json: JSONObject): Vector3 {
        return Vector3(
            x = json.getDouble("x").toFloat(),
            y = json.getDouble("y").toFloat(),
            z = json.getDouble("z").toFloat()
        )
    }

    private fun deserializeQuaternion(json: JSONObject): Quaternion {
        return Quaternion(
            x = json.getDouble("x").toFloat(),
            y = json.getDouble("y").toFloat(),
            z = json.getDouble("z").toFloat(),
            w = json.getDouble("w").toFloat()
        )
    }

    suspend fun generateSessionReport(sessionId: String): String = withContext(Dispatchers.IO) {
        val session = activeSessions[sessionId] ?: return@withContext "会话不存�?

        buildString {
            appendLine("=== AR/VR 会话报告 ===")
            appendLine()
            appendLine("【会话信息�?)
            appendLine("ID: ${session.id}")
            appendLine("类型: ${session.type.name}")
            appendLine("交互模式: ${session.mode.name}")
            appendLine("状�? ${if (session.isActive) "活跃" else "已结�?}")
            appendLine()

            appendLine("【场景统计�?)
            appendLine("对象数量: ${session.sceneObjects.size}")
            appendLine("锚点数量: ${session.spatialAnchors.size}")
            appendLine("交互次数: ${session.interactions.size}")
            appendLine()

            appendLine("【交互类型分布�?)
            val typeCounts = session.interactions.groupingBy { it.type }.eachCount()
            typeCounts.forEach { (type, count) ->
                appendLine("  ${type}: ${count}")
            }
        }
    }

    suspend fun cleanupOldSessions(daysToKeep: Int = 30) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)

        sessionsDir.listFiles()?.forEach { file ->
            try {
                val session = deserializeSession(file.readText())
                if (session.endTime != null && session.endTime < cutoffTime) {
                    file.delete()
                    activeSessions.remove(session.id)
                    AppLogger.d(TAG, "清理旧会�? ${file.name}")
                }
            } catch (e: Exception) {
                file.delete()
            }
        }
    }
}