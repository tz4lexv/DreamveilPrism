package dev.dreamveil.prism.api.graph;

import dev.dreamveil.prism.api.resource.PrismResourceHandle;

public interface PrismPassBuilder {
    PrismPassBuilder read(PrismResourceHandle resource);

    PrismPassBuilder write(PrismResourceHandle resource);

    PrismPassBuilder readWrite(PrismResourceHandle resource);
}
