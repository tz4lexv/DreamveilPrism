package dev.dreamveil.prism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dreamveil.prism.pack.PrismModelMotionCapture;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureMotionMixin {
    @WrapOperation(method = "prepareModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void prism$capture(Model<?> model, PoseStack pose, VertexConsumer vertices,
            int light, int overlay, int color, Operation<Void> original, ModelFeatureRenderer.Submit<?> submit) {
        var capture = PrismModelMotionCapture.begin(submit, vertices);
        VertexConsumer motionVertices = capture == null ? vertices : capture;
        var viewCapture = dev.dreamveil.prism.pack.PrismModelViewCapture.begin(submit, motionVertices);
        boolean success = false;
        try {
            original.call(model, pose, viewCapture == null ? motionVertices : viewCapture, light, overlay, color);
            success = true;
        } finally {
            dev.dreamveil.prism.pack.PrismModelViewCapture.finish(viewCapture, success);
            PrismModelMotionCapture.finish(capture, success);
        }
    }
}
