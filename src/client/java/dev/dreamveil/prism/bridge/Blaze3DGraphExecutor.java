package dev.dreamveil.prism.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.mojang.blaze3d.systems.CommandEncoder;

import dev.dreamveil.prism.graph.PrismCompiledGraph;
import dev.dreamveil.prism.graph.PrismPass;
import dev.dreamveil.prism.graph.PrismResourceDescriptor;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/** Executes a compiled Prism graph using Blaze3D-backed physical slot resources. */
public final class Blaze3DGraphExecutor {
    private final Map<String, Blaze3DPassExecutor> executors = new LinkedHashMap<>();
    private final Blaze3DHazardValidator hazardValidator = new Blaze3DHazardValidator();

    public void register(String passName, Blaze3DPassExecutor executor) {
        requireName(passName);
        Objects.requireNonNull(executor, "executor");
        if (executors.putIfAbsent(passName, executor) != null) {
            throw new IllegalStateException("Blaze3D executor already registered for pass '" + passName + "'");
        }
    }

    public void unregister(String passName) {
        if (passName != null) {
            executors.remove(passName);
        }
    }

    public void execute(
            PrismCompiledGraph graph,
            Blaze3DFrameResources resources,
            LevelRenderContext levelRenderContext,
            Blaze3DTransientResourcePool transientPool,
            CommandEncoder commandEncoder,
            double dynamicScale) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(levelRenderContext, "levelRenderContext");
        Objects.requireNonNull(transientPool, "transientPool");

        resources.validateImportedForExecution(graph);
        transientPool.prepareFrame(resources.referenceWidth(), resources.referenceHeight(), dynamicScale);

        Map<Integer, Blaze3DPhysicalResource> slotResources = new LinkedHashMap<>();
        try {
            for (Map.Entry<Integer, PrismResourceDescriptor> entry
                    : graph.transientPlan().representativeBySlot().entrySet()) {
                slotResources.put(entry.getKey(), transientPool.acquire(entry.getValue()));
            }

            for (Map.Entry<String, Integer> entry : graph.transientPlan().slotByResource().entrySet()) {
                Blaze3DPhysicalResource physical = slotResources.get(entry.getValue());
                if (physical == null) {
                    throw new IllegalStateException("Missing physical Prism slot " + entry.getValue());
                }
                resources.bindTransient(entry.getKey(), physical);
            }

            for (PrismPass pass : graph.orderedPasses()) {
                hazardValidator.validateBeforePass(graph, pass, resources);
                Blaze3DPassExecutor executor = executors.get(pass.name());
                if (executor == null) {
                    throw new IllegalStateException(
                            "No Blaze3D executor registered for compiled Prism pass '" + pass.name() + "'");
                }
                executor.execute(new Blaze3DPassContext(
                        pass,
                        resources,
                        levelRenderContext,
                        commandEncoder,
                        graph.transitionsTo(pass.name()),
                        graph.transitionsFrom(pass.name())));
            }
        } finally {
            resources.clearTransientBindings();
            for (Blaze3DPhysicalResource resource : slotResources.values()) {
                transientPool.release(resource);
            }
        }
    }

    public void clear() {
        executors.clear();
    }

    private static void requireName(String passName) {
        if (passName == null || passName.isBlank()) {
            throw new IllegalArgumentException("Pass name must not be blank");
        }
    }
}
