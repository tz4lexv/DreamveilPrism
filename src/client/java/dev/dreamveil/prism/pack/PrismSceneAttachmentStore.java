package dev.dreamveil.prism.pack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

import org.joml.Vector4f;
import org.joml.Vector4fc;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.bridge.Blaze3DFrameResources;

/** Owns full-resolution color attachments written by Minecraft scene draws and sampled later. */
final class PrismSceneAttachmentStore {
    record Definition(String creatorId, GpuFormat format) {
        Definition {
            if (creatorId == null || creatorId.isBlank() || format == null || format.hasDepthAspect()) {
                throw new IllegalArgumentException("Scene attachment definition must be a named color format");
            }
        }
    }

    private static final Vector4fc CLEAR = new Vector4f(0.0f);
    private static GpuDevice device;
    private static int width = -1;
    private static int height = -1;
    private static List<Definition> definitions = List.of();
    private static Map<String, Owned> attachments = Map.of();
    private static final Set<String> clearedThisFrame = new LinkedHashSet<>();
    private static long allocationGeneration;

    private PrismSceneAttachmentStore() {}

    static Prepared prepare(GpuDevice requiredDevice, int requiredWidth, int requiredHeight, List<Definition> required) {
        Objects.requireNonNull(requiredDevice, "requiredDevice");
        Objects.requireNonNull(required, "required");
        List<Definition> immutable = List.copyOf(required);
        boolean reusable = device == requiredDevice
                && width == requiredWidth
                && height == requiredHeight
                && definitions.equals(immutable)
                && attachments.size() == immutable.size()
                && attachments.values().stream().noneMatch(owned -> owned.view.isClosed());
        return new Prepared(requiredDevice, requiredWidth, requiredHeight, immutable,
                reusable, reusable ? null : allocate(requiredDevice, requiredWidth, requiredHeight, immutable));
    }

    static void install(Prepared prepared) {
        Objects.requireNonNull(prepared, "prepared");
        if (prepared.reuseExisting()) {
            prepared.consumeReuse();
            clearedThisFrame.clear();
            if (!attachments.isEmpty()) {
                PrismMod.LOGGER.info(
                        "Prism scene G-buffer retained: attachments={}, extent={}x{}, allocationGeneration={}",
                        attachments.size(), width, height, allocationGeneration);
            }
            return;
        }
        Map<String, Owned> replacement = prepared.take();
        closeOwned(attachments);
        device = prepared.device;
        width = prepared.width;
        height = prepared.height;
        definitions = prepared.definitions;
        attachments = replacement;
        clearedThisFrame.clear();
        allocationGeneration++;
        if (!attachments.isEmpty()) {
            PrismMod.LOGGER.info(
                    "Prism scene G-buffer allocated: attachments={}, extent={}x{}, allocationGeneration={}",
                    attachments.size(), width, height, allocationGeneration);
        }
    }

    static void beginFrame() {
        clearedThisFrame.clear();
    }

    static RenderPass createRenderPass(
            CommandEncoder encoder,
            Supplier<String> label,
            GpuTextureView mainColor,
            Optional<Vector4fc> mainClear,
            GpuTextureView depth,
            OptionalDouble depthClear,
            List<String> outputs) {
        Objects.requireNonNull(encoder, "encoder");
        if (outputs == null || outputs.size() <= 1) {
            return encoder.createRenderPass(label, mainColor, mainClear, depth, depthClear);
        }
        ensureExtent(mainColor.getWidth(0), mainColor.getHeight(0));
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(label)
                .withRenderArea(new RenderPass.RenderArea(
                        0, 0, mainColor.getWidth(0), mainColor.getHeight(0)))
                .withColorAttachment(mainColor, mainClear);
        for (int index = 1; index < outputs.size(); index++) {
            String id = outputs.get(index);
            Owned owned = require(id);
            Optional<Vector4fc> clear = clearedThisFrame.add(id) ? Optional.of(CLEAR) : Optional.empty();
            descriptor.withColorAttachment(owned.view, clear);
        }
        if (depth != null) descriptor.withDepthAttachment(depth, depthClear);
        return encoder.createRenderPass(descriptor);
    }

