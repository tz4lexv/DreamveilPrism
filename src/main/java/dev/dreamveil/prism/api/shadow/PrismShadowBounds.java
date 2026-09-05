package dev.dreamveil.prism.api.shadow;

/** Backend-neutral world-space AABB used by Prism shadow visibility planning. */
public record PrismShadowBounds(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ) {

    public PrismShadowBounds {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Shadow bounds must be finite and ordered");
        }
    }
}
