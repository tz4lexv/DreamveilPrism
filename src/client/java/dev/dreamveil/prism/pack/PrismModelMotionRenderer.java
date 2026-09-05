package dev.dreamveil.prism.pack;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4f;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.bridge.Blaze3DFrameResources;
import dev.dreamveil.prism.graph.PrismHostResources;
import net.minecraft.resources.Identifier;

/** Rasterizes captured final model positions against live depth, without changing world color/depth. */
final class PrismModelMotionRenderer implements AutoCloseable {
    private static final String VERTEX = """
            #version 450
            in vec4 Position;
            in vec4 PreviousClip;
            in float HistoryValid;
            out vec4 motionCurrentClip;
            out vec4 motionPreviousClip;
            flat out float motionValid;
            void main() {
                gl_Position = Position;
                motionCurrentClip = Position;
                motionPreviousClip = PreviousClip;
                motionValid = HistoryValid;
            }
            """;
    private static final String FRAGMENT = """
            #version 450
            uniform sampler2D SceneDepth;
            in vec4 motionCurrentClip;
            in vec4 motionPreviousClip;
            flat in float motionValid;
            layout(location=0) out vec4 ModelMotion;
            void main() {
                float sceneZ = texelFetch(SceneDepth, ivec2(gl_FragCoord.xy), 0).r;
                // Reject occluded surfaces and alpha-cutout holes using actual world depth.
                float tolerance = max(2e-7, 1.5 * (abs(dFdx(gl_FragCoord.z)) + abs(dFdy(gl_FragCoord.z))));
                if (sceneZ <= 0.0 || abs(sceneZ - gl_FragCoord.z) > tolerance) discard;
                float valid = motionValid;
                vec2 velocity = vec2(0.0);
                if (motionPreviousClip.w <= 1e-6 || motionCurrentClip.w <= 1e-6) valid = 0.0;
                if (valid > 0.5) {
                    velocity = 0.5 * (motionCurrentClip.xy / motionCurrentClip.w
                            - motionPreviousClip.xy / motionPreviousClip.w);
                    if (any(isnan(velocity)) || any(isinf(velocity)) || any(greaterThan(abs(velocity), vec2(1.0)))) {
                        velocity = vec2(0.0);
                        valid = 0.0;
                    }
                }
                ModelMotion = vec4(velocity, valid, 1.0);
            }
            """;
    private GpuDevice device;
    private GpuTexture texture;
    private GpuTextureView view;
    private GpuSampler sampler;
    private GpuBuffer vertices;
    private RenderPipeline pipeline;
    private int width, height;
    private boolean logged;

    void prepareAndBind(GpuDevice gpu, CommandEncoder encoder, GpuTextureView depth, Blaze3DFrameResources resources) {
        ensure(gpu, depth.getWidth(0), depth.getHeight(0));
        ByteBuffer data = PrismModelMotionCapture.data();
        int count = data.remaining() / PrismModelMotionCapture.STRIDE;
        if (count > 0) encoder.writeToBuffer(vertices.slice(0, data.remaining()), data);
        var descriptor = RenderPassDescriptor.create(() -> "Prism model motion")
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height))
                .withColorAttachment(view, Optional.of(new Vector4f(0,0,0,0)));
        try (var pass = encoder.createRenderPass(descriptor)) {
            if (count > 0) {
                pass.setPipeline(pipeline);
                pass.bindTexture("SceneDepth", depth, sampler);
                pass.setVertexBuffer(0, vertices.slice());
                pass.draw(count, 1, 0, 0);
            }
        }
        resources.bindTexture(PrismHostResources.MODEL_MOTION, view);
        if (!logged && PrismModelMotionCapture.validVertices() > 0) {
            logged = true;
            PrismMod.LOGGER.info("Prism model motion executed: validHistoryVertices={}, rasterVertices={}, output={}x{}, format=RGBA16_FLOAT",
                    PrismModelMotionCapture.validVertices(), count, width, height);
        }
    }
    private void ensure(GpuDevice gpu, int w, int h) {
        if (device == gpu && view != null && width == w && height == h) return;
        if (w <= 0 || h <= 0 || PrismPackGpuBudget.modelMotionBytes(w, h) > PrismPackGpuBudget.configuredMaxBytes()) {
            throw new IllegalStateException("Model motion extent exceeds the Prism GPU memory budget: " + w + "x" + h);
        }
        close();
        device = gpu; width = w; height = h;
        try {
            texture = gpu.createTexture(() -> "Prism model motion",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.RGBA16_FLOAT, w, h, 1, 1);
            view = gpu.createTextureView(texture);
            sampler = gpu.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
            vertices = gpu.createBuffer(() -> "Prism model motion vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    PrismModelMotionCapture.MAX_VERTICES * 3L / 2 * PrismModelMotionCapture.STRIDE);
            var vertexId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "motion/model_vertex");
            var fragmentId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "motion/model_fragment");
            var format = VertexFormat.builder(0)
                    .addAttribute("Position", GpuFormat.RGBA32_FLOAT)
                    .addAttribute("PreviousClip", GpuFormat.RGBA32_FLOAT)
                    .addAttribute("HistoryValid", GpuFormat.R32_FLOAT).build();
            pipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "motion/models"))
                    .withVertexShader(vertexId).withFragmentShader(fragmentId)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withVertexBinding(0, format)
                    .withCull(false).withDepthStencilState(Optional.empty())
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
                    .withBindGroupLayout(BindGroupLayout.builder().withSampler("SceneDepth").build()).build();
            ShaderSource source = (id, type) -> type == ShaderType.VERTEX && id.equals(vertexId) ? VERTEX
                    : type == ShaderType.FRAGMENT && id.equals(fragmentId) ? FRAGMENT : null;
            if (!gpu.precompilePipeline(pipeline, source).isValid()) throw new IllegalStateException("Model motion pipeline compilation failed");
        } catch (RuntimeException | Error error) { close(); throw error; }
    }
    public void close() {
        if (vertices != null) vertices.close();
        if (view != null) view.close();
        if (texture != null) texture.close();
        if (sampler != null) sampler.close();
        vertices = null; view = null; texture = null; sampler = null; pipeline = null; device = null; logged = false;
    }
}
