package dev.dreamveil.prism.runtime.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.dreamveil.prism.api.performance.PrismPassTiming;
import dev.dreamveil.prism.api.performance.PrismPerformanceApi;
import dev.dreamveil.prism.api.performance.PrismPerformanceSnapshot;
import dev.dreamveil.prism.api.performance.PrismGpuPassTiming;
import dev.dreamveil.prism.api.performance.PrismGpuPerformanceSnapshot;
import dev.dreamveil.prism.api.performance.PrismGraphDiagnosticsSnapshot;
import dev.dreamveil.prism.api.performance.PrismGraphPassSnapshot;
import dev.dreamveil.prism.api.performance.PrismGraphResourceSnapshot;
import dev.dreamveil.prism.api.performance.PrismGraphTransitionSnapshot;
import dev.dreamveil.prism.api.performance.PrismPipelineCacheSnapshot;
import dev.dreamveil.prism.api.performance.PrismResourceLifetimeSnapshot;
import dev.dreamveil.prism.api.performance.PrismShadowCullingSnapshot;
import dev.dreamveil.prism.graph.PrismCompiledGraph;
import dev.dreamveil.prism.graph.PrismResourceDescriptor;

/** Mutable low-allocation recorder; immutable objects are built only when creator tooling asks for a snapshot. */
public final class PrismPerformanceApiImpl implements PrismPerformanceApi {
    private static final double EWMA_ALPHA = 0.10;

    private final Map<String, MutableTiming> passes = new LinkedHashMap<>();
    private final Map<String, MutableTiming> gpuPasses = new LinkedHashMap<>();
    private boolean packActive;
    private String packId = "";
    private int pipelineCount;
    private int logicalTransientResources;
    private int physicalTransientSlots;
    private long lastPackCpuNanos;
    private double averagePackCpuNanos;
    private long maxPackCpuNanos;
    private long samples;
    private long compileGenerations;
    private long successfulCompiles;
    private long reusedPipelines;
    private long compiledPipelines;
    private long cachedFailuresSkipped;
    private long generationsInstalled;
    private long generationsRetired;
    private int liveGenerations;
    private int pendingGenerations;
    private long pipelineBindingsInstalled;
    private long pipelineBindingsRetired;
    private int livePipelineBindings;
    private long samplersCreated;
    private long samplersClosed;
    private int liveSamplers;
    private long transientTexturesCreated;
    private long transientTexturesClosed;
    private long transientTextureViewsCreated;
    private long transientTextureViewsClosed;
    private long transientBuffersCreated;
    private long transientBuffersClosed;
    private long transientReuseHits;
    private int transientCachedResources;
    private int transientActiveResources;
    private int sessionCachedPipelines;
    private PrismShadowCullingSnapshot shadowCulling = PrismShadowCullingSnapshot.EMPTY;
    private PrismGraphDiagnosticsSnapshot graphSnapshot = PrismGraphDiagnosticsSnapshot.EMPTY;

    public synchronized void configure(
            String packId,
            List<String> passNames,
            int logicalTransientResources,
            int physicalTransientSlots) {
        this.packActive = true;
        this.packId = packId == null ? "" : packId;
        this.pipelineCount = passNames.size();
        this.logicalTransientResources = Math.max(0, logicalTransientResources);
        this.physicalTransientSlots = Math.max(0, physicalTransientSlots);
        passes.clear();
        gpuPasses.clear();
        for (String passName : passNames) {
            passes.put(passName, new MutableTiming());
            gpuPasses.put(passName, new MutableTiming());
        }
        lastPackCpuNanos = 0L;
        averagePackCpuNanos = 0.0;
        maxPackCpuNanos = 0L;
        samples = 0L;
        shadowCulling = PrismShadowCullingSnapshot.EMPTY;
    }

    /** Builds immutable debug rows once per successful generation, never in the frame loop. */
    public synchronized void configureGraph(String packId, PrismCompiledGraph graph) {
        List<PrismGraphPassSnapshot> passRows = graph.orderedPasses().stream()
                .map(pass -> new PrismGraphPassSnapshot(
                        pass.name(),
                        pass.executionType().name(),
                        graph.transitionsTo(pass.name()).size(),
                        graph.transitionsFrom(pass.name()).size(),
                        List.copyOf(graph.dependencies().getOrDefault(pass.name(), java.util.Set.of()))))
                .toList();
        List<PrismGraphResourceSnapshot> resourceRows = graph.resources().values().stream()
                .sorted(java.util.Comparator.comparing(PrismResourceDescriptor::name))
                .map(resource -> {
                    var lifetime = graph.lifetimes().get(resource.name());
                    int slot = resource.imported() ? -1 : graph.transientPlan().slotFor(resource.name());
                    return new PrismGraphResourceSnapshot(
                            resource.name(),
                            resource.type().name(),
                            resource.imported(),
                            lifetime == null ? -1 : lifetime.firstUsePass(),
                            lifetime == null ? -1 : lifetime.lastUsePass(),
                            slot,
                            describe(resource));
                })
                .toList();
        List<PrismGraphTransitionSnapshot> transitionRows = graph.transitions().stream()
                .map(transition -> new PrismGraphTransitionSnapshot(
                        transition.resourceName(),
                        transition.resourceType().name(),
                        transition.fromPass(),
                        transition.toPass(),
                        transition.fromExecutionType().name(),
                        transition.toExecutionType().name(),
                        transition.fromState().name(),
                        transition.toState().name(),
                        transition.hazard().name(),
                        transition.requiresSynchronization()))
                .toList();
        graphSnapshot = new PrismGraphDiagnosticsSnapshot(
                true,
                packId,
                passRows,
                resourceRows,
                transitionRows,
                graph.transientPlan().slotByResource().size(),
                graph.transientPlan().slotCount());
    }

