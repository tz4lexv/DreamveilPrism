package dev.dreamveil.prism.api;

import java.util.EnumSet;

import dev.dreamveil.prism.api.graph.PrismGraphBuilder;
import dev.dreamveil.prism.api.pack.PrismPackInfo;
import dev.dreamveil.prism.api.pack.PrismPackStatus;
import dev.dreamveil.prism.api.graph.PrismGraphPlan;
import dev.dreamveil.prism.api.resource.PrismResourceId;
import dev.dreamveil.prism.api.resource.PrismResources;
import dev.dreamveil.prism.api.resource.PrismTextureDescriptor;
import dev.dreamveil.prism.api.resource.PrismTextureExtent;
import dev.dreamveil.prism.api.resource.PrismTextureFormat;
import dev.dreamveil.prism.api.resource.PrismTextureHandle;
import dev.dreamveil.prism.api.resource.PrismTextureUsage;
import dev.dreamveil.prism.runtime.api.PrismRenderGraphApiImpl;
import dev.dreamveil.prism.runtime.api.PrismPackApiImpl;
import dev.dreamveil.prism.runtime.api.PrismResourceApiImpl;
import dev.dreamveil.prism.runtime.api.PrismPackSettingsApiImpl;
import dev.dreamveil.prism.runtime.api.PrismPerformanceApiImpl;
import dev.dreamveil.prism.api.setting.PrismPackSetting;
import dev.dreamveil.prism.api.setting.PrismPackSettingDefinition;
import dev.dreamveil.prism.api.setting.PrismPackSettingType;

/** Standalone creator-facing API validation with no Minecraft/Fabric dependency. */
public final class PrismApiSmokeTest {
    private PrismApiSmokeTest() {
    }

    public static void main(String[] args) {
        testApiVersionCompatibility();
        testCreatorGraphUsesCorePlanner();
        testCreatorGraphValidation();
        testResourceIdValidation();
        testPackApiState();
        testPackVersionLibrary();
        testSettingsApiState();
        testPerformanceApiState();
        testGraphicsCapabilityMapping();
        testGraphicsDescriptors();
        testShadowHelpers();
        testTemporalHints();
        testWorldPipelineApi();
        testAuditedMinecraftCapabilities();
        System.out.println("Dreamveil Prism public API v1.25 smoke tests: PASS");
    }

