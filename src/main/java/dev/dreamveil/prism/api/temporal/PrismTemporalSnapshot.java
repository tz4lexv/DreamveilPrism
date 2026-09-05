package dev.dreamveil.prism.api.temporal;

/** Runtime-managed temporal validity hints. Packs remain free to ignore temporal techniques. */
public record PrismTemporalSnapshot(
        boolean historyValid,
        boolean resolutionChanged,
        boolean cameraJump,
        long currentFrameIndex,
        long previousFrameIndex) {
    public static final PrismTemporalSnapshot EMPTY = new PrismTemporalSnapshot(false, false, false, 0L, 0L);
}
