package dev.dreamveil.prism.bridge;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/** Transactional device-local storage for immutable PNG textures owned by a shader pack. */
public final class Blaze3DPackTextureStore implements AutoCloseable {
    private static final long MAX_ENCODED_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_DECODED_BYTES = 128L * 1024L * 1024L;

    public record Definition(
            String bindingName,
            List<Path> sources,
            String dimension,
            int expectedWidth,
            int expectedHeight,
            String colorSpace,
            int mipLevels) {
        public Definition {
            if (bindingName == null || bindingName.isBlank() || sources == null || sources.isEmpty()
                    || sources.stream().anyMatch(Objects::isNull)
                    || expectedWidth < 1 || expectedHeight < 1 || mipLevels < 1) {
                throw new IllegalArgumentException("Texture asset definition is incomplete");
            }
            sources = sources.stream().map(source -> source.toAbsolutePath().normalize()).toList();
            dimension = dimension == null || dimension.isBlank() ? "2d" : dimension;
            colorSpace = colorSpace == null || colorSpace.isBlank() ? "linear" : colorSpace;
            if (("cube".equals(dimension) && sources.size() != 6)
                    || ("2d".equals(dimension) && sources.size() != 1)
                    || (("2d_array".equals(dimension) || "3d".equals(dimension)) && sources.size() < 2)) {
                throw new IllegalArgumentException(
                        "2D textures require one source, cubemaps six faces, and arrays/volumes at least two layers");
            }
        }

        boolean cubemap() {
            return "cube".equals(dimension);
        }

        boolean array() {
            return "2d_array".equals(dimension);
        }

        boolean volume() {
            return "3d".equals(dimension);
        }
    }

    private GpuDevice device;
    private Map<String, OwnedTexture> textures = Map.of();

    /**
     * Decodes and uploads a complete candidate set without disturbing the active generation.
     * The returned allocation must be installed or closed.
     */
    public Prepared prepare(
            GpuDevice requiredDevice,
            CommandEncoder commandEncoder,
            List<Definition> definitions) {
        Objects.requireNonNull(requiredDevice, "requiredDevice");
        Objects.requireNonNull(commandEncoder, "commandEncoder");
        Objects.requireNonNull(definitions, "definitions");

        Map<String, OwnedTexture> replacement = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        long decodedBytes = 0L;
        try {
            for (Definition definition : definitions) {
                if (!names.add(definition.bindingName())) {
                    throw new IllegalArgumentException(
                            "Duplicate pack texture binding " + definition.bindingName());
                }
                long encodedBytes = 0L;
                for (Path source : definition.sources()) {
                    long faceBytes = Files.size(source);
                    if (faceBytes < 24L || faceBytes > MAX_ENCODED_BYTES) {
                        throw new IllegalArgumentException(
                                "Texture asset encoded size is outside the portable budget: " + source);
                    }
                    encodedBytes = Math.addExact(encodedBytes, faceBytes);
                }
                long textureDecodedBytes = decodedMipBytes(
                        definition.expectedWidth(), definition.expectedHeight(),
                        definition.mipLevels(), definition.sources().size());
                decodedBytes = Math.addExact(decodedBytes, textureDecodedBytes);
                if (decodedBytes > MAX_DECODED_BYTES) {
                    throw new IllegalArgumentException("Pack texture assets exceed the decoded RGBA budget");
                }

                int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
                if (definition.cubemap()) usage |= GpuTexture.USAGE_CUBEMAP_COMPATIBLE;
                if (definition.array()) usage |= PrismVulkanUsage.TEXTURE_2D_ARRAY;
                if (definition.volume()) usage |= PrismVulkanUsage.TEXTURE_3D;
                GpuTexture texture = requiredDevice.createTexture(
                        () -> "Dreamveil Prism pack texture " + definition.bindingName(),
                        usage,
                        GpuFormat.RGBA8_UNORM,
                        definition.expectedWidth(),
                        definition.expectedHeight(),
                        definition.sources().size(),
                        definition.mipLevels());
                GpuTextureView view = null;
                try {
                    if (definition.volume()) {
                        uploadVolume(commandEncoder, texture, definition);
                    } else {
                        for (int layer = 0; layer < definition.sources().size(); layer++) {
                            uploadLayer(commandEncoder, texture, definition, layer);
                        }
                    }
                    view = requiredDevice.createTextureView(texture);
                    replacement.put(definition.bindingName(), new OwnedTexture(texture, view));
                } catch (RuntimeException | Error failure) {
                    if (view != null) view.close();
                    texture.close();
                    throw failure;
                }
            }
            return new Prepared(requiredDevice, Map.copyOf(replacement));
        } catch (IOException exception) {
            closeTextures(replacement);
            throw new IllegalArgumentException(
                    "Could not decode/upload pack texture asset: " + exception.getMessage(), exception);
        } catch (RuntimeException | Error failure) {
            closeTextures(replacement);
            throw failure;
        }
    }

