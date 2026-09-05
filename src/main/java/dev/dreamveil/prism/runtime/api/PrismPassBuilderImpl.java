package dev.dreamveil.prism.runtime.api;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.dreamveil.prism.api.graph.PrismPassBuilder;
import dev.dreamveil.prism.api.graph.PrismResourceAccess;
import dev.dreamveil.prism.api.resource.PrismResourceHandle;

final class PrismPassBuilderImpl implements PrismPassBuilder {
    private final Map<PrismResourceHandle, PrismResourceAccess> accesses = new LinkedHashMap<>();

    @Override
    public PrismPassBuilder read(PrismResourceHandle resource) {
        return add(resource, PrismResourceAccess.READ);
    }

    @Override
    public PrismPassBuilder write(PrismResourceHandle resource) {
        return add(resource, PrismResourceAccess.WRITE);
    }

    @Override
    public PrismPassBuilder readWrite(PrismResourceHandle resource) {
        return add(resource, PrismResourceAccess.READ_WRITE);
    }

    Map<PrismResourceHandle, PrismResourceAccess> accesses() {
        return Map.copyOf(accesses);
    }

    private PrismPassBuilder add(PrismResourceHandle resource, PrismResourceAccess access) {
        if (resource == null) {
            throw new IllegalArgumentException("Pass resource must not be null");
        }
        PrismResourceAccess previous = accesses.putIfAbsent(resource, access);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Pass references Prism resource more than once: " + resource.id()
                            + ". Use readWrite() for combined access.");
        }
        return this;
    }
}
