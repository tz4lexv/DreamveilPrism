package dev.dreamveil.prism.mixin;

import dev.dreamveil.prism.pack.PrismMotionIdentity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMotionMixin {
    @Inject(method = "createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
    private void prism$identify(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> ci) {
        ((PrismMotionIdentity) ci.getReturnValue()).prism$motionId(entity.getUUID());
    }
}