    static void bindAndClearUnwritten(
            Blaze3DFrameResources resources,
            CommandEncoder encoder,
            GpuTextureView mainColor) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(encoder, "encoder");
        if (definitions.isEmpty()) return;
        ensureExtent(mainColor.getWidth(0), mainColor.getHeight(0));
        for (Definition definition : definitions) {
            Owned owned = require(definition.creatorId());
            if (clearedThisFrame.add(definition.creatorId())) {
                // No scene draw touched this target (for example an empty dimension). Clear it so
                // post passes never sample a previous frame by accident.
                encoder.clearColorTexture(owned.texture, CLEAR);
            }
            resources.bindTexture(PrismPackResources.toInternal(definition.creatorId()), owned.view);
        }
    }

    static void clear() {
        closeOwned(attachments);
        attachments = Map.of();
        definitions = List.of();
        clearedThisFrame.clear();
        device = null;
        width = -1;
        height = -1;
    }

    private static void ensureExtent(int requiredWidth, int requiredHeight) {
        if (definitions.isEmpty()) return;
        GpuDevice requiredDevice = RenderSystem.getDevice();
        if (device == requiredDevice && width == requiredWidth && height == requiredHeight) return;
        Map<String, Owned> replacement = allocate(requiredDevice, requiredWidth, requiredHeight, definitions);
        closeOwned(attachments);
        attachments = replacement;
        device = requiredDevice;
        width = requiredWidth;
        height = requiredHeight;
        clearedThisFrame.clear();
        allocationGeneration++;
        PrismMod.LOGGER.info(
                "Prism scene G-buffer resized: attachments={}, extent={}x{}, allocationGeneration={}",
                attachments.size(), width, height, allocationGeneration);
    }

    private static Map<String, Owned> allocate(
            GpuDevice requiredDevice, int requiredWidth, int requiredHeight, List<Definition> required) {
        if (requiredWidth < 1 || requiredHeight < 1) {
            throw new IllegalArgumentException("Scene attachment extent must be positive");
        }
        Map<String, Owned> result = new LinkedHashMap<>();
        List<Owned> partial = new ArrayList<>();
        try {
            for (Definition definition : required) {
                if (result.containsKey(definition.creatorId())) {
                    throw new IllegalArgumentException("Duplicate scene attachment " + definition.creatorId());
                }
                GpuTexture texture = requiredDevice.createTexture(
                        () -> "Dreamveil Prism scene attachment " + definition.creatorId(),
                        GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                                | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                        definition.format(), requiredWidth, requiredHeight, 1, 1);
                GpuTextureView view = null;
                try {
                    view = requiredDevice.createTextureView(texture);
                    Owned owned = new Owned(texture, view);
                    partial.add(owned);
                    result.put(definition.creatorId(), owned);
                } catch (RuntimeException | Error failure) {
                    if (view != null) view.close();
                    texture.close();
                    throw failure;
                }
            }
            return Map.copyOf(result);
        } catch (RuntimeException | Error failure) {
            partial.forEach(Owned::close);
            throw failure;
        }
    }

    private static Owned require(String id) {
        Owned owned = attachments.get(id);
        if (owned == null || owned.view.isClosed()) {
            throw new IllegalStateException("Missing active Prism scene attachment '" + id + "'");
        }
        return owned;
    }

    private static void closeOwned(Map<String, Owned> values) {
        values.values().forEach(Owned::close);
    }

    private record Owned(GpuTexture texture, GpuTextureView view) {
        void close() {
            view.close();
            texture.close();
        }
    }

    static final class Prepared implements AutoCloseable {
        private final GpuDevice device;
        private final int width;
        private final int height;
        private final List<Definition> definitions;
        private final boolean reuseExisting;
        private Map<String, Owned> owned;
        private boolean consumed;

        private Prepared(GpuDevice device, int width, int height,
                List<Definition> definitions, boolean reuseExisting, Map<String, Owned> owned) {
            this.device = device;
            this.width = width;
            this.height = height;
            this.definitions = definitions;
            this.reuseExisting = reuseExisting;
            this.owned = owned;
        }

        private boolean reuseExisting() {
            return reuseExisting;
        }

        private void consumeReuse() {
            if (!reuseExisting || consumed) {
                throw new IllegalStateException("Prepared scene attachments already consumed");
            }
            consumed = true;
        }

        private Map<String, Owned> take() {
            if (reuseExisting || consumed || owned == null) {
                throw new IllegalStateException("Prepared scene attachments already consumed");
            }
            Map<String, Owned> result = owned;
            owned = null;
            consumed = true;
            return result;
        }

        @Override
        public void close() {
            if (!consumed && owned != null) {
                closeOwned(owned);
                owned = null;
            }
        }
    }
}