    private static void testApiVersionCompatibility() {
        require(PrismApiVersion.CURRENT.equals(new PrismApiVersion(1, 25)), "unexpected current API version");
        require(PrismApiVersion.CURRENT.isCompatibleWith(new PrismApiVersion(1, 0)),
                "API 1.0 must accept requirement 1.0");
        require(!PrismApiVersion.CURRENT.isCompatibleWith(new PrismApiVersion(2, 0)),
                "API 1.x must reject API 2.x");
        require(PrismApiVersion.CURRENT.isCompatibleWith(new PrismApiVersion(1, 1)),
                "API 1.15 must accept requirement 1.1");
        require(PrismApiVersion.CURRENT.isCompatibleWith(new PrismApiVersion(1, 2)),
                "API 1.15 must accept requirement 1.2");
        require(PrismApiVersion.CURRENT.isCompatibleWith(new PrismApiVersion(1, 3)),
                "API 1.15 must accept requirement 1.3");
        require(PrismApiVersion.CURRENT.isCompatibleWith(new PrismApiVersion(1, 4)),
                "API 1.15 must accept requirement 1.4");
        require(PrismApiVersion.CURRENT.isCompatibleWith(new PrismApiVersion(1, 5)),
                "API 1.15 must accept requirement 1.5");
        require(PrismApiVersion.CURRENT.isCompatibleWith(new PrismApiVersion(1, 6)),
                "API 1.15 must accept requirement 1.6");
        require(PrismApiVersion.CURRENT.isCompatibleWith(new PrismApiVersion(1, 7)),
                "API 1.15 must accept requirement 1.7");
        require(PrismCapability.valueOf("SCENE_TERRAIN_PIPELINES") == PrismCapability.SCENE_TERRAIN_PIPELINES,
                "scene terrain pipeline capability must be published");
        require(PrismCapability.valueOf("NATIVE_PROGRAM_SETS") == PrismCapability.NATIVE_PROGRAM_SETS,
                "native ProgramSet capability must be published");
        require(PrismCapability.valueOf("MANIFESTLESS_NATIVE_PACKS") == PrismCapability.MANIFESTLESS_NATIVE_PACKS,
                "manifest-less native pack capability must be published");
        require(PrismCapability.valueOf("LEGACY_PACK_INSPECTION") == PrismCapability.LEGACY_PACK_INSPECTION,
                "legacy pack inspection capability must be published");
        require(PrismCapability.valueOf("FEATURE_RENDER_MODEL_PIPELINES") == PrismCapability.FEATURE_RENDER_MODEL_PIPELINES,
                "Feature Rendering model pipeline capability must be published");
        require(PrismCapability.valueOf("DYNAMIC_SCENE_PIPELINE_VARIANTS") == PrismCapability.DYNAMIC_SCENE_PIPELINE_VARIANTS,
                "dynamic scene pipeline variant capability must be published");
        require(PrismCapability.valueOf("ENTITY_SCENE_PIPELINES") == PrismCapability.ENTITY_SCENE_PIPELINES,
                "entity scene pipeline capability must be published");
        require(PrismCapability.valueOf("BLOCK_ENTITY_SCENE_PIPELINES") == PrismCapability.BLOCK_ENTITY_SCENE_PIPELINES,
                "block-entity scene pipeline capability must be published");
        require(dev.dreamveil.prism.api.render.PrismRenderDomain.valueOf("TERRAIN_OPAQUE")
                        == dev.dreamveil.prism.api.render.PrismRenderDomain.TERRAIN_OPAQUE,
                "semantic render domain API must be published");
        require(PrismCapability.valueOf("CLIP_CONVENTION_HELPERS") == PrismCapability.CLIP_CONVENTION_HELPERS,
                "clip convention helper capability must be published");
        require(PrismCapability.valueOf("FRUSTUM_CULLING_HELPERS") == PrismCapability.FRUSTUM_CULLING_HELPERS,
                "frustum culling helper capability must be published");
        require(PrismCapability.valueOf("SHADOW_CASTER_CLASSIFICATION") == PrismCapability.SHADOW_CASTER_CLASSIFICATION,
                "shadow caster classification capability must be published");
        require(PrismCapability.valueOf("SHADOW_VISIBILITY_PLANNING") == PrismCapability.SHADOW_VISIBILITY_PLANNING,
                "shadow visibility planner capability must be published");
        require(PrismCapability.valueOf("PACK_FRAME_UNIFORMS") == PrismCapability.PACK_FRAME_UNIFORMS,
                "pack frame uniform capability must be published");
        require(PrismCapability.valueOf("PACK_TEMPORAL_HISTORY") == PrismCapability.PACK_TEMPORAL_HISTORY,
                "pack temporal history capability must be published");
        require(PrismCapability.valueOf("PACK_TEXTURE_ASSETS") == PrismCapability.PACK_TEXTURE_ASSETS,
                "pack texture asset capability must be published");
        require(PrismCapability.valueOf("SCENE_PROJECTION_JITTER") == PrismCapability.SCENE_PROJECTION_JITTER,
                "scene projection jitter capability must be published");
        require(PrismCapability.valueOf("PACK_DYNAMIC_RESOLUTION") == PrismCapability.PACK_DYNAMIC_RESOLUTION,
                "pack dynamic resolution capability must be published");
        require(PrismCapability.valueOf("CUBEMAP_TEXTURES") == PrismCapability.CUBEMAP_TEXTURES,
                "cubemap capability must be published");
        require(PrismCapability.valueOf("VANILLA_SCENE_REPLACEMENT")
                        == PrismCapability.VANILLA_SCENE_REPLACEMENT,
                "vanilla scene replacement capability must be published");
        require(dev.dreamveil.prism.api.render.PrismVanillaRenderFeature.parse("clouds")
                        == dev.dreamveil.prism.api.render.PrismVanillaRenderFeature.CLOUDS,
                "vanilla cloud replacement token must be public and parseable");
        require(PrismApiVersion.parse("1.25").equals(PrismApiVersion.CURRENT),
                "API parser must round-trip current version");
        expectFailure(() -> PrismApiVersion.parse("1"), "invalid API version text must fail");
    }

    private static void testCreatorGraphUsesCorePlanner() {
        PrismResourceApiImpl resources = new PrismResourceApiImpl();
        PrismTextureDescriptor scratchDesc = resources.texture(
                PrismTextureExtent.relative(0.5),
                PrismTextureFormat.RGBA16_FLOAT,
                EnumSet.of(
                        PrismTextureUsage.RENDER_ATTACHMENT,
                        PrismTextureUsage.SAMPLED,
                        PrismTextureUsage.COPY_SRC,
                        PrismTextureUsage.COPY_DST),
                1);

        PrismGraphBuilder graph = new PrismRenderGraphApiImpl().create(PrismResourceId.of("example", "main"));
        graph.importTexture(PrismResources.MAIN_COLOR);
        graph.importTexture(PrismResources.SCENE_HIERARCHICAL_DEPTH);
        PrismTextureHandle a = graph.transientTexture(PrismResourceId.of("example", "scratch_a"), scratchDesc);
        PrismTextureHandle b = graph.transientTexture(PrismResourceId.of("example", "scratch_b"), scratchDesc);
        PrismTextureHandle c = graph.transientTexture(PrismResourceId.of("example", "scratch_c"), scratchDesc);

        graph.pass(PrismResourceId.of("example", "produce_a"), pass ->
                        pass.read(PrismResources.MAIN_COLOR)
                                .read(PrismResources.SCENE_HIERARCHICAL_DEPTH).write(a))
                .pass(PrismResourceId.of("example", "a_to_b"), pass -> pass.read(a).write(b))
                .pass(PrismResourceId.of("example", "b_to_c"), pass -> pass.read(b).write(c))
                .pass(PrismResourceId.of("example", "consume_c"), pass ->
                        pass.read(c).readWrite(PrismResources.MAIN_COLOR));

        PrismGraphPlan plan = graph.compile();
        int slotA = plan.transientSlots().get(a.id());
        int slotB = plan.transientSlots().get(b.id());
        int slotC = plan.transientSlots().get(c.id());
        require(slotA == slotC, "creator API must preserve non-overlapping aliasing");
        require(slotA != slotB, "creator API must not alias overlapping resources");
        require(plan.physicalTransientSlotCount() == 2, "creator graph must plan two physical slots");
        require(plan.orderedPasses().size() == 4, "creator graph pass order was not compiled");
    }

