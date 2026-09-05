package dev.dreamveil.prism.graph;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record PrismCompiledGraph(
        List<PrismPass> orderedPasses,
        Map<String, Set<String>> dependencies,
        Map<String, PrismResourceDescriptor> resources,
        Map<String, PrismResourceLifetime> lifetimes,
        PrismTransientAliasPlan transientPlan,
        List<PrismResourceTransition> transitions,
        Map<String, List<PrismResourceTransition>> incomingTransitionsByPass,
        Map<String, List<PrismResourceTransition>> outgoingTransitionsByPass) {

    public PrismCompiledGraph(
            List<PrismPass> orderedPasses,
            Map<String, Set<String>> dependencies,
            Map<String, PrismResourceDescriptor> resources,
            Map<String, PrismResourceLifetime> lifetimes,
            PrismTransientAliasPlan transientPlan,
            List<PrismResourceTransition> transitions) {
        this(
                orderedPasses,
                dependencies,
                resources,
                lifetimes,
                transientPlan,
                transitions,
                indexTransitions(transitions, true),
                indexTransitions(transitions, false));
    }

    public PrismCompiledGraph {
        orderedPasses = List.copyOf(orderedPasses);
        dependencies = Map.copyOf(dependencies);
        resources = Map.copyOf(resources);
        lifetimes = Map.copyOf(lifetimes);
        if (transientPlan == null) {
            throw new IllegalArgumentException("Transient alias plan must not be null");
        }
        transitions = List.copyOf(transitions == null ? List.of() : transitions);
        incomingTransitionsByPass = freezeTransitionIndex(incomingTransitionsByPass);
        outgoingTransitionsByPass = freezeTransitionIndex(outgoingTransitionsByPass);
    }

    public PrismResourceDescriptor requireResource(String name) {
        PrismResourceDescriptor descriptor = resources.get(name);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown Prism resource: " + name);
        }
        return descriptor;
    }

    public PrismResourceLifetime requireLifetime(String name) {
        PrismResourceLifetime lifetime = lifetimes.get(name);
        if (lifetime == null) {
            throw new IllegalArgumentException("Prism resource is unused or unknown: " + name);
        }
        return lifetime;
    }

    public List<PrismResourceTransition> transitionsTo(String passName) {
        return incomingTransitionsByPass.getOrDefault(passName, List.of());
    }

    public List<PrismResourceTransition> transitionsFrom(String passName) {
        return outgoingTransitionsByPass.getOrDefault(passName, List.of());
    }

    private static Map<String, List<PrismResourceTransition>> indexTransitions(
            List<PrismResourceTransition> transitions,
            boolean incoming) {
        Map<String, List<PrismResourceTransition>> result = new LinkedHashMap<>();
        for (PrismResourceTransition transition : transitions == null ? List.<PrismResourceTransition>of() : transitions) {
            String pass = incoming ? transition.toPass() : transition.fromPass();
            if (pass.isEmpty()) continue;
            result.computeIfAbsent(pass, ignored -> new ArrayList<>()).add(transition);
        }
        return result;
    }

    private static Map<String, List<PrismResourceTransition>> freezeTransitionIndex(
            Map<String, List<PrismResourceTransition>> index) {
        if (index == null || index.isEmpty()) return Map.of();
        Map<String, List<PrismResourceTransition>> frozen = new LinkedHashMap<>();
        index.forEach((pass, values) -> frozen.put(pass, List.copyOf(values)));
        return Map.copyOf(frozen);
    }
}
