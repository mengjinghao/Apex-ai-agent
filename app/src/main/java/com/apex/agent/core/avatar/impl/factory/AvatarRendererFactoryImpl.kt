package com.apex.agent.core.avatar.impl.factory

import com.apex.agent.core.avatar.common.factory.AvatarRendererFactory
import com.apex.agent.core.avatar.common.factory.RendererDescriptor
import com.apex.agent.core.avatar.common.model.AvatarModel

class AvatarRendererFactoryImpl : AvatarRendererFactory {

    override fun resolveRenderer(model: AvatarModel): RendererDescriptor? {
        return RendererDescriptor.from(model)
    }
}
