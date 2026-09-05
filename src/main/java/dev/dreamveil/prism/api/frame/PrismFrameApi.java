package dev.dreamveil.prism.api.frame;

import java.util.Optional;

/** Read-only frame snapshots. Values are immutable and safe to retain across frames. */
public interface PrismFrameApi {
    Optional<PrismFrameData> current();

    Optional<PrismFrameData> previous();
}
