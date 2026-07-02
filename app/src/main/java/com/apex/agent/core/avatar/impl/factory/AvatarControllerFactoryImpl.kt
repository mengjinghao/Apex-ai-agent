package com.apex.agent.core.avatar.impl.factory

import com.apex.agent.core.avatar.common.control.AvatarController
import com.apex.agent.core.avatar.common.factory.AvatarControllerFactory
import com.apex.agent.core.avatar.common.model.AvatarModel
import com.apex.agent.core.avatar.common.model.AvatarType
import com.apex.agent.core.avatar.impl.fbx.model.FbxAvatarModel
import com.apex.agent.core.avatar.impl.fbx.control.FbxAvatarController
import com.apex.agent.core.avatar.impl.gltf.model.GltfAvatarModel
import com.apex.agent.core.avatar.impl.gltf.control.GltfAvatarController
import com.apex.agent.core.avatar.impl.mmd.model.MmdAvatarModel
import com.apex.agent.core.avatar.impl.mmd.control.MmdAvatarController
import com.apex.agent.core.avatar.impl.mp4.model.Mp4AvatarModel
import com.apex.agent.core.avatar.impl.mp4.control.Mp4AvatarController
import com.apex.agent.core.avatar.impl.webp.model.WebPAvatarModel
import com.apex.agent.core.avatar.impl.webp.control.WebPAvatarController

class AvatarControllerFactoryImpl : AvatarControllerFactory {

    override fun createController(model: AvatarModel): AvatarController? {
        return when (model.type) {
            AvatarType.WEBP -> (model as? WebPAvatarModel)?.let { WebPAvatarController(it) }
            AvatarType.MP4 -> (model as? Mp4AvatarModel)?.let { Mp4AvatarController(it) }
            AvatarType.MMD -> (model as? MmdAvatarModel)?.let { MmdAvatarController(it) }
            AvatarType.GLTF -> (model as? GltfAvatarModel)?.let { GltfAvatarController(it) }
            AvatarType.FBX -> (model as? FbxAvatarModel)?.let { FbxAvatarController(it) }
        }
    }

    override fun canCreateController(model: AvatarModel): Boolean {
        return when (model.type) {
            // AvatarType.DRAGONBONES -> model is ISkeletalAvatarModel
            AvatarType.WEBP -> model is WebPAvatarModel
            AvatarType.MP4 -> model is Mp4AvatarModel
            AvatarType.MMD -> model is MmdAvatarModel
            AvatarType.GLTF -> model is GltfAvatarModel
            AvatarType.FBX -> model is FbxAvatarModel
        }
    }

    override val supportedTypes: List<String>
        get() = listOf(
            // AvatarType.DRAGONBONES.name,
            AvatarType.WEBP.name,
            AvatarType.MP4.name,
            AvatarType.MMD.name,
            AvatarType.GLTF.name,
            AvatarType.FBX.name
        )
}
