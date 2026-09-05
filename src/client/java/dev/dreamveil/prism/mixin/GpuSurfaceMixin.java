package dev.dreamveil.prism.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.dreamveil.prism.pack.PrismWorldRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Presentation fallback plus opt-in shadow-depth diagnostic boundary for Minecraft 26.2. */
@Mixin(targets = "com.mojang.blaze3d.systems.GpuSurface")
public abstract class GpuSurfaceMixin {
    @Inject(method = "blitFromTexture", at = @At("HEAD"), require = 1)
    private void dreamveilPrism$beforeSurfaceBlit(
            CommandEncoder commandEncoder,
            GpuTextureView source,
            CallbackInfo ci) {
        PrismWorldRenderingPipeline.finishBeforeSurfaceBlit(commandEncoder, source);
    }
}
