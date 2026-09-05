package dev.dreamveil.prism.api.performance;

/** One immutable pass row in the creator render-graph debugger. */
public record PrismGraphPassSnapshot(
        String name,
        String executionType,
        int incomingTransitions,
        int outgoingTransitions,
        java.util.List<String> dependencies) {
    public PrismGraphPassSnapshot {
        name = name == null ? "" : name;
        executionType = executionType == null ? "UNKNOWN" : executionType;
        dependencies = java.util.List.copyOf(dependencies == null ? java.util.List.of() : dependencies);
        if (incomingTransitions < 0 || outgoingTransitions < 0) {
            throw new IllegalArgumentException("Graph pass transition counts must be >= 0");
        }
    }
    public PrismGraphPassSnapshot(String name, String executionType, int incomingTransitions, int outgoingTransitions) {
        this(name, executionType, incomingTransitions, outgoingTransitions, java.util.List.of());
    }
}
