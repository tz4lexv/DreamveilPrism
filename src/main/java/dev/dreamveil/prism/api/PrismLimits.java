package dev.dreamveil.prism.api;

/** Loader admission limits, NOT queried physical-device limits or a feature-availability promise. */
public record PrismLimits(int maxSceneViews, int maxCapturedModelVertices, int maxCapturedModelDraws,
        int maxResidentModelsPerDomain, int maxScannedCandidatesPerDomain, int maxVisibilityRadius,
        int maxRelativeVisibilityOffset, int maxColorAttachments, int maxPackStorageBuffers,
        long maxPackStorageBufferBytes, long maxTotalPackStorageBufferBytes) {
    public static final PrismLimits LOADER = new PrismLimits(4, 65536, 2048, 128, 4096, 64, 512,
            4, 32, 64L * 1024 * 1024, 128L * 1024 * 1024);
}
