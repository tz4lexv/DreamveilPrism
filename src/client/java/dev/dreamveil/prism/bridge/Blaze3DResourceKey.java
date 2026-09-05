package dev.dreamveil.prism.bridge;

import com.mojang.blaze3d.GpuFormat;

sealed interface Blaze3DResourceKey permits Blaze3DResourceKey.TextureKey, Blaze3DResourceKey.BufferKey {
    record TextureKey(GpuFormat format, int usage, int width, int height, int mipLevels) implements Blaze3DResourceKey {
    }

    record BufferKey(int usage, long sizeBytes) implements Blaze3DResourceKey {
    }
}
