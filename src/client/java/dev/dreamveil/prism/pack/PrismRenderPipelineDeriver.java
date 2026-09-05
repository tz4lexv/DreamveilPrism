package dev.dreamveil.prism.pack;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.resources.Identifier;

/**
 * Rebuilds a vanilla Minecraft 26.2 {@link RenderPipeline} with Prism-authored
 * shader stages while preserving the immutable state exposed by the public Blaze3D API.
 *
 * <p>Fabric/vanilla 26.2 does not expose NeoForge's RenderPipeline#toBuilder()
 * helper. Keep all cloning logic here so scene and Feature Rendering variants
 * cannot silently diverge.</p>
 */
final class PrismRenderPipelineDeriver {
    private PrismRenderPipelineDeriver() {
    }

    static RenderPipeline derive(
            RenderPipeline template,
            Identifier location,
            Identifier vertexShader,
            Identifier fragmentShader) {
        return derive(template, location, vertexShader, fragmentShader, java.util.List.of());
    }

    static RenderPipeline derive(
            RenderPipeline template,
            Identifier location,
            Identifier vertexShader,
            Identifier fragmentShader,
            java.util.List<GpuFormat> additionalOutputFormats) {
        java.util.Objects.requireNonNull(template, "template");
        java.util.Objects.requireNonNull(location, "location");
        java.util.Objects.requireNonNull(vertexShader, "vertexShader");
        java.util.Objects.requireNonNull(fragmentShader, "fragmentShader");
        java.util.Objects.requireNonNull(additionalOutputFormats, "additionalOutputFormats");

        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(location)
                .withVertexShader(vertexShader)
                .withFragmentShader(fragmentShader)
                .withPolygonMode(template.getPolygonMode())
                .withCull(template.isCull())
                .withDepthStencilState(java.util.Optional.ofNullable(template.getDepthStencilState()))
                .withPrimitiveTopology(template.getPrimitiveTopology());

        // Bind groups are ordered. Reordering them changes descriptor/bind-group indices.
        for (var layout : template.getBindGroupLayouts()) {
            builder.withBindGroupLayout(layout);
        }

        // Preserve every existing target for the normal one-target path. Scene MRT is valid only
        // for a base pipeline whose sole active target is location zero; Prism then appends its
        // pack-owned G-buffer formats without changing vanilla target-zero state.
        ColorTargetState[] colorTargets = template.getColorTargetStates();
        if (colorTargets != null) {
            for (int index = 0; index < colorTargets.length; index++) {
                ColorTargetState state = colorTargets[index];
                if (!additionalOutputFormats.isEmpty() && index > 0 && state != null) {
                    throw new IllegalArgumentException(
                            "Cannot append Prism scene MRT to a base pipeline that already uses color target " + index);
                }
                if (state != null) {
                    builder.withColorTargetState(index, state);
                } else if (additionalOutputFormats.isEmpty()) {
                    builder.withUnusedColorTargetState(index);
                }
            }
        }
        if (!additionalOutputFormats.isEmpty()
                && (colorTargets == null || colorTargets.length == 0 || colorTargets[0] == null)) {
            throw new IllegalArgumentException("Scene MRT requires an active vanilla color target at location zero");
        }
        for (int index = 0; index < additionalOutputFormats.size(); index++) {
            builder.withColorTargetState(index + 1, new ColorTargetState(
                    java.util.Optional.empty(), additionalOutputFormats.get(index), ColorTargetState.WRITE_ALL));
        }

        // 26.2 supports multiple vertex-buffer bindings. A Feature pipeline must keep
        // the exact binding index and format chosen by Minecraft/modded RenderTypes.
        VertexFormat[] vertexBindings = template.getVertexFormatBindings();
        if (vertexBindings != null) {
            for (int index = 0; index < vertexBindings.length; index++) {
                VertexFormat format = vertexBindings[index];
                if (format != null) {
                    builder.withVertexBinding(index, format);
                }
            }
        }

        // Minecraft 26.2's public RenderPipeline API does not expose a stencil-test
        // getter. Do not guess at private state or call non-existent accessors here.
        // This derivation therefore guarantees preservation only for state observable
        // through the public getters copied above.

        copyShaderDefines(template, builder);
        return builder.build();
    }

    private static void copyShaderDefines(RenderPipeline template, RenderPipeline.Builder builder) {
        var defines = template.getShaderDefines();
        for (String flag : defines.flags()) {
            builder.withShaderDefine(flag);
        }

        for (var entry : defines.values().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            try {
                builder.withShaderDefine(key, Integer.parseInt(value));
                continue;
            } catch (NumberFormatException ignored) {
                // RenderPipeline.Builder only exposes int/float valued defines in vanilla 26.2.
            }

            try {
                float parsed = Float.parseFloat(value);
                if (!Float.isFinite(parsed)) {
                    throw new NumberFormatException("non-finite");
                }
                builder.withShaderDefine(key, parsed);
            } catch (NumberFormatException exception) {
                // Never silently drop a template define. If a mod constructs a pipeline with
                // a string-valued ShaderDefines entry that vanilla's public builder cannot
                // reproduce, this variant must stay vanilla instead of changing semantics.
                throw new IllegalArgumentException(
                        "Cannot faithfully derive pipeline '" + template.getLocation()
                                + "': shader define '" + key + "' has unsupported value '" + value + "'",
                        exception);
            }
        }
    }
}
