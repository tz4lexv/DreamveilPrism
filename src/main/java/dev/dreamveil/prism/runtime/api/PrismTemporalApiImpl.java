package dev.dreamveil.prism.runtime.api;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import dev.dreamveil.prism.api.frame.PrismFrameData;
import dev.dreamveil.prism.api.temporal.PrismTemporalApi;
import dev.dreamveil.prism.api.temporal.PrismTemporalInvalidationReason;
import dev.dreamveil.prism.api.temporal.PrismTemporalSnapshot;
import dev.dreamveil.prism.api.temporal.PrismTemporalState;

public final class PrismTemporalApiImpl implements PrismTemporalApi {
    private final AtomicReference<PrismTemporalSnapshot> snapshot = new AtomicReference<>(PrismTemporalSnapshot.EMPTY);
    private final AtomicReference<PrismTemporalState> state = new AtomicReference<>(PrismTemporalState.EMPTY);
    private PrismFrameData previous;

    public synchronized void publish(PrismFrameData current) {
        PrismFrameData old = previous;
        EnumSet<PrismTemporalInvalidationReason> reasons = EnumSet.noneOf(PrismTemporalInvalidationReason.class);
        boolean resolutionChanged = false;
        boolean cameraJump = false;
        long previousFrameIndex = current.frameIndex();

        if (old == null) {
            reasons.add(PrismTemporalInvalidationReason.FIRST_FRAME);
            cameraJump = true;
        } else {
            previousFrameIndex = old.frameIndex();
            resolutionChanged = old.renderWidth() != current.renderWidth() || old.renderHeight() != current.renderHeight();
            if (resolutionChanged) {
                reasons.add(PrismTemporalInvalidationReason.RESOLUTION_CHANGED);
            }

            double dx = current.cameraPosition().x() - old.cameraPosition().x();
            double dy = current.cameraPosition().y() - old.cameraPosition().y();
            double dz = current.cameraPosition().z() - old.cameraPosition().z();
            cameraJump = dx * dx + dy * dy + dz * dz > 64.0 * 64.0;
            if (cameraJump) {
                reasons.add(PrismTemporalInvalidationReason.CAMERA_JUMP);
            }
            if (current.frameIndex() != old.frameIndex() + 1) {
                reasons.add(PrismTemporalInvalidationReason.NON_CONSECUTIVE_FRAME);
            }
        }

        boolean historyValid = reasons.isEmpty();
        PrismTemporalSnapshot nextSnapshot = new PrismTemporalSnapshot(
                historyValid,
                resolutionChanged,
                cameraJump || reasons.contains(PrismTemporalInvalidationReason.NON_CONSECUTIVE_FRAME),
                current.frameIndex(),
                previousFrameIndex);
        snapshot.set(nextSnapshot);

        double[] jitter = jitter(current.frameIndex());
        state.set(new PrismTemporalState(
                nextSnapshot,
                Set.copyOf(reasons),
                current,
                old,
                jitter[0],
                jitter[1]));
        previous = current;
    }

    public synchronized void clear() {
        previous = null;
        snapshot.set(PrismTemporalSnapshot.EMPTY);
        state.set(PrismTemporalState.EMPTY);
    }

    @Override
    public PrismTemporalSnapshot snapshot() {
        return snapshot.get();
    }

    @Override
    public PrismTemporalState state() {
        return state.get();
    }
}
