package dev.dreamveil.prism.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.dreamveil.prism.bridge.PrismVulkanUsage;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Selects Vulkan 2D-array and 3D image views for Prism private texture usages. */
@Mixin(VulkanGpuTextureView.class)
public abstract class VulkanGpuTextureViewDimensionMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkImageViewCreateInfo;viewType(I)Lorg/lwjgl/vulkan/VkImageViewCreateInfo;"),
            require = 1)
    private VkImageViewCreateInfo dreamveilPrism$viewType(
            VkImageViewCreateInfo info,
            int original,
            VulkanDevice device,
            VulkanGpuTexture texture,
            int baseMipLevel,
            int mipLevels) {
        int usage = texture.usage();
        if ((usage & PrismVulkanUsage.TEXTURE_3D) != 0) {
            return info.viewType(VK12.VK_IMAGE_VIEW_TYPE_3D);
        }
        if ((usage & PrismVulkanUsage.TEXTURE_2D_ARRAY) != 0) {
            return info.viewType(VK12.VK_IMAGE_VIEW_TYPE_2D_ARRAY);
        }
        return info.viewType(original);
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkImageSubresourceRange;layerCount(I)Lorg/lwjgl/vulkan/VkImageSubresourceRange;"),
            require = 1)
    private VkImageSubresourceRange dreamveilPrism$layerCount(
            VkImageSubresourceRange range,
            int original,
            VulkanDevice device,
            VulkanGpuTexture texture,
            int baseMipLevel,
            int mipLevels) {
        return range.layerCount((texture.usage() & PrismVulkanUsage.TEXTURE_2D_ARRAY) != 0
                ? texture.getDepthOrLayers()
                : original);
    }
}