    private static void testCreatorGraphValidation() {
        PrismTextureDescriptor desc = new PrismResourceApiImpl().colorAttachment(
                PrismTextureExtent.relative(1.0),
                PrismTextureFormat.RGBA8_UNORM);
        PrismGraphBuilder graph = new PrismRenderGraphApiImpl().create(PrismResourceId.of("example", "bad"));
        PrismTextureHandle scratch = graph.transientTexture(PrismResourceId.of("example", "scratch"), desc);
        expectFailure(() -> graph.pass(PrismResourceId.of("example", "bad_read"), pass -> pass.read(scratch)).compile(),
                "read-before-produce must be rejected through public API");
    }

    private static void testResourceIdValidation() {
        require(PrismResourceId.parse("dreamveil:test/path").toString().equals("dreamveil:test/path"),
                "resource id round trip failed");
        expectFailure(() -> PrismResourceId.parse("MissingNamespace"), "missing namespace must fail");
        expectFailure(() -> PrismResourceId.of("Bad Namespace", "x"), "invalid namespace must fail");
    }


    private static void testPackApiState() {
        PrismPackApiImpl packs = new PrismPackApiImpl();
        java.util.concurrent.atomic.AtomicBoolean reloadRequested = new java.util.concurrent.atomic.AtomicBoolean();
        packs.setReloadHandler(() -> reloadRequested.set(true));

        PrismPackInfo ready = new PrismPackInfo(
                "creator.example",
                "Creator Example",
                "1.0.0",
                PrismPackStatus.READY,
                2,
                7,
                java.util.List.of());
        var metadata = new dev.dreamveil.prism.api.pack.PrismPackMetadata(
                "Creator", "Example", "creator-example.zip", true);
        var variant = new dev.dreamveil.prism.api.pack.PrismPackVariant(
                ready.id(), ready.name(), ready.version(), metadata, true, true, java.util.List.of());
        packs.publish(
                java.util.List.of(ready),
                ready.id(),
                8,
                java.util.Map.of(ready.id(), metadata),
                java.util.Map.of(ready.id(), java.util.List.of(variant)));

        require(packs.installed().size() == 1, "pack API must publish installed packs");
        require(packs.active().orElseThrow().id().equals(ready.id()), "pack API active id mismatch");
        require(packs.reloadGeneration() == 8, "pack API reload generation mismatch");
        require(packs.variants(ready.id()).size() == 1, "pack API version variants missing");
        require(packs.allVariants().get(0).selector().equals("creator.example@1.0.0"),
                "pack variant selector mismatch");
        packs.requestReload();
        require(reloadRequested.get(), "pack API reload request handler was not called");
    }


    private static void testPackVersionLibrary() {
        var selector = dev.dreamveil.prism.api.pack.PrismPackSelector.parse("dreamveil.shadow_test@0.2.1");
        require(selector.id().equals("dreamveil.shadow_test") && selector.version().equals("0.2.1"),
                "versioned pack selector parse failed");
        require(selector.persisted().equals("dreamveil.shadow_test@0.2.1"),
                "versioned pack selector round trip failed");
        require(dev.dreamveil.prism.api.pack.PrismPackVersion.compare("0.2.1", "0.2.0") > 0,
                "pack version ordering must prefer 0.2.1 over 0.2.0");
        require(dev.dreamveil.prism.api.pack.PrismPackVersion.compare("1.0.0", "1.0.0-beta.2") > 0,
                "release must sort after pre-release");
        require(dev.dreamveil.prism.api.pack.PrismPackVersion.compare("1.0.10", "1.0.9") > 0,
                "numeric version components must compare numerically");
    }


