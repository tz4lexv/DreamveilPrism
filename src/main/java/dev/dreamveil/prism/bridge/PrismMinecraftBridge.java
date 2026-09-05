package dev.dreamveil.prism.bridge;

import dev.dreamveil.prism.backend.PrismBackend;

/**
 * Version-facing boundary between Minecraft internals and Prism core.
 * Each materially different Minecraft renderer generation gets a bridge adapter.
 */
public interface PrismMinecraftBridge extends AutoCloseable {
    String minecraftVersion();

    PrismBackend backend();

    void initialize(PrismBridgeContext context);

    @Override
    void close();
}
