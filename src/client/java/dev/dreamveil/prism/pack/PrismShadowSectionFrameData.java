package dev.dreamveil.prism.pack;

import java.util.Arrays;

/**
 * Immutable CPU snapshot of Prism's independent terrain-section shadow visibility for one frame.
 *
 * <p>The snapshot stores accepted sections as a compact bit set over a regular camera-centered
 * section grid. Alpha.5 adds a second bit set identifying accepted coordinates that resolve to
 * renderer-owned resident RenderSection slots. The snapshot still never retains RenderSection
 * objects or GPU buffers; it stores only Prism-owned coordinates/masks so ViewArea rotation cannot
 * create an ownership or lifetime dependency.</p>
 */
final class PrismShadowSectionFrameData {
    static final PrismShadowSectionFrameData EMPTY = new PrismShadowSectionFrameData(
            -1L,
            0.0, 0.0, 0.0,
            0, 0, 0,
            0, 0,
            0, 0, 0,
            0, 0,
            0,
            new long[0],
            new long[0]);

    private final long frameIndex;
    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final int minSectionX;
    private final int minSectionY;
    private final int minSectionZ;
    private final int horizontalDiameter;
    private final int verticalSectionCount;
    private final int candidates;
    private final int accepted;
    private final int culled;
    private final int residentResolved;
    private final int residentMissing;
    private final int mainCameraVisibleDiagnostic;
    private final long[] acceptedMask;
    private final long[] residentMask;