    private static void testSettingsApiState() {
        PrismPackSettingsApiImpl settings = new PrismPackSettingsApiImpl();
        PrismPackSettingDefinition definition = new PrismPackSettingDefinition(
                "quality",
                "Quality",
                PrismPackSettingType.ENUM,
                "high",
                "Creator-owned quality permutation",
                Double.NaN,
                Double.NaN,
                Double.NaN,
                java.util.List.of("low", "high"));
        PrismPackSetting value = new PrismPackSetting(definition, "high");
        settings.publish(java.util.Map.of("creator.example", java.util.List.of(value)));
        require(settings.settings("creator.example").size() == 1, "settings API publish failed");
        require(settings.setting("creator.example", "quality").orElseThrow().value().equals("high"),
                "settings API lookup failed");

        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        settings.setHandlers((packId, settingId, raw) -> {
            changed.set(packId.equals("creator.example") && settingId.equals("quality") && raw.equals("low"));
            return true;
        }, packId -> true);
        require(settings.set("creator.example", "quality", "low"), "settings API set handler failed");
        require(changed.get(), "settings API set handler arguments mismatch");
    }

    private static void testPerformanceApiState() {
        PrismPerformanceApiImpl performance = new PrismPerformanceApiImpl();
        performance.configure("creator.example", java.util.List.of("lighting"), 3, 2);
        var graphTexture = new dev.dreamveil.prism.graph.PrismTextureDesc(
                dev.dreamveil.prism.graph.PrismTextureExtent.relative(1.0),
                dev.dreamveil.prism.graph.PrismTextureFormat.RGBA16_FLOAT,
                java.util.EnumSet.of(
                        dev.dreamveil.prism.graph.PrismTextureUsage.RENDER_ATTACHMENT,
                        dev.dreamveil.prism.graph.PrismTextureUsage.SAMPLED,
                        dev.dreamveil.prism.graph.PrismTextureUsage.STORAGE),
                1);
        var debugGraph = new dev.dreamveil.prism.graph.PrismRenderGraph()
                .importResource(dev.dreamveil.prism.graph.PrismResourceDescriptor.importedTexture("host"))
                .declareResource(dev.dreamveil.prism.graph.PrismResourceDescriptor.transientTexture(
                        "lighting", graphTexture))
                .addPass(new dev.dreamveil.prism.graph.PrismPass(
                        "gbuffer",
                        java.util.List.of(
                                new dev.dreamveil.prism.graph.PrismResourceRef(
                                        "host", dev.dreamveil.prism.graph.PrismResourceAccess.READ),
                                new dev.dreamveil.prism.graph.PrismResourceRef(
                                        "lighting", dev.dreamveil.prism.graph.PrismResourceAccess.WRITE)),
                        dev.dreamveil.prism.graph.PrismPassExecutionType.GRAPHICS))
                .addPass(new dev.dreamveil.prism.graph.PrismPass(
                        "compute",
                        java.util.List.of(
                                new dev.dreamveil.prism.graph.PrismResourceRef(
                                        "lighting", dev.dreamveil.prism.graph.PrismResourceAccess.READ,
                                        dev.dreamveil.prism.graph.PrismResourceUsageState.STORAGE_IMAGE_READ),
                                new dev.dreamveil.prism.graph.PrismResourceRef(
                                        "host", dev.dreamveil.prism.graph.PrismResourceAccess.READ_WRITE)),
                        dev.dreamveil.prism.graph.PrismPassExecutionType.COMPUTE))
                .compile();
        performance.configureGraph("creator.example", debugGraph);
        performance.recordPass("lighting", 200_000L);
        performance.recordPackFrame(300_000L);
        var snapshot = performance.snapshot();
        require(snapshot.packActive(), "performance API must report active pack");
        require(snapshot.pipelineCount() == 1, "performance pipeline count mismatch");
        require(snapshot.logicalTransientResources() == 3, "performance logical transient count mismatch");
        require(snapshot.physicalTransientSlots() == 2, "performance physical transient count mismatch");
        require(snapshot.passes().get(0).lastCpuNanos() == 200_000L, "pass timing mismatch");
        var graphSnapshot = performance.graphSnapshot();
        require(graphSnapshot.available() && graphSnapshot.passes().size() == 2,
                "render-graph diagnostics snapshot must expose installed passes");
        require(graphSnapshot.resources().size() == 2
                        && graphSnapshot.logicalTransientResources() == 1
                        && graphSnapshot.physicalTransientSlots() == 1,
                "render-graph diagnostics allocation summary mismatch");
        require(graphSnapshot.transitions().stream().anyMatch(edge ->
                        edge.resourceName().equals("lighting")
                                && edge.fromExecutionType().equals("GRAPHICS")
                                && edge.toExecutionType().equals("COMPUTE")
                                && edge.hazard().equals("READ_AFTER_WRITE")
                                && edge.requiresSynchronization()),
                "render-graph diagnostics must retain graphics-to-compute RAW hazards");
        performance.recordGpuPass("lighting", 450_000L);
        performance.recordCompileGeneration();
        performance.recordCompileSuccess(2, 1);
        performance.recordCachedFailureSkip();
        var gpu = performance.gpuSnapshot();
        require(gpu.available(), "GPU timing snapshot must become available after a timestamp sample");
        require(gpu.passes().get(0).lastGpuNanos() == 450_000L, "GPU timing mismatch");
        var cache = performance.pipelineCacheSnapshot();
        require(cache.reusedPipelines() == 2 && cache.compiledPipelines() == 1,
                "pipeline cache counters mismatch");
        require(cache.cachedFailuresSkipped() == 1, "failed compile cache counter mismatch");

        performance.recordPendingGenerationStarted();
        performance.recordGenerationInstalled(3);
        performance.recordSamplerCreated();
        performance.recordTransientPool(4, 1, 4, 1, 2, 1, 7, 3, 2);
        performance.recordSessionCachedPipelines(5);
        var lifetime = performance.resourceLifetimeSnapshot();
        require(lifetime.pendingGenerations() == 1, "pending generation lifetime counter mismatch");
        require(lifetime.liveGenerations() == 1 && lifetime.livePipelineBindings() == 3,
                "generation/binding lifetime counters mismatch");
        require(lifetime.liveSamplers() == 1 && lifetime.sessionCachedPipelines() == 5,
                "sampler/session-cache lifetime counters mismatch");
        require(lifetime.transientTexturesCreated() == 4 && lifetime.transientTexturesClosed() == 1
                        && lifetime.transientActiveResources() == 2,
                "transient resource lifetime counters mismatch");
        performance.recordShadowCullingFrame(42, 1000, 375, 625, 210, true, 1_750_000L);
        var shadowCulling = performance.shadowCullingSnapshot();
        require(shadowCulling.available() && shadowCulling.frameIndex() == 42,
                "shadow culling snapshot availability mismatch");
        require(shadowCulling.accepted() == 375 && shadowCulling.culled() == 625
                        && shadowCulling.drawCalls() == 210,
                "shadow culling counters mismatch");
        require(Math.abs(shadowCulling.gpuMilliseconds() - 1.75) < 1.0e-9,
                "shadow culling GPU timing mismatch");
        performance.recordShadowVisibilityFrame(43, 1200, 400, 800);
        shadowCulling = performance.shadowCullingSnapshot();
        require(shadowCulling.frameIndex() == 43 && shadowCulling.accepted() == 400
                        && shadowCulling.culled() == 800,
                "shadow visibility refresh mismatch");
        require(shadowCulling.drawCalls() == 210 && shadowCulling.gpuTimeAvailable()
                        && Math.abs(shadowCulling.gpuMilliseconds() - 1.75) < 1.0e-9,
                "shadow visibility refresh must preserve completed draw/timestamp metrics");
        performance.recordSamplerClosed();
        performance.recordGenerationRetired(3);
        performance.recordPendingGenerationFinished();
        performance.recordTransientPool(4, 4, 4, 4, 2, 2, 9, 0, 0);
        performance.recordSessionCachedPipelines(0);
        require(performance.resourceLifetimeSnapshot().atLogicalBaseline(),
                "resource lifetime tracker must return to logical baseline");
        performance.deactivate();
        require(!performance.snapshot().packActive(), "performance deactivate failed");
        require(!performance.shadowCullingSnapshot().available(),
                "deactivate must clear stale shadow culling metrics");
        require(!performance.graphSnapshot().available(),
                "deactivate must clear the installed graph snapshot");
    }

