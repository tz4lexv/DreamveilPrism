package dev.dreamveil.prism.runtime.api;

import dev.dreamveil.prism.api.graph.PrismGraphBuilder;
import dev.dreamveil.prism.api.graph.PrismRenderGraphApi;
import dev.dreamveil.prism.api.resource.PrismResourceId;

public final class PrismRenderGraphApiImpl implements PrismRenderGraphApi {
    @Override
    public PrismGraphBuilder create(PrismResourceId graphId) {
        return new PrismGraphBuilderImpl(graphId);
    }
}
