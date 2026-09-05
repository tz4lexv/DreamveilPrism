package dev.dreamveil.prism.pack;

import java.util.List;
import com.mojang.blaze3d.GpuFormat;

/**
 * Prepared scene shader stages for Minecraft Feature Rendering.
 *
 * <p>Feature geometry can use many vanilla RenderPipeline/vertex contracts. Prism therefore keeps
 * shader stages CPU-prepared here and derives immutable concrete Blaze3D variants lazily from the
 * exact base pipeline encountered during world Feature Rendering.</p>
 */
record PrismCompiledFeatureProgram(
        String pipelineId,
        PrismSceneDomain domain,
        boolean inheritVertexShader,
        String vertexSource,
        String fragmentSource,
        List<String> outputs,
        List<GpuFormat> additionalOutputFormats,
        long sourceFingerprint) {
    PrismCompiledFeatureProgram {
        if (pipelineId == null || pipelineId.isBlank()) throw new IllegalArgumentException("pipelineId must not be blank");
        if (domain == null) throw new IllegalArgumentException("domain must not be null");
        if (vertexSource == null || fragmentSource == null) throw new IllegalArgumentException("shader source must not be null");
        outputs = List.copyOf(outputs);
        additionalOutputFormats = List.copyOf(additionalOutputFormats);
        if (outputs.size() != additionalOutputFormats.size() + 1) {
            throw new IllegalArgumentException("Feature scene outputs/formats do not match");
        }
        if (inheritVertexShader && !vertexSource.isEmpty()) {
            throw new IllegalArgumentException("inherited vertex programs must not carry authored vertex source");
        }
    }
}
