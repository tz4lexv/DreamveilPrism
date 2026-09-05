package dev.dreamveil.prism.graph;

/**
 * One required logical transition between graph passes.
 *
 * Backends use this plan to validate that their native abstraction observes the
 * required attachment/sample ordering. Prism does not expose Vulkan/OpenGL barriers.
 */
public record PrismResourceTransition(
        String resourceName,
        String fromPass,
        String toPass,
        PrismResourceType resourceType,
        PrismPassExecutionType fromExecutionType,
        PrismPassExecutionType toExecutionType,
        PrismResourceUsageState fromState,
        PrismResourceUsageState toState,
        PrismResourceHazard hazard) {

    public PrismResourceTransition {
        if (resourceName == null || resourceName.isBlank()) {
            throw new IllegalArgumentException("Transition resource name must not be blank");
        }
        if (toPass == null || toPass.isBlank()) {
            throw new IllegalArgumentException("Transition destination pass must not be blank");
        }
        if (resourceType == null || fromExecutionType == null || toExecutionType == null
                || fromState == null || toState == null || hazard == null) {
            throw new IllegalArgumentException("Transition type/execution/states/hazard must not be null");
        }
        fromPass = fromPass == null ? "" : fromPass;
    }

    public boolean requiresSynchronization() {
        return hazard != PrismResourceHazard.NONE || fromState != toState;
    }
}
