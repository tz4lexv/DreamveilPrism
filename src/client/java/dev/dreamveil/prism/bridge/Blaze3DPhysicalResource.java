package dev.dreamveil.prism.bridge;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.graph.PrismResourceType;

sealed interface Blaze3DPhysicalResource extends AutoCloseable
        permits Blaze3DPhysicalResource.TextureResource, Blaze3DPhysicalResource.BufferResource {
    Blaze3DResourceKey key();

    PrismResourceType type();

    @Override
    void close();

    final class TextureResource implements Blaze3DPhysicalResource {
        private final Blaze3DResourceKey.TextureKey key;
        private final GpuTexture texture;
        private final GpuTextureView view;
        private boolean closed;

        TextureResource(Blaze3DResourceKey.TextureKey key, GpuTexture texture, GpuTextureView view) {
            this.key = key;
            this.texture = texture;
            this.view = view;
        }

        @Override
        public Blaze3DResourceKey.TextureKey key() {
            return key;
        }

        @Override
        public PrismResourceType type() {
            return PrismResourceType.TEXTURE;
        }

        GpuTexture texture() {
            return texture;
        }

        GpuTextureView view() {
            return view;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            // A view references its texture; destroy the view first.
            view.close();
            texture.close();
        }
    }

    final class BufferResource implements Blaze3DPhysicalResource {
        private final Blaze3DResourceKey.BufferKey key;
        private final GpuBuffer buffer;
        private boolean closed;

        BufferResource(Blaze3DResourceKey.BufferKey key, GpuBuffer buffer) {
            this.key = key;
            this.buffer = buffer;
        }

        @Override
        public Blaze3DResourceKey.BufferKey key() {
            return key;
        }

        @Override
        public PrismResourceType type() {
            return PrismResourceType.BUFFER;
        }

        GpuBuffer buffer() {
            return buffer;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            buffer.close();
        }
    }
}
