package dev.dreamveil.prism.api.graph;

import dev.dreamveil.prism.api.resource.PrismResourceId;

/** Creator-facing entry point for declaring and validating render graphs. */
public interface PrismRenderGraphApi {
    PrismGraphBuilder create(PrismResourceId graphId);
}
