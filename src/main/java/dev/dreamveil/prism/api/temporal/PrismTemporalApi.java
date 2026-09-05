package dev.dreamveil.prism.api.temporal;

public interface PrismTemporalApi {
    PrismTemporalApi EMPTY = () -> PrismTemporalSnapshot.EMPTY;

    PrismTemporalSnapshot snapshot();

    /** Added in API 1.4; richer invalidation reasons, frame pair and deterministic jitter. */
    default PrismTemporalState state() {
        return PrismTemporalState.EMPTY;
    }

    /**
     * Halton(2,3) jitter centered around zero. Returned values are in pixel units [-0.5, 0.5).
     * A pack may opt into loader-applied scene projection jitter with scene_jitter. The TAA
     * resolve itself remains pack-authored.
     */
    default double[] jitter(long frameIndex) {
        long index = Math.max(0L, frameIndex) + 1L;
        return new double[] { halton(index, 2) - 0.5, halton(index, 3) - 0.5 };
    }

    private static double halton(long index, int base) {
        double result = 0.0;
        double fraction = 1.0 / base;
        long value = index;
        while (value > 0L) {
            result += fraction * (value % base);
            value /= base;
            fraction /= base;
        }
        return result;
    }
}
