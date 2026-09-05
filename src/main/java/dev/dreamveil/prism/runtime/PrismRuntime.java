package dev.dreamveil.prism.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.api.internal.PrismApiLocator;
import dev.dreamveil.prism.bridge.PrismBridgeContext;
import dev.dreamveil.prism.bridge.PrismMinecraftBridge;
import dev.dreamveil.prism.graph.PrismCompiledGraph;
import dev.dreamveil.prism.graph.PrismHostResources;
import dev.dreamveil.prism.graph.PrismPass;
import dev.dreamveil.prism.graph.PrismRenderGraph;
import dev.dreamveil.prism.graph.PrismResourceAccess;
import dev.dreamveil.prism.graph.PrismResourceDescriptor;
import dev.dreamveil.prism.graph.PrismResourceRef;
import dev.dreamveil.prism.graph.PrismTextureDesc;
import dev.dreamveil.prism.graph.PrismTextureExtent;
import dev.dreamveil.prism.graph.PrismTextureFormat;
import dev.dreamveil.prism.runtime.api.PrismApiImpl;

public final class PrismRuntime implements AutoCloseable {
    private static final String SCRATCH_A = "prism.smoke.a";
    private static final String SCRATCH_B = "prism.smoke.b";
    private static final String SCRATCH_C = "prism.smoke.c";

    private final PrismMinecraftBridge bridge;
    private final AtomicBoolean initialized = new AtomicBoolean();
    private PrismApiImpl publicApi;

    public PrismRuntime(PrismMinecraftBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    public void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        boolean success = false;
        try {
            publicApi = new PrismApiImpl(
                    PrismMod.VERSION,
                    bridge.minecraftVersion(),
                    bridge.backend().info());
            PrismApiLocator.install(publicApi);

            bridge.initialize(new PrismBridgeContext(
                    frame -> {
                        publicApi.mutableFrames().publish(frame);
                        publicApi.mutableTemporal().publish(frame);
                    },
                    publicApi.mutablePacks(),
                    publicApi.mutableSettings(),
                    publicApi.mutablePerformance(),
                    publicApi.mutableWorldRendering()));

            PrismCompiledGraph smokeGraph = buildSmokeGraph();
            verifySmokeAliasPlan(smokeGraph);
            PrismMod.LOGGER.info(
                    "Prism runtime initialized: Prism {}, API {}, Minecraft {}, backend='{}' ({}), capabilities={}, graph={}, resources={}, transientSlots={}",
                    PrismMod.VERSION,
                    publicApi.apiVersion(),
                    bridge.minecraftVersion(),
                    bridge.backend().info().name(),
                    bridge.backend().info().implementation(),
                    bridge.backend().info().capabilities(),
                    smokeGraph.orderedPasses().stream().map(PrismPass::name).toList(),
                    smokeGraph.resources().keySet(),
                    smokeGraph.transientPlan().slotCount());
            success = true;
        } finally {
            if (!success) {
                try {
                    bridge.close();
                } catch (RuntimeException closeFailure) {
                    PrismMod.LOGGER.warn("Prism bridge cleanup failed after initialization error", closeFailure);
                }
                if (publicApi != null) {
                    publicApi.mutableFrames().clear();
                    publicApi.mutablePacks().clear();
                    publicApi.mutableSettings().clear();
                    publicApi.mutablePerformance().deactivate();
                    publicApi.mutableTemporal().clear();
                    publicApi.mutableWorldRendering().reset();
                    PrismApiLocator.uninstall(publicApi);
                    publicApi = null;
                }
                initialized.set(false);
            }
        }
    }

    public PrismMinecraftBridge bridge() {
        return bridge;
    }

    private static PrismCompiledGraph buildSmokeGraph() {
        PrismTextureDesc scratch = PrismTextureDesc.colorAttachment(
                PrismTextureExtent.relative(0.5),
                PrismTextureFormat.RGBA8_UNORM);

        return new PrismRenderGraph()
                .importResource(PrismResourceDescriptor.importedTexture(PrismHostResources.MAIN_COLOR))
                .declareResource(PrismResourceDescriptor.transientTexture(SCRATCH_A, scratch))
                .declareResource(PrismResourceDescriptor.transientTexture(SCRATCH_B, scratch))
                .declareResource(PrismResourceDescriptor.transientTexture(SCRATCH_C, scratch))
                .addPass(PrismPass.of(
                        "prism.smoke.produce_a",
                        new PrismResourceRef(PrismHostResources.MAIN_COLOR, PrismResourceAccess.READ),
                        new PrismResourceRef(SCRATCH_A, PrismResourceAccess.WRITE)))
                .addPass(PrismPass.of(
                        "prism.smoke.a_to_b",
                        new PrismResourceRef(SCRATCH_A, PrismResourceAccess.READ),
                        new PrismResourceRef(SCRATCH_B, PrismResourceAccess.WRITE)))
                .addPass(PrismPass.of(
                        "prism.smoke.b_to_c",
                        new PrismResourceRef(SCRATCH_B, PrismResourceAccess.READ),
                        new PrismResourceRef(SCRATCH_C, PrismResourceAccess.WRITE)))
                .addPass(PrismPass.of(
                        "prism.smoke.consume_c",
                        new PrismResourceRef(SCRATCH_C, PrismResourceAccess.READ),
                        new PrismResourceRef(PrismHostResources.MAIN_COLOR, PrismResourceAccess.READ_WRITE)))
                .compile();
    }

    private static void verifySmokeAliasPlan(PrismCompiledGraph graph) {
        int slotA = graph.transientPlan().slotFor(SCRATCH_A);
        int slotB = graph.transientPlan().slotFor(SCRATCH_B);
        int slotC = graph.transientPlan().slotFor(SCRATCH_C);
        if (slotA != slotC || slotA == slotB || graph.transientPlan().slotCount() != 2) {
            throw new IllegalStateException(
                    "Prism render-graph alias smoke test failed: A=" + slotA
                            + ", B=" + slotB + ", C=" + slotC);
        }
    }

    @Override
    public void close() {
        if (!initialized.compareAndSet(true, false)) {
            return;
        }

        try {
            bridge.close();
        } finally {
            if (publicApi != null) {
                publicApi.mutableFrames().clear();
                publicApi.mutablePacks().clear();
                publicApi.mutableSettings().clear();
                publicApi.mutablePerformance().deactivate();
                publicApi.mutableTemporal().clear();
                publicApi.mutableWorldRendering().reset();
                PrismApiLocator.uninstall(publicApi);
                publicApi = null;
            }
        }
    }
}
