package dev.dreamveil.prism.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.graph.PrismTextureDesc;

/**
 * Device-local ping-pong textures retained across frames for pack-authored temporal effects.
 *
 * <p>The render graph sees the current and previous views as separate imported resources. This
 * store owns both physical textures, swaps them only after a successful graph execution and
 * invalidates history whenever the device, extent or declaration changes.</p>
 */
public final class Blaze3DHistoryTextureStore implements AutoCloseable {
    public record Definition(String currentName, String previousName, PrismTextureDesc descriptor) {
        public Definition {
            if (currentName == null || currentName.isBlank()
                    || previousName == null || previousName.isBlank()
                    || currentName.equals(previousName)
                    || descriptor == null) {
                throw new IllegalArgumentException("History texture names/descriptor must be valid and distinct");
            }
        }
    }

    private GpuDevice device;
    private List<ResolvedDefinition> configuration = List.of();
    private Map<String, TexturePair> pairs = Map.of();
    private int writeIndex;
    private boolean historyValid;
    private long allocationGeneration;

    /** Returns true when physical history storage had to be recreated. */
    public boolean prepare(
            GpuDevice requiredDevice,
            int referenceWidth,
            int referenceHeight,
            double dynamicScale,
            List<Definition> definitions) {
        Objects.requireNonNull(requiredDevice, "requiredDevice");
        Objects.requireNonNull(definitions, "definitions");
        if (referenceWidth < 1 || referenceHeight < 1) {
            throw new IllegalArgumentException("History reference extent must be positive");
        }

        List<ResolvedDefinition> resolved = resolve(definitions, referenceWidth, referenceHeight, dynamicScale);
        if (device == requiredDevice && configuration.equals(resolved)) {
            return false;
        }

        Map<String, TexturePair> replacement = new LinkedHashMap<>();
        try {
            for (ResolvedDefinition definition : resolved) {
                replacement.put(definition.currentName(), createPair(requiredDevice, definition));
            }
        } catch (RuntimeException | Error failure) {
            replacement.values().forEach(TexturePair::close);
            throw failure;
        }

        closePairs();
        device = requiredDevice;
        configuration = List.copyOf(resolved);
        pairs = Map.copyOf(replacement);
        writeIndex = 0;
        historyValid = false;
        allocationGeneration++;
        return true;
    }

    public void bind(Blaze3DFrameResources resources) {
        Objects.requireNonNull(resources, "resources");
        for (ResolvedDefinition definition : configuration) {
            TexturePair pair = pairs.get(definition.currentName());
            if (pair == null) {
                throw new IllegalStateException("Missing physical history pair for " + definition.currentName());
            }
            resources.bindTexture(definition.currentName(), pair.views()[writeIndex]);
            resources.bindTexture(definition.previousName(), pair.views()[1 - writeIndex]);
        }
    }

    /** Swaps current/previous only after every pass completed successfully. */
    public void commitFrame() {
        if (pairs.isEmpty()) {
            return;
        }
        writeIndex = 1 - writeIndex;
        historyValid = true;
    }

    public boolean historyValid() {
        return !pairs.isEmpty() && historyValid;
    }

    public int pairCount() {
        return pairs.size();
    }

    public long allocationGeneration() {
        return allocationGeneration;
    }

    private static List<ResolvedDefinition> resolve(
            List<Definition> definitions,
            int referenceWidth,
            int referenceHeight,
            double dynamicScale) {
        List<ResolvedDefinition> result = new ArrayList<>(definitions.size());
        Set<String> names = new LinkedHashSet<>();
        for (Definition definition : definitions) {
            if (!names.add(definition.currentName()) || !names.add(definition.previousName())) {
                throw new IllegalArgumentException("Duplicate history texture binding name");
            }
            PrismTextureDesc descriptor = definition.descriptor();
            int width = descriptor.extent().resolveWidth(referenceWidth, dynamicScale);
            int height = descriptor.extent().resolveHeight(referenceHeight, dynamicScale);
            int maxMipLevels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
            if (descriptor.mipLevels() > maxMipLevels) {
                throw new IllegalArgumentException(
                        "History texture requests " + descriptor.mipLevels() + " mip levels for "
                                + width + "x" + height + "; maximum is " + maxMipLevels);
            }
            result.add(new ResolvedDefinition(
                    definition.currentName(),
                    definition.previousName(),
                    Blaze3DResourceMapper.format(descriptor.format()),
                    Blaze3DResourceMapper.textureUsage(descriptor.usages()),
                    width,
                    height,
                    descriptor.mipLevels()));
        }
        return List.copyOf(result);
    }

    private static TexturePair createPair(GpuDevice device, ResolvedDefinition definition) {
        GpuTexture[] textures = new GpuTexture[2];
        GpuTextureView[] views = new GpuTextureView[2];
        try {
            for (int index = 0; index < 2; index++) {
                int copy = index;
                textures[index] = device.createTexture(
                        () -> "Dreamveil Prism history " + definition.currentName() + " [" + copy + "]",
                        definition.usage(),
                        definition.format(),
                        definition.width(),
                        definition.height(),
                        1,
                        definition.mipLevels());
                views[index] = device.createTextureView(textures[index]);
            }
            return new TexturePair(textures, views);
        } catch (RuntimeException | Error failure) {
            closePartial(textures, views);
            throw failure;
        }
    }

    private static void closePartial(GpuTexture[] textures, GpuTextureView[] views) {
        for (GpuTextureView view : views) {
            if (view != null) view.close();
        }
        for (GpuTexture texture : textures) {
            if (texture != null) texture.close();
        }
    }

    private void closePairs() {
        pairs.values().forEach(TexturePair::close);
        pairs = Map.of();
        configuration = List.of();
        device = null;
        writeIndex = 0;
        historyValid = false;
    }

    @Override
    public void close() {
        closePairs();
    }

    private record ResolvedDefinition(
            String currentName,
            String previousName,
            GpuFormat format,
            int usage,
            int width,
            int height,
            int mipLevels) {
    }

    private record TexturePair(GpuTexture[] textures, GpuTextureView[] views) {
        void close() {
            closePartial(textures, views);
        }
    }
}
