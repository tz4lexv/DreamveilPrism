package dev.dreamveil.prism.api;

import java.util.Optional;

import dev.dreamveil.prism.api.internal.PrismApiLocator;

/** Stable entry point for creator-facing Dreamveil Prism APIs. */
public final class Prism {
    private Prism() {
    }

    public static PrismApi api() {
        return PrismApiLocator.require();
    }

    public static Optional<PrismApi> tryApi() {
        return PrismApiLocator.current();
    }
}