    private static void testGraphicsCapabilityMapping() {
        var backend = new dev.dreamveil.prism.backend.PrismBackendInfo(
                "Test",
                "API smoke",
                java.util.EnumSet.of(
                        PrismCapability.CUSTOM_RENDER_PIPELINES,
                        PrismCapability.OFFSCREEN_RENDER_TARGETS,
                        PrismCapability.CUBEMAP_TEXTURES,
                        PrismCapability.GPU_TIMESTAMP_QUERIES));
        var graphics = new dev.dreamveil.prism.runtime.api.PrismGraphicsApiImpl(backend);
        require(graphics.supports(dev.dreamveil.prism.api.graphics.PrismGraphicsFeature.GRAPHICS_PIPELINES),
                "graphics pipeline capability mapping failed");
        require(graphics.supports(dev.dreamveil.prism.api.graphics.PrismGraphicsFeature.OFFSCREEN_RENDER_TARGETS),
                "offscreen capability mapping failed");
        require(!graphics.supports(dev.dreamveil.prism.api.graphics.PrismGraphicsFeature.COMPUTE_PIPELINES),
                "unsupported compute must remain disabled");
        require(graphics.supports(dev.dreamveil.prism.api.graphics.PrismGraphicsFeature.CUBEMAP_TEXTURES),
                "cubemap capability mapping failed");
        require(graphics.linearClampSampler().minFilter() == dev.dreamveil.prism.api.graphics.PrismSamplerFilter.LINEAR,
                "sampler descriptor factory mismatch");
    }

