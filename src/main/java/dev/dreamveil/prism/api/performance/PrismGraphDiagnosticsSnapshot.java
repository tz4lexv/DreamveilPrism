package dev.dreamveil.prism.api.performance;

import java.util.List;

/** Immutable graph plan built only when a generation is installed; reads do no per-frame graph work. */
public record PrismGraphDiagnosticsSnapshot(
        boolean available,
        String packId,
        List<PrismGraphPassSnapshot> passes,
        List<PrismGraphResourceSnapshot> resources,
        List<PrismGraphTransitionSnapshot> transitions,
        int logicalTransientResources,
        int physicalTransientSlots) {
    public static final PrismGraphDiagnosticsSnapshot EMPTY = new PrismGraphDiagnosticsSnapshot(
            false, "", List.of(), List.of(), List.of(), 0, 0);

    public PrismGraphDiagnosticsSnapshot {
        packId = packId == null ? "" : packId;
        passes = List.copyOf(passes == null ? List.of() : passes);
        resources = List.copyOf(resources == null ? List.of() : resources);
        transitions = List.copyOf(transitions == null ? List.of() : transitions);
        if (logicalTransientResources < 0 || physicalTransientSlots < 0
                || physicalTransientSlots > logicalTransientResources) {
            throw new IllegalArgumentException("Invalid graph transient allocation counts");
        }
    }

    public int aliasSavings() {
        return logicalTransientResources - physicalTransientSlots;
    }

    public long hazardCount(String hazard) {
        return transitions.stream().filter(value -> value.hazard().equals(hazard)).count();
    }
}
