package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.lang.reflect.Field;
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
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.blaze3d.buffers.GpuBuffer;

import dev.dreamveil.prism.PrismMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;

/**
 * Final public-ABI probe before Prism emits independent terrain shadow draws.
 *
 * <p>The user-proven 26.2 ABI establishes the read-only chain
 * RenderSection#getSectionMesh -> SectionMesh#getSectionDraw and
 * SectionRenderDispatcher#getRenderSectionSlice. This probe invokes only that chain on the first
 * accepted, resident, non-empty layer and records the exact SectionDraw metadata together with the
 * actual GPU buffer-slice offsets. It never compiles, resets, closes, mutates, retains or draws a
 * renderer-owned object.</p>
 */
final class PrismShadowDrawMetadataProbe {
    private static final String REPORT_NAME = "prism-shadow-section-draw-abi-26.2.txt";
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();

    private PrismShadowDrawMetadataProbe() {
    }

    static Path reportPath() {
        return FabricLoader.getInstance().getGameDir().resolve(REPORT_NAME).toAbsolutePath().normalize();
    }

    static boolean captureFirstReady(
            SectionRenderDispatcher dispatcher,
            ViewArea viewArea,
            PrismShadowSectionFrameData frame) {
        if (CAPTURED.get() || dispatcher == null || viewArea == null || frame == null
                || frame.frameIndex() < 0 || frame.residentResolved() == 0) {
            return CAPTURED.get();
        }

        BlockPos.MutableBlockPos sectionOrigin = new BlockPos.MutableBlockPos();
        try {
            for (int linearIndex = 0; linearIndex < frame.candidates(); linearIndex++) {
                if (!frame.isResident(linearIndex)) {
                    continue;
                }
                sectionOrigin.set(
                        frame.sectionX(linearIndex) << 4,
                        frame.sectionY(linearIndex) << 4,
                        frame.sectionZ(linearIndex) << 4);
                SectionRenderDispatcher.RenderSection renderSection = viewArea.getRenderSectionAt(sectionOrigin);
                if (renderSection == null) {
                    continue;
                }
                SectionMesh mesh = renderSection.getSectionMesh();
                if (mesh == null || !mesh.hasRenderableLayers()) {
                    continue;
                }

                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    if (mesh.isEmpty(layer)) {
                        continue;
                    }
                    SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
                    if (draw == null) {
                        continue;
                    }
                    SectionRenderDispatcher.RenderSectionBufferSlice slice =
                            dispatcher.getRenderSectionSlice(mesh, layer);
                    if (slice == null || slice.vertexBuffer() == null) {
                        continue;
                    }
                    if (!CAPTURED.compareAndSet(false, true)) {
                        return true;
                    }
                    writeReport(frame, linearIndex, renderSection, mesh, layer, draw, slice);
                    PrismMod.LOGGER.info(
                            "Prism 26.2 independent shadow draw metadata captured from public API: {}",
                            reportPath());
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError | IOException exception) {
            CAPTURED.set(false);
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                PrismMod.LOGGER.warn(
                        "Prism 26.2 independent shadow draw metadata probe is waiting for a compatible uploaded section",
                        exception);
            }
        }
        return false;
    }

