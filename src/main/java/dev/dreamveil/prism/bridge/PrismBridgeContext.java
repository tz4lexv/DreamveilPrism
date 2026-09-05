package dev.dreamveil.prism.bridge;

import java.util.Objects;
import java.util.function.Consumer;

import dev.dreamveil.prism.api.frame.PrismFrameData;
import dev.dreamveil.prism.runtime.api.PrismPackApiImpl;
import dev.dreamveil.prism.runtime.api.PrismPackSettingsApiImpl;
import dev.dreamveil.prism.runtime.api.PrismPerformanceApiImpl;
import dev.dreamveil.prism.runtime.api.PrismWorldRenderApiImpl;

/** Internal callbacks exposed by Prism core to a Minecraft-version bridge. */
public record PrismBridgeContext(
        Consumer<PrismFrameData> framePublisher,
        PrismPackApiImpl packApi,
        PrismPackSettingsApiImpl settingsApi,
        PrismPerformanceApiImpl performanceApi,
        PrismWorldRenderApiImpl worldRenderApi) {
    public PrismBridgeContext {
        framePublisher = Objects.requireNonNull(framePublisher, "framePublisher");
        packApi = Objects.requireNonNull(packApi, "packApi");
        settingsApi = Objects.requireNonNull(settingsApi, "settingsApi");
        performanceApi = Objects.requireNonNull(performanceApi, "performanceApi");
        worldRenderApi = Objects.requireNonNull(worldRenderApi, "worldRenderApi");
    }

    public void publishFrame(PrismFrameData frame) {
        framePublisher.accept(frame);
    }
}
