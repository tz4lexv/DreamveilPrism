package dev.dreamveil.prism.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.dreamveil.prism.bridge.PrismVulkanUsage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps rectangular/volumetric Prism mip extents clamped to Vulkan's minimum of one texel. */
@Mixin(CommandEncoder.class)
public abstract class CommandEncoderTextureDimensionMixin {
    private static final String WRITE_TEXTURE =
            "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V";

    @Redirect(
            method = WRITE_TEXTURE,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/textures/GpuTexture;getWidth(I)I"),
            require = 2)
    private int dreamveilPrism$clampedMipWidth(GpuTexture texture, int mipLevel) {
        int width = texture.getWidth(mipLevel);
        return prismMultidimensional(texture) ? Math.max(1, width) : width;
    }

    @Redirect(
            method = WRITE_TEXTURE,
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/textures/GpuTexture;getHeight(I)I"),
            require = 2)
    private int dreamveilPrism$clampedMipHeight(GpuTexture texture, int mipLevel) {
        int height = texture.getHeight(mipLevel);
        return prismMultidimensional(texture) ? Math.max(1, height) : height;
    }

    private static boolean prismMultidimensional(GpuTexture texture) {
        return (texture.usage() & (PrismVulkanUsage.TEXTURE_2D_ARRAY | PrismVulkanUsage.TEXTURE_3D)) != 0;
    }
}