    private static void uploadLayer(
            CommandEncoder commandEncoder,
            GpuTexture texture,
            Definition definition,
            int layer) throws IOException {
        Path source = definition.sources().get(layer);
        try (InputStream file = new BufferedInputStream(Files.newInputStream(source));
                NativeImage image = NativeImage.read(NativeImage.Format.RGBA, file)) {
            int width = image.getWidth();
            int height = image.getHeight();
            if (width != definition.expectedWidth() || height != definition.expectedHeight()) {
                throw new IllegalArgumentException(
                        "Texture asset changed dimensions during reload: " + source
                                + " expected " + definition.expectedWidth() + 'x'
                                + definition.expectedHeight() + " but decoded " + width + 'x' + height);
            }
            if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                throw new IllegalArgumentException(
                        "Texture asset dimensions exceed the portable " + MAX_DIMENSION + " limit: " + source);
            }
            ByteBuffer level = copyPixels(image.getPixelBytes(), width * height * 4);
            for (int mip = 0; mip < definition.mipLevels(); mip++) {
                commandEncoder.writeToTexture(texture, level, mip, layer, 0, 0, width, height);
                if (mip + 1 < definition.mipLevels()) {
                    level = downsampleRgba(level, width, height, "srgb".equals(definition.colorSpace()));
                    width = Math.max(1, width / 2);
                    height = Math.max(1, height / 2);
                }
            }
        }
    }

    private static void uploadVolume(
            CommandEncoder commandEncoder,
            GpuTexture texture,
            Definition definition) throws IOException {
        List<ByteBuffer> slices = new ArrayList<>(definition.sources().size());
        for (Path source : definition.sources()) {
            try (InputStream file = new BufferedInputStream(Files.newInputStream(source));
                    NativeImage image = NativeImage.read(NativeImage.Format.RGBA, file)) {
                validateDimensions(image, definition, source);
                slices.add(copyPixels(image.getPixelBytes(), image.getWidth() * image.getHeight() * 4));
            }
        }

        int width = definition.expectedWidth();
        int height = definition.expectedHeight();
        for (int mip = 0; mip < definition.mipLevels(); mip++) {
            for (int z = 0; z < slices.size(); z++) {
                // Prism's Vulkan upload mixin interprets the layer argument as a Z slice for TEXTURE_3D.
                commandEncoder.writeToTexture(texture, slices.get(z), mip, z, 0, 0, width, height);
            }
            if (mip + 1 < definition.mipLevels()) {
                slices = downsampleVolumeRgba(
                        slices, width, height, "srgb".equals(definition.colorSpace()));
                width = Math.max(1, width / 2);
                height = Math.max(1, height / 2);
            }
        }
    }

    private static void validateDimensions(
            NativeImage image,
            Definition definition,
            Path source) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width != definition.expectedWidth() || height != definition.expectedHeight()) {
            throw new IllegalArgumentException(
                    "Texture asset changed dimensions during reload: " + source
                            + " expected " + definition.expectedWidth() + 'x'
                            + definition.expectedHeight() + " but decoded " + width + 'x' + height);
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                    "Texture asset dimensions exceed the portable " + MAX_DIMENSION + " limit: " + source);
        }
    }

    private static ByteBuffer copyPixels(ByteBuffer source, int bytes) {
        ByteBuffer result = ByteBuffer.allocateDirect(bytes);
        ByteBuffer copy = source.duplicate();
        copy.position(0).limit(bytes);
        result.put(copy).flip();
        return result;
    }

    /** CPU box filter. RGB is averaged in linear light for sRGB-authored assets. */
    static ByteBuffer downsampleRgba(ByteBuffer source, int width, int height, boolean srgb) {
        int nextWidth = Math.max(1, width / 2);
        int nextHeight = Math.max(1, height / 2);
        ByteBuffer result = ByteBuffer.allocateDirect(nextWidth * nextHeight * 4);
        for (int y = 0; y < nextHeight; y++) {
            for (int x = 0; x < nextWidth; x++) {
                for (int channel = 0; channel < 4; channel++) {
                    double sum = 0.0;
                    int count = 0;
                    for (int oy = 0; oy < 2; oy++) {
                        int sy = Math.min(height - 1, y * 2 + oy);
                        for (int ox = 0; ox < 2; ox++) {
                            int sx = Math.min(width - 1, x * 2 + ox);
                            double value = Byte.toUnsignedInt(source.get((sy * width + sx) * 4 + channel)) / 255.0;
                            sum += srgb && channel < 3 ? srgbToLinear(value) : value;
                            count++;
                        }
                    }
                    double average = sum / count;
                    if (srgb && channel < 3) average = linearToSrgb(average);
                    result.put((byte) Math.clamp(Math.round(average * 255.0), 0L, 255L));
                }
            }
        }
        return result.flip();
    }

    /** CPU 2x2x2 box filter for generated 3D texture mip levels. */
    static List<ByteBuffer> downsampleVolumeRgba(
            List<ByteBuffer> source,
            int width,
            int height,
            boolean srgb) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("3D mip source must contain at least one slice");
        }
        int nextWidth = Math.max(1, width / 2);
        int nextHeight = Math.max(1, height / 2);
        int nextDepth = Math.max(1, source.size() / 2);
        List<ByteBuffer> result = new ArrayList<>(nextDepth);
        for (int z = 0; z < nextDepth; z++) {
            ByteBuffer slice = ByteBuffer.allocateDirect(nextWidth * nextHeight * 4);
            for (int y = 0; y < nextHeight; y++) {
                for (int x = 0; x < nextWidth; x++) {
                    for (int channel = 0; channel < 4; channel++) {
                        double sum = 0.0;
                        int count = 0;
                        for (int oz = 0; oz < 2; oz++) {
                            ByteBuffer sourceSlice = source.get(Math.min(source.size() - 1, z * 2 + oz));
                            for (int oy = 0; oy < 2; oy++) {
                                int sy = Math.min(height - 1, y * 2 + oy);
                                for (int ox = 0; ox < 2; ox++) {
                                    int sx = Math.min(width - 1, x * 2 + ox);
                                    double value = Byte.toUnsignedInt(
                                            sourceSlice.get((sy * width + sx) * 4 + channel)) / 255.0;
                                    sum += srgb && channel < 3 ? srgbToLinear(value) : value;
                                    count++;
                                }
                            }
                        }
                        double average = sum / count;
                        if (srgb && channel < 3) average = linearToSrgb(average);
                        slice.put((byte) Math.clamp(Math.round(average * 255.0), 0L, 255L));
                    }
                }
            }
            result.add(slice.flip());
        }
        return List.copyOf(result);
    }

    private static double srgbToLinear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double linearToSrgb(double value) {
        return value <= 0.0031308 ? value * 12.92 : 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055;
    }

    private static long decodedMipBytes(int width, int height, int levels, int layers) {
        long total = 0L;
        for (int mip = 0; mip < levels; mip++) {
            total = Math.addExact(total, Math.multiplyExact((long) width * height * 4L, layers));
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
        return total;
    }

    /** Atomically replaces the active set; this operation cannot allocate or decode. */
    public void install(Prepared prepared) {
        Objects.requireNonNull(prepared, "prepared");
        Map<String, OwnedTexture> replacement = prepared.take();
        closeTextures(textures);
        textures = replacement;
        device = prepared.device;
    }

    public void bind(Blaze3DFrameResources resources) {
        Objects.requireNonNull(resources, "resources");
        for (var entry : textures.entrySet()) {
            resources.bindTexture(entry.getKey(), entry.getValue().view());
        }
    }

    public int textureCount() {
        return textures.size();
    }

    public GpuDevice device() {
        return device;
    }

    @Override
    public void close() {
        closeTextures(textures);
        textures = Map.of();
        device = null;
    }

    private static void closeTextures(Map<String, OwnedTexture> values) {
        values.values().forEach(OwnedTexture::close);
    }

    public static final class Prepared implements AutoCloseable {
        private final GpuDevice device;
        private Map<String, OwnedTexture> textures;

        private Prepared(GpuDevice device, Map<String, OwnedTexture> textures) {
            this.device = device;
            this.textures = textures;
        }

        private Map<String, OwnedTexture> take() {
            if (textures == null) {
                throw new IllegalStateException("Prepared texture allocation was already consumed");
            }
            Map<String, OwnedTexture> result = textures;
            textures = null;
            return result;
        }

        public int textureCount() {
            return textures == null ? 0 : textures.size();
        }

        @Override
        public void close() {
            if (textures != null) {
                closeTextures(textures);
                textures = null;
            }
        }
    }

    private record OwnedTexture(GpuTexture texture, GpuTextureView view) {
        void close() {
            view.close();
            texture.close();
        }
    }
}
