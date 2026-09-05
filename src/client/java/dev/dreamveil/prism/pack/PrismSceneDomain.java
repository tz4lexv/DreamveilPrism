package dev.dreamveil.prism.pack;

import java.util.Locale;
import dev.dreamveil.prism.api.render.PrismRenderDomain;

/** Normalized scene domains. Recognition and execution support are deliberately separate. */
enum PrismSceneDomain {
    TERRAIN_SOLID("terrain_opaque", PrismRenderDomain.TERRAIN_OPAQUE),
    TERRAIN_CUTOUT("terrain_cutout", PrismRenderDomain.TERRAIN_CUTOUT),
    TERRAIN_TRANSLUCENT("terrain_translucent", PrismRenderDomain.TERRAIN_TRANSLUCENT),
    ENTITY_OPAQUE("entity_opaque", PrismRenderDomain.ENTITY_OPAQUE),
    ENTITY_TRANSLUCENT("entity_translucent", PrismRenderDomain.ENTITY_TRANSLUCENT),
    BLOCK_ENTITY("block_entity", PrismRenderDomain.BLOCK_ENTITY),
    WATER("water", PrismRenderDomain.WATER),
    PARTICLE("particle", PrismRenderDomain.PARTICLE),
    SKY("sky", PrismRenderDomain.SKY),
    CLOUD("cloud", PrismRenderDomain.CLOUD),
    WEATHER("weather", PrismRenderDomain.WEATHER),
    HAND("hand", PrismRenderDomain.HAND),
    SHADOW_CASTER("shadow_caster", PrismRenderDomain.SHADOW_CASTER);

    private final String manifestName;
    private final PrismRenderDomain publicDomain;

    PrismSceneDomain(String manifestName, PrismRenderDomain publicDomain) {
        this.manifestName = manifestName;
        this.publicDomain = publicDomain;
    }

    static PrismSceneDomain parse(String raw, String source) throws PrismPackLoadException {
        if (raw == null || raw.isBlank()) {
            throw new PrismPackLoadException("scene_domain_missing", "Scene programs must declare a domain", source);
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "terrain_opaque", "terrain_solid" -> TERRAIN_SOLID;
            case "terrain_cutout", "terrain_alpha_test" -> TERRAIN_CUTOUT;
            case "terrain_translucent" -> TERRAIN_TRANSLUCENT;
            case "entity_opaque", "entities_opaque" -> ENTITY_OPAQUE;
            case "entity_translucent", "entities_translucent" -> ENTITY_TRANSLUCENT;
            case "block_entity", "block_entities" -> BLOCK_ENTITY;
            case "water" -> WATER;
            case "particle", "particles" -> PARTICLE;
            case "sky" -> SKY;
            case "cloud", "clouds" -> CLOUD;
            case "weather" -> WEATHER;
            case "hand" -> HAND;
            case "shadow", "shadow_caster" -> SHADOW_CASTER;
            default -> throw new PrismPackLoadException(
                    "scene_domain_unsupported",
                    "Unsupported scene domain '" + raw + "'. Use a PrismRenderDomain semantic name.",
                    source);
        };
    }

    String manifestName() { return manifestName; }
    String fileStem() { return manifestName; }
    PrismRenderDomain publicDomain() { return publicDomain; }
    boolean isTerrainDomain() {
        return this == TERRAIN_SOLID || this == TERRAIN_CUTOUT || this == TERRAIN_TRANSLUCENT;
    }

}
