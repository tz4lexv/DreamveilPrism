package dev.dreamveil.prism.pack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.api.pack.PrismPackDiagnostic;
import dev.dreamveil.prism.api.setting.PrismPackSetting;
import dev.dreamveil.prism.graph.PrismCompiledGraph;
import dev.dreamveil.prism.graph.PrismPass;
import dev.dreamveil.prism.graph.PrismPassExecutionType;
import dev.dreamveil.prism.graph.PrismRenderGraph;
import dev.dreamveil.prism.graph.PrismResourceAccess;
import dev.dreamveil.prism.graph.PrismResourceDescriptor;
import dev.dreamveil.prism.graph.PrismResourceRef;
import dev.dreamveil.prism.graph.PrismTextureFormat;
import dev.dreamveil.prism.graph.PrismResourceUsageState;
import dev.dreamveil.prism.mixin.GpuDeviceAccessorMixin;
import net.minecraft.resources.Identifier;

/** CPU preprocessing is parallel-capable; native Blaze3D pipeline compilation remains render-thread-owned. */
final class PrismPipelineCompiler {
    private PrismPipelineCompiler() {
    }

    static PreparationJob prepareAsync(
            PrismPackDefinition pack,
            List<PrismPackSetting> settings,
            Executor executor) {
        AtomicBoolean cancelled = new AtomicBoolean();
        PrismShaderSourceLoader.Session sourceSession = PrismShaderSourceLoader.session(pack.root(), cancelled::get);
        PrismProgramSet programSet = pack.programSet();
        List<CompletableFuture<PreparedPipeline>> futures = new ArrayList<>(programSet.orderedPrograms().size());
        for (PrismPipelineDefinition definition : programSet.orderedPrograms()) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                checkCancelled(cancelled);
                try {
                    if (definition.isScene()) {
                        PrismSceneExecutionSupport.requireSupported(
                                definition.sceneDomain(),
                                "program-set:" + definition.id() + ":domain");
                    }
                    PrismResourceBudget.validatePipeline(definition);
                    PreparedSources sources = prepareSources(pack, settings, definition, sourceSession);
                    checkCancelled(cancelled);
                    return new PreparedPipeline(definition, sources);
                } catch (PrismPackLoadException exception) {
                    throw new CompletionException(exception);
                }
            }, executor));
        }

        CompletableFuture<PreparedPack> result = CompletableFuture
                .allOf(futures.toArray(CompletableFuture[]::new))
                .thenApplyAsync(ignored -> {
                    checkCancelled(cancelled);
                    List<PreparedPipeline> prepared = futures.stream().map(CompletableFuture::join).toList();
                    try {
                        PreparedPack preparedPack = buildPreparedPack(pack, prepared);
                        checkCancelled(cancelled);
                        return preparedPack;
                    } catch (PrismPackLoadException exception) {
                        throw new CompletionException(exception);
                    }
                }, executor);
        return new PreparationJob(result, futures, cancelled);
    }

    private static void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Prism shader preprocessing cancelled");
        }
    }

    static CompileSession beginCompile(
            PreparedPack prepared,
            long generation,
            GpuDevice device,
            GpuFormat outputFormat,
            PrismCompiledPack previous,
            PrismPipelineSessionCache sessionCache) {
        return new CompileSession(prepared, generation, device, outputFormat, previous, sessionCache);
    }

    private static PreparedPack buildPreparedPack(
            PrismPackDefinition pack,
            List<PreparedPipeline> preparedPipelines) throws PrismPackLoadException {
        PrismRenderGraph graph = new PrismRenderGraph();
        java.util.LinkedHashSet<String> imports = new java.util.LinkedHashSet<>();
        java.util.Map<String, PrismPackTextureDefinition> packResources = new java.util.LinkedHashMap<>();
        java.util.Map<String, PrismPackBufferDefinition> packBuffers = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> usedPackResources = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> usedPackBuffers = new java.util.LinkedHashSet<>();
        List<PrismPackDiagnostic> diagnostics = new ArrayList<>();

        for (PrismPackTextureDefinition resource : pack.resources()) {
            packResources.put(resource.id(), resource);
        }
        for (PrismPackBufferDefinition buffer : pack.buffers()) packBuffers.put(buffer.id(), buffer);

        for (PreparedPipeline prepared : preparedPipelines) {
            PrismPipelineDefinition definition = prepared.definition();
            diagnostics.addAll(prepared.sources().diagnostics());
            if (definition.isScene()) {
                for (String output : definition.outputs().stream().skip(1).toList()) {
                    usedPackResources.add(output);
                }
                continue;
            }
            if (!definition.isGraphGraphics() && !definition.isCompute()) {
                continue;
            }
            if (definition.isSceneView()) usedPackResources.add(definition.sceneView().depth());
            for (String output : definition.outputs()) {
                if (packResources.containsKey(output)) {
                    usedPackResources.add(output);
                } else {
                    imports.add(PrismPackResources.toInternal(output));
                }
            }
            for (PrismSamplerBinding sampler : definition.samplers()) {
                if (packResources.containsKey(sampler.resource())) {
                    usedPackResources.add(sampler.resource());
                } else {
                    imports.add(PrismPackResources.toInternal(sampler.resource()));
                }
            }
            for (PrismStorageBinding storage : definition.storageImages()) {
                usedPackResources.add(storage.resource());
            }
            for (PrismStorageBinding storage : definition.storageBuffers()) {
                usedPackBuffers.add(storage.resource());
            }
        }

        for (String resourceId : usedPackResources) {
            PrismPackTextureDefinition resource = packResources.get(resourceId);
            if (resource.history()) {
                // History textures are owned and double-buffered by the pack runtime. They are
                // imported into the single-frame graph as two distinct physical views so sampling
                // the previous frame while writing the current frame is never a feedback loop.
                graph.importResource(PrismResourceDescriptor.importedTexture(
                        PrismPackResources.toInternal(resource.id())));
                graph.importResource(PrismResourceDescriptor.importedTexture(
                        PrismPackResources.previousHistory(resource.id())));
            } else if (resource.scene()) {
                // Scene attachments are allocated before Minecraft world draws and imported into
                // the final pack graph. Treating them as graph transients would allocate too late.
                graph.importResource(PrismResourceDescriptor.importedTexture(
                        PrismPackResources.toInternal(resource.id())));
            } else {
                graph.declareResource(PrismResourceDescriptor.transientTexture(
                        PrismPackResources.toInternal(resource.id()), resource.descriptor()));
            }
        }
        for (String resource : imports) {
            graph.importResource(PrismResourceDescriptor.importedTexture(resource));
        }
        for (String bufferId : usedPackBuffers) {
            PrismPackBufferDefinition buffer = packBuffers.get(bufferId);
            graph.declareResource(PrismResourceDescriptor.transientBuffer(
                    PrismPackResources.toInternal(buffer.id()), buffer.descriptor()));
        }

        for (PreparedPipeline prepared : preparedPipelines) {
            PrismPipelineDefinition definition = prepared.definition();
            if (!definition.isGraphGraphics() && !definition.isCompute()) {
                continue;
            }
            List<PrismResourceRef> refs = graphRefs(definition, packResources);
            graph.addPass(new PrismPass(
                    definition.id(),
                    refs,
                    definition.isCompute()
                            ? PrismPassExecutionType.COMPUTE
                            : PrismPassExecutionType.GRAPHICS, definition.after()));
        }

        final PrismCompiledGraph compiledGraph;
        try {
            compiledGraph = pack.requiredApi().compareTo(dev.dreamveil.prism.api.PrismApiVersion.V1_25) >= 0
                    ? graph.compileExplicit() : graph.compile();
        } catch (RuntimeException exception) {
            throw new PrismPackLoadException(
                    "graph_compile",
                    "Pack render graph is invalid: " + pack.diagnosticNames(exception.getMessage()),
                    "prism.json",
                    exception);
        }
        return new PreparedPack(pack, preparedPipelines, compiledGraph, diagnostics);
    }

    private static PreparedSources prepareSources(
            PrismPackDefinition pack,
            List<PrismPackSetting> settings,
            PrismPipelineDefinition definition,
            PrismShaderSourceLoader.Session sourceSession) throws PrismPackLoadException {
        if (definition.isCompute()) {
            String computeSource = pack.source(definition.compute(), sourceSession);
            PrismShaderLanguageValidator.validate(computeSource, definition.compute());
            validateFrameUniformContract(definition, "", computeSource);
            List<PrismPackDiagnostic> diagnostics = PrismShaderAnalyzer.analyzeCompute(
                    definition, computeSource);
            computeSource = PrismShaderDefines.inject(computeSource, settings);
            computeSource = PrismShaderDefines.injectTextureMetadata(
                    computeSource, definition.samplers(), pack.textureAssets());
            long fingerprint = 0xcbf29ce484222325L;
            fingerprint = mix(fingerprint, definition.id());
            fingerprint = mix(fingerprint, definition.type());
            fingerprint = mix(fingerprint, definition.compute());
            fingerprint = mix(fingerprint, definition.dispatch().toString());
            fingerprint = mix(fingerprint, Boolean.toString(definition.frameUniforms()));
            for (PrismSamplerBinding sampler : definition.samplers()) fingerprint = mix(fingerprint, sampler.toString());
            for (PrismStorageBinding storage : definition.storageImages()) fingerprint = mix(fingerprint, storage.toString());
            for (PrismStorageBinding storage : definition.storageBuffers()) fingerprint = mix(fingerprint, storage.toString());
            fingerprint = mix(fingerprint, computeSource);
            return new PreparedSources("", "", computeSource, fingerprint, diagnostics);
        }

        boolean inheritVertexShader = definition.inheritsVertexShader();
        String vertexSource = inheritVertexShader ? "" : pack.source(definition.vertex(), sourceSession);
        String fragmentSource = pack.source(definition.fragment(), sourceSession);
        if (!inheritVertexShader) {
            PrismShaderLanguageValidator.validate(vertexSource, definition.vertex());
        }
        PrismShaderLanguageValidator.validate(fragmentSource, definition.fragment());
        PrismSceneShaderContractValidator.validate(definition, vertexSource, fragmentSource);
        validateFrameUniformContract(definition, vertexSource, fragmentSource);

        List<PrismPackDiagnostic> diagnostics = PrismShaderAnalyzer.analyze(
                definition, vertexSource, fragmentSource);
        if (!inheritVertexShader) {
            vertexSource = PrismShaderDefines.inject(vertexSource, settings);
        }
        fragmentSource = PrismShaderDefines.inject(fragmentSource, settings);
        fragmentSource = PrismShaderDefines.injectTextureMetadata(
                fragmentSource, definition.samplers(), pack.textureAssets());

        long fingerprint = 0xcbf29ce484222325L;
        fingerprint = mix(fingerprint, definition.id());
        fingerprint = mix(fingerprint, definition.type());
        fingerprint = mix(fingerprint, definition.domain());
        if (definition.sceneView() != null) {
            fingerprint = mix(fingerprint, definition.sceneView().toString());
            fingerprint = mix(fingerprint, pack.resource(definition.sceneView().depth()).descriptor().toString());
        }
        fingerprint = mix(fingerprint, definition.output());
        for (String output : definition.additionalOutputs()) fingerprint = mix(fingerprint, output);
        fingerprint = mix(fingerprint, definition.vertex());
        fingerprint = mix(fingerprint, definition.fragment());
        for (String output : definition.outputs()) {
            PrismPackTextureDefinition outputResource = pack.resource(output);
            if (outputResource != null) {
                fingerprint = mix(fingerprint, outputResource.descriptor().toString());
                fingerprint = mix(fingerprint, outputResource.lifetime().name());
            }
        }
        fingerprint = mix(fingerprint, definition.blend());
        fingerprint = mix(fingerprint, Boolean.toString(definition.frameUniforms()));
        for (PrismSamplerBinding sampler : definition.samplers()) {
            fingerprint = mix(fingerprint, sampler.name());
            fingerprint = mix(fingerprint, sampler.resource());
            fingerprint = mix(fingerprint, sampler.filter());
            fingerprint = mix(fingerprint, sampler.wrap());
            fingerprint = mix(fingerprint, sampler.history());
        }
        fingerprint = mix(fingerprint, vertexSource);
        fingerprint = mix(fingerprint, fragmentSource);
        return new PreparedSources(vertexSource, fragmentSource, "", fingerprint, diagnostics);
    }

    private static PrismCompiledFullscreenPipeline compileFullscreenPipeline(
            PrismPackDefinition pack,
            PrismPipelineDefinition definition,
            String passName,
            GpuDevice device,
            List<GpuFormat> outputFormats,
            PreparedSources prepared) throws PrismPackLoadException {
        long pipelineFingerprint = mix(prepared.fingerprint(), outputFormats.toString());
        String fingerprintHex = Long.toUnsignedString(pipelineFingerprint, 16);
        String basePath = "pack/" + pack.id() + "/" + definition.id() + "/f" + fingerprintHex;
        Identifier vertexId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, basePath + "/vertex");
        Identifier fragmentId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, basePath + "/fragment");
        Identifier pipelineId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, basePath + "/pipeline");

        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(pipelineId)
                .withVertexShader(vertexId)
                .withFragmentShader(fragmentId)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(Optional.empty());
        if (definition.isSceneView()) {
            builder.withPrimitiveTopology(PrimitiveTopology.QUADS)
                    .withVertexBinding(0, definition.sceneView().capturedModels() ? PrismModelViewReplay.FORMAT
                            : net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID.vertexFormat())
                    .withCull(definition.sceneView().cull())
                    .withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(
                            definition.sceneView().reversedDepth()
                                    ? com.mojang.blaze3d.platform.CompareOp.GREATER_THAN
                                    : com.mojang.blaze3d.platform.CompareOp.LESS_THAN, definition.sceneView().depthWrite()));
        }
        for (int attachment = 0; attachment < outputFormats.size(); attachment++) {
            builder.withColorTargetState(attachment, new ColorTargetState(
                    blend(definition.blend()),
                    outputFormats.get(attachment),
                    ColorTargetState.WRITE_ALL));
        }

        if (!definition.samplers().isEmpty() || definition.frameUniforms() || definition.isSceneView()) {
            BindGroupLayout.Builder bindings = BindGroupLayout.builder();
            if (definition.isSceneView()) {
                bindings.withUniform("Globals", UniformType.UNIFORM_BUFFER);
                bindings.withSampler(definition.sceneView().atlasSampler());
            }
            for (PrismSamplerBinding sampler : definition.samplers()) {
                bindings.withSampler(sampler.name());
            }
            if (definition.frameUniforms()) {
                bindings.withUniform(PrismPackFrameUniforms.BINDING_NAME, UniformType.UNIFORM_BUFFER);
            }
            builder.withBindGroupLayout(bindings.build());
        }

        final RenderPipeline pipeline;
        try {
            RenderPipeline built = builder.build();
            pipeline = definition.isSceneView() && outputFormats.isEmpty()
                    ? PrismDepthOnlyPipeline.from(built) : built;
        } catch (RuntimeException exception) {
            throw new PrismPackLoadException(
                    "pipeline_build",
                    "Could not build pipeline '" + definition.id() + "': " + exception.getMessage(),
                    "prism.json",
                    exception);
        }

        ShaderSource source = (id, type) -> {
            if (type == ShaderType.VERTEX && id.equals(vertexId)) return prepared.vertexSource();
            if (type == ShaderType.FRAGMENT && id.equals(fragmentId)) return prepared.fragmentSource();
            return null;
        };

        final CompiledRenderPipeline compiled;
        try {
            compiled = device.precompilePipeline(pipeline, source);
        } catch (RuntimeException exception) {
            throw new PrismPackLoadException(
                    "shader_compile_exception",
                    "Shader compiler threw for pipeline '" + definition.id() + "': " + exception.getMessage(),
                    definition.fragment(),
                    exception);
        }
        if (!compiled.isValid()) {
            throw new PrismPackLoadException(
                    "shader_compile",
                    "Pipeline '" + definition.id()
                            + "' did not compile. See latest.log for the native compiler diagnostic.",
                    definition.vertex() + " + " + definition.fragment());
        }

        return new PrismCompiledFullscreenPipeline(
                definition.id(),
                passName,
                definition.outputs(),
                pipeline,
                definition.frameUniforms(),
                definition.samplers(),
                prepared.fingerprint(), definition.sceneView());
    }

    private static void validateFrameUniformContract(
            PrismPipelineDefinition definition,
            String vertexSource,
            String fragmentSource) throws PrismPackLoadException {
        boolean declaresFrameBlock = vertexSource.matches("(?s).*\\buniform\\s+PrismFrame\\b.*")
                || fragmentSource.matches("(?s).*\\buniform\\s+PrismFrame\\b.*");
        if (definition.frameUniforms() == declaresFrameBlock) return;
        if (definition.frameUniforms()) {
            throw new PrismPackLoadException(
                    "frame_uniform_contract",
                    "Program '" + definition.id() + "' enables frame_uniforms but does not declare the PrismFrame block. Add #include <prism/frame.glsl> after #version 450.",
                    definition.fragment());
        }
        throw new PrismPackLoadException(
                "frame_uniform_contract",
                "Program '" + definition.id() + "' declares PrismFrame but frame_uniforms is not enabled in prism.json",
                definition.fragment());
    }

    private static PrismCompiledScenePipeline compileScenePipeline(
            PrismPackDefinition pack,
            PrismPipelineDefinition definition,
            GpuDevice device,
            PreparedSources prepared) throws PrismPackLoadException {
        PrismSceneDomain domain = definition.sceneDomain();
        RenderPipeline template = PrismWorldRenderingPipeline.vanillaTemplate(domain);
        List<GpuFormat> additionalFormats = resolveAdditionalSceneOutputFormats(pack, definition);
        long pipelineFingerprint = mix(mix(prepared.fingerprint(), template.getLocation().toString()), additionalFormats.toString());
        String fingerprintHex = Long.toUnsignedString(pipelineFingerprint, 16);
        String basePath = "scene/" + pack.id() + "/" + definition.id() + "/f" + fingerprintHex;
        Identifier vertexId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, basePath + "/vertex");
        Identifier fragmentId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, basePath + "/fragment");
        Identifier pipelineId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, basePath + "/pipeline");

        final RenderPipeline pipeline;
        try {
            // Preserve Minecraft 26.2's vertex format, bind-group layouts, target formats,
            // reversed-depth/depth-stencil state, culling and primitive topology. Only the
            // pack-authored shader stages and identity are replaced.
            pipeline = PrismRenderPipelineDeriver.derive(
                    template, pipelineId, vertexId, fragmentId, additionalFormats);
        } catch (RuntimeException exception) {
            throw new PrismPackLoadException(
                    "scene_pipeline_build",
                    "Could not build scene pipeline '" + definition.id() + "' for "
                            + domain.manifestName() + ": " + exception.getMessage(),
                    "prism.json",
                    exception);
        }

        ShaderSource source = (id, type) -> {
            if (type == ShaderType.VERTEX && id.equals(vertexId)) return prepared.vertexSource();
            if (type == ShaderType.FRAGMENT && id.equals(fragmentId)) return prepared.fragmentSource();
            return null;
        };

        final CompiledRenderPipeline compiled;
        long compileStarted = System.nanoTime();
        PrismMod.LOGGER.info(
                "Prism scene pipeline native compile start: pack='{}', pipeline='{}', domain={}, pipelineId={}",
                pack.id(), definition.id(), domain.manifestName(), pipelineId);
        try {
            compiled = device.precompilePipeline(pipeline, source);
        } catch (RuntimeException exception) {
            long elapsedMs = (System.nanoTime() - compileStarted) / 1_000_000L;
            PrismMod.LOGGER.error(
                    "Prism scene pipeline native compile failed after {} ms: pack='{}', pipeline='{}', domain={}",
                    elapsedMs, pack.id(), definition.id(), domain.manifestName(), exception);
            throw new PrismPackLoadException(
                    "scene_shader_compile_exception",
                    "Shader compiler threw for scene pipeline '" + definition.id() + "': "
                            + exception.getMessage(),
                    definition.fragment(),
                    exception);
        }
        long compileElapsedMs = (System.nanoTime() - compileStarted) / 1_000_000L;
        if (compileElapsedMs >= 250L) {
            PrismMod.LOGGER.warn(
                    "Prism scene pipeline native compile was slow ({} ms): pack='{}', pipeline='{}', domain={}",
                    compileElapsedMs, pack.id(), definition.id(), domain.manifestName());
        } else {
            PrismMod.LOGGER.info(
                    "Prism scene pipeline native compile finished in {} ms: pack='{}', pipeline='{}', domain={}",
                    compileElapsedMs, pack.id(), definition.id(), domain.manifestName());
        }
        if (!compiled.isValid()) {
            throw new PrismPackLoadException(
                    "scene_shader_compile",
                    "Scene pipeline '" + definition.id() + "' for " + domain.manifestName()
                            + " did not compile. Its vertex inputs and bind-group declarations must match "
                            + "Minecraft 26.2's pipeline contract for that domain. See latest.log for the native compiler diagnostic.",
                    definition.vertex() + " + " + definition.fragment());
        }

        return new PrismCompiledScenePipeline(
                definition.id(),
                domain,
                pipeline,
                definition.outputs(),
                prepared.fingerprint());
    }

    static RenderPipeline compileFeatureVariant(
            String packId,
            long generation,
            PrismCompiledFeatureProgram program,
            RenderPipeline template,
            GpuDevice device) throws PrismPackLoadException {
        java.util.Objects.requireNonNull(packId, "packId");
        java.util.Objects.requireNonNull(program, "program");
        java.util.Objects.requireNonNull(template, "template");
        java.util.Objects.requireNonNull(device, "device");
        if (!PrismSceneExecutionSupport.isDynamicFeature(program.domain())) {
            throw new IllegalArgumentException("Not a dynamic Feature Rendering domain: " + program.domain().manifestName());
        }

        long pipelineFingerprint = mix(program.sourceFingerprint(), template.getLocation().toString());
        String fingerprintHex = Long.toUnsignedString(pipelineFingerprint, 16);
        String basePath = "feature/" + packId + "/g" + generation + "/" + program.pipelineId()
                + "/f" + fingerprintHex;
        Identifier vertexId = program.inheritVertexShader()
                ? template.getVertexShader()
                : Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, basePath + "/vertex");
        Identifier fragmentId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, basePath + "/fragment");
        Identifier pipelineId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, basePath + "/pipeline");

        final RenderPipeline pipeline;
        try {
            // The base Feature Rendering pipeline owns the vertex format, bind groups, targets,
            // blend/depth/cull state and topology. Prism changes only immutable shader stages.
            pipeline = PrismRenderPipelineDeriver.derive(
                    template, pipelineId, vertexId, fragmentId, program.additionalOutputFormats());
        } catch (RuntimeException exception) {
            throw new PrismPackLoadException(
                    "feature_pipeline_build",
                    "Could not derive feature pipeline '" + program.pipelineId() + "' for "
                            + program.domain().manifestName() + " from base pipeline "
                            + template.getLocation() + ": " + exception.getMessage(),
                    "feature:" + program.pipelineId(),
                    exception);
        }

        ShaderSource source = (id, type) -> {
            if (!program.inheritVertexShader()
                    && type == ShaderType.VERTEX
                    && id.equals(vertexId)) {
                return program.vertexSource();
            }
            if (type == ShaderType.FRAGMENT && id.equals(fragmentId)) return program.fragmentSource();
            // In inherited-vertex mode the base shader id intentionally remains Minecraft's.
            // Blaze3D can reuse the already-precompiled vanilla shader for that exact id/defines.
            return null;
        };

        long compileStarted = System.nanoTime();
        PrismMod.LOGGER.info(
                "Prism feature variant native compile start: pack='{}', program='{}', domain={}, vertexMode={}, basePipeline={}, derivedPipeline={}",
                packId, program.pipelineId(), program.domain().manifestName(),
                program.inheritVertexShader() ? "inherit" : "replace", template.getLocation(), pipelineId);
        final CompiledRenderPipeline compiled;
        try {
            compiled = device.precompilePipeline(pipeline, source);
        } catch (RuntimeException exception) {
            throw new PrismPackLoadException(
                    "feature_shader_compile_exception",
                    "Native compiler threw for feature program '" + program.pipelineId() + "' on base pipeline "
                            + template.getLocation() + ": " + exception.getMessage(),
                    "feature:" + program.pipelineId(),
                    exception);
        }
        long elapsedMs = (System.nanoTime() - compileStarted) / 1_000_000L;
        if (!compiled.isValid()) {
            throw new PrismPackLoadException(
                    "feature_shader_compile",
                    "Feature program '" + program.pipelineId() + "' did not compile for base pipeline "
                            + template.getLocation() + ". Vanilla fallback remains active for this variant.",
                    "feature:" + program.pipelineId());
        }
        if (elapsedMs >= 250L) {
            PrismMod.LOGGER.warn(
                    "Prism feature variant native compile was slow ({} ms): pack='{}', program='{}', domain={}, basePipeline={}",
                    elapsedMs, packId, program.pipelineId(), program.domain().manifestName(), template.getLocation());
        } else {
            PrismMod.LOGGER.info(
                    "Prism feature variant native compile finished in {} ms: pack='{}', program='{}', domain={}, basePipeline={}",
                    elapsedMs, packId, program.pipelineId(), program.domain().manifestName(), template.getLocation());
        }
        return pipeline;
    }

    private static List<GpuFormat> resolveOutputFormats(
            PrismPackDefinition pack,
            PrismPipelineDefinition definition,
            GpuFormat mainOutputFormat) throws PrismPackLoadException {
        List<GpuFormat> result = new ArrayList<>(definition.outputs().size());
        for (String output : definition.outputs()) {
            result.add(resolveOutputFormat(pack, output, mainOutputFormat));
        }
        return List.copyOf(result);
    }

    private static List<GpuFormat> resolveAdditionalSceneOutputFormats(
            PrismPackDefinition pack,
            PrismPipelineDefinition definition) throws PrismPackLoadException {
        List<GpuFormat> result = new ArrayList<>(Math.max(0, definition.outputs().size() - 1));
        for (int index = 1; index < definition.outputs().size(); index++) {
            PrismPackTextureDefinition resource = pack.resource(definition.outputs().get(index));
            if (resource == null || !resource.scene()) {
                throw new PrismPackLoadException(
                        "scene_output_resource",
                        "Missing scene-lifetime output resource '" + definition.outputs().get(index) + "'",
                        "prism.json");
            }
            result.add(gpuFormat(resource.descriptor().format()));
        }
        return List.copyOf(result);
    }

    private static GpuFormat resolveOutputFormat(
            PrismPackDefinition pack,
            String output,
            GpuFormat mainOutputFormat) throws PrismPackLoadException {
        if (output.equals(PrismPackResources.MAIN_COLOR)) return mainOutputFormat;
        PrismPackTextureDefinition resource = pack.resource(output);
        if (resource == null) {
            throw new PrismPackLoadException(
                    "pipeline_output_unknown",
                    "Unknown pack output resource '" + output + "'",
                    "prism.json");
        }
        return gpuFormat(resource.descriptor().format());
    }

    private static List<PrismResourceRef> graphRefs(
            PrismPipelineDefinition definition,
            Map<String, PrismPackTextureDefinition> packResources) {
        List<PrismResourceRef> refs = new ArrayList<>();
        if (definition.isSceneView()) {
            boolean load = definition.sceneView().loadDepth();
            refs.add(new PrismResourceRef(PrismPackResources.toInternal(definition.sceneView().depth()),
                    load ? PrismResourceAccess.READ_WRITE : PrismResourceAccess.WRITE,
                    load ? PrismResourceUsageState.DEPTH_ATTACHMENT_READ_WRITE : PrismResourceUsageState.DEPTH_ATTACHMENT_WRITE));
        }
        for (String output : definition.outputs()) {
            PrismPackTextureDefinition outputResource = packResources.get(output);
            refs.add(new PrismResourceRef(
                    PrismPackResources.toInternal(output),
                    outputResource == null || outputResource.history()
                            || (definition.isSceneView() && definition.sceneView().loadColor())
                            ? PrismResourceAccess.READ_WRITE : PrismResourceAccess.WRITE));
        }
        for (PrismSamplerBinding sampler : definition.samplers()) {
            refs.add(new PrismResourceRef(
                    PrismPackResources.toInternal(sampler.resource(), sampler.previousHistory()),
                    PrismResourceAccess.READ,
                    PrismResourceUsageState.SAMPLED_READ));
        }
        for (PrismStorageBinding storage : definition.storageImages()) {
            PrismResourceAccess access = storageAccess(storage);
            PrismResourceUsageState state = switch (access) {
                case READ -> PrismResourceUsageState.STORAGE_IMAGE_READ;
                case WRITE -> PrismResourceUsageState.STORAGE_IMAGE_WRITE;
                case READ_WRITE -> PrismResourceUsageState.STORAGE_IMAGE_READ_WRITE;
            };
            refs.add(new PrismResourceRef(
                    PrismPackResources.toInternal(storage.resource(), storage.previousHistory()),
                    access, state));
        }
        for (PrismStorageBinding storage : definition.storageBuffers()) {
            refs.add(new PrismResourceRef(
                    PrismPackResources.toInternal(storage.resource()), storageAccess(storage)));
        }
        return List.copyOf(refs);
    }

    private static PrismResourceAccess storageAccess(PrismStorageBinding storage) {
        if (storage.reads() && storage.writes()) return PrismResourceAccess.READ_WRITE;
        return storage.writes() ? PrismResourceAccess.WRITE : PrismResourceAccess.READ;
    }

    static GpuFormat gpuFormat(PrismTextureFormat format) {
        return switch (format) {
            case R8_UNORM -> GpuFormat.R8_UNORM;
            case RG8_UNORM -> GpuFormat.RG8_UNORM;
            case RGBA8_UNORM -> GpuFormat.RGBA8_UNORM;
            case R16_FLOAT -> GpuFormat.R16_FLOAT;
            case RG16_FLOAT -> GpuFormat.RG16_FLOAT;
            case RGBA16_FLOAT -> GpuFormat.RGBA16_FLOAT;
            case R32_FLOAT -> GpuFormat.R32_FLOAT;
            case RG32_FLOAT -> GpuFormat.RG32_FLOAT;
            case RGBA32_FLOAT -> GpuFormat.RGBA32_FLOAT;
            case RG11B10_FLOAT -> GpuFormat.RG11B10_FLOAT;
            case D16_UNORM -> GpuFormat.D16_UNORM;
            case D24_UNORM_S8_UINT -> GpuFormat.D24_UNORM_S8_UINT;
            case D32_FLOAT -> GpuFormat.D32_FLOAT;
            case D32_FLOAT_S8_UINT -> GpuFormat.D32_FLOAT_S8_UINT;
        };
    }

    private static Optional<BlendFunction> blend(String mode) {
        return switch (mode) {
            case "alpha" -> Optional.of(BlendFunction.TRANSLUCENT);
            case "additive" -> Optional.of(BlendFunction.ADDITIVE);
            case "opaque" -> Optional.empty();
            default -> throw new IllegalArgumentException("Unknown Prism blend mode: " + mode);
        };
    }

    private static long mix(long hash, String value) {
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    static Throwable unwrapPreparationFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }


    static final class PreparationJob {
        private final CompletableFuture<PreparedPack> result;
        private final List<CompletableFuture<PreparedPipeline>> children;
        private final AtomicBoolean cancelled;

        PreparationJob(
                CompletableFuture<PreparedPack> result,
                List<CompletableFuture<PreparedPipeline>> children,
                AtomicBoolean cancelled) {
            this.result = result;
            this.children = List.copyOf(children);
            this.cancelled = cancelled;
        }

        boolean isDone() {
            return result.isDone();
        }

        PreparedPack join() {
            return result.join();
        }

        void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            for (CompletableFuture<PreparedPipeline> child : children) {
                child.cancel(false);
            }
            result.cancel(false);
        }
    }

    static final class CompileSession {
        private final PreparedPack prepared;
        private final long generation;
        private final GpuDevice device;
        private final GpuFormat mainOutputFormat;
        private final PrismPipelineSessionCache sessionCache;
        private final Map<String, PrismCompiledFullscreenPipeline> previousFullscreenById;
        private final Map<String, PrismCompiledScenePipeline> previousSceneById;
        private final Map<String, PrismCompiledFeatureProgram> previousFeatureById;
        private final Map<String, PrismCompiledFullscreenPipeline> fullscreenById = new java.util.LinkedHashMap<>();
        private final Map<String, PrismCompiledScenePipeline> sceneById = new java.util.LinkedHashMap<>();
        private final Map<String, PrismCompiledFeatureProgram> featureById = new java.util.LinkedHashMap<>();
        private final Map<String, PrismCompiledComputePipeline> computeById = new java.util.LinkedHashMap<>();
        private int cursor;
        private int reused;
        private int nativeCompiles;
        private boolean ownershipTransferred;

        CompileSession(
                PreparedPack prepared,
                long generation,
                GpuDevice device,
                GpuFormat mainOutputFormat,
                PrismCompiledPack previous,
                PrismPipelineSessionCache sessionCache) {
            this.prepared = prepared;
            this.generation = generation;
            this.device = device;
            this.mainOutputFormat = mainOutputFormat;
            this.sessionCache = sessionCache;
            this.previousFullscreenById = previousFullscreenById(previous, prepared.pack().id());
            this.previousSceneById = previousSceneById(previous, prepared.pack().id());
            this.previousFeatureById = previousFeatureById(previous, prepared.pack().id());
        }

        void step(int maxNativeCompiles) throws PrismPackLoadException {
            if (maxNativeCompiles < 1) {
                throw new IllegalArgumentException("maxNativeCompiles must be >= 1");
            }
            int compiledThisStep = 0;
            while (cursor < prepared.pipelines().size()) {
                PreparedPipeline item = prepared.pipelines().get(cursor);
                PrismPipelineDefinition definition = item.definition();

                if (definition.isScene()) {
                    PrismSceneDomain domain = definition.sceneDomain();
                    if (PrismSceneExecutionSupport.isDynamicFeature(domain)) {
                        PrismCompiledFeatureProgram reusableFeature = previousFeatureById.get(definition.id());
                        if (reusableFeature != null
                                && reusableFeature.domain() == domain
                                && reusableFeature.sourceFingerprint() == item.sources().fingerprint()) {
                            featureById.put(definition.id(), reusableFeature);
                        } else {
                            featureById.put(definition.id(), new PrismCompiledFeatureProgram(
                                    definition.id(),
                                    domain,
                                    definition.inheritsVertexShader(),
                                    item.sources().vertexSource(),
                                    item.sources().fragmentSource(),
                                    definition.outputs(),
                                    resolveAdditionalSceneOutputFormats(prepared.pack(), definition),
                                    item.sources().fingerprint()));
                        }
                        // Concrete native variants are derived from exact Feature Rendering base
                        // pipelines later. CPU preparation consumes no native compile budget.
                        cursor++;
                        continue;
                    }

                    PrismCompiledScenePipeline reusable = previousSceneById.get(definition.id());
                    if (reusable != null
                            && reusable.domain() == domain
                            && reusable.sourceFingerprint() == item.sources().fingerprint()) {
                        sceneById.put(definition.id(), reusable);
                        reused++;
                        cursor++;
                        continue;
                    }
                    if (compiledThisStep >= maxNativeCompiles) {
                        return;
                    }
                    PrismCompiledScenePipeline fresh = compileScenePipeline(
                            prepared.pack(), definition, device, item.sources());
                    sceneById.put(definition.id(), fresh);
                    nativeCompiles++;
                    compiledThisStep++;
                    cursor++;
                    continue;
                }

                if (definition.isCompute()) {
                    if (compiledThisStep >= maxNativeCompiles) return;
                    Object backend = ((GpuDeviceAccessorMixin) (Object) device).prism$getBackend();
                    if (!(backend instanceof VulkanDevice vulkanDevice)) {
                        throw new PrismPackLoadException(
                                "compute_backend",
                                "Compute program '" + definition.id() + "' requires the Vulkan backend",
                                definition.compute());
                    }
                    String passName = "prism.pack." + prepared.pack().id() + ".g" + generation + "." + definition.id();
                    PrismCompiledComputePipeline fresh = PrismCompiledComputePipeline.compile(
                            prepared.pack(), definition, passName, vulkanDevice,
                            item.sources().computeSource(), item.sources().fingerprint());
                    computeById.put(definition.id(), fresh);
                    nativeCompiles++;
                    compiledThisStep++;
                    cursor++;
                    continue;
                }

                String passName = "prism.pack." + prepared.pack().id() + ".g" + generation + "." + definition.id();
                List<GpuFormat> outputFormats = resolveOutputFormats(
                        prepared.pack(), definition, mainOutputFormat);

                PrismCompiledFullscreenPipeline reusable = previousFullscreenById.get(definition.id());
                if (reusable != null && reusable.sourceFingerprint() == item.sources().fingerprint()) {
                    PrismCompiledFullscreenPipeline rebound = reusable.rebindPassName(passName);
                    fullscreenById.put(definition.id(), rebound);
                    sessionCache.put(device, outputFormats, prepared.pack().id(), reusable);
                    reused++;
                    cursor++;
                    continue;
                }

                reusable = sessionCache.find(
                        device,
                        outputFormats,
                        prepared.pack().id(),
                        definition.id(),
                        item.sources().fingerprint());
                if (reusable != null) {
                    fullscreenById.put(definition.id(), reusable.rebindPassName(passName));
                    reused++;
                    cursor++;
                    continue;
                }

                if (compiledThisStep >= maxNativeCompiles) {
                    return;
                }
                PrismCompiledFullscreenPipeline fresh = compileFullscreenPipeline(
                        prepared.pack(),
                        definition,
                        passName,
                        device,
                        outputFormats,
                        item.sources());
                fullscreenById.put(definition.id(), fresh);
                sessionCache.put(device, outputFormats, prepared.pack().id(), fresh);
                nativeCompiles++;
                compiledThisStep++;
                cursor++;
            }
        }

        boolean complete() {
            return cursor >= prepared.pipelines().size();
        }

        int reusedPipelines() {
            return reused;
        }

        int nativeCompiles() {
            return nativeCompiles;
        }

        PrismCompiledPack finish() throws PrismPackLoadException {
            if (!complete()) {
                throw new IllegalStateException("Prism compile session is not complete");
            }

            // Rebuild only the fullscreen subgraph with generation-specific executor ids. Scene
            // pipelines are consumed directly by Minecraft's terrain renderer through the registry.
            PrismRenderGraph graph = new PrismRenderGraph();
            for (var descriptor : prepared.graph().resources().values()) {
                if (descriptor.imported()) graph.importResource(descriptor);
                else graph.declareResource(descriptor);
            }
            java.util.Map<String, PrismPackTextureDefinition> packResources = new java.util.HashMap<>();
            for (PrismPackTextureDefinition resource : prepared.pack().resources()) {
                packResources.put(resource.id(), resource);
            }

            List<PrismCompiledFullscreenPipeline> fullscreen = new ArrayList<>();
            List<PrismCompiledScenePipeline> scene = new ArrayList<>();
            List<PrismCompiledFeatureProgram> features = new ArrayList<>();
            List<PrismCompiledComputePipeline> compute = new ArrayList<>();
            for (PreparedPipeline item : prepared.pipelines()) {
                PrismPipelineDefinition definition = item.definition();
                if (definition.isScene()) {
                    if (PrismSceneExecutionSupport.isDynamicFeature(definition.sceneDomain())) {
                        PrismCompiledFeatureProgram program = featureById.get(definition.id());
                        if (program == null) {
                            throw new IllegalStateException("Missing prepared feature program " + definition.id());
                        }
                        features.add(program);
                    } else {
                        PrismCompiledScenePipeline pipeline = sceneById.get(definition.id());
                        if (pipeline == null) {
                            throw new IllegalStateException("Missing compiled scene pipeline " + definition.id());
                        }
                        scene.add(pipeline);
                    }
                    continue;
                }

                if (definition.isCompute()) {
                    PrismCompiledComputePipeline pipeline = computeById.get(definition.id());
                    if (pipeline == null) throw new IllegalStateException("Missing compiled compute pipeline " + definition.id());
                    compute.add(pipeline);
                    graph.addPass(new PrismPass(
                            pipeline.passName(),
                            graphRefs(definition, packResources),
                            PrismPassExecutionType.COMPUTE,
                            definition.after().stream().map(id -> "prism.pack." + prepared.pack().id() + ".g" + generation + "." + id).toList()));
                    continue;
                }

                PrismCompiledFullscreenPipeline pipeline = fullscreenById.get(definition.id());
                if (pipeline == null) {
                    throw new IllegalStateException("Missing compiled fullscreen pipeline " + definition.id());
                }
                fullscreen.add(pipeline);
                graph.addPass(new PrismPass(
                        pipeline.passName(), graphRefs(definition, packResources), PrismPassExecutionType.GRAPHICS,
                        definition.after().stream().map(id -> "prism.pack." + prepared.pack().id() + ".g" + generation + "." + id).toList()));
            }

            final PrismCompiledGraph finalGraph;
            try {
                finalGraph = prepared.pack().requiredApi().compareTo(dev.dreamveil.prism.api.PrismApiVersion.V1_25) >= 0
                        ? graph.compileExplicit() : graph.compile();
            } catch (RuntimeException exception) {
                throw new PrismPackLoadException(
                        "graph_compile",
                        "Generation-specific pack render graph is invalid: " + prepared.pack().diagnosticNames(exception.getMessage()),
                        "prism.json",
                        exception);
            }
            PrismCompiledPack compiled = new PrismCompiledPack(
                    prepared.pack(),
                    generation,
                    finalGraph,
                    fullscreen,
                    scene,
                    features,
                    compute,
                    prepared.diagnostics(),
                    reused);
            ownershipTransferred = true;
            return compiled;
        }

        /** Releases Vulkan objects created by an incomplete transactional candidate. */
        void abort() {
            if (ownershipTransferred) return;
            ownershipTransferred = true;
            computeById.values().forEach(PrismCompiledComputePipeline::close);
            computeById.clear();
        }
    }

    private static Map<String, PrismCompiledFullscreenPipeline> previousFullscreenById(
            PrismCompiledPack previous,
            String packId) {
        if (previous == null || !previous.definition().id().equals(packId)) {
            return Map.of();
        }
        Map<String, PrismCompiledFullscreenPipeline> byId = new HashMap<>();
        for (PrismCompiledFullscreenPipeline pipeline : previous.pipelines()) {
            byId.put(pipeline.pipelineId(), pipeline);
        }
        return byId;
    }

    private static Map<String, PrismCompiledScenePipeline> previousSceneById(
            PrismCompiledPack previous,
            String packId) {
        if (previous == null || !previous.definition().id().equals(packId)) {
            return Map.of();
        }
        Map<String, PrismCompiledScenePipeline> byId = new HashMap<>();
        for (PrismCompiledScenePipeline pipeline : previous.scenePipelines()) {
            byId.put(pipeline.pipelineId(), pipeline);
        }
        return byId;
    }

    private static Map<String, PrismCompiledFeatureProgram> previousFeatureById(
            PrismCompiledPack previous,
            String packId) {
        if (previous == null || !previous.definition().id().equals(packId)) {
            return Map.of();
        }
        Map<String, PrismCompiledFeatureProgram> byId = new HashMap<>();
        for (PrismCompiledFeatureProgram program : previous.featurePrograms()) {
            byId.put(program.pipelineId(), program);
        }
        return byId;
    }

    record PreparedPack(
            PrismPackDefinition pack,
            List<PreparedPipeline> pipelines,
            PrismCompiledGraph graph,
            List<PrismPackDiagnostic> diagnostics) {
        PreparedPack {
            pipelines = List.copyOf(pipelines);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    record PreparedPipeline(PrismPipelineDefinition definition, PreparedSources sources) {
    }

    record PreparedSources(
            String vertexSource,
            String fragmentSource,
            String computeSource,
            long fingerprint,
            List<PrismPackDiagnostic> diagnostics) {
        PreparedSources {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