    private static void testGraphicsDescriptors() {
        var resources = new PrismResourceApiImpl();
        var rich = resources.textureResource(
                PrismTextureExtent.relative(0.5),
                dev.dreamveil.prism.api.resource.PrismTextureGeometry.texture2DArray(4),
                PrismTextureFormat.RGBA16_FLOAT,
                java.util.EnumSet.of(PrismTextureUsage.SAMPLED, PrismTextureUsage.RENDER_ATTACHMENT),
                3);
        require(rich.geometry().arrayLayers() == 4, "rich texture array layer count mismatch");
        rich.fullView().validateAgainst(3, 4);
        expectFailure(
                () -> new dev.dreamveil.prism.api.resource.PrismResourceViewDescriptor(2, 2, 0, 1)
                        .validateAgainst(3, 1),
                "out-of-range texture view must fail");
        var attachment = dev.dreamveil.prism.api.graphics.PrismAttachmentDescriptor.clearColor(0, 0, 0, 1);
        require(attachment.loadOp() == dev.dreamveil.prism.api.graphics.PrismAttachmentLoadOp.CLEAR,
                "attachment clear load op mismatch");
        require(java.util.Arrays.equals(attachment.clearValue(), new double[] {0, 0, 0, 1}),
                "attachment clear value mismatch");
    }


    private static void testShadowHelpers() {
        var shadows = dev.dreamveil.prism.api.shadow.PrismShadowApi.DEFAULT;
        var layout = shadows.computeCascadeSplits(new dev.dreamveil.prism.api.shadow.PrismCsmConfig(4, 0.1, 256.0, 0.65));
        require(layout.cascadeCount() == 4, "CSM helper must produce requested cascade count");
        require(layout.splitFarDistances().get(3) == 256.0, "last CSM split must end at far plane");
        require(Math.abs(shadows.snapToTexel(3.13, 0.25) - 3.25) < 1.0e-9, "texel snapping mismatch");
        var regions = shadows.computeCascadeRegions(
                new dev.dreamveil.prism.api.shadow.PrismCsmConfig(4, 0.1, 256.0, 0.65), 0.10);
        require(regions.size() == 4 && regions.get(0).blendStartDistance() < regions.get(0).farDistance(),
                "CSM blend region helper mismatch");
        var atlas = shadows.computeAtlasLayout(4, 2048);
        require(atlas.width() == 4096 && atlas.height() == 4096 && atlas.tiles().size() == 4,
                "CSM atlas helper mismatch");

        var clip = dev.dreamveil.prism.api.visibility.PrismClipConvention.reversedZeroToOne(
                0.1, 256.0, dev.dreamveil.prism.api.visibility.PrismProjectionType.PERSPECTIVE);
        require(clip.depthRange() == dev.dreamveil.prism.api.visibility.PrismDepthRange.ZERO_TO_ONE
                        && clip.depthDirection() == dev.dreamveil.prism.api.visibility.PrismDepthDirection.REVERSED_Z,
                "reversed zero-to-one clip convention helper mismatch");
        require(clip.normalizedForFrustum().depthDirection()
                        == dev.dreamveil.prism.api.visibility.PrismDepthDirection.FORWARD_Z,
                "frustum normalization convention must be forward-Z");
        require(clip.depthDirection().farDepthBufferValue() == 0.0
                        && clip.depthDirection().nearDepthBufferValue() == 1.0,
                "reversed-Z normalized depth endpoints mismatch");
        require(clip.depthDirection().farthestReduction()
                        == dev.dreamveil.prism.api.visibility.PrismDepthReduction.MIN,
                "reversed-Z HZB farthest reduction must use MIN");
        require(dev.dreamveil.prism.api.visibility.PrismDepthDirection.FORWARD_Z.farthestReduction()
                        == dev.dreamveil.prism.api.visibility.PrismDepthReduction.MAX,
                "forward-Z HZB farthest reduction must use MAX");
    }

    private static void testTemporalHints() {
        var temporal = new dev.dreamveil.prism.runtime.api.PrismTemporalApiImpl();
        var identity = dev.dreamveil.prism.api.frame.PrismMatrix4.identity();
        var frame0 = new dev.dreamveil.prism.api.frame.PrismFrameData(10, 0, 0.016f, 1920, 1080,
                dev.dreamveil.prism.api.frame.PrismVec3.ZERO, 0, 0, 1024, identity, identity, true);
        var frame1 = new dev.dreamveil.prism.api.frame.PrismFrameData(11, 0, 0.016f, 1920, 1080,
                new dev.dreamveil.prism.api.frame.PrismVec3(1, 0, 0), 0, 0, 1024, identity, identity, true);
        var resized = new dev.dreamveil.prism.api.frame.PrismFrameData(12, 0, 0.016f, 1280, 720,
                new dev.dreamveil.prism.api.frame.PrismVec3(1, 0, 0), 0, 0, 1024, identity, identity, true);
        temporal.publish(frame0);
        require(!temporal.snapshot().historyValid(), "first temporal frame must invalidate history");
        require(temporal.state().invalidationReasons().contains(
                        dev.dreamveil.prism.api.temporal.PrismTemporalInvalidationReason.FIRST_FRAME),
                "first-frame invalidation reason missing");
        temporal.publish(frame1);
        require(temporal.snapshot().historyValid(), "continuous frame should preserve temporal history");
        require(temporal.state().invalidationReasons().isEmpty(),
                "continuous frame must have no temporal invalidation reason");
        require(Math.abs(temporal.state().jitterX()) <= 0.5 && Math.abs(temporal.state().jitterY()) <= 0.5,
                "temporal jitter must be centered in half-pixel range");
        temporal.publish(resized);
        require(temporal.snapshot().resolutionChanged() && !temporal.snapshot().historyValid(),
                "resize must invalidate temporal history");
    }

