package dev.dreamveil.prism.api.render;

import java.util.Set;

/** Immutable creator/debug snapshot of the currently committed world-rendering generation. */
public record PrismWorldPipelineSnapshot(
        long generation,
        String packId,
        PrismWorldRenderPhase phase,
        Set<PrismRenderDomain> activeDomains,
        boolean vanillaFallback) {

    public static final PrismWorldPipelineSnapshot VANILLA = new PrismWorldPipelineSnapshot(
            0L, "", PrismWorldRenderPhase.INACTIVE, Set.of(), true);

    public PrismWorldPipelineSnapshot {
        if (generation < 0L) throw new IllegalArgumentException("generation must be >= 0");
        packId = packId == null ? "" : packId;
        if (phase == null) throw new IllegalArgumentException("phase must not be null");
        activeDomains = Set.copyOf(activeDomains);
        if (vanillaFallback && !activeDomains.isEmpty()) {
            throw new IllegalArgumentException("vanilla fallback cannot publish active Prism domains");
        }
    }

    public boolean hasDomain(PrismRenderDomain domain) {
        return activeDomains.contains(domain);
    }
}
