package dev.dreamveil.prism.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanConst;
import dev.dreamveil.prism.bridge.PrismVulkanUsage;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds Prism's private storage usages without changing Blaze3D's public API surface. */
@Mixin(VulkanConst.class)
public abstract class VulkanConstMixin {
    @Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true, require = 0)
    private static void dreamveilPrism$storageTexture(
            int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
        if ((usage & PrismVulkanUsage.STORAGE_TEXTURE) != 0) {
            cir.setReturnValue(cir.getReturnValue() | VK12.VK_IMAGE_USAGE_STORAGE_BIT);
        }
    }

    @Inject(method = "bufferUsageToVk", at = @At("RETURN"), cancellable = true, require = 0)
    private static void dreamveilPrism$storageBuffer(
            int usage, CallbackInfoReturnable<Integer> cir) {
        if ((usage & PrismVulkanUsage.STORAGE_BUFFER) != 0) {
            cir.setReturnValue(cir.getReturnValue() | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        }
    }
}
