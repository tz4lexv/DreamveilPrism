package dev.dreamveil.prism.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.graph.PrismBufferDesc;
import dev.dreamveil.prism.graph.PrismResourceDescriptor;
import dev.dreamveil.prism.graph.PrismResourceType;
import dev.dreamveil.prism.graph.PrismTextureDesc;

/**
 * Device-local cache of physical resources backing transient Prism slots.
 * Logical aliasing is decided by the backend-neutral graph compiler; this pool
 * reuses the resulting physical slot allocations across frames.
 */
final class Blaze3DTransientResourcePool implements AutoCloseable {
    private final GpuDevice device;
    private final Map<Blaze3DResourceKey, ArrayDeque<Blaze3DPhysicalResource>> free = new LinkedHashMap<>();
    private final Set<Blaze3DPhysicalResource> active = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private int referenceWidth = -1;
    private int referenceHeight = -1;
    private double dynamicScale = -1.0;
    private long createdTextures;
    private long closedTextures;
    private long createdTextureViews;
    private long closedTextureViews;
    private long createdBuffers;
    private long closedBuffers;
    private long reuseHits;
    private boolean closed;

    Blaze3DTransientResourcePool(GpuDevice device) {
        this.device = Objects.requireNonNull(device, "device");
    }

    GpuDevice device() {
        return device;
    }

    void prepareFrame(int width, int height, double requiredDynamicScale) {
        ensureOpen();
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Reference extent must be positive");
        }
        if (!Double.isFinite(requiredDynamicScale) || requiredDynamicScale <= 0.0 || requiredDynamicScale > 1.0) {
            throw new IllegalArgumentException("Dynamic scale must be within (0,1]");
        }
        if (referenceWidth != width || referenceHeight != height
                || Double.compare(dynamicScale, requiredDynamicScale) != 0) {
            if (!active.isEmpty()) {
                throw new IllegalStateException("Cannot resize Prism transient pool while resources are active");
            }
            closeFreeResources();
            referenceWidth = width;
            referenceHeight = height;
            dynamicScale = requiredDynamicScale;
        }
    }

    Blaze3DPhysicalResource acquire(PrismResourceDescriptor descriptor) {
        ensureOpen();
        if (descriptor.imported()) {
            throw new IllegalArgumentException("Cannot allocate imported Prism resource: " + descriptor.name());
        }

        Blaze3DResourceKey key = keyFor(descriptor);
        ArrayDeque<Blaze3DPhysicalResource> queue = free.get(key);
        Blaze3DPhysicalResource resource = queue == null ? null : queue.pollFirst();
        if (resource == null) {
            resource = createResource(descriptor, key);
        } else {
            reuseHits++;
        }

        if (!active.add(resource)) {
            throw new IllegalStateException("Prism transient resource acquired twice");
        }
        return resource;
    }

    void release(Blaze3DPhysicalResource resource) {
        if (resource == null) {
            return;
        }
        ensureOpen();
        if (!active.remove(resource)) {
            throw new IllegalStateException("Prism transient resource released without active ownership");
        }
        free.computeIfAbsent(resource.key(), ignored -> new ArrayDeque<>()).addLast(resource);
    }

    Blaze3DTransientPoolStats stats() {
        int cached = free.values().stream().mapToInt(ArrayDeque::size).sum();
        return new Blaze3DTransientPoolStats(
                createdTextures,
                closedTextures,
                createdTextureViews,
                closedTextureViews,
                createdBuffers,
                closedBuffers,
                reuseHits,
                cached,
                active.size());
    }

    private Blaze3DResourceKey keyFor(PrismResourceDescriptor descriptor) {
        return switch (descriptor.type()) {
            case TEXTURE -> textureKey(descriptor.textureDesc());
            case BUFFER -> bufferKey(descriptor.bufferDesc());
        };
    }

    private Blaze3DResourceKey.TextureKey textureKey(PrismTextureDesc desc) {
        int width = desc.extent().resolveWidth(referenceWidth, dynamicScale);
        int height = desc.extent().resolveHeight(referenceHeight, dynamicScale);
        int maxMipLevels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        if (desc.mipLevels() > maxMipLevels) {
            throw new IllegalArgumentException(
                    "Prism texture requests " + desc.mipLevels() + " mip levels for "
                            + width + "x" + height + "; maximum is " + maxMipLevels);
        }

        GpuFormat format = Blaze3DResourceMapper.format(desc.format());
        int usage = Blaze3DResourceMapper.textureUsage(desc.usages());
        return new Blaze3DResourceKey.TextureKey(format, usage, width, height, desc.mipLevels());
    }

    private static Blaze3DResourceKey.BufferKey bufferKey(PrismBufferDesc desc) {
        return new Blaze3DResourceKey.BufferKey(
                Blaze3DResourceMapper.bufferUsage(desc.usages()),
                desc.sizeBytes());
    }

    private Blaze3DPhysicalResource createResource(
            PrismResourceDescriptor descriptor,
            Blaze3DResourceKey key) {
        if (descriptor.type() == PrismResourceType.TEXTURE) {
            Blaze3DResourceKey.TextureKey textureKey = (Blaze3DResourceKey.TextureKey) key;
            GpuTexture texture = device.createTexture(
                    () -> "Dreamveil Prism transient slot for " + descriptor.name(),
                    textureKey.usage(),
                    textureKey.format(),
                    textureKey.width(),
                    textureKey.height(),
                    1,
                    textureKey.mipLevels());
            try {
                GpuTextureView view = device.createTextureView(texture);
                createdTextures++;
                createdTextureViews++;
                return new Blaze3DPhysicalResource.TextureResource(textureKey, texture, view);
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
        }

        Blaze3DResourceKey.BufferKey bufferKey = (Blaze3DResourceKey.BufferKey) key;
        GpuBuffer buffer = device.createBuffer(
                () -> "Dreamveil Prism transient slot for " + descriptor.name(),
                bufferKey.usage(),
                bufferKey.sizeBytes());
        createdBuffers++;
        return new Blaze3DPhysicalResource.BufferResource(bufferKey, buffer);
    }

    private void closeFreeResources() {
        List<Blaze3DPhysicalResource> resources = new ArrayList<>();
        free.values().forEach(resources::addAll);
        free.clear();
        for (Blaze3DPhysicalResource resource : resources) {
            closeResource(resource);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Prism transient resource pool is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeFreeResources();
        for (Blaze3DPhysicalResource resource : List.copyOf(active)) {
            closeResource(resource);
        }
        active.clear();
    }

    private void closeResource(Blaze3DPhysicalResource resource) {
        if (resource instanceof Blaze3DPhysicalResource.TextureResource) {
            closedTextureViews++;
            closedTextures++;
        } else if (resource instanceof Blaze3DPhysicalResource.BufferResource) {
            closedBuffers++;
        }
        resource.close();
    }
}
