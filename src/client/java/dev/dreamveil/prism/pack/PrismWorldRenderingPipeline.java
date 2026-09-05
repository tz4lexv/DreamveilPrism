package dev.dreamveil.prism.pack;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

import org.joml.Vector4fc;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.bridge.Blaze3DFrameResources;
import dev.dreamveil.prism.api.render.PrismFeaturePipelineStats;
import dev.dreamveil.prism.api.render.PrismRenderDomain;
import dev.dreamveil.prism.api.render.PrismWorldPipelineSnapshot;
import dev.dreamveil.prism.api.render.PrismWorldRenderPhase;
import dev.dreamveil.prism.api.render.PrismVanillaRenderFeature;
import dev.dreamveil.prism.runtime.api.PrismPerformanceApiImpl;
import dev.dreamveil.prism.runtime.api.PrismWorldRenderApiImpl;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * Always-valid Minecraft 26.2 world-pipeline publication point.
 *
 * <p>Chunk terrain owns fixed vanilla templates. Feature Rendering is different: entities and
 * block entities can submit many distinct RenderPipelines/vertex formats. Prism therefore stores
 * shader-stage programs and derives concrete feature variants lazily from the exact vanilla base
 * pipeline. A cache miss always returns vanilla immediately; native compilation happens later from
 * the bounded warmup queue.</p>
 */
public final class PrismWorldRenderingPipeline {
    @FunctionalInterface
    interface FinalCompositeExecutor {
        void execute(LevelRenderContext context, GpuTextureView presentedColor, CommandEncoder commandEncoder);
    }

    private static final int MAX_DYNAMIC_FEATURE_VARIANTS_PER_GENERATION = 256;

    private record State(
            long generation,
            String packId,
            Map<ChunkSectionLayer, RenderPipeline> terrainPipelines,
            Map<ChunkSectionLayer, List<String>> terrainOutputs,
            Map<PrismSceneDomain, PrismCompiledFeatureProgram> featurePrograms,
            Set<PrismRenderDomain> activeDomains,
            Set<PrismVanillaRenderFeature> vanillaReplacements,
            FeatureVariantCache featureVariants) {
        private static State vanilla() {
            return new State(0L, "", Map.of(), Map.of(), Map.of(), Set.of(), Set.of(),
                    new FeatureVariantCache(0L));
        }
    }

    private enum VariantStatus { QUEUED, READY, FAILED }

    private static final class VariantEntry {
        VariantStatus status = VariantStatus.QUEUED;
        RenderPipeline pipeline;
    }

    private record VariantRequest(
            long generation,
            String packId,
            PrismCompiledFeatureProgram program,
            RenderPipeline basePipeline,
            VariantEntry entry) {}

    /** Generation-local, identity-keyed because RenderPipeline is immutable GPU state. */
    private static final class FeatureVariantCache {
        private final long generation;
        private final EnumMap<PrismSceneDomain, IdentityHashMap<RenderPipeline, VariantEntry>> byDomain =
                new EnumMap<>(PrismSceneDomain.class);
        private final ArrayDeque<VariantRequest> queue = new ArrayDeque<>();
        private final IdentityHashMap<RenderPipeline, List<String>> outputsByReadyPipeline = new IdentityHashMap<>();
        private long requests;
        private long hits;
        private long vanillaFallbacks;
        private long budgetRejectedRequests;
        private int variantEntries;
        private boolean budgetWarningLogged;
        private long compileAttempts;
        private long compileSuccesses;
        private long compileFailures;

        FeatureVariantCache(long generation) {
            this.generation = generation;
        }

