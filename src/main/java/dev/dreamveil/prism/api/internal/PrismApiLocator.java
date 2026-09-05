package dev.dreamveil.prism.api.internal;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import dev.dreamveil.prism.api.PrismApi;

/** Internal runtime locator. Creator code should use dev.dreamveil.prism.api.Prism instead. */
public final class PrismApiLocator {
    private static final AtomicReference<PrismApi> CURRENT = new AtomicReference<>();

    private PrismApiLocator() {
    }

    public static Optional<PrismApi> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static PrismApi require() {
        PrismApi api = CURRENT.get();
        if (api == null) {
            throw new IllegalStateException("Dreamveil Prism API is not initialized yet");
        }
        return api;
    }

    public static void install(PrismApi api) {
        if (api == null) {
            throw new IllegalArgumentException("Prism API must not be null");
        }
        if (!CURRENT.compareAndSet(null, api)) {
            throw new IllegalStateException("Dreamveil Prism API is already installed");
        }
    }

    public static void uninstall(PrismApi api) {
        CURRENT.compareAndSet(api, null);
    }
}
