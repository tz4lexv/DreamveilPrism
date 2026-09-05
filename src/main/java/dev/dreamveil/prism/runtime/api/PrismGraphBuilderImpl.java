package dev.dreamveil.prism.runtime.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import dev.dreamveil.prism.api.graph.PrismGraphBuilder;
import dev.dreamveil.prism.api.graph.PrismGraphPlan;
import dev.dreamveil.prism.api.graph.PrismPassBuilder;
import dev.dreamveil.prism.api.resource.PrismBufferDescriptor;
import dev.dreamveil.prism.api.resource.PrismBufferHandle;
import dev.dreamveil.prism.api.resource.PrismResourceHandle;
import dev.dreamveil.prism.api.resource.PrismResourceId;
import dev.dreamveil.prism.api.resource.PrismResources;
import dev.dreamveil.prism.api.resource.PrismTextureDescriptor;
import dev.dreamveil.prism.api.resource.PrismTextureHandle;
import dev.dreamveil.prism.graph.PrismCompiledGraph;
import dev.dreamveil.prism.graph.PrismHostResources;
import dev.dreamveil.prism.graph.PrismPass;
import dev.dreamveil.prism.graph.PrismRenderGraph;
import dev.dreamveil.prism.graph.PrismResourceDescriptor;
import dev.dreamveil.prism.graph.PrismResourceRef;

final class PrismGraphBuilderImpl implements PrismGraphBuilder {
    private final PrismResourceId graphId;
    private final PrismRenderGraph graph = new PrismRenderGraph();
    private final Map<PrismResourceId, PrismResourceHandle> declared = new LinkedHashMap<>();
    private final List<PrismResourceId> passIds = new ArrayList<>();
    private boolean compiled;

    PrismGraphBuilderImpl(PrismResourceId graphId) {
        this.graphId = Objects.requireNonNull(graphId, "graphId");
    }

    @Override
    public PrismGraphBuilder importTexture(PrismTextureHandle resource) {
        ensureMutable();
        Objects.requireNonNull(resource, "resource");
        if (!resource.equals(PrismResources.MAIN_COLOR)
                && !resource.equals(PrismResources.MAIN_DEPTH)
                && !resource.equals(PrismResources.MODEL_MOTION)
                && !resource.equals(PrismResources.SCENE_HIERARCHICAL_DEPTH)) {
            throw new UnsupportedOperationException(
                    "Prism API 1.19 can currently import only main scene color/depth/HZB/model motion: "
                            + resource.id());
        }
        registerHandle(resource);
        graph.importResource(PrismResourceDescriptor.importedTexture(toInternalName(resource)));
        return this;
    }

    @Override
    public PrismTextureHandle transientTexture(PrismResourceId id, PrismTextureDescriptor descriptor) {
        ensureMutable();
        Objects.requireNonNull(id, "id");
        requireCreatorNamespace(id);
        PrismTextureHandle handle = new PrismTextureHandle(id);
        registerHandle(handle);
        graph.declareResource(PrismResourceDescriptor.transientTexture(
                id.toString(),
                PrismApiAdapters.textureDescriptor(Objects.requireNonNull(descriptor, "descriptor"))));
        return handle;
    }

    @Override
    public PrismBufferHandle transientBuffer(PrismResourceId id, PrismBufferDescriptor descriptor) {
        ensureMutable();
        Objects.requireNonNull(id, "id");
        requireCreatorNamespace(id);
        PrismBufferHandle handle = new PrismBufferHandle(id);
        registerHandle(handle);
        graph.declareResource(PrismResourceDescriptor.transientBuffer(
                id.toString(),
                PrismApiAdapters.bufferDescriptor(Objects.requireNonNull(descriptor, "descriptor"))));
        return handle;
    }

    @Override
    public PrismGraphBuilder pass(PrismResourceId id, Consumer<PrismPassBuilder> declaration) {
        ensureMutable();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(declaration, "declaration");
        if (passIds.contains(id)) {
            throw new IllegalArgumentException("Duplicate Prism creator pass: " + id);
        }

        PrismPassBuilderImpl builder = new PrismPassBuilderImpl();
        declaration.accept(builder);
        List<PrismResourceRef> refs = new ArrayList<>();
        builder.accesses().forEach((handle, access) -> {
            PrismResourceHandle declaredHandle = declared.get(handle.id());
            if (declaredHandle == null) {
                throw new IllegalStateException(
                        "Pass '" + id + "' references resource that is not declared/imported in graph '"
                                + graphId + "': " + handle.id());
            }
            if (!declaredHandle.getClass().equals(handle.getClass())) {
                throw new IllegalStateException("Resource handle type mismatch for " + handle.id());
            }
            refs.add(new PrismResourceRef(
                    toInternalName(handle),
                    PrismApiAdapters.resourceAccess(access)));
        });

        passIds.add(id);
        graph.addPass(new PrismPass(id.toString(), refs));
        return this;
    }

    @Override
    public PrismGraphPlan compile() {
        ensureMutable();
        PrismCompiledGraph compiledGraph = graph.compile();
        compiled = true;

        List<PrismResourceId> orderedPasses = compiledGraph.orderedPasses().stream()
                .map(PrismPass::name)
                .map(PrismResourceId::parse)
                .toList();
        Map<PrismResourceId, Integer> slots = new LinkedHashMap<>();
        compiledGraph.transientPlan().slotByResource().forEach((name, slot) ->
                slots.put(PrismResourceId.parse(name), slot));

        return new PrismGraphPlan(
                graphId,
                orderedPasses,
                slots,
                compiledGraph.transientPlan().slotCount());
    }

    private void registerHandle(PrismResourceHandle handle) {
        PrismResourceHandle previous = declared.putIfAbsent(handle.id(), handle);
        if (previous != null && !previous.equals(handle)) {
            throw new IllegalArgumentException("Conflicting Prism resource declaration: " + handle.id());
        }
    }

    private static String toInternalName(PrismResourceHandle handle) {
        if (handle.equals(PrismResources.MODEL_MOTION)) return PrismHostResources.MODEL_MOTION;
        if (handle.equals(PrismResources.MAIN_COLOR)) {
            return PrismHostResources.MAIN_COLOR;
        }
        if (handle.equals(PrismResources.MAIN_DEPTH)) {
            return PrismHostResources.MAIN_DEPTH;
        }
        if (handle.equals(PrismResources.SCENE_HIERARCHICAL_DEPTH)) {
            return PrismHostResources.SCENE_HIERARCHICAL_DEPTH;
        }
        return handle.id().toString();
    }

    private static void requireCreatorNamespace(PrismResourceId id) {
        if (id.namespace().equals("minecraft")) {
            throw new IllegalArgumentException(
                    "The 'minecraft' namespace is reserved for host resources in Prism API: " + id);
        }
    }

    private void ensureMutable() {
        if (compiled) {
            throw new IllegalStateException("Prism graph builder cannot be mutated after compile(): " + graphId);
        }
    }
}