    private static void testWorldPipelineApi() {
        var world = new dev.dreamveil.prism.runtime.api.PrismWorldRenderApiImpl();
        require(world.snapshot().vanillaFallback(), "world pipeline must start with always-valid vanilla fallback");
        require(world.supportedDomains().isEmpty(), "unattached creator API must not invent executable scene domains");
        world.publishSupportedDomains(java.util.EnumSet.of(
                dev.dreamveil.prism.api.render.PrismRenderDomain.TERRAIN_OPAQUE,
                dev.dreamveil.prism.api.render.PrismRenderDomain.TERRAIN_CUTOUT,
                dev.dreamveil.prism.api.render.PrismRenderDomain.TERRAIN_TRANSLUCENT,
                dev.dreamveil.prism.api.render.PrismRenderDomain.ENTITY_OPAQUE,
                dev.dreamveil.prism.api.render.PrismRenderDomain.ENTITY_TRANSLUCENT,
                dev.dreamveil.prism.api.render.PrismRenderDomain.BLOCK_ENTITY));
        require(world.supportsDomain(dev.dreamveil.prism.api.render.PrismRenderDomain.TERRAIN_OPAQUE),
                "terrain execution support must be queryable separately from enum recognition");
        require(world.supportsDomain(dev.dreamveil.prism.api.render.PrismRenderDomain.ENTITY_OPAQUE)
                        && world.supportsDomain(dev.dreamveil.prism.api.render.PrismRenderDomain.BLOCK_ENTITY),
                "scoped 26.2 Feature Rendering domains must be reported executable");
        world.publishFeaturePipelineStats(new dev.dreamveil.prism.api.render.PrismFeaturePipelineStats(
                7L, 2, 3, 1, 20L, 8L, 12L, 2L, 6L, 3L, 1L));
        require(world.featurePipelineStats().readyVariants() == 3
                        && world.featurePipelineStats().vanillaFallbacks() == 12L
                        && world.featurePipelineStats().budgetRejectedRequests() == 2L,
                "feature pipeline statistics publication failed");
        var shadowStatus = new dev.dreamveil.prism.api.shadow.PrismShadowExecutionSnapshot(
                true, false, true, "shadow_auxiliary_view_unavailable_26_2", "dedicated replay not active");
        world.publishShadowExecution(shadowStatus);
        require(world.shadowExecutionSnapshot().visibilityPlanningAvailable()
                        && !world.shadowExecutionSnapshot().gpuShadowPassAvailable()
                        && world.shadowExecutionSnapshot().recursiveLevelRenderForbidden(),
                "shadow bridge execution status publication failed");
        var activeDomains = java.util.EnumSet.of(
                dev.dreamveil.prism.api.render.PrismRenderDomain.TERRAIN_OPAQUE,
                dev.dreamveil.prism.api.render.PrismRenderDomain.TERRAIN_CUTOUT);
        world.publish(new dev.dreamveil.prism.api.render.PrismWorldPipelineSnapshot(
                7L, "creator.example",
                dev.dreamveil.prism.api.render.PrismWorldRenderPhase.OPAQUE_TERRAIN_COMPLETE,
                activeDomains, false));
        require(world.snapshot().generation() == 7L && world.snapshot().hasDomain(
                dev.dreamveil.prism.api.render.PrismRenderDomain.TERRAIN_OPAQUE),
                "world pipeline snapshot publication failed");
        require(dev.dreamveil.prism.api.resource.PrismSceneResources.COLOR.equals(PrismResources.MAIN_COLOR)
                        && dev.dreamveil.prism.api.resource.PrismSceneResources.DEPTH.equals(PrismResources.MAIN_DEPTH)
                        && dev.dreamveil.prism.api.resource.PrismSceneResources.HIERARCHICAL_DEPTH
                                .equals(PrismResources.SCENE_HIERARCHICAL_DEPTH),
                "semantic scene resources must alias the singular host color/depth/HZB handles");
        require(dev.dreamveil.prism.api.render.PrismRenderDomain.valueOf("BLOCK_ENTITY")
                        == dev.dreamveil.prism.api.render.PrismRenderDomain.BLOCK_ENTITY,
                "expanded semantic scene domain contract missing");
        require(PrismCapability.WORLD_RENDER_PIPELINE.name().equals("WORLD_RENDER_PIPELINE")
                        && PrismCapability.SEMANTIC_SCENE_RESOURCES.name().equals("SEMANTIC_SCENE_RESOURCES"),
                "world/semantic capabilities missing");
        world.clear();
        require(world.snapshot().equals(dev.dreamveil.prism.api.render.PrismWorldPipelineSnapshot.VANILLA),
                "world pipeline clear must restore vanilla fallback");
        require(world.featurePipelineStats().equals(dev.dreamveil.prism.api.render.PrismFeaturePipelineStats.EMPTY),
                "world pipeline clear must reset feature variant statistics");
        require(world.supportsDomain(dev.dreamveil.prism.api.render.PrismRenderDomain.TERRAIN_OPAQUE),
                "disabling a pack must not erase bridge execution capabilities");
        require(world.shadowExecutionSnapshot().equals(shadowStatus),
                "disabling a pack must not erase bridge-level shadow execution status");
        world.reset();
        require(world.supportedDomains().isEmpty(), "bridge reset must clear execution capabilities");
        require(world.shadowExecutionSnapshot().equals(
                        dev.dreamveil.prism.api.shadow.PrismShadowExecutionSnapshot.UNAVAILABLE),
                "bridge reset must clear shadow execution status");
    }

