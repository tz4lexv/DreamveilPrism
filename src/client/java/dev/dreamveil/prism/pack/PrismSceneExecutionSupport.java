package dev.dreamveil.prism.pack;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import dev.dreamveil.prism.api.render.PrismRenderDomain;

/** Audited scene-domain execution table for the Minecraft 26.2 bridge. */
final class PrismSceneExecutionSupport {
    static final String SHADOW_AUXILIARY_VIEW_BLOCKER = "shadow_auxiliary_view_unavailable_26_2";

    enum Mode {
        STATIC_TERRAIN,
        DYNAMIC_FEATURE,
        UNAVAILABLE
    }

    private static final Map<PrismSceneDomain, Mode> MODES;
    private static final Set<PrismRenderDomain> PUBLIC_DOMAINS;

    static {
        EnumMap<PrismSceneDomain, Mode> modes = new EnumMap<>(PrismSceneDomain.class);
        for (PrismSceneDomain domain : PrismSceneDomain.values()) modes.put(domain, Mode.UNAVAILABLE);
        modes.put(PrismSceneDomain.TERRAIN_SOLID, Mode.STATIC_TERRAIN);
        modes.put(PrismSceneDomain.TERRAIN_CUTOUT, Mode.STATIC_TERRAIN);
        modes.put(PrismSceneDomain.TERRAIN_TRANSLUCENT, Mode.STATIC_TERRAIN);
        modes.put(PrismSceneDomain.ENTITY_OPAQUE, Mode.DYNAMIC_FEATURE);
        modes.put(PrismSceneDomain.ENTITY_TRANSLUCENT, Mode.DYNAMIC_FEATURE);
        modes.put(PrismSceneDomain.BLOCK_ENTITY, Mode.DYNAMIC_FEATURE);
        MODES = Collections.unmodifiableMap(modes);

        EnumSet<PrismRenderDomain> publicDomains = EnumSet.noneOf(PrismRenderDomain.class);
        for (var entry : MODES.entrySet()) {
            if (entry.getValue() != Mode.UNAVAILABLE) publicDomains.add(entry.getKey().publicDomain());
        }
        PUBLIC_DOMAINS = Collections.unmodifiableSet(publicDomains);
    }

    private PrismSceneExecutionSupport() {}

    static Mode mode(PrismSceneDomain domain) {
        return MODES.getOrDefault(java.util.Objects.requireNonNull(domain, "domain"), Mode.UNAVAILABLE);
    }

    static boolean supports(PrismSceneDomain domain) {
        return mode(domain) != Mode.UNAVAILABLE;
    }

    static boolean isStaticTerrain(PrismSceneDomain domain) {
        return mode(domain) == Mode.STATIC_TERRAIN;
    }

    static boolean isDynamicFeature(PrismSceneDomain domain) {
        return mode(domain) == Mode.DYNAMIC_FEATURE;
    }

    static Set<PrismRenderDomain> publicDomains() {
        return PUBLIC_DOMAINS;
    }

    static void requireSupported(PrismSceneDomain domain, String source) throws PrismPackLoadException {
        if (supports(domain)) return;
        if (domain == PrismSceneDomain.SHADOW_CASTER) {
            throw new PrismPackLoadException(
                    SHADOW_AUXILIARY_VIEW_BLOCKER,
                    "Scene domain 'shadow_caster' is not pack-addressable yet. Prism 0.14-alpha.5 owns an internal directional terrain shadow-caster path "
                            + "for shadow.depth bring-up, but a pack-authored shadow-caster pipeline is still guarded until "
                            + "the dedicated depth-only shader contract and entity/block-entity replay paths are defined. Recursive LevelRenderer.render remains forbidden.",
                    source);
        }
        throw new PrismPackLoadException(
                "scene_domain_unavailable",
                "Scene domain '" + domain.manifestName() + "' is recognized by Prism ProgramSet but the Minecraft 26.2 bridge "
                        + "does not yet own a verified execution path for it. Supported execution domains: "
                        + PUBLIC_DOMAINS.stream().map(d -> d.name().toLowerCase(java.util.Locale.ROOT)).sorted().collect(Collectors.joining(", ")) + ".",
                source);
    }

    static void requireStaticTerrainUnchecked(PrismSceneDomain domain) {
        if (!isStaticTerrain(domain)) {
            throw new IllegalStateException("No fixed vanilla terrain template for scene domain: " + domain.manifestName());
        }
    }
}
