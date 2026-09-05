package dev.dreamveil.prism.pack;

import java.util.List;

record PrismPipelineDefinition(
        String id,
        String type,
        String domain,
        String vertex,
        String fragment,
        String output,
        String blend,
        boolean frameUniforms,
        List<PrismSamplerBinding> samplers,
        String compute,
        List<String> additionalOutputs,
        List<PrismStorageBinding> storageImages,
        List<PrismStorageBinding> storageBuffers,
        PrismComputeDispatch dispatch,
        PrismSceneViewDefinition sceneView,
        List<String> after) {
    static final String INHERIT_VERTEX_SHADER = "$inherit";

    PrismPipelineDefinition {
        domain = domain == null ? "" : domain;
        output = output == null ? "" : output;
        blend = blend == null ? "" : blend;
        samplers = List.copyOf(samplers);
        compute = compute == null ? "" : compute;
        additionalOutputs = List.copyOf(additionalOutputs == null ? List.of() : additionalOutputs);
        storageImages = List.copyOf(storageImages == null ? List.of() : storageImages);
        storageBuffers = List.copyOf(storageBuffers == null ? List.of() : storageBuffers);
        after = List.copyOf(after == null ? List.of() : after);
    }

    PrismPipelineDefinition(String id, String type, String domain, String vertex, String fragment,
            String output, String blend, boolean frameUniforms, List<PrismSamplerBinding> samplers,
            String compute, List<String> additionalOutputs, List<PrismStorageBinding> storageImages,
            List<PrismStorageBinding> storageBuffers, PrismComputeDispatch dispatch) {
        this(id, type, domain, vertex, fragment, output, blend, frameUniforms, samplers, compute,
                additionalOutputs, storageImages, storageBuffers, dispatch, null, List.of());
    }

    PrismPipelineDefinition withAfter(List<String> dependencies) {
        return new PrismPipelineDefinition(id, type, domain, vertex, fragment, output, blend, frameUniforms,
                samplers, compute, additionalOutputs, storageImages, storageBuffers, dispatch, sceneView, dependencies);
    }

    boolean isSceneView() { return "scene_view".equals(type); }
    boolean isGraphGraphics() { return isFullscreen() || isSceneView(); }

    PrismPipelineDefinition(
            String id,
            String type,
            String domain,
            String vertex,
            String fragment,
            String output,
            String blend,
            boolean frameUniforms,
            List<PrismSamplerBinding> samplers) {
        this(id, type, domain, vertex, fragment, output, blend, frameUniforms, samplers,
                "", List.of(), List.of(), List.of(), null);
    }

    boolean inheritsVertexShader() {
        return isScene() && INHERIT_VERTEX_SHADER.equals(vertex);
    }

    boolean isFullscreen() {
        return "fullscreen".equals(type);
    }

    boolean isScene() {
        return "scene".equals(type);
    }

    boolean isCompute() {
        return "compute".equals(type);
    }

    List<String> outputs() {
        if (output.isBlank()) return List.of();
        if (additionalOutputs.isEmpty()) return List.of(output);
        java.util.ArrayList<String> result = new java.util.ArrayList<>(1 + additionalOutputs.size());
        result.add(output);
        result.addAll(additionalOutputs);
        return List.copyOf(result);
    }

    PrismSceneDomain sceneDomain() throws PrismPackLoadException {
        if (!isScene()) {
            throw new PrismPackLoadException(
                    "scene_domain_invalid",
                    "Pipeline '" + id + "' is not a scene pipeline",
                    "prism.json");
        }
        return PrismSceneDomain.parse(domain, "prism.json:pipeline:" + id + ":domain");
    }
}
