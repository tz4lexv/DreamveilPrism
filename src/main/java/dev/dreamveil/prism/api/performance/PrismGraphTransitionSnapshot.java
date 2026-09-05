package dev.dreamveil.prism.api.performance;

/** One resource state/hazard edge in the creator render-graph debugger. */
public record PrismGraphTransitionSnapshot(
        String resourceName,
        String resourceType,
        String fromPass,
        String toPass,
        String fromExecutionType,
        String toExecutionType,
        String fromState,
        String toState,
        String hazard,
        boolean requiresSynchronization) {
    public PrismGraphTransitionSnapshot {
        resourceName = safe(resourceName);
        resourceType = safe(resourceType);
        fromPass = safe(fromPass);
        toPass = safe(toPass);
        fromExecutionType = safe(fromExecutionType);
        toExecutionType = safe(toExecutionType);
        fromState = safe(fromState);
        toState = safe(toState);
        hazard = safe(hazard);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