        synchronized RenderPipeline resolve(
                String packId,
                PrismCompiledFeatureProgram program,
                RenderPipeline vanilla) {
            requests++;
            IdentityHashMap<RenderPipeline, VariantEntry> variants =
                    byDomain.computeIfAbsent(program.domain(), ignored -> new IdentityHashMap<>());
            VariantEntry entry = variants.get(vanilla);
            if (entry == null) {
                if (variantEntries >= MAX_DYNAMIC_FEATURE_VARIANTS_PER_GENERATION) {
                    budgetRejectedRequests++;
                    vanillaFallbacks++;
                    if (!budgetWarningLogged) {
                        budgetWarningLogged = true;
                        PrismMod.LOGGER.warn(
                                "Prism feature variant budget reached for generation {} (max={}); additional base pipelines stay vanilla",
                                generation, MAX_DYNAMIC_FEATURE_VARIANTS_PER_GENERATION);
                    }
                    return vanilla;
                }
                entry = new VariantEntry();
                variants.put(vanilla, entry);
                variantEntries++;
                queue.addLast(new VariantRequest(generation, packId, program, vanilla, entry));
                vanillaFallbacks++;
                return vanilla;
            }
            if (entry.status == VariantStatus.READY && entry.pipeline != null) {
                hits++;
                return entry.pipeline;
            }
            vanillaFallbacks++;
            return vanilla;
        }

        synchronized VariantRequest poll() {
            VariantRequest request = queue.pollFirst();
            if (request != null) compileAttempts++;
            return request;
        }

        synchronized void complete(VariantRequest request, RenderPipeline pipeline) {
            request.entry().pipeline = pipeline;
            request.entry().status = VariantStatus.READY;
            outputsByReadyPipeline.put(pipeline, request.program().outputs());
            compileSuccesses++;
        }

        synchronized List<String> outputs(RenderPipeline pipeline) {
            return outputsByReadyPipeline.getOrDefault(pipeline, List.of());
        }

        synchronized void fail(VariantRequest request) {
            request.entry().pipeline = null;
            request.entry().status = VariantStatus.FAILED;
            compileFailures++;
        }

        synchronized PrismFeaturePipelineStats snapshot() {
            int queued = 0;
            int ready = 0;
            int failed = 0;
            for (IdentityHashMap<RenderPipeline, VariantEntry> variants : byDomain.values()) {
                for (VariantEntry entry : variants.values()) {
                    switch (entry.status) {
                        case QUEUED -> queued++;
                        case READY -> ready++;
                        case FAILED -> failed++;
                    }
                }
            }
            return new PrismFeaturePipelineStats(
                    generation,
                    queued,
                    ready,
                    failed,
                    requests,
                    hits,
                    vanillaFallbacks,
                    budgetRejectedRequests,
                    compileAttempts,
                    compileSuccesses,
                    compileFailures);
        }
    }

    private static volatile State active = State.vanilla();
    private static volatile Map<PrismSceneDomain, RenderPipeline> vanillaTemplates = Map.of();
    private static volatile PrismWorldRenderPhase phase = PrismWorldRenderPhase.INACTIVE;
    private static volatile PrismWorldRenderApiImpl publicApi;
    private static volatile FinalCompositeExecutor finalCompositeExecutor = (context, presentedColor, commandEncoder) -> {};
    /**
     * Same-frame context captured at Fabric END_MAIN and consumed at the scene/overlay boundary.
     * Prism fullscreen pack executors never dereference world state from this context; it is
     * retained solely to satisfy the existing graph-pass context ABI.
     */
    private static volatile LevelRenderContext pendingFinalCompositeContext;
    private static boolean finalDispatchLogged;
    private static boolean eventsRegistered;

    private PrismWorldRenderingPipeline() {}

