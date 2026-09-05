package dev.dreamveil.prism.pack;

/**
 * Backend-neutral stable light-space volume math for alpha.7.2.7.
 *
 * <p>The visual shadow projection may translate every shadow texel, but the CPU caster set is
 * deliberately anchored to coarser light-space cells and expanded by a guard band. This prevents
 * sections from thrashing in/out for sub-section camera motion while preserving conservative
 * coverage. The volume is evaluated in world light-space, not camera-visible receiver space.</p>
 */
final class PrismStableCasterVolumeMath {
    private PrismStableCasterVolumeMath() {}

    record Volume(
            boolean available,
            long anchorRightCell,
            long anchorUpCell,
            long anchorDepthCell,
            double minRight,
            double maxRight,
            double minUp,
            double maxUp,
            double minDepth,
            double maxDepth) {
        static final Volume EMPTY = new Volume(
                false, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    static Volume volume(
            double cameraX, double cameraY, double cameraZ,
            float rightX, float rightY, float rightZ,
            float upX, float upY, float upZ,
            float rayX, float rayY, float rayZ,
            double transverseHalfExtent,
            double nearReach,
            double farReach,
            double guardBlocks,
            double anchorStepBlocks) {
        if (!finite(cameraX, cameraY, cameraZ)
                || !(transverseHalfExtent > 0.0)
                || !(nearReach >= 0.0) || !(farReach >= 0.0)
                || !(guardBlocks >= 0.0) || !(anchorStepBlocks > 0.0)) {
            return Volume.EMPTY;
        }

        double cameraRight = dot(cameraX, cameraY, cameraZ, rightX, rightY, rightZ);
        double cameraUp = dot(cameraX, cameraY, cameraZ, upX, upY, upZ);
        double cameraDepth = dot(cameraX, cameraY, cameraZ, rayX, rayY, rayZ);
        if (!finite(cameraRight, cameraUp, cameraDepth)) {
            return Volume.EMPTY;
        }

        long rightCell = floorCell(cameraRight, anchorStepBlocks);
        long upCell = floorCell(cameraUp, anchorStepBlocks);
        long depthCell = floorCell(cameraDepth, anchorStepBlocks);
        double anchorRight = rightCell * anchorStepBlocks;
        double anchorUp = upCell * anchorStepBlocks;
        double anchorDepth = depthCell * anchorStepBlocks;

        return new Volume(
                true,
                rightCell, upCell, depthCell,
                anchorRight - transverseHalfExtent - guardBlocks,
                anchorRight + transverseHalfExtent + guardBlocks + anchorStepBlocks,
                anchorUp - transverseHalfExtent - guardBlocks,
                anchorUp + transverseHalfExtent + guardBlocks + anchorStepBlocks,
                anchorDepth - nearReach - guardBlocks,
                anchorDepth + farReach + guardBlocks + anchorStepBlocks);
    }

    static boolean sameKey(Volume a, Volume b) {
        return a != null && b != null
                && a.available() && b.available()
                && a.anchorRightCell() == b.anchorRightCell()
                && a.anchorUpCell() == b.anchorUpCell()
                && a.anchorDepthCell() == b.anchorDepthCell();
    }

    static boolean overlaps(
            Volume volume,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ,
            float rightX, float rightY, float rightZ,
            float upX, float upY, float upZ,
            float rayX, float rayY, float rayZ) {
        if (volume == null || !volume.available()) {
            return true;
        }
        double minR = PrismReceiverAwareCasterMath.projectAabbMin(
                minX, minY, minZ, maxX, maxY, maxZ, rightX, rightY, rightZ);
        double maxR = PrismReceiverAwareCasterMath.projectAabbMax(
                minX, minY, minZ, maxX, maxY, maxZ, rightX, rightY, rightZ);
        if (maxR < volume.minRight() || minR > volume.maxRight()) {
            return false;
        }
        double minU = PrismReceiverAwareCasterMath.projectAabbMin(
                minX, minY, minZ, maxX, maxY, maxZ, upX, upY, upZ);
        double maxU = PrismReceiverAwareCasterMath.projectAabbMax(
                minX, minY, minZ, maxX, maxY, maxZ, upX, upY, upZ);
        if (maxU < volume.minUp() || minU > volume.maxUp()) {
            return false;
        }
        double minD = PrismReceiverAwareCasterMath.projectAabbMin(
                minX, minY, minZ, maxX, maxY, maxZ, rayX, rayY, rayZ);
        double maxD = PrismReceiverAwareCasterMath.projectAabbMax(
                minX, minY, minZ, maxX, maxY, maxZ, rayX, rayY, rayZ);
        return maxD >= volume.minDepth() && minD <= volume.maxDepth();
    }

    static long floorCell(double coordinate, double step) {
        return (long) Math.floor(coordinate / step);
    }

    private static double dot(
            double x, double y, double z,
            float axisX, float axisY, float axisZ) {
        return axisX * x + axisY * y + axisZ * z;
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) return false;
        }
        return true;
    }
}
