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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.dreamveil.prism.PrismMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.ViewArea;

/**
 * One-shot public-ABI report for Minecraft 26.2's resident terrain section object.
 *
 * <p>The probe is deliberately descriptive only: it invokes no RenderSection methods, does not
 * make private members accessible, does not retain the renderer-owned object, and does not touch
 * GPU resources. Alpha.5 uses it to derive the first safe independent draw-preparation adapter from
 * the exact runtime API instead of guessing buffer/compiled-mesh accessors.</p>
 */
final class PrismShadowRenderSectionProbe {
    private static final String REPORT_NAME = "prism-shadow-render-section-abi-26.2.txt";
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();

    private PrismShadowRenderSectionProbe() {
    }

    static Path reportPath() {
        return FabricLoader.getInstance().getGameDir().resolve(REPORT_NAME).toAbsolutePath().normalize();
    }

    static void capture(ViewArea viewArea, Object renderSection) {
        if (viewArea == null || renderSection == null || !CAPTURED.compareAndSet(false, true)) {
            return;
        }
        try {
            writeReport(viewArea, renderSection);
        } catch (RuntimeException | LinkageError | IOException exception) {
            CAPTURED.set(false);
            PrismMod.LOGGER.warn("Prism 26.2 RenderSection ABI report could not be written", exception);
        }
    }

    private static void writeReport(ViewArea viewArea, Object renderSection) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Dreamveil Prism 0.14.0-beta.1 - Minecraft 26.2 resident RenderSection ABI report");
        lines.add("capturedUtc=" + Instant.now());
        lines.add("purpose=derive independent shadow draw preparation from accepted light-visible sections");
        lines.add("safety=public API description only; no private access; no RenderSection invocation; no object retention");
        lines.add("viewAreaClass=" + viewArea.getClass().getName());
        lines.add("renderSectionClass=" + renderSection.getClass().getName());
        lines.add("");

        appendPublicType(lines, "VIEW_AREA_PUBLIC_API", viewArea.getClass(), true);
        lines.add("");
        appendPublicType(lines, "RENDER_SECTION_PUBLIC_API", renderSection.getClass(), true);

        Class<?> enclosing = renderSection.getClass().getEnclosingClass();
        if (enclosing != null) {
            lines.add("");
            appendPublicType(lines, "RENDER_SECTION_ENCLOSING_PUBLIC_API", enclosing, true);
        }

        lines.add("");
        lines.add("[public-return-types-from-render-section]");
        Arrays.stream(renderSection.getClass().getMethods())
                .sorted(Comparator.comparing(PrismShadowRenderSectionProbe::methodSortKey))
                .filter(PrismShadowRenderSectionProbe::relevant)
                .map(Method::getReturnType)
                .filter(type -> type != void.class && !type.isPrimitive() && type != String.class)
                .distinct()
                .forEach(type -> lines.add("  " + type.getName()));

        Path report = reportPath();
        Files.createDirectories(report.getParent());
        Path temporary = report.resolveSibling(report.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
        }
        PrismMod.LOGGER.info("Prism 26.2 resident RenderSection ABI captured without invocation: {}", report);
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

        lines.add("[public-methods]");
        Arrays.stream(type.getMethods())
                .sorted(Comparator.comparing(PrismShadowRenderSectionProbe::methodSortKey))
                .filter(method -> !filterRelevant || relevant(method))
                .forEach(method -> lines.add("  " + describe(method)));
    }

    private static boolean relevant(Method method) {
        String text = (method.getName() + " " + method.getReturnType().getTypeName() + " "
                + Arrays.toString(method.getParameterTypes())).toLowerCase(Locale.ROOT);
        return text.contains("buffer")
                || text.contains("compile")
                || text.contains("mesh")
                || text.contains("layer")
                || text.contains("section")
                || text.contains("origin")
                || text.contains("node")
                || text.contains("render")
                || text.contains("transluc")
                || text.contains("geometry")
                || text.contains("index")
                || text.contains("vertex")
                || text.contains("reset");
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
