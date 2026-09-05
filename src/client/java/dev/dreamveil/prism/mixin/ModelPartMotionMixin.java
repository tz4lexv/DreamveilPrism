package dev.dreamveil.prism.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dreamveil.prism.pack.PrismModelMotionCapture;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
public abstract class ModelPartMotionMixin {
    @Inject(method = "compile", at = @At("HEAD"))
    private void prism$part(PoseStack.Pose pose, VertexConsumer vertices, int light, int overlay, int color, CallbackInfo ci) {
        if (PrismModelMotionCapture.enabled()) PrismModelMotionCapture.part(this);
    }
}