    private static String describe(PrismResourceDescriptor resource) {
        if (resource.imported()) return "host-owned";
        if (resource.textureDesc() != null) {
            var texture = resource.textureDesc();
            return texture.format() + " " + texture.extent() + " mips=" + texture.mipLevels()
                    + " usage=" + texture.usages();
        }
        var buffer = resource.bufferDesc();
        return buffer.sizeBytes() + " B usage=" + buffer.usages();
    }

    public synchronized void deactivate() {
        packActive = false;
        packId = "";
        pipelineCount = 0;
        logicalTransientResources = 0;
        physicalTransientSlots = 0;
        passes.clear();
        gpuPasses.clear();
        lastPackCpuNanos = 0L;
        averagePackCpuNanos = 0.0;
        maxPackCpuNanos = 0L;
        samples = 0L;
        shadowCulling = PrismShadowCullingSnapshot.EMPTY;
        graphSnapshot = PrismGraphDiagnosticsSnapshot.EMPTY;
    }

    public synchronized void recordPass(String passName, long nanos) {
        MutableTiming timing = passes.get(passName);
        if (timing != null) {
            timing.record(Math.max(0L, nanos));
        }
    }


    public synchronized void recordGpuPass(String passName, long nanos) {
        MutableTiming timing = gpuPasses.get(passName);
        if (timing != null) timing.record(Math.max(0L, nanos));
    }

    public synchronized void recordPackFrame(long nanos) {
        long safe = Math.max(0L, nanos);
        lastPackCpuNanos = safe;
        averagePackCpuNanos = samples == 0 ? safe : averagePackCpuNanos + EWMA_ALPHA * (safe - averagePackCpuNanos);
        maxPackCpuNanos = Math.max(maxPackCpuNanos, safe);
        samples++;
    }

    @Override
    public synchronized PrismPerformanceSnapshot snapshot() {
        if (!packActive) {
            return PrismPerformanceSnapshot.EMPTY;
        }
        List<PrismPassTiming> snapshot = new ArrayList<>(passes.size());
        passes.forEach((name, timing) -> snapshot.add(timing.snapshot(name)));
        return new PrismPerformanceSnapshot(
                true,
                packId,
                lastPackCpuNanos,
                averagePackCpuNanos,
                maxPackCpuNanos,
                samples,
                pipelineCount,
                logicalTransientResources,
                physicalTransientSlots,
                snapshot);
    }


    @Override
    public synchronized PrismGpuPerformanceSnapshot gpuSnapshot() {
        if (!packActive) return PrismGpuPerformanceSnapshot.EMPTY;
        List<PrismGpuPassTiming> snapshot = new ArrayList<>(gpuPasses.size());
        gpuPasses.forEach((name, timing) -> {
            if (timing.samples > 0) snapshot.add(new PrismGpuPassTiming(name, timing.last, timing.average, timing.max, timing.samples));
        });
        return new PrismGpuPerformanceSnapshot(!snapshot.isEmpty(), packId, snapshot);
    }

    public synchronized void recordCompileGeneration() {
        compileGenerations++;
    }

    public synchronized void recordCompileSuccess(int reused, int compiled) {
        successfulCompiles++;
        reusedPipelines += Math.max(0, reused);
        compiledPipelines += Math.max(0, compiled);
    }

    public synchronized void recordCachedFailureSkip() {
        cachedFailuresSkipped++;
    }

    @Override
    public synchronized PrismPipelineCacheSnapshot pipelineCacheSnapshot() {
        return new PrismPipelineCacheSnapshot(
                compileGenerations,
                successfulCompiles,
                reusedPipelines,
                compiledPipelines,
                cachedFailuresSkipped);
    }


    public synchronized void recordPendingGenerationStarted() {
        pendingGenerations++;
    }

    public synchronized void recordPendingGenerationFinished() {
        pendingGenerations = Math.max(0, pendingGenerations - 1);
    }

