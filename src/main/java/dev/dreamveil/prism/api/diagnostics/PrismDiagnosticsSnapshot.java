package dev.dreamveil.prism.api.diagnostics;

import java.util.Set;

import dev.dreamveil.prism.api.PrismApiVersion;
import dev.dreamveil.prism.api.PrismCapability;

public record PrismDiagnosticsSnapshot(
        String prismVersion,
        PrismApiVersion apiVersion,
        String minecraftVersion,
        String backendName,
        String backendImplementation,
        Set<PrismCapability> capabilities,
        boolean hasFrameData,
        long currentFrameIndex) {

    public PrismDiagnosticsSnapshot {
        capabilities = Set.copyOf(capabilities);
    }
}
