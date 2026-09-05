package dev.dreamveil.prism.pack;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.List;

/** Precompiled scene pipeline that replaces one vanilla world-render domain. */
final class PrismCompiledScenePipeline {
    private final String pipelineId;
    private final PrismSceneDomain domain;
    private final RenderPipeline pipeline;
    private final List<String> outputs;
    private final long sourceFingerprint;

    PrismCompiledScenePipeline(
            String pipelineId,
            PrismSceneDomain domain,
            RenderPipeline pipeline,
            List<String> outputs,
            long sourceFingerprint) {
        this.pipelineId = pipelineId;
        this.domain = domain;
        this.pipeline = pipeline;
        this.outputs = List.copyOf(outputs);
        this.sourceFingerprint = sourceFingerprint;
    }

    String pipelineId() {
        return pipelineId;
    }

    PrismSceneDomain domain() {
        return domain;
    }

    RenderPipeline pipeline() {
        return pipeline;
    }

    List<String> outputs() {
        return outputs;
    }

    long sourceFingerprint() {
        return sourceFingerprint;
    }
}
