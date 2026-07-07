package com.apex.agent.core.avatar.common.factory

import com.apex.agent.core.avatar.common.control.AvatarController
import com.apex.agent.core.avatar.common.model.AvatarModel
import com.apex.agent.core.avatar.common.model.AvatarType

sealed class RendererDescriptor {
    abstract val model: AvatarModel

    data class WebP(override val model: com.apex.agent.core.avatar.impl.webp.model.WebPAvatarModel) : RendererDescriptor()
    data class Mp4(override val model: com.apex.agent.core.avatar.impl.mp4.model.Mp4AvatarModel) : RendererDescriptor()
    data class Mmd(override val model: com.apex.agent.core.avatar.impl.mmd.model.MmdAvatarModel) : RendererDescriptor()
    data class Gltf(override val model: com.apex.agent.core.avatar.impl.gltf.model.GltfAvatarModel) : RendererDescriptor()
    data class Fbx(override val model: com.apex.agent.core.avatar.impl.fbx.model.FbxAvatarModel) : RendererDescriptor()

    companion object {
        fun from(model: AvatarModel): RendererDescriptor? {
            return when (model) {
                is com.apex.agent.core.avatar.impl.webp.model.WebPAvatarModel -> WebP(model)
                is com.apex.agent.core.avatar.impl.mp4.model.Mp4AvatarModel -> Mp4(model)
                is com.apex.agent.core.avatar.impl.mmd.model.MmdAvatarModel -> Mmd(model)
                is com.apex.agent.core.avatar.impl.gltf.model.GltfAvatarModel -> Gltf(model)
                is com.apex.agent.core.avatar.impl.fbx.model.FbxAvatarModel -> Fbx(model)
                else -> null
            }
        }
    }
}
