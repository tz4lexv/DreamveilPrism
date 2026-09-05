package dev.dreamveil.prism.mixin;

import dev.dreamveil.prism.pack.PrismTaggedSubmitNodeCollector;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Scopes Prism tags to LevelRenderer's world entity/block-entity extraction only. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFeatureMixin {
    @ModifyVariable(method = "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private SubmitNodeCollector dreamveilPrism$tagEntitySubmits(SubmitNodeCollector original) {
        return PrismTaggedSubmitNodeCollector.entity(original);
    }

    @ModifyVariable(method = "submitBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private SubmitNodeCollector dreamveilPrism$tagBlockEntitySubmits(SubmitNodeCollector original) {
        return PrismTaggedSubmitNodeCollector.blockEntity(original);
    }
}
