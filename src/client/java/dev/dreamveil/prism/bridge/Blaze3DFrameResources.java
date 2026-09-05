package dev.dreamveil.prism.bridge;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.graph.PrismCompiledGraph;
import dev.dreamveil.prism.graph.PrismHostResources;
import dev.dreamveil.prism.graph.PrismResourceDescriptor;
import dev.dreamveil.prism.graph.PrismResourceType;

/**
 * Per-frame logical resource table. Host resources are imported without taking
 * ownership; Prism transient bindings are installed only for graph execution.
 */
public final class Blaze3DFrameResources {
    private final Map<String, GpuTextureView> textures = new LinkedHashMap<>();
    private final Map<String, GpuBuffer> buffers = new LinkedHashMap<>();
    private final Set<String> transientNames = new LinkedHashSet<>();

    public Blaze3DFrameResources bindTexture(String name, GpuTextureView textureView) {
        requireName(name);
        if (buffers.containsKey(name) || transientNames.contains(name)) {
            throw new IllegalStateException("Prism resource name is already bound with incompatible ownership/type: " + name);
        }
        textures.put(name, Objects.requireNonNull(textureView, "textureView"));
        return this;
    }

    public Blaze3DFrameResources bindBuffer(String name, GpuBuffer buffer) {
        requireName(name);
        if (textures.containsKey(name) || transientNames.contains(name)) {
            throw new IllegalStateException("Prism resource name is already bound with incompatible ownership/type: " + name);
        }
        buffers.put(name, Objects.requireNonNull(buffer, "buffer"));
        return this;
    }

    void bindTransient(String name, Blaze3DPhysicalResource resource) {
        requireName(name);
        Objects.requireNonNull(resource, "resource");
        if (textures.containsKey(name) || buffers.containsKey(name) || !transientNames.add(name)) {
            throw new IllegalStateException("Transient Prism resource name collides with an existing binding: " + name);
        }

        switch (resource) {
            case Blaze3DPhysicalResource.TextureResource texture -> textures.put(name, texture.view());
            case Blaze3DPhysicalResource.BufferResource buffer -> buffers.put(name, buffer.buffer());
        }
    }

    void clearTransientBindings() {
        for (String name : transientNames) {
            textures.remove(name);
            buffers.remove(name);
        }
        transientNames.clear();
    }

    public GpuTextureView requireTexture(String name) {
        GpuTextureView texture = textures.get(name);
        if (texture == null) {
            throw new IllegalStateException("No Blaze3D texture bound for Prism resource '" + name + "'");
        }
        if (texture.isClosed()) {
            throw new IllegalStateException("Blaze3D texture view is closed for Prism resource '" + name + "'");
        }
        return texture;
    }

    public GpuBuffer requireBuffer(String name) {
        GpuBuffer buffer = buffers.get(name);
        if (buffer == null) {
            throw new IllegalStateException("No Blaze3D buffer bound for Prism resource '" + name + "'");
        }
        if (buffer.isClosed()) {
            throw new IllegalStateException("Blaze3D buffer is closed for Prism resource '" + name + "'");
        }
        return buffer;
    }

    int referenceWidth() {
        return requireTexture(PrismHostResources.MAIN_COLOR).getWidth(0);
    }

    int referenceHeight() {
        return requireTexture(PrismHostResources.MAIN_COLOR).getHeight(0);
    }

    public void validateImportedForExecution(PrismCompiledGraph graph) {
        for (PrismResourceDescriptor descriptor : graph.resources().values()) {
            if (!descriptor.imported()) {
                continue;
            }
            if (descriptor.type() == PrismResourceType.TEXTURE && !textures.containsKey(descriptor.name())) {
                throw new IllegalStateException(
                        "Missing imported texture binding for Prism resource '" + descriptor.name() + "'");
            }
            if (descriptor.type() == PrismResourceType.BUFFER && !buffers.containsKey(descriptor.name())) {
                throw new IllegalStateException(
                        "Missing imported buffer binding for Prism resource '" + descriptor.name() + "'");
            }
        }
    }

    public static Blaze3DFrameResources fromMainTarget(RenderTarget target) {
        Objects.requireNonNull(target, "target");

        GpuTextureView color = target.getColorTextureView();
        if (color == null) {
            throw new IllegalStateException("Minecraft main render target has no color texture view");
        }

        Blaze3DFrameResources resources = new Blaze3DFrameResources()
                .bindTexture(PrismHostResources.MAIN_COLOR, color);

        GpuTextureView depth = target.getDepthTextureView();
        if (depth != null) {
            resources.bindTexture(PrismHostResources.MAIN_DEPTH, depth);
        }

        return resources;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Resource name must not be blank");
        }
    }
}
