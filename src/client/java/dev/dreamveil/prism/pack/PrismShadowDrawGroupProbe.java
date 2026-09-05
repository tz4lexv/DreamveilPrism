package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.dreamveil.prism.PrismMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;

/**
 * One-shot runtime description of Minecraft 26.2's prepared chunk draw-group layout.
 *
 * <p>The probe only reads public record/accessor data already extracted for the current frame. It
 * does not execute any draw, make private members accessible, mutate the draw lists, or invoke a
 * second {@code ChunkSectionsToRender.renderGroup}.</p>
 */
final class PrismShadowDrawGroupProbe {
    private static final String REPORT_NAME = "prism-shadow-draw-groups-26.2.txt";
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();

    private PrismShadowDrawGroupProbe() {
    }

    static Path reportPath() {
        return FabricLoader.getInstance().getGameDir().resolve(REPORT_NAME).toAbsolutePath().normalize();
    }

    static void capture(ChunkSectionsToRender sections, ChunkSectionLayerGroup group) {
        if (!CAPTURED.compareAndSet(false, true)) {
            return;
        }
        try {
            writeReport(sections, group);
        } catch (RuntimeException | LinkageError | IOException exception) {
            CAPTURED.set(false);
            PrismMod.LOGGER.warn("Prism 26.2 shadow draw-group report could not be written", exception);
        }
    }

    private static void writeReport(ChunkSectionsToRender sections, ChunkSectionLayerGroup group) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Dreamveil Prism 0.14.0-beta.1 - Minecraft 26.2 prepared terrain draw-group report");
        lines.add("capturedUtc=" + Instant.now());
        lines.add("purpose=derive explicit terrain shadow replay without RenderSystem output overrides");
        lines.add("safety=public accessors only; no private invocation; no draw replay; no mutation");
        lines.add("group=" + group);
        lines.add("preparedSections=" + sections.chunkSectionInfos().length);
        lines.add("maxIndicesRequired=" + sections.maxIndicesRequired());
        lines.add("terrainTexture=" + describeObject(sections.textureView()));
        lines.add("");

        Object drawGroupsObject = sections.drawGroupsPerLayer();
        lines.add("[draw-groups-per-layer]");
        lines.add("containerClass=" + drawGroupsObject.getClass().getName());
        if (drawGroupsObject instanceof Map<?, ?> layers) {
            lines.add("layerCount=" + layers.size());
            Class<?> firstDrawClass = null;
            for (Map.Entry<?, ?> layerEntry : layers.entrySet()) {
                Object layerObject = layerEntry.getKey();
                Object groupedObject = layerEntry.getValue();
                lines.add("");
                lines.add("layer=" + layerObject);
                lines.add("layerClass=" + className(layerObject));
                if (layerObject instanceof ChunkSectionLayer layer) {
                    lines.add("layerPipeline=" + layer.pipeline());
                }
                lines.add("groupMapClass=" + className(groupedObject));

                if (groupedObject instanceof Map<?, ?> grouped) {
                    lines.add("groupKeyCount=" + grouped.size());
                    for (Map.Entry<?, ?> groupEntry : grouped.entrySet()) {
                        Object key = groupEntry.getKey();
                        Object value = groupEntry.getValue();
                        lines.add("  key=" + key + " keyClass=" + className(key) + " valueClass=" + className(value)
                                + " valueSize=" + collectionSize(value));
                        Object first = firstElement(value);
                        if (first != null) {
                            lines.add("    firstDraw=" + describeObject(first));
                            if (firstDrawClass == null) {
                                firstDrawClass = first.getClass();
                            }
                        }
                    }
                } else {
                    lines.add("groupValue=" + describeObject(groupedObject));
                }
            }

            if (firstDrawClass != null) {
                lines.add("");
                appendPublicType(lines, "FIRST_DRAW_TYPE", firstDrawClass, true);
            }
        }

        lines.add("");
        appendPublicType(lines, "CHUNK_SECTION_LAYER_GROUP", group.getClass(), false);
        appendPublicType(lines, "RENDER_PASS_DRAW_API", com.mojang.blaze3d.systems.RenderPass.class, true);
        appendPublicType(lines, "RENDER_SYSTEM_INDEX_UNIFORM_API", com.mojang.blaze3d.systems.RenderSystem.class, true);

        Path report = reportPath();
        Files.createDirectories(report.getParent());
        Path temporary = report.resolveSibling(report.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
        }
        PrismMod.LOGGER.info("Prism 26.2 shadow draw-group ABI captured without replay: {}", report);
    }

    private static void appendPublicType(List<String> lines, String label, Class<?> type, boolean filterRelevant) {
        lines.add("============================================================");
        lines.add(label);
        lines.add("runtimeClass=" + type.getName());
        lines.add("modifiers=" + Modifier.toString(type.getModifiers()));

        RecordComponent[] components = type.getRecordComponents();
        if (components != null && components.length > 0) {
            lines.add("[record-components]");
            Arrays.stream(components)
                    .sorted(Comparator.comparing(RecordComponent::getName))
                    .forEach(component -> lines.add("  " + component.getGenericType().getTypeName() + " " + component.getName()));
        }

        lines.add("[public-methods]");
        Arrays.stream(type.getMethods())
                .sorted(Comparator.comparing(PrismShadowDrawGroupProbe::methodSortKey))
                .filter(method -> !filterRelevant || relevant(method))
                .forEach(method -> lines.add("  " + describe(method)));
    }

    private static boolean relevant(Method method) {
        String text = (method.getName() + " " + method.getReturnType().getTypeName() + " "
                + Arrays.toString(method.getParameterTypes())).toLowerCase(java.util.Locale.ROOT);
        return text.contains("draw")
                || text.contains("index")
                || text.contains("uniform")
                || text.contains("sequential")
                || text.contains("pipeline")
                || text.contains("layer")
                || text.contains("group")
                || text.contains("buffer");
    }

    private static String methodSortKey(Method method) {
        return method.getName() + Arrays.toString(method.getParameterTypes()) + method.getReturnType().getName();
    }

    private static String describe(Method method) {
        return Modifier.toString(method.getModifiers()) + " " + method.getGenericReturnType().getTypeName() + " "
                + method.getName() + "(" + Arrays.stream(method.getGenericParameterTypes())
                .map(java.lang.reflect.Type::getTypeName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("") + ")";
    }

    private static String describeObject(Object value) {
        return value == null ? "<null>" : value + " [" + value.getClass().getName() + "]";
    }

    private static String className(Object value) {
        return value == null ? "<null>" : value.getClass().getName();
    }

    private static int collectionSize(Object value) {
        if (value instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        return -1;
    }

    private static Object firstElement(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.getFirst();
        }
        if (value instanceof java.util.Collection<?> collection && !collection.isEmpty()) {
            return collection.iterator().next();
        }
        return null;
    }
}
