package dev.dreamveil.prism.api.temporal;

import java.util.Set;

import dev.dreamveil.prism.api.frame.PrismFrameData;

/** Rich API 1.4 temporal state. Visual temporal algorithms remain pack-owned. */
public record PrismTemporalState(
        PrismTemporalSnapshot snapshot,
        Set<PrismTemporalInvalidationReason> invalidationReasons,
        PrismFrameData currentFrame,
        PrismFrameData previousFrame,
        double jitterX,
        double jitterY) {

    public static final PrismTemporalState EMPTY = new PrismTemporalState(
            PrismTemporalSnapshot.EMPTY, Set.of(), null, null, 0.0, 0.0);

    public PrismTemporalState {
        snapshot = snapshot == null ? PrismTemporalSnapshot.EMPTY : snapshot;
        invalidationReasons = Set.copyOf(invalidationReasons == null ? Set.of() : invalidationReasons);
        if (!Double.isFinite(jitterX) || !Double.isFinite(jitterY)) {
            throw new IllegalArgumentException("Temporal jitter values must be finite");
        }
    }
}
