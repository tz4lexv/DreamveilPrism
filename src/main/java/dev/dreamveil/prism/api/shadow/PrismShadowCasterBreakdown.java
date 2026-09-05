package dev.dreamveil.prism.api.shadow;

/** Per-source shadow-caster counts. */
public record PrismShadowCasterBreakdown(int terrain, int entities, int blockEntities) {
    public static final PrismShadowCasterBreakdown EMPTY = new PrismShadowCasterBreakdown(0, 0, 0);

    public PrismShadowCasterBreakdown {
        if (terrain < 0 || entities < 0 || blockEntities < 0) {
            throw new IllegalArgumentException("Shadow caster counts must be >= 0");
        }
    }

    public int total() {
        return Math.addExact(Math.addExact(terrain, entities), blockEntities);
    }

    public int count(PrismShadowCasterKind kind) {
        return switch (java.util.Objects.requireNonNull(kind, "kind")) {
            case TERRAIN -> terrain;
            case ENTITY -> entities;
            case BLOCK_ENTITY -> blockEntities;
        };
    }
}