    private static void testAuditedMinecraftCapabilities() {
        var proven = dev.dreamveil.prism.backend.PrismMinecraft26_2Capabilities.proven();
        require(proven.contains(PrismCapability.WORLD_RENDER_PIPELINE)
                        && proven.contains(PrismCapability.SEMANTIC_SCENE_RESOURCES)
                        && proven.contains(PrismCapability.FEATURE_RENDER_MODEL_PIPELINES)
                        && proven.contains(PrismCapability.DYNAMIC_SCENE_PIPELINE_VARIANTS)
                        && proven.contains(PrismCapability.ENTITY_SCENE_PIPELINES)
                        && proven.contains(PrismCapability.BLOCK_ENTITY_SCENE_PIPELINES)
                        && proven.contains(PrismCapability.SHADOW_CASTER_CLASSIFICATION)
                        && proven.contains(PrismCapability.SHADOW_VISIBILITY_PLANNING)
                        && proven.contains(PrismCapability.PACK_FRAME_UNIFORMS)
                        && proven.contains(PrismCapability.PACK_TEMPORAL_HISTORY)
                        && proven.contains(PrismCapability.PACK_TEXTURE_ASSETS)
                        && proven.contains(PrismCapability.SCENE_PROJECTION_JITTER)
                        && proven.contains(PrismCapability.PACK_DYNAMIC_RESOLUTION)
                        && proven.contains(PrismCapability.RENDER_GRAPH_DIAGNOSTICS)
                        && proven.contains(PrismCapability.MULTIPLE_RENDER_TARGETS)
                        && proven.contains(PrismCapability.SCENE_GBUFFER_ATTACHMENTS)
                        && proven.contains(PrismCapability.MIPMAPPED_TEXTURES)
                        && proven.contains(PrismCapability.CUBEMAP_TEXTURES),
                "audited Minecraft capability set must include verified world Feature Rendering pipeline support");
        require(!proven.contains(PrismCapability.INDIRECT_DRAWS)
                        && !proven.contains(PrismCapability.MULTI_DRAW_INDIRECT),
                "indirect draw capabilities must not be inferred from Blaze3D primitives alone");
        require(!proven.contains(PrismCapability.COMPUTE_PIPELINES)
                        && !proven.contains(PrismCapability.STORAGE_IMAGES)
                        && !proven.contains(PrismCapability.STORAGE_BUFFERS)
                        && !proven.contains(PrismCapability.VULKAN_BACKEND),
                "portable capability set must not advertise Vulkan-native compute");
        var vulkan = dev.dreamveil.prism.backend.PrismMinecraft26_2Capabilities.proven(true);
        require(vulkan.contains(PrismCapability.VULKAN_BACKEND)
                        && vulkan.contains(PrismCapability.SCENE_HIERARCHICAL_DEPTH)
                        && vulkan.contains(PrismCapability.COMPUTE_PIPELINES)
                        && vulkan.contains(PrismCapability.STORAGE_IMAGES)
                        && vulkan.contains(PrismCapability.STORAGE_BUFFERS)
                        && vulkan.contains(PrismCapability.TEXTURE_ARRAYS)
                        && vulkan.contains(PrismCapability.TEXTURE_3D),
                "Vulkan capability negotiation must publish compute/storage/multidimensional textures");
        require(!proven.contains(PrismCapability.AUXILIARY_SCENE_VIEWS)
                        && !proven.contains(PrismCapability.DEPTH_ONLY_RENDER_TARGETS),
                "GPU shadow-view capabilities must remain disabled until the 26.2 auxiliary replay path is proven");
        try {
            proven.add(PrismCapability.COMPUTE_PIPELINES);
            throw new AssertionError("audited capability set must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
