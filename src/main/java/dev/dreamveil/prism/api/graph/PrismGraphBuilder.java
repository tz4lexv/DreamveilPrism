package dev.dreamveil.prism.api.graph;

import java.util.function.Consumer;

import dev.dreamveil.prism.api.resource.PrismBufferDescriptor;
import dev.dreamveil.prism.api.resource.PrismBufferHandle;
import dev.dreamveil.prism.api.resource.PrismResourceId;
import dev.dreamveil.prism.api.resource.PrismTextureDescriptor;
import dev.dreamveil.prism.api.resource.PrismTextureHandle;

public interface PrismGraphBuilder {
    PrismGraphBuilder importTexture(PrismTextureHandle resource);

    PrismTextureHandle transientTexture(PrismResourceId id, PrismTextureDescriptor descriptor);

    PrismBufferHandle transientBuffer(PrismResourceId id, PrismBufferDescriptor descriptor);

    PrismGraphBuilder pass(PrismResourceId id, Consumer<PrismPassBuilder> declaration);

    PrismGraphPlan compile();
}
