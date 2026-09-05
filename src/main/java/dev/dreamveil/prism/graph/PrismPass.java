package dev.dreamveil.prism.graph;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record PrismPass(
        String name,
        List<PrismResourceRef> resources,
        PrismPassExecutionType executionType,
        List<String> after) {
    public PrismPass {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Pass name must not be blank");
        }
        resources = List.copyOf(resources == null ? List.of() : resources);
        executionType = executionType == null ? PrismPassExecutionType.UNKNOWN : executionType;
        after = List.copyOf(after == null ? List.of() : after);
        if (after.stream().anyMatch(value -> value == null || value.isBlank() || value.equals(name))
                || new HashSet<>(after).size() != after.size()) {
            throw new IllegalArgumentException("Invalid explicit dependencies for pass " + name);
        }

        Set<String> seenResources = new HashSet<>();
        for (PrismResourceRef resource : resources) {
            if (resource == null) {
                throw new IllegalArgumentException("Pass resources must not contain null entries");
            }
            if (!seenResources.add(resource.name())) {
                throw new IllegalArgumentException(
                        "Pass '" + name + "' references resource '" + resource.name()
                                + "' more than once; use READ_WRITE for combined access");
            }
        }
    }

    public PrismPass(String name, List<PrismResourceRef> resources) {
        this(name, resources, PrismPassExecutionType.UNKNOWN);
    }

    public PrismPass(String name, List<PrismResourceRef> resources, PrismPassExecutionType executionType) {
        this(name, resources, executionType, List.of());
    }

    public static PrismPass of(String name, PrismResourceRef... resources) {
        return new PrismPass(name, List.of(resources), PrismPassExecutionType.UNKNOWN);
    }
}
