package dev.dreamveil.prism.mixin;

import dev.dreamveil.prism.pack.PrismFeatureRenderTypeRegistry;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies READY Prism variants only to RenderTypes tagged during world Feature Rendering extraction. */
@Mixin(RenderType.class)
public abstract class RenderTypeMixin {
    @Inject(method = "prepare", at = @At("RETURN"), cancellable = true)
    private void dreamveilPrism$resolveWorldFeaturePipeline(CallbackInfoReturnable<PreparedRenderType> cir) {
        RenderType self = (RenderType) (Object) this;
        cir.setReturnValue(PrismFeatureRenderTypeRegistry.resolvePrepared(self, cir.getReturnValue()));
    }
}
