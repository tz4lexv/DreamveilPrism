package dev.dreamveil.prism.api.render;

/**
 * Semantic scene domains understood by Prism ProgramSets.
 *
 * <p>The enum describes intent, not a legacy shader filename. Runtime support is capability- and
 * bridge-dependent; pack loaders may recognize a domain before the current Minecraft bridge can
 * execute it. Callers must not infer execution support from enum presence alone.</p>
 */
public enum PrismRenderDomain {
    TERRAIN_OPAQUE,
    TERRAIN_CUTOUT,
    TERRAIN_TRANSLUCENT,
    ENTITY_OPAQUE,
    ENTITY_TRANSLUCENT,
    BLOCK_ENTITY,
    WATER,
    PARTICLE,
    SKY,
    CLOUD,
    WEATHER,
    HAND,
    SHADOW_CASTER
}