    public synchronized void recordGenerationInstalled(int pipelineBindings) {
        generationsInstalled++;
        liveGenerations++;
        int safe = Math.max(0, pipelineBindings);
        pipelineBindingsInstalled += safe;
        livePipelineBindings += safe;
    }

    public synchronized void recordGenerationRetired(int pipelineBindings) {
        generationsRetired++;
        liveGenerations = Math.max(0, liveGenerations - 1);
        int safe = Math.max(0, pipelineBindings);
        pipelineBindingsRetired += safe;
        livePipelineBindings = Math.max(0, livePipelineBindings - safe);
    }

    public synchronized void recordSamplerCreated() {
        samplersCreated++;
        liveSamplers++;
    }

    public synchronized void recordSamplerClosed() {
        samplersClosed++;
        liveSamplers = Math.max(0, liveSamplers - 1);
    }

    public synchronized void recordTransientPool(
            long createdTextures,
            long closedTextures,
            long createdTextureViews,
            long closedTextureViews,
            long createdBuffers,
            long closedBuffers,
            long reuseHits,
            int cachedResources,
            int activeResources) {
        transientTexturesCreated = Math.max(0, createdTextures);
        transientTexturesClosed = Math.max(0, closedTextures);
        transientTextureViewsCreated = Math.max(0, createdTextureViews);
        transientTextureViewsClosed = Math.max(0, closedTextureViews);
        transientBuffersCreated = Math.max(0, createdBuffers);
        transientBuffersClosed = Math.max(0, closedBuffers);
        transientReuseHits = Math.max(0, reuseHits);
        transientCachedResources = Math.max(0, cachedResources);
        transientActiveResources = Math.max(0, activeResources);
    }

    public synchronized void recordSessionCachedPipelines(int count) {
        sessionCachedPipelines = Math.max(0, count);
    }

    public synchronized void recordShadowCullingFrame(
            long frameIndex,
            int candidates,
            int accepted,
            int culled,
            int drawCalls,
            boolean gpuTimeAvailable,
            long gpuTimeNanos) {
        shadowCulling = new PrismShadowCullingSnapshot(
                true,
                frameIndex,
                candidates,
                accepted,
                culled,
                drawCalls,
                gpuTimeAvailable,
                gpuTimeAvailable ? Math.max(0L, gpuTimeNanos) : 0L);
    }

    /**
     * Publishes extraction-time light visibility without erasing the most recent completed
     * shadow-pass draw/timestamp sample. Extraction can run before drawing for the same frame;
     * replacing those fields with zero made the F3 overlay report "Shadow draws: 0" even while
     * the GPU pass was actively submitting independent terrain draws.
     */
    public synchronized void recordShadowVisibilityFrame(
            long frameIndex,
            int candidates,
            int accepted,
            int culled) {
        int drawCalls = shadowCulling.available() ? shadowCulling.drawCalls() : 0;
        boolean gpuTimeAvailable = shadowCulling.available() && shadowCulling.gpuTimeAvailable();
        long gpuTimeNanos = gpuTimeAvailable ? shadowCulling.gpuTimeNanos() : 0L;
        shadowCulling = new PrismShadowCullingSnapshot(
                true,
                frameIndex,
                candidates,
                accepted,
                culled,
                drawCalls,
                gpuTimeAvailable,
                gpuTimeNanos);
    }

    public synchronized void clearShadowCullingFrame() {
        shadowCulling = PrismShadowCullingSnapshot.EMPTY;
    }

    @Override
    public synchronized PrismShadowCullingSnapshot shadowCullingSnapshot() {
        return shadowCulling;
    }

    @Override
    public synchronized PrismGraphDiagnosticsSnapshot graphSnapshot() {
        return graphSnapshot;
    }

    @Override
    public synchronized PrismResourceLifetimeSnapshot resourceLifetimeSnapshot() {
        return new PrismResourceLifetimeSnapshot(
                generationsInstalled,
                generationsRetired,
                liveGenerations,
                pendingGenerations,
                pipelineBindingsInstalled,
                pipelineBindingsRetired,
                livePipelineBindings,
                samplersCreated,
                samplersClosed,
                liveSamplers,
                transientTexturesCreated,
                transientTexturesClosed,
                transientTextureViewsCreated,
                transientTextureViewsClosed,
                transientBuffersCreated,
                transientBuffersClosed,
                transientReuseHits,
                transientCachedResources,
                transientActiveResources,
                sessionCachedPipelines);
    }

    private static final class MutableTiming {
        long last;
        double average;
        long max;
        long samples;

        void record(long nanos) {
            last = nanos;
            average = samples == 0 ? nanos : average + EWMA_ALPHA * (nanos - average);
            max = Math.max(max, nanos);
            samples++;
        }

        PrismPassTiming snapshot(String name) {
            return new PrismPassTiming(name, last, average, max, samples);
        }
    }
}
