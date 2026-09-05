package dev.dreamveil.prism.render;

import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.bridge.Blaze3DFrameResources;
import dev.dreamveil.prism.bridge.Blaze3DPassContext;
import dev.dreamveil.prism.bridge.Blaze3DPrismBackend;
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
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * v0.3 proof graph.
 *
 * Three logical quarter-resolution textures exercise Prism-owned allocation and
 * lifetime aliasing. Scratch A and C have non-overlapping lifetimes and therefore
 * share one physical slot; Scratch B overlaps both boundaries and receives a
 * second slot. A final visible pass still verifies Minecraft color/depth imports.
 */
public final class PrismProbeRenderer {
    private static final String SCRATCH_A = "prism.probe.scratch_a";
    private static final String SCRATCH_B = "prism.probe.scratch_b";
    private static final String SCRATCH_C = "prism.probe.scratch_c";

    private static final String PASS_SCRATCH_A = "prism.probe.transient_a";
    private static final String PASS_SCRATCH_B = "prism.probe.transient_b";
    private static final String PASS_SCRATCH_C = "prism.probe.transient_c";
    private static final String PASS_VISUAL = "prism.probe.visual";

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("dreamveil.prism.probe", "false"));

    private static final PrismTextureDesc SCRATCH_DESC = PrismTextureDesc.colorAttachment(
            PrismTextureExtent.relative(0.25),
            PrismTextureFormat.RGBA8_UNORM);

    private static final PrismCompiledGraph GRAPH = new PrismRenderGraph()
            .importResource(PrismResourceDescriptor.importedTexture(PrismHostResources.MAIN_COLOR))
            .importResource(PrismResourceDescriptor.importedTexture(PrismHostResources.MAIN_DEPTH))
            .declareResource(PrismResourceDescriptor.transientTexture(SCRATCH_A, SCRATCH_DESC))
            .declareResource(PrismResourceDescriptor.transientTexture(SCRATCH_B, SCRATCH_DESC))
            .declareResource(PrismResourceDescriptor.transientTexture(SCRATCH_C, SCRATCH_DESC))
            .addPass(PrismPass.of(
                    PASS_SCRATCH_A,
                    new PrismResourceRef(SCRATCH_A, PrismResourceAccess.WRITE)))
            .addPass(PrismPass.of(
                    PASS_SCRATCH_B,
                    new PrismResourceRef(SCRATCH_A, PrismResourceAccess.READ),
                    new PrismResourceRef(SCRATCH_B, PrismResourceAccess.WRITE)))
            .addPass(PrismPass.of(
                    PASS_SCRATCH_C,
                    new PrismResourceRef(SCRATCH_B, PrismResourceAccess.READ),
                    new PrismResourceRef(SCRATCH_C, PrismResourceAccess.WRITE)))
            .addPass(PrismPass.of(
                    PASS_VISUAL,
                    new PrismResourceRef(PrismHostResources.MAIN_COLOR, PrismResourceAccess.READ_WRITE),
                    new PrismResourceRef(PrismHostResources.MAIN_DEPTH, PrismResourceAccess.READ)))
            .compile();

    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(
                            PrismMod.MOD_ID, "pipeline/v0_3_transient_probe"))
                    .build());

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer STAGED_BUFFER = new StagedVertexBuffer(
            () -> "Dreamveil Prism validation probe buffer",
            RenderType.SMALL_BUFFER_SIZE);

    private static volatile ProbeState probeState;
    private static volatile boolean initialized;
    private static volatile boolean closed;

    private PrismProbeRenderer() {
    }

    public static void initialize(Blaze3DPrismBackend backend) {
        if (initialized || !ENABLED) {
            return;
        }
        initialized = true;
        closed = false;

        verifyAliasPlan();
        backend.registerPassExecutor(PASS_SCRATCH_A, PrismProbeRenderer::validateScratchA);
        backend.registerPassExecutor(PASS_SCRATCH_B, PrismProbeRenderer::validateScratchB);
        backend.registerPassExecutor(PASS_SCRATCH_C, PrismProbeRenderer::validateScratchC);
        backend.registerPassExecutor(PASS_VISUAL, PrismProbeRenderer::executeVisualPass);
        LevelExtractionEvents.END_EXTRACTION.register(PrismProbeRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(
                context -> render(backend, context));

        PrismMod.LOGGER.info(
                "Prism validation probe registered; logicalTransients={}, physicalSlots={}, aliasPlan={}",
                GRAPH.transientPlan().slotByResource().size(),
                GRAPH.transientPlan().slotCount(),
                GRAPH.transientPlan().slotByResource());
    }

    private static void extract(LevelExtractionContext context) {
        if (closed) {
            return;
        }
        // Immutable state is safe to hand from extraction to drawing when Mojang
        // advances those phases to overlap on separate threads.
        probeState = new ProbeState(0.0f, 100.0f, 0.0f, 0.10f, 0.85f, 1.0f, 0.55f);
    }

    private static void render(Blaze3DPrismBackend backend, LevelRenderContext context) {
        if (closed || probeState == null) {
            return;
        }

        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        Blaze3DFrameResources resources = Blaze3DFrameResources.fromMainTarget(mainTarget);
        backend.executeGraph(GRAPH, resources, context);
    }

    private static void verifyAliasPlan() {
        int slotA = GRAPH.transientPlan().slotFor(SCRATCH_A);
        int slotB = GRAPH.transientPlan().slotFor(SCRATCH_B);
        int slotC = GRAPH.transientPlan().slotFor(SCRATCH_C);
        if (slotA != slotC || slotA == slotB || GRAPH.transientPlan().slotCount() != 2) {
            throw new IllegalStateException(
                    "Prism transient alias invariant failed: A=" + slotA
                            + ", B=" + slotB + ", C=" + slotC
                            + ", slots=" + GRAPH.transientPlan().slotCount());
        }
    }

    private static void validateScratchA(Blaze3DPassContext context) {
        validateScratchExtent(context.requireTexture(SCRATCH_A));
    }

    private static void validateScratchB(Blaze3DPassContext context) {
        GpuTextureView a = context.requireTexture(SCRATCH_A);
        GpuTextureView b = context.requireTexture(SCRATCH_B);
        validateScratchExtent(a);
        validateScratchExtent(b);
        if (a == b) {
            throw new IllegalStateException(
                    "Prism allocator aliased overlapping transient lifetimes for Scratch A/B");
        }
    }

    private static void validateScratchC(Blaze3DPassContext context) {
        GpuTextureView b = context.requireTexture(SCRATCH_B);
        GpuTextureView c = context.requireTexture(SCRATCH_C);
        validateScratchExtent(b);
        validateScratchExtent(c);
        if (b == c) {
            throw new IllegalStateException(
                    "Prism allocator aliased overlapping transient lifetimes for Scratch B/C");
        }
    }

    private static void validateScratchExtent(GpuTextureView view) {
        if (view.getWidth(0) < 1 || view.getHeight(0) < 1) {
            throw new IllegalStateException("Prism transient texture resolved to an invalid extent");
        }
    }

    private static void executeVisualPass(Blaze3DPassContext context) {
        VertexFormat formatBinding = PIPELINE.getVertexFormatBinding(0);
        if (formatBinding == null) {
            throw new IllegalStateException("Prism probe pipeline has no vertex format binding 0");
        }

        PrimitiveTopology primitive = PIPELINE.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = STAGED_BUFFER.appendDraw(
                formatBinding,
                primitive,
                primitive == PrimitiveTopology.QUADS
                        ? RenderSystem.getProjectionType().vertexSorting()
                        : null);

        try {
            emitProbeGeometry(context.levelRenderContext(), draw);
            STAGED_BUFFER.upload();

            StagedVertexBuffer.ExecuteInfo info = STAGED_BUFFER.getExecuteInfo(draw);
            if (info != null) {
                draw(context, info);
            }
        } finally {
            STAGED_BUFFER.endFrame();
        }
    }

    private static void emitProbeGeometry(LevelRenderContext context, StagedVertexBuffer.Draw draw) {
        PoseStack matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;

        matrices.pushPose();
        try {
            matrices.translate(-camera.x, -camera.y, -camera.z);

            VertexConsumer builder = STAGED_BUFFER.getVertexBuilder(draw);
            ProbeState state = probeState;
            if (state == null) {
                return;
            }

            emitFilledBox(
                    matrices.last().pose(),
                    builder,
                    state.x(), state.y(), state.z(),
                    state.x() + 1.0f, state.y() + 1.0f, state.z() + 1.0f,
                    state.r(), state.g(), state.b(), state.a());
        } finally {
            matrices.popPose();
        }
    }

    private static void draw(Blaze3DPassContext context, StagedVertexBuffer.ExecuteInfo info) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(
                        RenderSystem.getModelViewMatrixCopy(),
                        COLOR_MODULATOR,
                        MODEL_OFFSET,
                        TEXTURE_MATRIX);

        GpuTextureView colorTexture = context.requireTexture(PrismHostResources.MAIN_COLOR);
        GpuTextureView depthTexture = context.requireTexture(PrismHostResources.MAIN_DEPTH);

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Dreamveil Prism validation visual probe",
                        colorTexture,
                        Optional.empty(),
                        depthTexture,
                        OptionalDouble.empty())) {
            renderPass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
            renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());
            renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    private static void emitFilledBox(
            Matrix4fc matrix,
            VertexConsumer buffer,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            float red, float green, float blue, float alpha) {

        face(buffer, matrix, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ,
                red, green, blue, alpha);
        face(buffer, matrix, maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ,
                red, green, blue, alpha);
        face(buffer, matrix, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ,
                red, green, blue, alpha);
        face(buffer, matrix, maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ,
                red, green, blue, alpha);
        face(buffer, matrix, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ,
                red, green, blue, alpha);
        face(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ,
                red, green, blue, alpha);
    }

    private static void face(
            VertexConsumer buffer,
            Matrix4fc matrix,
            float x0, float y0, float z0,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float red, float green, float blue, float alpha) {
        buffer.addVertex(matrix, x0, y0, z0).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, x1, y1, z1).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, x2, y2, z2).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, x3, y3, z3).setColor(red, green, blue, alpha);
    }

    public static void close(Blaze3DPrismBackend backend) {
        if (closed) {
            return;
        }
        closed = true;
        probeState = null;
        backend.unregisterPassExecutor(PASS_SCRATCH_A);
        backend.unregisterPassExecutor(PASS_SCRATCH_B);
        backend.unregisterPassExecutor(PASS_SCRATCH_C);
        backend.unregisterPassExecutor(PASS_VISUAL);
        STAGED_BUFFER.close();
        PrismMod.LOGGER.info("Prism validation probe resources released");
    }

    private record ProbeState(
            float x, float y, float z,
            float r, float g, float b, float a) {
    }
}