    private static void writeReport(
            PrismShadowSectionFrameData frame,
            int linearIndex,
            SectionRenderDispatcher.RenderSection renderSection,
            SectionMesh mesh,
            ChunkSectionLayer layer,
            SectionMesh.SectionDraw draw,
            SectionRenderDispatcher.RenderSectionBufferSlice slice) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Dreamveil Prism 0.14.0-beta.1 - Minecraft 26.2 independent shadow draw metadata ABI report");
        lines.add("capturedUtc=" + Instant.now());
        lines.add("purpose=derive compile-time indexed shadow draw arguments from an accepted light-visible resident section");
        lines.add("safety=public read-only API only; no compile/reset/close/private access/object retention/GPU draw");
        lines.add("frame=" + frame.frameIndex());
        lines.add("linearIndex=" + linearIndex);
        lines.add("section=(" + frame.sectionX(linearIndex) + "," + frame.sectionY(linearIndex) + "," + frame.sectionZ(linearIndex) + ")");
        lines.add("renderOrigin=" + renderSection.getRenderOrigin());
        lines.add("layer=" + layer.name());
        lines.add("layerPipeline=" + layer.pipeline().getLocation());
        lines.add("sectionMeshRuntimeClass=" + mesh.getClass().getName());
        lines.add("vertexFormat=" + layer.vertexFormat());
        lines.add("vertexFormatRuntimeClass=" + layer.vertexFormat().getClass().getName());
        lines.add("sectionDrawRuntimeClass=" + draw.getClass().getName());
        lines.add("sectionDrawValue=" + draw);
        lines.add("sliceRuntimeClass=" + slice.getClass().getName());
        lines.add("vertexBuffer=" + describeBuffer(slice.vertexBuffer()));
        lines.add("vertexBufferOffset=" + slice.vertexBufferOffset());
        lines.add("indexBuffer=" + describeBuffer(slice.indexBuffer()));
        lines.add("indexBufferOffset=" + slice.indexBufferOffset());
        lines.add("");

        appendPublicType(lines, "SECTION_MESH_SECTION_DRAW", SectionMesh.SectionDraw.class);
        RecordComponent[] drawComponents = SectionMesh.SectionDraw.class.getRecordComponents();
        if (drawComponents != null) {
            for (RecordComponent component : drawComponents) {
                Class<?> componentType = component.getType();
                if (!componentType.isPrimitive() && componentType != String.class) {
                    lines.add("");
                    appendPublicType(lines,
                            "SECTION_DRAW_COMPONENT_TYPE_" + component.getName().toUpperCase(java.util.Locale.ROOT),
                            componentType);
                }
            }
        }
        lines.add("");
        appendPublicType(lines, "RENDER_SECTION_BUFFER_SLICE", SectionRenderDispatcher.RenderSectionBufferSlice.class);
        lines.add("");
        appendPublicType(lines, "VERTEX_FORMAT", layer.vertexFormat().getClass());
        lines.add("");
        appendPublicType(lines, "GPU_BUFFER", GpuBuffer.class);

        Path report = reportPath();
        Files.createDirectories(report.getParent());
        Path temporary = report.resolveSibling(report.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String describeBuffer(GpuBuffer buffer) {
        if (buffer == null) {
            return "<null>";
        }
        return buffer + " [" + buffer.getClass().getName() + "]";
    }

    private static void appendPublicType(List<String> lines, String label, Class<?> type) {
        lines.add("============================================================");
        lines.add(label);
        lines.add("runtimeClass=" + type.getName());
        lines.add("modifiers=" + Modifier.toString(type.getModifiers()));

        RecordComponent[] components = type.getRecordComponents();
        if (components != null && components.length > 0) {
            lines.add("[record-components]");
            Arrays.stream(components)
                    .sorted(Comparator.comparing(RecordComponent::getName))
                    .forEach(component -> lines.add(
                            "  " + component.getGenericType().getTypeName() + " " + component.getName()));
        }

        Field[] fields = type.getFields();
        if (fields.length > 0) {
            lines.add("[public-fields]");
            Arrays.stream(fields)
                    .sorted(Comparator.comparing(Field::getName))
                    .forEach(field -> lines.add("  " + Modifier.toString(field.getModifiers()) + " "
                            + field.getGenericType().getTypeName() + " " + field.getName()));
        }

        lines.add("[public-methods]");
        Arrays.stream(type.getMethods())
                .sorted(Comparator.comparing(PrismShadowDrawMetadataProbe::methodSortKey))
                .forEach(method -> lines.add("  " + describe(method)));
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
}
