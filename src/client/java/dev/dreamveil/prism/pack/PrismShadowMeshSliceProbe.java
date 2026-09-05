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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.dreamveil.prism.PrismMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * One-shot public ABI report for the Minecraft 26.2 mesh -> GPU slice bridge.
 *
 * <p>Unlike the alpha.5 RenderSection report, alpha.6 invokes exactly one verified public,
 * read-only accessor: {@code RenderSection#getSectionMesh()}. It does not compile/reset a section,
 * does not invoke {@code getRenderSectionSlice()}, does not touch private members, and retains no
 * renderer-owned object. The returned mesh is used only long enough to describe its runtime type.</p>
 */
final class PrismShadowMeshSliceProbe {
    private static final String REPORT_NAME = "prism-shadow-mesh-slice-abi-26.2.txt";
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();

    private PrismShadowMeshSliceProbe() {
    }

    static Path reportPath() {
        return FabricLoader.getInstance().getGameDir().resolve(REPORT_NAME).toAbsolutePath().normalize();
    }

    static void capture(ViewArea viewArea, SectionRenderDispatcher.RenderSection renderSection) {
        if (viewArea == null || renderSection == null || !CAPTURED.compareAndSet(false, true)) {
            return;
        }
        try {
            SectionMesh mesh = renderSection.getSectionMesh();
            writeReport(viewArea, renderSection, mesh);
        } catch (RuntimeException | LinkageError | IOException exception) {
            CAPTURED.set(false);
            PrismMod.LOGGER.warn("Prism 26.2 SectionMesh / RenderSectionBufferSlice ABI report could not be written", exception);
        }
    }

    private static void writeReport(
            ViewArea viewArea,
            SectionRenderDispatcher.RenderSection renderSection,
            SectionMesh mesh) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Dreamveil Prism 0.14.0-beta.1 - Minecraft 26.2 SectionMesh / RenderSectionBufferSlice ABI report");
        lines.add("capturedUtc=" + Instant.now());
        lines.add("purpose=derive compile-time independent shadow draw preparation from resident light-visible sections");
        lines.add("safety=public API only; invokes only RenderSection.getSectionMesh(); no compile/reset/slice invocation; no private access; no retention");
        lines.add("viewAreaClass=" + viewArea.getClass().getName());
        lines.add("renderSectionClass=" + renderSection.getClass().getName());
        lines.add("sectionMeshInterface=" + SectionMesh.class.getName());
        lines.add("sectionMeshRuntimeClass=" + (mesh == null ? "<null>" : mesh.getClass().getName()));
        lines.add("");

        appendPublicType(lines, "SECTION_MESH_INTERFACE", SectionMesh.class, false);
        if (mesh != null && mesh.getClass() != SectionMesh.class) {
            lines.add("");
            appendPublicType(lines, "SECTION_MESH_RUNTIME_TYPE", mesh.getClass(), false);
        }

        lines.add("");
        appendPublicType(lines, "SECTION_RENDER_DISPATCHER", SectionRenderDispatcher.class, true);

        Arrays.stream(SectionRenderDispatcher.class.getDeclaredClasses())
                .sorted(Comparator.comparing(Class::getName))
                .filter(type -> {
                    String lower = type.getSimpleName().toLowerCase(Locale.ROOT);
                    return lower.contains("buffer") || lower.contains("slice") || lower.contains("mesh");
                })
                .forEach(type -> {
                    lines.add("");
                    appendPublicType(lines,
                            "SECTION_RENDER_DISPATCHER_NESTED_" + type.getSimpleName().toUpperCase(Locale.ROOT),
                            type,
                            false);
                });

        lines.add("");
        appendPublicType(lines, "CHUNK_SECTION_LAYER", ChunkSectionLayer.class, false);

        Path report = reportPath();
        Files.createDirectories(report.getParent());
        Path temporary = report.resolveSibling(report.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
        }
        PrismMod.LOGGER.info("Prism 26.2 SectionDraw metadata ABI captured from public API: {}", report);
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
                    .forEach(component -> lines.add(
                            "  " + component.getGenericType().getTypeName() + " " + component.getName()));
        }

        Field[] fields = type.getFields();
        if (fields.length > 0) {
            lines.add("[public-fields]");
            Arrays.stream(fields)
                    .sorted(Comparator.comparing(Field::getName))
                    .filter(field -> !filterRelevant || relevant(field.getName() + " " + field.getGenericType().getTypeName()))
                    .forEach(field -> lines.add("  " + Modifier.toString(field.getModifiers()) + " "
                            + field.getGenericType().getTypeName() + " " + field.getName()));
        }

        lines.add("[public-methods]");
        Arrays.stream(type.getMethods())
                .sorted(Comparator.comparing(PrismShadowMeshSliceProbe::methodSortKey))
                .filter(method -> !filterRelevant || relevant(describe(method)))
                .forEach(method -> lines.add("  " + describe(method)));
    }

    private static boolean relevant(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("buffer")
                || lower.contains("slice")
                || lower.contains("mesh")
                || lower.contains("layer")
                || lower.contains("section")
                || lower.contains("index")
                || lower.contains("vertex")
                || lower.contains("upload")
                || lower.contains("count")
                || lower.contains("offset")
                || lower.contains("range");
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