    PrismShadowSectionFrameData(
            long frameIndex,
            double cameraX,
            double cameraY,
            double cameraZ,
            int minSectionX,
            int minSectionY,
            int minSectionZ,
            int horizontalDiameter,
            int verticalSectionCount,
            int candidates,
            int accepted,
            int culled,
            int residentResolved,
            int residentMissing,
            int mainCameraVisibleDiagnostic,
            long[] acceptedMask,
            long[] residentMask) {
        if (frameIndex < -1) throw new IllegalArgumentException("frameIndex must be >= -1");
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)) {
            throw new IllegalArgumentException("camera position must be finite");
        }
        boolean empty = frameIndex == -1;
        if (!empty && (horizontalDiameter <= 0 || verticalSectionCount <= 0)) {
            throw new IllegalArgumentException("shadow section grid dimensions must be positive");
        }
        if (empty && (horizontalDiameter != 0 || verticalSectionCount != 0 || candidates != 0
                || accepted != 0 || culled != 0)) {
            throw new IllegalArgumentException("empty shadow section frame must contain zero-sized visibility data");
        }
        if (candidates < 0 || accepted < 0 || culled < 0 || accepted + culled != candidates) {
            throw new IllegalArgumentException("invalid shadow visibility counters");
        }
        if (residentResolved < 0 || residentMissing < 0 || residentResolved + residentMissing != accepted) {
            throw new IllegalArgumentException("invalid resident section counters");
        }
        if (mainCameraVisibleDiagnostic < 0) {
            throw new IllegalArgumentException("mainCameraVisibleDiagnostic must be >= 0");
        }
        int expectedCandidates = empty ? 0 : Math.multiplyExact(
                Math.multiplyExact(horizontalDiameter, horizontalDiameter),
                verticalSectionCount);
        if (expectedCandidates != candidates) {
            throw new IllegalArgumentException("candidate count does not match section-grid dimensions");
        }
        int requiredWords = (candidates + Long.SIZE - 1) / Long.SIZE;
        if (acceptedMask == null || acceptedMask.length != requiredWords) {
            throw new IllegalArgumentException("acceptedMask length does not match candidate count");
        }
        if (residentMask == null || residentMask.length != requiredWords) {
            throw new IllegalArgumentException("residentMask length does not match candidate count");
        }
        for (int word = 0; word < requiredWords; word++) {
            if ((residentMask[word] & ~acceptedMask[word]) != 0L) {
                throw new IllegalArgumentException("residentMask must be a subset of acceptedMask");
            }
        }

        this.frameIndex = frameIndex;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.minSectionX = minSectionX;
        this.minSectionY = minSectionY;
        this.minSectionZ = minSectionZ;
        this.horizontalDiameter = horizontalDiameter;
        this.verticalSectionCount = verticalSectionCount;
        this.candidates = candidates;
        this.accepted = accepted;
        this.culled = culled;
        this.residentResolved = residentResolved;
        this.residentMissing = residentMissing;
        this.mainCameraVisibleDiagnostic = mainCameraVisibleDiagnostic;
        // Clone on publish: extraction and drawing may overlap in future renderer versions, so the
        // published snapshot must not share writable mask storage with its builder.
        this.acceptedMask = acceptedMask.clone();
        this.residentMask = residentMask.clone();
    }

    long frameIndex() { return frameIndex; }
    double cameraX() { return cameraX; }
    double cameraY() { return cameraY; }
    double cameraZ() { return cameraZ; }
    int minSectionX() { return minSectionX; }
    int minSectionY() { return minSectionY; }
    int minSectionZ() { return minSectionZ; }
    int horizontalDiameter() { return horizontalDiameter; }
    int verticalSectionCount() { return verticalSectionCount; }
    int candidates() { return candidates; }
    int accepted() { return accepted; }
    int culled() { return culled; }
    int residentResolved() { return residentResolved; }
    int residentMissing() { return residentMissing; }
    int mainCameraVisibleDiagnostic() { return mainCameraVisibleDiagnostic; }

    boolean isAccepted(int linearIndex) {
        if (linearIndex < 0 || linearIndex >= candidates) {
            throw new IndexOutOfBoundsException("linearIndex=" + linearIndex + ", candidates=" + candidates);
        }
        return (acceptedMask[linearIndex >>> 6] & (1L << (linearIndex & 63))) != 0L;
    }

    int sectionX(int linearIndex) {
        checkLinearIndex(linearIndex);
        int horizontalIndex = linearIndex / verticalSectionCount;
        return minSectionX + (horizontalIndex % horizontalDiameter);
    }

    int sectionY(int linearIndex) {
        checkLinearIndex(linearIndex);
        return minSectionY + (linearIndex % verticalSectionCount);
    }

    int sectionZ(int linearIndex) {
        checkLinearIndex(linearIndex);
        int horizontalIndex = linearIndex / verticalSectionCount;
        return minSectionZ + (horizontalIndex / horizontalDiameter);
    }

    boolean isResident(int linearIndex) {
        if (linearIndex < 0 || linearIndex >= candidates) {
            throw new IndexOutOfBoundsException("linearIndex=" + linearIndex + ", candidates=" + candidates);
        }
        return (residentMask[linearIndex >>> 6] & (1L << (linearIndex & 63))) != 0L;
    }

    /**
     * Returns the first resident accepted section at or after {@code fromLinearIndex}, or -1.
     * Uses word-level bit scanning so the draw phase skips non-resident candidate slots without
     * allocating an auxiliary index list every frame.
     */
    int nextResidentIndex(int fromLinearIndex) {
        if (fromLinearIndex < 0) {
            fromLinearIndex = 0;
        }
        if (fromLinearIndex >= candidates) {
            return -1;
        }

        int wordIndex = fromLinearIndex >>> 6;
        long word = residentMask[wordIndex] & (-1L << (fromLinearIndex & 63));
        while (true) {
            if (word != 0L) {
                int index = (wordIndex << 6) + Long.numberOfTrailingZeros(word);
                return index < candidates ? index : -1;
            }
            wordIndex++;
            if (wordIndex >= residentMask.length) {
                return -1;
            }
            word = residentMask[wordIndex];
        }
    }

    long[] acceptedMaskCopy() {
        return Arrays.copyOf(acceptedMask, acceptedMask.length);
    }

    long[] residentMaskCopy() {
        return Arrays.copyOf(residentMask, residentMask.length);
    }

    private void checkLinearIndex(int linearIndex) {
        if (linearIndex < 0 || linearIndex >= candidates) {
            throw new IndexOutOfBoundsException("linearIndex=" + linearIndex + ", candidates=" + candidates);
        }
    }
}
