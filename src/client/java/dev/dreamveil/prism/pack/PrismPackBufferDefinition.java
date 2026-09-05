package dev.dreamveil.prism.pack;

import dev.dreamveil.prism.graph.PrismBufferDesc;

/** Device-local storage buffer declared by a creator pack. */
record PrismPackBufferDefinition(String id, PrismBufferDesc descriptor) {
    PrismPackBufferDefinition {
        if (id == null || id.isBlank() || descriptor == null) {
            throw new IllegalArgumentException("Pack buffer id/descriptor must not be blank");
        }
    }
}
