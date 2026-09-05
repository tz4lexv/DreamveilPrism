package dev.dreamveil.prism.api.graph;

import java.util.List;
import java.util.Map;

import dev.dreamveil.prism.api.resource.PrismResourceId;

/** Backend-neutral compiled graph metadata safe to expose to creator tooling. */
public record PrismGraphPlan(
        PrismResourceId id,
        List<PrismResourceId> orderedPasses,
        Map<PrismResourceId, Integer> transientSlots,
        int physicalTransientSlotCount) {

    public PrismGraphPlan {
        if (id == null || orderedPasses == null || transientSlots == null) {
            throw new IllegalArgumentException("Graph plan fields must not be null");
        }
        orderedPasses = List.copyOf(orderedPasses);
        transientSlots = Map.copyOf(transientSlots);
        if (physicalTransientSlotCount < 0) {
            throw new IllegalArgumentException("physicalTransientSlotCount must be >= 0");
        }
    }
}
