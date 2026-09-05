package dev.dreamveil.prism.runtime.api;

import dev.dreamveil.prism.api.PrismApiVersion;
import dev.dreamveil.prism.api.diagnostics.PrismDiagnosticsApi;
import dev.dreamveil.prism.api.diagnostics.PrismDiagnosticsSnapshot;
import dev.dreamveil.prism.api.frame.PrismFrameData;
import dev.dreamveil.prism.backend.PrismBackendInfo;

public final class PrismDiagnosticsApiImpl implements PrismDiagnosticsApi {
    private final String prismVersion;
    private final String minecraftVersion;
    private final PrismBackendInfo backendInfo;
    private final PrismFrameApiImpl frames;

    public PrismDiagnosticsApiImpl(
            String prismVersion,
            String minecraftVersion,
            PrismBackendInfo backendInfo,
            PrismFrameApiImpl frames) {
        this.prismVersion = prismVersion;
        this.minecraftVersion = minecraftVersion;
        this.backendInfo = backendInfo;
        this.frames = frames;
    }

    @Override
    public PrismDiagnosticsSnapshot snapshot() {
        PrismFrameData frame = frames.current().orElse(null);
        return new PrismDiagnosticsSnapshot(
                prismVersion,
                PrismApiVersion.CURRENT,
                minecraftVersion,
                backendInfo.name(),
                backendInfo.implementation(),
                backendInfo.capabilities(),
                frame != null,
                frame == null ? -1L : frame.frameIndex());
    }
}
