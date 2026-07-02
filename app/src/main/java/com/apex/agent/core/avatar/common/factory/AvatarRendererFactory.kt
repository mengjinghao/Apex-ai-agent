package com.apex.agent.core.avatar.common.factory

import com.apex.agent.core.avatar.common.control.AvatarController
import com.apex.agent.core.avatar.common.model.AvatarModel

/**
 * An interface for a factory that creates avatar renderers.
 * The concrete implementation of this factory will live in the implementation layer
 * and will know about all the concrete renderer classes.
 */
interface AvatarRendererFactory {
    /**
     * Determines the renderer type and parameters for the given avatar model.
     * Returns null if the model type is not supported.
     */
    fun resolveRenderer(model: AvatarModel): RendererDescriptor?
} 