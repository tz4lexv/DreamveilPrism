package dev.dreamveil.prism.pack;

/**
 * Backend-neutral math for alpha.7.2.6 receiver-aware directional shadow caster filtering.
 *
 * <p>The receiver footprint lives in the two axes perpendicular to the light ray. Directional
 * casters are not required to be visible to the main camera; a caster remains eligible whenever
 * its projected AABB overlaps the guard-expanded receiver footprint.</p>
 */
final class PrismReceiverAwareCasterMath {
    private PrismReceiverAwareCasterMath() {}

    record Bounds(
            boolean available,
            int receiverSections,
            double minRight,
            double maxRight,
            double minUp,
            double maxUp) {
        static final Bounds EMPTY = new Bounds(false, 0, 0.0, 0.0, 0.0, 0.0);
    }

    static Bounds guardAndQuantize(
            int receiverSections,
            double minRight,
            double maxRight,
            double minUp,
            double maxUp,
            double guardBlocks,
            double sectionSize) {
        if (receiverSections <= 0
                || !Double.isFinite(minRight) || !Double.isFinite(maxRight)
                || !Double.isFinite(minUp) || !Double.isFinite(maxUp)
                || !(guardBlocks >= 0.0) || !(sectionSize > 0.0)) {
            return Bounds.EMPTY;
        }
        double minR = Math.floor((minRight - guardBlocks) / sectionSize) * sectionSize;
        double maxR = Math.ceil((maxRight + guardBlocks) / sectionSize) * sectionSize;
        double minU = Math.floor((minUp - guardBlocks) / sectionSize) * sectionSize;
        double maxU = Math.ceil((maxUp + guardBlocks) / sectionSize) * sectionSize;
        return new Bounds(true, receiverSections, minR, maxR, minU, maxU);
    }

    static boolean overlaps(
            Bounds bounds,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            float rightX, float rightY, float rightZ,
            float upX, float upY, float upZ) {
        if (bounds == null || !bounds.available()) {
            return true;
        }
        double candidateMinRight = projectAabbMin(
                minX, minY, minZ, maxX, maxY, maxZ, rightX, rightY, rightZ);
        double candidateMaxRight = projectAabbMax(
                minX, minY, minZ, maxX, maxY, maxZ, rightX, rightY, rightZ);
        if (candidateMaxRight < bounds.minRight() || candidateMinRight > bounds.maxRight()) {
            return false;
        }

        double candidateMinUp = projectAabbMin(
                minX, minY, minZ, maxX, maxY, maxZ, upX, upY, upZ);
        double candidateMaxUp = projectAabbMax(
                minX, minY, minZ, maxX, maxY, maxZ, upX, upY, upZ);
        return candidateMaxUp >= bounds.minUp() && candidateMinUp <= bounds.maxUp();
    }

    static double projectAabbMin(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            float axisX, float axisY, float axisZ) {
        return (axisX >= 0.0f ? axisX * minX : axisX * maxX)
                + (axisY >= 0.0f ? axisY * minY : axisY * maxY)
                + (axisZ >= 0.0f ? axisZ * minZ : axisZ * maxZ);
    }

    static double projectAabbMax(
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            float axisX, float axisY, float axisZ) {
        return (axisX >= 0.0f ? axisX * maxX : axisX * minX)
                + (axisY >= 0.0f ? axisY * maxY : axisY * minY)
                + (axisZ >= 0.0f ? axisZ * maxZ : axisZ * minZ);
    }
}
