package dev.dreamveil.prism.pack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import dev.dreamveil.prism.api.pack.PrismDiagnosticSeverity;
import dev.dreamveil.prism.api.pack.PrismPackDiagnostic;

/** Conservative static diagnostics. It reports source facts/heuristics and never pretends to be a GPU profiler. */
final class PrismShaderAnalyzer {
    private static final int LARGE_EXPANDED_SOURCE_BYTES = 256 * 1024;
    private static final int MANY_TEXTURE_CALLS = 32;
    private static final Pattern TEXTURE_CALL = Pattern.compile("\\btexture(?:Lod|Grad|Proj|Offset|Gather)?\\s*\\(");

    private PrismShaderAnalyzer() {
    }

    static List<PrismPackDiagnostic> analyze(
            PrismPipelineDefinition definition,
            String vertexSource,
            String fragmentSource) {
        List<PrismPackDiagnostic> warnings = new ArrayList<>();
        if (!definition.inheritsVertexShader()) {
            warnLargeSource(definition.vertex(), vertexSource, warnings);
        }
        warnLargeSource(definition.fragment(), fragmentSource, warnings);

        int calls = count(TEXTURE_CALL, fragmentSource);
        if (calls >= MANY_TEXTURE_CALLS) {
            warnings.add(warning(
                    "shader_texture_call_count",
                    "Expanded fragment source contains " + calls
                            + " static texture-call occurrences. This is a source heuristic, not a runtime sample count.",
                    definition.fragment()));
        }

        for (PrismSamplerBinding sampler : definition.samplers()) {
            Pattern reference = Pattern.compile("\\b" + Pattern.quote(sampler.name()) + "\\b");
            if (!reference.matcher(fragmentSource).find()) {
                warnings.add(warning(
                        "shader_sampler_unused",
                        "Sampler '" + sampler.name() + "' is declared in prism.json but is not referenced by expanded fragment source",
                        definition.fragment()));
            }
        }
        return List.copyOf(warnings);
    }

    static List<PrismPackDiagnostic> analyzeCompute(
            PrismPipelineDefinition definition,
            String computeSource) {
        List<PrismPackDiagnostic> warnings = new ArrayList<>();
        warnLargeSource(definition.compute(), computeSource, warnings);
        int calls = count(TEXTURE_CALL, computeSource);
        if (calls >= MANY_TEXTURE_CALLS) {
            warnings.add(warning(
                    "shader_texture_call_count",
                    "Expanded compute source contains " + calls
                            + " static texture-call occurrences. This is a source heuristic, not a runtime sample count.",
                    definition.compute()));
        }
        for (PrismSamplerBinding sampler : definition.samplers()) {
            if (!Pattern.compile("\\b" + Pattern.quote(sampler.name()) + "\\b").matcher(computeSource).find()) {
                warnings.add(warning(
                        "shader_sampler_unused",
                        "Sampler '" + sampler.name() + "' is declared but not referenced by compute source",
                        definition.compute()));
            }
        }
        return List.copyOf(warnings);
    }

    private static void warnLargeSource(String path, String source, List<PrismPackDiagnostic> warnings) {
        int bytes = source.getBytes(StandardCharsets.UTF_8).length;
        if (bytes >= LARGE_EXPANDED_SOURCE_BYTES) {
            warnings.add(warning(
                    "shader_large_expanded_source",
                    "Expanded shader source is " + bytes + " bytes; consider reducing generated/permutation code if compile times become high",
                    path));
        }
    }

    private static int count(Pattern pattern, String source) {
        int count = 0;
        var matcher = pattern.matcher(source);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static PrismPackDiagnostic warning(String code, String message, String source) {
        return new PrismPackDiagnostic(PrismDiagnosticSeverity.WARNING, code, message, source);
    }
}
