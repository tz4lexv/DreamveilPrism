package dev.dreamveil.prism.pack;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;

/** Minecraft 26.2's builder defaults to one color target; depth-only needs zero. */
final class PrismDepthOnlyPipeline extends RenderPipeline {
    private PrismDepthOnlyPipeline(RenderPipeline template) {
        super(template.getLocation(), template.getVertexShader(), template.getFragmentShader(),
                template.getShaderDefines(), template.getBindGroupLayouts(), new ColorTargetState[0],
                template.getDepthStencilState(), template.getPolygonMode(), template.isCull(),
                template.getVertexFormatBindings().clone(), template.getPrimitiveTopology(), template.getSortKey());
    }

    static RenderPipeline from(RenderPipeline template) {
        if (template.getDepthStencilState() == null) {
            throw new IllegalArgumentException("A depth-only pipeline must declare depth state");
        }
        // Do not use a null color slot: CommandEncoder dereferences its first attachment.
        return new PrismDepthOnlyPipeline(template);
    }
}
