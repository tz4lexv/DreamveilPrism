package dev.dreamveil.prism.bridge;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;

import dev.dreamveil.prism.graph.PrismBufferUsage;
import dev.dreamveil.prism.graph.PrismTextureFormat;
import dev.dreamveil.prism.graph.PrismTextureUsage;

final class Blaze3DResourceMapper {
    private Blaze3DResourceMapper() {
    }

    static GpuFormat format(PrismTextureFormat format) {
        return switch (format) {
            case R8_UNORM -> GpuFormat.R8_UNORM;
            case RG8_UNORM -> GpuFormat.RG8_UNORM;
            case RGBA8_UNORM -> GpuFormat.RGBA8_UNORM;
            case R16_FLOAT -> GpuFormat.R16_FLOAT;
            case RG16_FLOAT -> GpuFormat.RG16_FLOAT;
            case RGBA16_FLOAT -> GpuFormat.RGBA16_FLOAT;
            case R32_FLOAT -> GpuFormat.R32_FLOAT;
            case RG32_FLOAT -> GpuFormat.RG32_FLOAT;
            case RGBA32_FLOAT -> GpuFormat.RGBA32_FLOAT;
            case RG11B10_FLOAT -> GpuFormat.RG11B10_FLOAT;
            case D16_UNORM -> GpuFormat.D16_UNORM;
            case D24_UNORM_S8_UINT -> GpuFormat.D24_UNORM_S8_UINT;
            case D32_FLOAT -> GpuFormat.D32_FLOAT;
            case D32_FLOAT_S8_UINT -> GpuFormat.D32_FLOAT_S8_UINT;
        };
    }

    static int textureUsage(java.util.Set<PrismTextureUsage> usages) {
        int result = 0;
        for (PrismTextureUsage usage : usages) {
            result |= switch (usage) {
                case COPY_DST -> GpuTexture.USAGE_COPY_DST;
                case COPY_SRC -> GpuTexture.USAGE_COPY_SRC;
                case SAMPLED -> GpuTexture.USAGE_TEXTURE_BINDING;
                case RENDER_ATTACHMENT -> GpuTexture.USAGE_RENDER_ATTACHMENT;
                case STORAGE -> PrismVulkanUsage.STORAGE_TEXTURE;
            };
        }
        return result;
    }

    static int bufferUsage(java.util.Set<PrismBufferUsage> usages) {
        int result = 0;
        for (PrismBufferUsage usage : usages) {
            result |= switch (usage) {
                case MAP_READ -> GpuBuffer.USAGE_MAP_READ;
                case MAP_WRITE -> GpuBuffer.USAGE_MAP_WRITE;
                case CLIENT_STORAGE_HINT -> GpuBuffer.USAGE_HINT_CLIENT_STORAGE;
                case COPY_DST -> GpuBuffer.USAGE_COPY_DST;
                case COPY_SRC -> GpuBuffer.USAGE_COPY_SRC;
                case VERTEX -> GpuBuffer.USAGE_VERTEX;
                case INDEX -> GpuBuffer.USAGE_INDEX;
                case UNIFORM -> GpuBuffer.USAGE_UNIFORM;
                case UNIFORM_TEXEL -> GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER;
                case INDIRECT -> GpuBuffer.USAGE_INDIRECT_PARAMETERS;
                case STORAGE -> PrismVulkanUsage.STORAGE_BUFFER;
            };
        }
        return result;
    }
}
