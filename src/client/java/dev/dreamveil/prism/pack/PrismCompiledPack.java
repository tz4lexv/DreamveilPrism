package dev.dreamveil.prism.pack;

import java.util.List;

import dev.dreamveil.prism.api.pack.PrismPackDiagnostic;
import dev.dreamveil.prism.graph.PrismCompiledGraph;

record PrismCompiledPack(
        PrismPackDefinition definition,
        long generation,
        PrismCompiledGraph graph,
        List<PrismCompiledFullscreenPipeline> pipelines,
        List<PrismCompiledScenePipeline> scenePipelines,
        List<PrismCompiledFeatureProgram> featurePrograms,
        List<PrismCompiledComputePipeline> computePipelines,
        List<PrismPackDiagnostic> diagnostics,
        int reusedPipelines) {
    PrismCompiledPack {
        pipelines = List.copyOf(pipelines);
        scenePipelines = List.copyOf(scenePipelines);
        featurePrograms = List.copyOf(featurePrograms);
        computePipelines = List.copyOf(computePipelines);
        diagnostics = List.copyOf(diagnostics);
        if (reusedPipelines < 0 || reusedPipelines > totalPipelineCount(pipelines, scenePipelines, computePipelines)) {
            throw new IllegalArgumentException("reusedPipelines out of range");
        }
    }

    int totalPipelineCount() {
        return totalPipelineCount(pipelines, scenePipelines, computePipelines);
    }

    List<String> pipelineIds() {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>(totalPipelineCount());
        for (PrismCompiledScenePipeline pipeline : scenePipelines) ids.add(pipeline.pipelineId());
        for (PrismCompiledFullscreenPipeline pipeline : pipelines) ids.add(pipeline.pipelineId());
        for (PrismCompiledComputePipeline pipeline : computePipelines) ids.add(pipeline.pipelineId());
        return List.copyOf(ids);
    }

    List<String> featureProgramIds() {
        return featurePrograms.stream().map(PrismCompiledFeatureProgram::pipelineId).toList();
    }

    private static int totalPipelineCount(
            List<PrismCompiledFullscreenPipeline> fullscreen,
            List<PrismCompiledScenePipeline> scene,
            List<PrismCompiledComputePipeline> compute) {
        return fullscreen.size() + scene.size() + compute.size();
    }
}
