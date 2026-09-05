package dev.dreamveil.prism.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Backend-neutral dependency compiler for Prism passes.
 *
 * v0.3 adds concrete transient resource descriptions, lifetime analysis and a
 * static alias plan. No Minecraft, OpenGL or Vulkan object is exposed here.
 */
public final class PrismRenderGraph {
    private final LinkedHashMap<String, PrismPass> passes = new LinkedHashMap<>();
    private final LinkedHashMap<String, PrismResourceDescriptor> resources = new LinkedHashMap<>();

    public PrismRenderGraph importResource(String name) {
        return importResource(PrismResourceDescriptor.importedTexture(name));
    }

    public PrismRenderGraph importResource(PrismResourceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (!descriptor.imported()) {
            throw new IllegalArgumentException(
                    "Imported resource descriptor must have imported=true: " + descriptor.name());
        }
        addResource(descriptor);
        return this;
    }

    public PrismRenderGraph declareResource(PrismResourceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.imported()) {
            throw new IllegalArgumentException(
                    "Declared transient resource must have imported=false: " + descriptor.name());
        }
        addResource(descriptor);
        return this;
    }

    public PrismRenderGraph addPass(PrismPass pass) {
        Objects.requireNonNull(pass, "pass");
        if (passes.putIfAbsent(pass.name(), pass) != null) {
            throw new IllegalArgumentException("Duplicate Prism pass: " + pass.name());
        }
        return this;
    }

    public PrismCompiledGraph compile() {
        validateReferencedResources();

        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        Map<String, String> lastWriter = new HashMap<>();
        Map<String, Set<String>> readersSinceWrite = new HashMap<>();

        for (PrismPass pass : passes.values()) {
            for (String dependency : pass.after()) {
                if (!passes.containsKey(dependency)) {
                    throw new IllegalArgumentException("Unknown dependency '" + dependency + "' for pass '" + pass.name() + "'");
                }
            }
            dependencies.put(pass.name(), new LinkedHashSet<>(pass.after()));

            for (PrismResourceRef resource : pass.resources()) {
                String resourceName = resource.name();
                PrismResourceDescriptor descriptor = resources.get(resourceName);

                switch (resource.access()) {
                    case READ -> {
                        String writer = lastWriter.get(resourceName);
                        if (writer != null) {
                            dependencies.get(pass.name()).add(writer);
                        } else if (!descriptor.imported()) {
                            throw new IllegalStateException(
                                    "Pass '" + pass.name() + "' reads transient resource '" + resourceName
                                            + "' before it is produced");
                        }
                        readersSinceWrite.computeIfAbsent(resourceName, ignored -> new LinkedHashSet<>())
                                .add(pass.name());
                    }
                    case WRITE, READ_WRITE -> {
                        String writer = lastWriter.get(resourceName);
                        if (resource.access() == PrismResourceAccess.READ_WRITE) {
                            if (writer != null) {
                                dependencies.get(pass.name()).add(writer);
                            } else if (!descriptor.imported()) {
                                throw new IllegalStateException(
                                        "Pass '" + pass.name() + "' read-writes transient resource '"
                                                + resourceName + "' before it is produced");
                            }
                        } else if (writer != null) {
                            dependencies.get(pass.name()).add(writer);
                        }

                        Set<String> readers = readersSinceWrite.get(resourceName);
                        if (readers != null) {
                            dependencies.get(pass.name()).addAll(readers);
                            readers.clear();
                        }
                        lastWriter.put(resourceName, pass.name());
                    }
                }
            }
            dependencies.get(pass.name()).remove(pass.name());
        }

        return finishCompile(dependencies);
    }

    /**
     * Creator-authored ordering: only a unique producer of a transient resource
     * proves a data-flow edge. Imported versions and conflicting writers require
     * a declared ordering path. Independent passes need no artificial dependency.
     */
    public PrismCompiledGraph compileExplicit() {
        validateReferencedResources();
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (PrismPass pass : passes.values()) {
            for (String dependency : pass.after()) {
                if (!passes.containsKey(dependency)) {
                    throw new IllegalArgumentException("Unknown dependency '" + dependency + "' for pass '" + pass.name() + "'");
                }
            }
            dependencies.put(pass.name(), new LinkedHashSet<>(pass.after()));
        }
        // Add every provable edge before inspecting ambiguity, independent of map order.
        for (PrismResourceDescriptor resource : resources.values()) {
            List<PrismPass> users = usersOf(resource.name());
            List<PrismPass> writers = users.stream().filter(p -> accessOf(p, resource.name()) != PrismResourceAccess.READ).toList();
            if (!resource.imported() && writers.size() == 1
                    && accessOf(writers.getFirst(), resource.name()) == PrismResourceAccess.WRITE) {
                String producer = writers.getFirst().name();
                for (PrismPass user : users) {
                    if (!user.name().equals(producer)) dependencies.get(user.name()).add(producer);
                }
            }
        }
        topologicalSort(dependencies); // Report cycles before asking for more declarations.
        for (PrismResourceDescriptor resource : resources.values()) {
            List<PrismPass> users = usersOf(resource.name());
            for (int i = 0; i < users.size(); i++) {
                for (int j = i + 1; j < users.size(); j++) {
                    PrismPass a = users.get(i), b = users.get(j);
                    if (accessOf(a, resource.name()) == PrismResourceAccess.READ
                            && accessOf(b, resource.name()) == PrismResourceAccess.READ) continue;
                    if (!dependsOn(a.name(), b.name(), dependencies)
                            && !dependsOn(b.name(), a.name(), dependencies)) {
                        throw new IllegalStateException("PRISM E2104: Pass ordering is ambiguous between '"
                                + a.name() + "' and '" + b.name() + "' for resource '" + resource.name()
                                + "'. Declare after in the intended direction; Prism will not choose a resource version or write order.");
                    }
                }
            }
        }
        Set<String> produced = new LinkedHashSet<>();
        for (PrismPass pass : topologicalSort(dependencies)) {
            for (PrismResourceRef ref : pass.resources()) {
                if (ref.access() != PrismResourceAccess.WRITE && !resources.get(ref.name()).imported()
                        && !produced.contains(ref.name())) {
                    throw new IllegalStateException("Pass '" + pass.name() + "' reads transient resource '"
                            + ref.name() + "' before it is produced");
                }
                if (ref.access() != PrismResourceAccess.READ) produced.add(ref.name());
            }
        }
        return finishCompile(dependencies);
    }

    private List<PrismPass> usersOf(String resource) {
        return passes.values().stream().filter(p -> p.resources().stream().anyMatch(r -> r.name().equals(resource))).toList();
    }

    private static PrismResourceAccess accessOf(PrismPass pass, String resource) {
        return pass.resources().stream().filter(r -> r.name().equals(resource)).findFirst().orElseThrow().access();
    }

    private static boolean dependsOn(String pass, String ancestor, Map<String, Set<String>> dependencies) {
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(dependencies.get(pass));
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (next.equals(ancestor)) return true;
            if (visited.add(next)) pending.addAll(dependencies.get(next));
        }
        return false;
    }

    private PrismCompiledGraph finishCompile(Map<String, Set<String>> dependencies) {
        List<PrismPass> orderedPasses = topologicalSort(dependencies);
        Map<String, PrismResourceLifetime> lifetimes = computeLifetimes(orderedPasses);
        validateTransientResourcesAreUsed(lifetimes);
        PrismTransientAliasPlan transientPlan = PrismTransientAliasPlan.build(resources, lifetimes);
        List<PrismResourceTransition> transitions = buildTransitions(orderedPasses);

        return new PrismCompiledGraph(
                orderedPasses,
                freezeDependencies(dependencies),
                resources,
                lifetimes,
                transientPlan,
                transitions);
    }


    private List<PrismResourceTransition> buildTransitions(List<PrismPass> orderedPasses) {
        Map<String, LastUse> lastUses = new LinkedHashMap<>();
        List<PrismResourceTransition> transitions = new ArrayList<>();

        for (PrismPass pass : orderedPasses) {
            for (PrismResourceRef ref : pass.resources()) {
                PrismResourceDescriptor descriptor = resources.get(ref.name());
                PrismResourceUsageState nextState = stateFor(descriptor, ref);
                validateUsageSupportsState(descriptor, nextState, pass.name());

                LastUse previous = lastUses.get(ref.name());
                PrismResourceUsageState previousState = previous == null
                        ? PrismResourceUsageState.UNDEFINED
                        : previous.state();
                PrismResourceHazard hazard = previous == null
                        ? PrismResourceHazard.NONE
                        : hazard(previous.access(), ref.access());

                transitions.add(new PrismResourceTransition(
                        ref.name(),
                        previous == null ? "" : previous.passName(),
                        pass.name(),
                        descriptor.type(),
                        previous == null ? PrismPassExecutionType.UNKNOWN : previous.executionType(),
                        pass.executionType(),
                        previousState,
                        nextState,
                        hazard));
                lastUses.put(ref.name(), new LastUse(
                        pass.name(), pass.executionType(), ref.access(), nextState));
            }
        }
        return List.copyOf(transitions);
    }

    private static PrismResourceUsageState stateFor(
            PrismResourceDescriptor descriptor,
            PrismResourceRef ref) {
        if (ref.requiredState() != null) return ref.requiredState();
        if (descriptor.type() == PrismResourceType.BUFFER) {
            return switch (ref.access()) {
                case READ -> PrismResourceUsageState.BUFFER_READ;
                case WRITE -> PrismResourceUsageState.BUFFER_WRITE;
                case READ_WRITE -> PrismResourceUsageState.BUFFER_READ_WRITE;
            };
        }

        boolean depth = descriptor.imported()
                ? PrismHostResources.MAIN_DEPTH.equals(descriptor.name())
                : descriptor.textureDesc().format().hasDepthAspect();
        return switch (ref.access()) {
            case READ -> PrismResourceUsageState.SAMPLED_READ;
            case WRITE -> depth
                    ? PrismResourceUsageState.DEPTH_ATTACHMENT_WRITE
                    : PrismResourceUsageState.COLOR_ATTACHMENT_WRITE;
            case READ_WRITE -> depth
                    ? PrismResourceUsageState.DEPTH_ATTACHMENT_READ_WRITE
                    : PrismResourceUsageState.COLOR_ATTACHMENT_READ_WRITE;
        };
    }

    private static PrismResourceHazard hazard(PrismResourceAccess previous, PrismResourceAccess next) {
        boolean previousWrites = previous != PrismResourceAccess.READ;
        boolean nextWrites = next != PrismResourceAccess.READ;
        if (previousWrites && !nextWrites) return PrismResourceHazard.READ_AFTER_WRITE;
        if (!previousWrites && nextWrites) return PrismResourceHazard.WRITE_AFTER_READ;
        if (previousWrites) return PrismResourceHazard.WRITE_AFTER_WRITE;
        return PrismResourceHazard.NONE;
    }

    private static void validateUsageSupportsState(
            PrismResourceDescriptor descriptor,
            PrismResourceUsageState state,
            String passName) {
        if (descriptor.imported() || descriptor.type() != PrismResourceType.TEXTURE) {
            return;
        }
        Set<PrismTextureUsage> usages = descriptor.textureDesc().usages();
        if (state == PrismResourceUsageState.SAMPLED_READ && !usages.contains(PrismTextureUsage.SAMPLED)) {
            throw new IllegalStateException(
                    "Pass '" + passName + "' samples transient texture '" + descriptor.name()
                            + "' without SAMPLED usage");
        }
        boolean storage = switch (state) {
            case STORAGE_IMAGE_READ, STORAGE_IMAGE_WRITE, STORAGE_IMAGE_READ_WRITE -> true;
            default -> false;
        };
        if (storage && !usages.contains(PrismTextureUsage.STORAGE)) {
            throw new IllegalStateException(
                    "Pass '" + passName + "' uses transient texture '" + descriptor.name()
                            + "' as storage without STORAGE usage");
        }
        boolean attachment = switch (state) {
            case COLOR_ATTACHMENT_WRITE, COLOR_ATTACHMENT_READ_WRITE,
                    DEPTH_ATTACHMENT_WRITE, DEPTH_ATTACHMENT_READ_WRITE -> true;
            default -> false;
        };
        if (attachment && !usages.contains(PrismTextureUsage.RENDER_ATTACHMENT)) {
            throw new IllegalStateException(
                    "Pass '" + passName + "' writes transient texture '" + descriptor.name()
                            + "' without RENDER_ATTACHMENT usage");
        }
    }

    private record LastUse(
            String passName,
            PrismPassExecutionType executionType,
            PrismResourceAccess access,
            PrismResourceUsageState state) {
    }

    private void addResource(PrismResourceDescriptor descriptor) {
        PrismResourceDescriptor previous = resources.putIfAbsent(descriptor.name(), descriptor);
        if (previous != null && !previous.equals(descriptor)) {
            throw new IllegalArgumentException(
                    "Conflicting Prism resource declaration for '" + descriptor.name() + "': "
                            + previous + " vs " + descriptor);
        }
    }

    private void validateReferencedResources() {
        for (PrismPass pass : passes.values()) {
            for (PrismResourceRef resource : pass.resources()) {
                if (!resources.containsKey(resource.name())) {
                    throw new IllegalStateException(
                            "Pass '" + pass.name() + "' references undeclared resource '"
                                    + resource.name() + "'");
                }
            }
        }
    }

    private void validateTransientResourcesAreUsed(Map<String, PrismResourceLifetime> lifetimes) {
        for (PrismResourceDescriptor descriptor : resources.values()) {
            if (!descriptor.imported() && !lifetimes.containsKey(descriptor.name())) {
                throw new IllegalStateException(
                        "Transient Prism resource is declared but never used: " + descriptor.name());
            }
        }
    }

    private static Map<String, PrismResourceLifetime> computeLifetimes(List<PrismPass> orderedPasses) {
        Map<String, Integer> firstUse = new LinkedHashMap<>();
        Map<String, Integer> lastUse = new LinkedHashMap<>();

        for (int passIndex = 0; passIndex < orderedPasses.size(); passIndex++) {
            for (PrismResourceRef ref : orderedPasses.get(passIndex).resources()) {
                firstUse.putIfAbsent(ref.name(), passIndex);
                lastUse.put(ref.name(), passIndex);
            }
        }

        Map<String, PrismResourceLifetime> result = new LinkedHashMap<>();
        firstUse.forEach((name, first) -> result.put(name, new PrismResourceLifetime(first, lastUse.get(name))));
        return Map.copyOf(result);
    }

    private List<PrismPass> topologicalSort(Map<String, Set<String>> dependencies) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, Set<String>> dependents = new LinkedHashMap<>();

        for (String pass : passes.keySet()) {
            indegree.put(pass, dependencies.get(pass).size());
            dependents.put(pass, new LinkedHashSet<>());
        }
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            for (String dependency : entry.getValue()) {
                dependents.get(dependency).add(entry.getKey());
            }
        }

        ArrayDeque<String> ready = new ArrayDeque<>();
        indegree.forEach((name, degree) -> {
            if (degree == 0) {
                ready.addLast(name);
            }
        });

        List<PrismPass> ordered = new ArrayList<>(passes.size());
        while (!ready.isEmpty()) {
            String name = ready.removeFirst();
            ordered.add(passes.get(name));
            for (String dependent : dependents.get(name)) {
                int degree = indegree.computeIfPresent(dependent, (ignored, value) -> value - 1);
                if (degree == 0) {
                    ready.addLast(dependent);
                }
            }
        }

        if (ordered.size() != passes.size()) {
            throw new IllegalStateException("Prism render graph contains a dependency cycle");
        }
        return ordered;
    }

    private static Map<String, Set<String>> freezeDependencies(Map<String, Set<String>> input) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        input.forEach((name, deps) -> result.put(name, Set.copyOf(deps)));
        return Map.copyOf(result);
    }
}
