package dev.dreamveil.prism.mixin;

import java.nio.ByteBuffer;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.dreamveil.prism.bridge.PrismVulkanUsage;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkOffset3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Maps the public upload layer argument to a Z slice for Prism true 3D textures. */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderTextureDimensionMixin {
    private static final String WRITE_TEXTURE =
            "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V";

    @Redirect(
            method = WRITE_TEXTURE,
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkImageSubresourceLayers;baseArrayLayer(I)Lorg/lwjgl/vulkan/VkImageSubresourceLayers;"),
            require = 1)
    private VkImageSubresourceLayers dreamveilPrism$baseArrayLayer(
            VkImageSubresourceLayers layers,
            int original,
            GpuTexture texture,
            ByteBuffer data,
            int mipLevel,
            int layer,
            int x,
            int y,
            int width,
            int height) {
        return layers.baseArrayLayer((texture.usage() & PrismVulkanUsage.TEXTURE_3D) != 0 ? 0 : original);
    }

    @Redirect(
            method = WRITE_TEXTURE,
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VkOffset3D;set(III)Lorg/lwjgl/vulkan/VkOffset3D;"),
            require = 1)
    private VkOffset3D dreamveilPrism$imageOffset(
            VkOffset3D offset,
            int x,
            int y,
            int originalZ,
            GpuTexture texture,
            ByteBuffer data,
            int mipLevel,
            int layer,
            int requestedX,
            int requestedY,
            int width,
            int height) {
        return offset.set(x, y,
                (texture.usage() & PrismVulkanUsage.TEXTURE_3D) != 0 ? layer : originalZ);
    }
}
