package dev.dreamveil.prism.pack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.bridge.Blaze3DPassContext;

final class PrismCompiledFullscreenPipeline {
    private final String pipelineId;
    private final String passName;
    private final List<String> outputResources;
    private final RenderPipeline pipeline;
    private final boolean frameUniforms;
    private final List<PrismSamplerBinding> samplers;
    private final long sourceFingerprint;
    private final PrismSceneViewDefinition sceneView;
    private boolean firstPresentationRecordLogged;
    private int replayDraws;
    private boolean replayLogged;

    PrismCompiledFullscreenPipeline(
            String pipelineId,
            String passName,
            List<String> outputResources,
            RenderPipeline pipeline,
            boolean frameUniforms,
            List<PrismSamplerBinding> samplers,
            long sourceFingerprint, PrismSceneViewDefinition sceneView) {
        this.pipelineId = pipelineId;
        this.passName = passName;
        this.outputResources = List.copyOf(outputResources);
        this.pipeline = pipeline;
        this.frameUniforms = frameUniforms;
        this.samplers = List.copyOf(samplers);
        this.sourceFingerprint = sourceFingerprint;
        this.sceneView = sceneView;
    }

    String pipelineId() {
        return pipelineId;
    }

    String passName() {
        return passName;
    }

    RenderPipeline pipeline() {
        return pipeline;
    }

    long sourceFingerprint() {
        return sourceFingerprint;
    }

    PrismCompiledFullscreenPipeline rebindPassName(String newPassName) {
        return new PrismCompiledFullscreenPipeline(
                pipelineId,
                newPassName,
                outputResources,
                pipeline,
                frameUniforms,
                samplers,
                sourceFingerprint, sceneView);
    }

    boolean hasSamplers() {
        return !samplers.isEmpty();
    }

    boolean usesFrameUniforms() {
        return frameUniforms;
    }

    void execute(
            Blaze3DPassContext context,
            Map<String, GpuSampler> samplerCache,
            GpuBuffer frameUniformBuffer,
            PrismGpuProfiler gpuProfiler) {
        List<GpuTextureView> outputs = outputResources.stream()
                .map(resource -> context.requireTexture(PrismPackResources.toInternal(resource)))
                .toList();
        GpuTextureView depth = sceneView == null ? null
                : context.requireTexture(PrismPackResources.toInternal(sceneView.depth()));
        GpuTextureView output = outputs.isEmpty() ? depth : outputs.getFirst();
        CommandEncoder commandEncoder = context.requireCommandEncoder();
        int outputWidth = output.getWidth(0);
        int outputHeight = output.getHeight(0);

        RenderPassDescriptor descriptor = RenderPassDescriptor.create(
                () -> "Dreamveil Prism pack pass " + passName)
                .withRenderArea(new RenderPass.RenderArea(0, 0, outputWidth, outputHeight));
        if (depth != null) {
            if (depth.getWidth(0) != outputWidth || depth.getHeight(0) != outputHeight) {
                throw new IllegalStateException("Replay attachment extent mismatch for " + passName);
            }
            descriptor.withDepthAttachment(depth, sceneView.loadDepth() ? java.util.OptionalDouble.empty()
                    : java.util.OptionalDouble.of(sceneView.reversedDepth() ? 0.0 : 1.0));
        }
        for (GpuTextureView attachment : outputs) {
            if (attachment.getWidth(0) != outputWidth || attachment.getHeight(0) != outputHeight) {
                throw new IllegalStateException(
                        "MRT output extents changed after validation for pass '" + passName + "'");
            }
            descriptor.withColorAttachment(attachment, sceneView == null || sceneView.loadColor() ? Optional.empty()
                    : Optional.of(new org.joml.Vector4f(0, 0, 0, 0)));
        }
        try (RenderPass renderPass = commandEncoder.createRenderPass(descriptor)) {
            renderPass.setPipeline(pipeline);
            if (sceneView != null) com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(renderPass);
            if (frameUniforms) {
                if (frameUniformBuffer == null) {
                    throw new IllegalStateException("PrismFrame uniform buffer is unavailable for pass '" + passName + "'");
                }
                renderPass.setUniform(PrismPackFrameUniforms.BINDING_NAME, frameUniformBuffer.slice());
            }
            if (!samplers.isEmpty() && samplerCache.isEmpty()) {
                throw new IllegalStateException("Prism pipeline requires a sampler but none is available");
            }
            for (PrismSamplerBinding binding : samplers) {
                GpuTextureView input = context.requireTexture(
                        PrismPackResources.toInternal(binding.resource(), binding.previousHistory()));
                GpuTexture texture = input.texture();
                if ((texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0) {
                    throw new IllegalStateException(
                            "Prism resource '" + binding.resource()
                                    + "' is not texture-bindable for sampler '" + binding.name() + "'");
                }
                GpuSampler selectedSampler = samplerCache.get(binding.samplerKey());
                if (selectedSampler == null) {
                    throw new IllegalStateException(
                            "Prism sampler " + binding.samplerKey()
                                    + " is unavailable for '" + binding.name() + "'");
                }
                renderPass.bindTexture(binding.name(), input, selectedSampler);
            }
            Runnable draw = sceneView == null ? () -> PrismFullscreenDraw.draw(renderPass)
                    : () -> replayDraws = sceneView.capturedModels() ? PrismModelViewReplay.draw(renderPass, sceneView)
                            : PrismTerrainReplay.draw(renderPass, sceneView, pipelineId);
            if (gpuProfiler != null) gpuProfiler.profileDraw(pipelineId, renderPass, draw);
            else draw.run();
        }

        if (!firstPresentationRecordLogged) {
            firstPresentationRecordLogged = true;
            PrismMod.LOGGER.info(
                    "Prism graphics pass recorded on Minecraft world-scene encoder: pass={}, pipeline={}, encoderClass={}, encoderId={}, attachments={}, output={}x{}, outputViewId={}",
                    passName,
                    pipelineId,
                    commandEncoder.getClass().getName(),
                    Integer.toHexString(System.identityHashCode(commandEncoder)),
                    outputs.size(),
                    outputWidth,
                    outputHeight,
                    Integer.toHexString(System.identityHashCode(output)));
        }
        if (sceneView != null && replayDraws > 0 && !replayLogged) {
            replayLogged = true;
            PrismMod.LOGGER.info("Prism creator replay: program={}, draws={}, depth={}, reversedZ={}, sectionRadius={}, layers={}, loadColor={}, loadDepth={}, depthWrite={}, geometry={}, modelDomains={}, modelRadius={}, visibility={}",
                    pipelineId, replayDraws, sceneView.depth(), sceneView.reversedDepth(), sceneView.sectionRadius(),
                    sceneView.layers(), sceneView.loadColor(), sceneView.loadDepth(), sceneView.depthWrite(),
                    sceneView.geometry(), sceneView.modelDomains(), sceneView.modelRadius(), sceneView.visibility());
        }
    }
}