    static synchronized void initialize(
            PrismWorldRenderApiImpl api,
            PrismPerformanceApiImpl performanceApi,
            FinalCompositeExecutor compositeExecutor) {
        publicApi = java.util.Objects.requireNonNull(api, "api");
        java.util.Objects.requireNonNull(performanceApi, "performanceApi");
        finalCompositeExecutor = java.util.Objects.requireNonNull(compositeExecutor, "compositeExecutor");
        if (vanillaTemplates.isEmpty()) {
            EnumMap<PrismSceneDomain, RenderPipeline> templates = new EnumMap<>(PrismSceneDomain.class);
            templates.put(PrismSceneDomain.TERRAIN_SOLID, ChunkSectionLayer.SOLID.pipeline());
            templates.put(PrismSceneDomain.TERRAIN_CUTOUT, ChunkSectionLayer.CUTOUT.pipeline());
            templates.put(PrismSceneDomain.TERRAIN_TRANSLUCENT, ChunkSectionLayer.TRANSLUCENT.pipeline());
            vanillaTemplates = Map.copyOf(templates);
        }
        api.publishSupportedDomains(PrismSceneExecutionSupport.publicDomains());
        PrismShadowRenderer.initialize(api, performanceApi);
        if (!eventsRegistered) {
            // Fabric 26.2 explicitly separates extraction from drawing. Shadow section visibility is
            // therefore snapshotted at END_EXTRACTION; GPU shadow replay remains in the drawing path.
            LevelExtractionEvents.END_EXTRACTION.register(PrismShadowRenderer::captureIndependentVisibility);
            LevelRenderEvents.START_MAIN.register(context -> {
                // Never allow a context from an interrupted/previous world frame to survive.
                pendingFinalCompositeContext = null;
                PrismSceneAttachmentStore.beginFrame();
                publishPhase(PrismWorldRenderPhase.START_MAIN);
            });
            LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register(context -> publishPhase(PrismWorldRenderPhase.OPAQUE_TERRAIN_COMPLETE));
            LevelRenderEvents.AFTER_SOLID_FEATURES.register(context -> publishPhase(PrismWorldRenderPhase.SOLID_FEATURES_COMPLETE));
            LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(context -> {
                publishPhase(PrismWorldRenderPhase.BEFORE_TRANSLUCENT_TERRAIN);
                // Fabric 26.2 guarantees that opaque terrain plus solid entity/block-entity/particle
                // geometry has already reached the level framebuffers here, while translucent terrain
                // has not started yet. Consume the live reversed-Z world depth at this exact boundary;
                // waiting until first-person rendering leaves the main depth cleared/reused.
                PrismShadowRenderer.applyTerrainShadowReceiverBeforeTranslucentTerrain();
            });
            LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> publishPhase(PrismWorldRenderPhase.TRANSLUCENT_TERRAIN_COMPLETE));
            LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> publishPhase(PrismWorldRenderPhase.TRANSLUCENT_FEATURES_COMPLETE));
            LevelRenderEvents.END_MAIN.register(context -> {
                publishPhase(PrismWorldRenderPhase.END_MAIN);
                // Capture only. GameRendererMixin consumes this after LevelRenderer has completed
                // clouds/weather/fabulous composition but before hand, HUD, debug text and menus.
                pendingFinalCompositeContext = context;
            });
            eventsRegistered = true;
        }
        publishSnapshot();
        publishFeatureStats();
        PrismMod.LOGGER.info(
                "Prism WorldRenderingPipeline initialized: terrain templates + scoped Feature Rendering variants + always-valid vanilla fallback");
    }

    /** Executes the creator graph at Minecraft 26.2's audited world/overlay boundary. */
    public static void finishWorldBeforeOverlays() {
        LevelRenderContext context = pendingFinalCompositeContext;
        pendingFinalCompositeContext = null;
        if (context == null) return;

        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null
                || minecraft.gameRenderer.mainRenderTarget() == null) {
            return;
        }
        GpuTextureView worldColor = minecraft.gameRenderer.mainRenderTarget().getColorTextureView();
        if (worldColor == null) return;

        CommandEncoder commandEncoder = com.mojang.blaze3d.systems.RenderSystem
                .getDevice().createCommandEncoder();
        executeFinalComposite(context, worldColor, commandEncoder, "world_before_overlays");
        commandEncoder.submit();
    }

    /**
     * Safety fallback at the presentation boundary. Normal level frames have already consumed the
     * graph before overlays; this remains for nonstandard render paths and the opt-in depth inset.
     */
    public static void finishBeforeSurfaceBlit(CommandEncoder commandEncoder, GpuTextureView presentedColor) {
        java.util.Objects.requireNonNull(commandEncoder, "commandEncoder");
        java.util.Objects.requireNonNull(presentedColor, "presentedColor");
        LevelRenderContext context = pendingFinalCompositeContext;
        pendingFinalCompositeContext = null;
        if (context != null) {
            executeFinalComposite(context, presentedColor, commandEncoder, "presentation_fallback");
        }
        PrismShadowRenderer.drawDebugBeforeSurfaceBlit(commandEncoder, presentedColor);
    }

    private static void executeFinalComposite(
            LevelRenderContext context,
            GpuTextureView outputColor,
            CommandEncoder commandEncoder,
            String boundary) {
        finalCompositeExecutor.execute(context, outputColor, commandEncoder);
        if (!finalDispatchLogged) {
            finalDispatchLogged = true;
            PrismMod.LOGGER.info(
                    "Prism final fullscreen/composite dispatch executed at boundary={} before hand/HUD/UI; encoderClass={}, output={}x{}, format={}",
                    boundary,
                    commandEncoder.getClass().getName(),
                    outputColor.getWidth(0), outputColor.getHeight(0),
                    outputColor.texture().getFormat());
        }
    }

    static RenderPipeline vanillaTemplate(PrismSceneDomain domain) {
        PrismSceneExecutionSupport.requireStaticTerrainUnchecked(domain);
        RenderPipeline pipeline = vanillaTemplates.get(domain);
        if (pipeline == null) {
            throw new IllegalStateException("No verified Minecraft 26.2 vanilla terrain template for " + domain.manifestName());
        }
        return pipeline;
    }

    static void install(
            String packId,
            long generation,
            List<PrismCompiledScenePipeline> scenePipelines,
            List<PrismCompiledFeatureProgram> featurePrograms,
            List<PrismVanillaRenderFeature> vanillaReplacements,
            PrismSceneAttachmentStore.Prepared preparedAttachments) {
        EnumMap<ChunkSectionLayer, RenderPipeline> terrain = new EnumMap<>(ChunkSectionLayer.class);
        EnumMap<ChunkSectionLayer, List<String>> terrainOutputs = new EnumMap<>(ChunkSectionLayer.class);
        EnumMap<PrismSceneDomain, PrismCompiledFeatureProgram> features = new EnumMap<>(PrismSceneDomain.class);
        EnumSet<PrismRenderDomain> domains = EnumSet.noneOf(PrismRenderDomain.class);

        for (PrismCompiledScenePipeline pipeline : scenePipelines) {
            PrismSceneDomain domain = pipeline.domain();
            if (!PrismSceneExecutionSupport.isStaticTerrain(domain)) {
                throw new IllegalStateException("Non-terrain concrete scene pipeline reached commit: " + domain.manifestName());
            }
            ChunkSectionLayer layer = layerFor(domain);
            if (terrain.put(layer, pipeline.pipeline()) != null) {
                throw new IllegalStateException("Duplicate Prism scene pipeline for " + domain.manifestName());
            }
            terrainOutputs.put(layer, pipeline.outputs());
            domains.add(domain.publicDomain());
        }

        for (PrismCompiledFeatureProgram program : featurePrograms) {
            PrismSceneDomain domain = program.domain();
            if (!PrismSceneExecutionSupport.isDynamicFeature(domain)) {
                throw new IllegalStateException("Non-feature shader program reached feature commit: " + domain.manifestName());
            }
            if (features.put(domain, program) != null) {
                throw new IllegalStateException("Duplicate Prism feature program for " + domain.manifestName());
            }
            domains.add(domain.publicDomain());
        }

        State next = new State(
                generation,
                java.util.Objects.requireNonNull(packId, "packId"),
                Map.copyOf(terrain),
                Map.copyOf(terrainOutputs),
                Map.copyOf(features),
                Set.copyOf(domains),
                Set.copyOf(vanillaReplacements),
                new FeatureVariantCache(generation));
        PrismSceneAttachmentStore.install(preparedAttachments);
        active = next;
        publishSnapshot();
        publishFeatureStats();
    }

    static void clear() {
        active = State.vanilla();
        PrismSceneAttachmentStore.clear();
        phase = PrismWorldRenderPhase.INACTIVE;
        publishSnapshot();
        publishFeatureStats();
    }

    static synchronized void close() {
        PrismWorldRenderApiImpl api = publicApi;
        active = State.vanilla();
        PrismSceneAttachmentStore.clear();
        phase = PrismWorldRenderPhase.INACTIVE;
        if (api != null) api.reset();
        PrismShadowRenderer.close();
        publicApi = null;
        finalCompositeExecutor = (context, presentedColor, commandEncoder) -> {};
        pendingFinalCompositeContext = null;
    }

    public static RenderPipeline resolveTerrain(ChunkSectionLayer layer, RenderPipeline vanilla) {
        // Auxiliary shadow replay must use the exact vanilla terrain contract. Scene-pack pipelines
        // are camera-view programs and are not implicitly valid shadow-caster programs.
        if (PrismShadowRenderer.isReplayActive()) {
            return vanilla;
        }
        RenderPipeline replacement = active.terrainPipelines().get(layer);
        return replacement == null ? vanilla : replacement;
    }

    /**
     * True only while a completely compiled generation owns this late vanilla world feature.
     * The volatile state swap makes suppression and shader activation one transaction.
     */
    public static boolean replacesVanillaFeature(PrismVanillaRenderFeature feature) {
        return feature != null && active.vanillaReplacements().contains(feature);
    }

    /** Captures the late-world environmental ABI before any creator frame uniform is encoded. */
    public static void captureEnvironment(
            CameraRenderState camera,
            LevelRenderState level,
            Vector4fc fogColor) {
        PrismPackFrameUniforms.captureEnvironment(camera, level, fogColor);
    }

    /** Redirect target for Minecraft's one-pass terrain groups. */
    public static RenderPass createTerrainRenderPass(
            CommandEncoder encoder,
            ChunkSectionLayerGroup group,
            Supplier<String> label,
            GpuTextureView color,
            Optional<Vector4fc> colorClear,
            GpuTextureView depth,
            OptionalDouble depthClear) {
        State state = active;
        if (group == null || PrismShadowRenderer.isReplayActive()
                || (net.minecraft.SharedConstants.DEBUG_HOTKEYS
                        && net.minecraft.client.Minecraft.getInstance().wireframe)) {
            return encoder.createRenderPass(label, color, colorClear, depth, depthClear);
        }
        List<String> outputs = List.of();
        for (ChunkSectionLayer layer : group.layers()) {
            List<String> candidate = state.terrainOutputs().get(layer);
            if (candidate == null || candidate.size() <= 1) continue;
            if (outputs.isEmpty()) outputs = candidate;
            else if (!outputs.equals(candidate)) {
                throw new IllegalStateException("Incompatible Prism MRT layouts reached one terrain group");
            }
        }
        return PrismSceneAttachmentStore.createRenderPass(
                encoder, label, color, colorClear, depth, depthClear, outputs);
    }

    /** Redirect target for isolated Feature Rendering entity/block-entity passes. */
    public static RenderPass createFeatureRenderPass(
            CommandEncoder encoder,
            RenderPipeline pipeline,
            Supplier<String> label,
            GpuTextureView color,
            Optional<Vector4fc> colorClear,
            GpuTextureView depth,
            OptionalDouble depthClear) {
        List<String> outputs = active.featureVariants().outputs(pipeline);
        return PrismSceneAttachmentStore.createRenderPass(
                encoder, label, color, colorClear, depth, depthClear, outputs);
    }

    static void bindSceneAttachments(
            Blaze3DFrameResources resources,
            CommandEncoder encoder,
            GpuTextureView mainColor) {
        PrismSceneAttachmentStore.bindAndClearUnwritten(resources, encoder, mainColor);
    }

    /**
     * Returns a READY world-feature variant or the exact vanilla pipeline. A miss only queues work;
     * native compilation never runs inside RenderType.prepare()/feature drawing.
     */
    static boolean hasFeatureProgram(PrismSceneDomain domain) {
        return active.featurePrograms().containsKey(domain);
    }

    static boolean needsFeatureTagging(PrismFeatureSource source) {
        State state = active;
        return switch (source) {
            case ENTITY -> state.featurePrograms().containsKey(PrismSceneDomain.ENTITY_OPAQUE)
                    || state.featurePrograms().containsKey(PrismSceneDomain.ENTITY_TRANSLUCENT);
            case BLOCK_ENTITY -> state.featurePrograms().containsKey(PrismSceneDomain.BLOCK_ENTITY);
        };
    }

    static RenderPipeline resolveFeature(PrismSceneDomain domain, RenderPipeline vanilla) {
        State state = active;
        PrismCompiledFeatureProgram program = state.featurePrograms().get(domain);
        if (program == null) return vanilla;
        RenderPipeline resolved = state.featureVariants().resolve(state.packId(), program, vanilla);
        publishFeatureStats();
        return resolved;
    }

    /** Render-thread warmup: at most {@code maxNativeCompiles} feature variants in one frame. */
    static void advanceFeatureWarmup(GpuDevice device, int maxNativeCompiles) {
        java.util.Objects.requireNonNull(device, "device");
        if (maxNativeCompiles < 1) throw new IllegalArgumentException("maxNativeCompiles must be >= 1");
        State state = active;
        FeatureVariantCache cache = state.featureVariants();
        int completed = 0;
        while (completed < maxNativeCompiles) {
            VariantRequest request = cache.poll();
            if (request == null) break;
            if (request.generation() != state.generation() || active != state) continue;
            try {
                RenderPipeline pipeline = PrismPipelineCompiler.compileFeatureVariant(
                        state.packId(), state.generation(), request.program(), request.basePipeline(), device);
                if (active == state) cache.complete(request, pipeline);
            } catch (PrismPackLoadException | RuntimeException exception) {
                if (active == state) cache.fail(request);
                PrismMod.LOGGER.error(
                        "Prism feature variant failed; keeping vanilla fallback: pack='{}', generation={}, program='{}', domain={}, basePipeline={}",
                        state.packId(), state.generation(), request.program().pipelineId(),
                        request.program().domain().manifestName(), request.basePipeline().getLocation(), exception);
            }
            completed++;
        }
        publishFeatureStats();
    }

    public static PrismWorldPipelineSnapshot snapshot() {
        State state = active;
        return new PrismWorldPipelineSnapshot(
                state.generation(), state.packId(), phase, state.activeDomains(), state.activeDomains().isEmpty());
    }

    public static PrismFeaturePipelineStats featureStats() {
        return active.featureVariants().snapshot();
    }

    private static void publishPhase(PrismWorldRenderPhase next) {
        phase = next;
        publishSnapshot();
    }

    private static void publishSnapshot() {
        PrismWorldRenderApiImpl api = publicApi;
        if (api != null) api.publish(snapshot());
    }

    private static void publishFeatureStats() {
        PrismWorldRenderApiImpl api = publicApi;
        if (api != null) api.publishFeaturePipelineStats(featureStats());
    }

    private static ChunkSectionLayer layerFor(PrismSceneDomain domain) {
        return switch (domain) {
            case TERRAIN_SOLID -> ChunkSectionLayer.SOLID;
            case TERRAIN_CUTOUT -> ChunkSectionLayer.CUTOUT;
            case TERRAIN_TRANSLUCENT -> ChunkSectionLayer.TRANSLUCENT;
            default -> throw new IllegalArgumentException("No chunk layer for scene domain " + domain.manifestName());
        };
    }
}
