package dev.dreamveil.prism.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.bridge.Blaze3DFrameResources;
import dev.dreamveil.prism.graph.PrismHostResources;
import net.minecraft.resources.Identifier;

/**
 * Runtime-owned reversed-Z hierarchical depth texture.
 *
 * <p>The pyramid is allocated and generated only for a committed pack that samples the semantic
 * HZB resource. Mip zero copies Minecraft's raw scene depth into color-readable R32_FLOAT; every
 * following mip stores the minimum of the previous level's 2x2 footprint. MIN is the conservative
 * farthest-depth reduction for Minecraft 26.2's reversed-Z convention.</p>
 */
final class PrismHierarchicalDepthPyramid implements AutoCloseable {
    private static final String SAMPLER_NAME = "SourceDepth";
    private static final String VERTEX_SOURCE = """
            #version 450

            layout(location = 0) out vec2 prismUv;

            void main()
            {
                vec2 position = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                prismUv = position;
                gl_Position = vec4(position * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String COPY_SOURCE = """
            #version 450

            uniform sampler2D SourceDepth;
            layout(location = 0) out float HzbDepth;

            void main()
            {
                ivec2 pixel = ivec2(gl_FragCoord.xy);
                HzbDepth = texelFetch(SourceDepth, pixel, 0).r;
            }
            """;
    private static final String REDUCE_SOURCE = """
            #version 450

            uniform sampler2D SourceDepth;
            layout(location = 0) out float HzbDepth;

            void main()
            {
                ivec2 sourceSize = textureSize(SourceDepth, 0);
                ivec2 maximum = sourceSize - ivec2(1);
                ivec2 base = ivec2(gl_FragCoord.xy) * 2;
                float z00 = texelFetch(SourceDepth, min(base, maximum), 0).r;
                float z10 = texelFetch(SourceDepth, min(base + ivec2(1, 0), maximum), 0).r;
                float z01 = texelFetch(SourceDepth, min(base + ivec2(0, 1), maximum), 0).r;
                float z11 = texelFetch(SourceDepth, min(base + ivec2(1, 1), maximum), 0).r;
                HzbDepth = min(min(z00, z10), min(z01, z11));
            }
            """;

    private GpuDevice device;
    private GpuTexture texture;
    private GpuTextureView fullView;
    private List<GpuTextureView> mipViews = List.of();
    private GpuSampler nearestSampler;
    private RenderPipeline copyPipeline;
    private RenderPipeline reducePipeline;
    private int width = -1;
    private int height = -1;
    private long allocationGeneration;

    void prepareAndBind(
            GpuDevice requiredDevice,
            CommandEncoder commandEncoder,
            GpuTextureView sceneDepth,
            Blaze3DFrameResources resources) {
        if (requiredDevice == null || commandEncoder == null || sceneDepth == null || resources == null) {
            throw new IllegalArgumentException("HZB generation inputs must not be null");
        }
        GpuTexture sourceTexture = sceneDepth.texture();
        if ((sourceTexture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
            throw new IllegalStateException("minecraft:main_depth is not sampleable; cannot generate scene HZB");
        }
        int requiredWidth = sceneDepth.getWidth(0);
        int requiredHeight = sceneDepth.getHeight(0);
        if (requiredWidth < 1 || requiredHeight < 1) {
            throw new IllegalStateException("Cannot generate HZB from an empty scene depth texture");
        }
        ensureAllocated(requiredDevice, requiredWidth, requiredHeight);

        recordLevel(commandEncoder, copyPipeline, sceneDepth, mipViews.getFirst(), 0);
        for (int mip = 1; mip < mipViews.size(); mip++) {
            recordLevel(commandEncoder, reducePipeline, mipViews.get(mip - 1), mipViews.get(mip), mip);
        }
        resources.bindTexture(PrismHostResources.SCENE_HIERARCHICAL_DEPTH, fullView);
    }

    int mipLevels() {
        return mipViews.size();
    }

    long allocationGeneration() {
        return allocationGeneration;
    }

    private void ensureAllocated(GpuDevice requiredDevice, int requiredWidth, int requiredHeight) {
        if (device == requiredDevice && texture != null && !texture.isClosed()
                && width == requiredWidth && height == requiredHeight) {
            return;
        }
        close();
        device = requiredDevice;
        width = requiredWidth;
        height = requiredHeight;
        int mipCount = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        texture = device.createTexture(
                () -> "Dreamveil Prism scene HZB",
                usage,
                GpuFormat.R32_FLOAT,
                width,
                height,
                1,
                mipCount);
        try {
            fullView = device.createTextureView(texture);
            ArrayList<GpuTextureView> levels = new ArrayList<>(mipCount);
            mipViews = levels;
            for (int mip = 0; mip < mipCount; mip++) {
                levels.add(device.createTextureView(texture, mip, 1));
            }
            mipViews = List.copyOf(levels);
            nearestSampler = device.createSampler(
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,
                    FilterMode.NEAREST,
                    1,
                    OptionalDouble.empty());
            copyPipeline = createPipeline(device, "copy", COPY_SOURCE);
            reducePipeline = createPipeline(device, "reduce_min", REDUCE_SOURCE);
            allocationGeneration++;
            PrismMod.LOGGER.info(
                    "Prism scene HZB allocated: format=R32_FLOAT, reversedZReduction=MIN, size={}x{}, mips={}, generation={}",
                    width, height, mipCount, allocationGeneration);
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private void recordLevel(
            CommandEncoder commandEncoder,
            RenderPipeline pipeline,
            GpuTextureView source,
            GpuTextureView destination,
            int mip) {
        int outputWidth = destination.getWidth(0);
        int outputHeight = destination.getHeight(0);
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(
                        () -> "Dreamveil Prism scene HZB mip " + mip)
                .withRenderArea(new RenderPass.RenderArea(0, 0, outputWidth, outputHeight))
                .withColorAttachment(destination, Optional.empty());
        try (RenderPass pass = commandEncoder.createRenderPass(descriptor)) {
            pass.setPipeline(pipeline);
            pass.bindTexture(SAMPLER_NAME, source, nearestSampler);
            PrismFullscreenDraw.draw(pass);
        }
    }

    private static RenderPipeline createPipeline(GpuDevice device, String suffix, String fragmentSource) {
        Identifier vertexId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "hzb/fullscreen_vertex");
        Identifier fragmentId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "hzb/" + suffix + "_fragment");
        Identifier pipelineId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "hzb/" + suffix + "_pipeline");
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(pipelineId)
                .withVertexShader(vertexId)
                .withFragmentShader(fragmentId)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(Optional.empty())
                .withColorTargetState(new ColorTargetState(
                        Optional.<BlendFunction>empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler(SAMPLER_NAME).build())
                .build();
        ShaderSource source = (id, type) -> {
            if (type == ShaderType.VERTEX && id.equals(vertexId)) return VERTEX_SOURCE;
            if (type == ShaderType.FRAGMENT && id.equals(fragmentId)) return fragmentSource;
            return null;
        };
        CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, source);
        if (!compiled.isValid()) {
            throw new IllegalStateException("Prism HZB " + suffix + " pipeline did not compile; inspect latest.log");
        }
        return pipeline;
    }

    @Override
    public void close() {
        for (GpuTextureView view : List.copyOf(mipViews)) {
            view.close();
        }
        mipViews = List.of();
        if (fullView != null) {
            fullView.close();
            fullView = null;
        }
        if (nearestSampler != null) {
            nearestSampler.close();
            nearestSampler = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        copyPipeline = null;
        reducePipeline = null;
        device = null;
        width = -1;
        height = -1;
    }
}
