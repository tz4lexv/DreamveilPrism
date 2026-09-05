package dev.dreamveil.prism.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import dev.dreamveil.prism.bridge.PrismVulkanUsage;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Teaches Minecraft's Vulkan allocation path about Prism-owned true 3D texture assets. */
@Mixin(VulkanGpuTexture.class)
public abstract class VulkanGpuTextureDimensionMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;imageType(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"),
            require = 1)
    private VkImageCreateInfo dreamveilPrism$imageType(
            VkImageCreateInfo info,
            int original,
            VulkanDevice device,
            int usage,
            String label,
            GpuFormat format,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels) {
        return info.imageType((usage & PrismVulkanUsage.TEXTURE_3D) != 0
                ? VK12.VK_IMAGE_TYPE_3D
                : original);
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkExtent3D;set(III)Lorg/lwjgl/vulkan/VkExtent3D;"),
            require = 1)
    private VkExtent3D dreamveilPrism$extent(
            VkExtent3D extent,
            int width,
            int height,
            int originalDepth,
            VulkanDevice device,
            int usage,
            String label,
            GpuFormat format,
            int requestedWidth,
            int requestedHeight,
            int depthOrLayers,
            int mipLevels) {
        return extent.set(width, height,
                (usage & PrismVulkanUsage.TEXTURE_3D) != 0 ? depthOrLayers : originalDepth);
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;arrayLayers(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"),
            require = 1)
    private VkImageCreateInfo dreamveilPrism$arrayLayers(
            VkImageCreateInfo info,
            int original,
            VulkanDevice device,
            int usage,
            String label,
            GpuFormat format,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels) {
        return info.arrayLayers((usage & PrismVulkanUsage.TEXTURE_3D) != 0 ? 1 : original);
    }
}
