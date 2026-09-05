package dev.dreamveil.prism.mixin;

import com.mojang.blaze3d.systems.GpuDevice;
import dev.dreamveil.prism.bridge.PrismVulkanUsage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scoped validation extension for Prism-owned Vulkan array and 3D textures. */
@Mixin(GpuDevice.class)
public abstract class GpuDeviceTextureDimensionMixin {
    @Inject(method = "verifyTextureCreationArgs", at = @At("HEAD"), cancellable = true, require = 1)
    private void dreamveilPrism$verifyTextureDimension(
            int usage,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels,
            CallbackInfo ci) {
        boolean array = (usage & PrismVulkanUsage.TEXTURE_2D_ARRAY) != 0;
        boolean volume = (usage & PrismVulkanUsage.TEXTURE_3D) != 0;
        if (!array && !volume) return;
        if (array && volume) {
            throw new IllegalArgumentException("A Prism texture cannot be both 2D-array and 3D");
        }
        if (width < 1 || height < 1 || depthOrLayers < 2 || mipLevels < 1) {
            throw new IllegalArgumentException("Prism array/3D texture dimensions and mip count are invalid");
        }
        int largest = volume ? Math.max(Math.max(width, height), depthOrLayers) : Math.max(width, height);
        int maxMipLevels = 32 - Integer.numberOfLeadingZeros(largest);
        if (mipLevels > maxMipLevels) {
            throw new IllegalArgumentException(
                    "Prism array/3D texture requests " + mipLevels
                            + " mip levels; maximum is " + maxMipLevels);
        }
        ci.cancel();
    }
}
