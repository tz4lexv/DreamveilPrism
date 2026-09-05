package dev.dreamveil.prism.runtime.api;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import dev.dreamveil.prism.api.frame.PrismFrameApi;
import dev.dreamveil.prism.api.frame.PrismFrameData;

public final class PrismFrameApiImpl implements PrismFrameApi {
    private record State(PrismFrameData current, PrismFrameData previous) {
    }

    private final AtomicReference<State> state = new AtomicReference<>(new State(null, null));

    @Override
    public Optional<PrismFrameData> current() {
        return Optional.ofNullable(state.get().current());
    }

    @Override
    public Optional<PrismFrameData> previous() {
        return Optional.ofNullable(state.get().previous());
    }

    public void publish(PrismFrameData frame) {
        if (frame == null) {
            throw new IllegalArgumentException("Published Prism frame must not be null");
        }
        state.updateAndGet(old -> new State(frame, old.current()));
    }

    public void clear() {
        state.set(new State(null, null));
    }
}
