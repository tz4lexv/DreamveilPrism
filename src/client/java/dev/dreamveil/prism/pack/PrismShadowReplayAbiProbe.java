package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.lang.reflect.Constructor;
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
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * One-shot Minecraft 26.2 terrain replay ABI capture used by the 0.14 shadow bring-up.
 *
 * <p>This deliberately performs metadata-only reflection. It never invokes private Minecraft
 * methods, never opens fields, never mutates the prepared frame and never recursively calls
 * LevelRenderer.render(). The generated report is the runtime source of truth for the next
 * dedicated shadow terrain replay adapter.</p>
 */
final class PrismShadowReplayAbiProbe {
    private static final String REPORT_NAME = "prism-shadow-replay-abi.txt";
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();
    private static boolean registered;

    private PrismShadowReplayAbiProbe() {
    }

    static synchronized void initialize() {
        if (registered) {
            return;
        }
        registered = true;
        LevelRenderEvents.AFTER_OPAQUE_TERRAIN.register(context -> {
            if (CAPTURED.get()) {
                return;
            }
            Object sections = context.sectionsToRender();
            if (sections == null) {
                return;
            }
            if (!CAPTURED.compareAndSet(false, true)) {
                return;
            }

            try {
                writeReport(context.levelRenderer(), sections, context.levelState());
            } catch (RuntimeException | LinkageError | IOException exception) {
                // This probe must never destabilize rendering. Allow another frame to retry if IO failed.
                CAPTURED.set(false);
                PrismMod.LOGGER.warn("Prism shadow replay ABI report could not be written", exception);
            }
        });
        PrismMod.LOGGER.info(
                "Prism 0.14 shadow replay ABI probe armed; report will be captured after opaque terrain without invoking Minecraft private methods");
    }

    static Path reportPath() {
        return FabricLoader.getInstance().getGameDir().resolve(REPORT_NAME).toAbsolutePath().normalize();
    }

    private static void writeReport(Object levelRenderer, Object sections, Object levelState) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("Dreamveil Prism 0.14.0-beta.1 - Minecraft 26.2 shadow replay ABI report");
        lines.add("capturedUtc=" + Instant.now());
        lines.add("purpose=verify dedicated terrain replay surface before issuing shadow-view GPU draws");
        lines.add("safety=metadata-only reflection; no private method invocation; no field accessibility override; no LevelRenderer.render recursion");
        lines.add("");

        appendType(lines, "SECTIONS_TO_RENDER", sections.getClass(), false);
        appendType(lines, "LEVEL_RENDERER", levelRenderer.getClass(), true);
        appendType(lines, "LEVEL_RENDER_STATE", levelState.getClass(), true);

        Path report = reportPath();
        Files.createDirectories(report.getParent());
        Path temporary = report.resolveSibling(report.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
        }
        PrismMod.LOGGER.info("Prism shadow replay ABI captured: {}", report);
    }

    private static void appendType(List<String> lines, String label, Class<?> type, boolean renderFilter) {
        lines.add("============================================================");
        lines.add(label);
        lines.add("runtimeClass=" + type.getName());
        lines.add("superClass=" + (type.getSuperclass() == null ? "<none>" : type.getSuperclass().getName()));
        lines.add("interfaces=" + Arrays.stream(type.getInterfaces()).map(Class::getName).sorted().toList());
        lines.add("modifiers=" + Modifier.toString(type.getModifiers()));
        lines.add("");

        RecordComponent[] components = type.getRecordComponents();
        if (components != null && components.length > 0) {
            lines.add("[record-components]");
            Arrays.stream(components)
                    .sorted(Comparator.comparing(RecordComponent::getName))
                    .forEach(component -> lines.add("  " + component.getType().getTypeName() + " " + component.getName()));
            lines.add("");
        }

        lines.add("[constructors]");
        Arrays.stream(type.getDeclaredConstructors())
                .sorted(Comparator.comparing(PrismShadowReplayAbiProbe::constructorSortKey))
                .forEach(constructor -> lines.add("  " + describe(constructor)));
        lines.add("");

        lines.add("[fields]");
        Arrays.stream(type.getDeclaredFields())
                .sorted(Comparator.comparing(Field::getName))
                .filter(field -> !renderFilter || isRenderRelevant(field.getName(), field.getType().getTypeName()))
                .forEach(field -> lines.add("  " + describe(field)));
        lines.add("");

        lines.add("[methods]");
        Arrays.stream(type.getDeclaredMethods())
                .sorted(Comparator.comparing(PrismShadowReplayAbiProbe::methodSortKey))
                .filter(method -> !renderFilter || isRenderRelevant(method.getName(), method.getReturnType().getTypeName())
                        || Arrays.stream(method.getParameterTypes()).anyMatch(parameter -> isRenderRelevant("", parameter.getTypeName())))
                .forEach(method -> lines.add("  " + describe(method)));
        lines.add("");

        Class<?>[] nested = type.getDeclaredClasses();
        if (nested.length > 0) {
            lines.add("[nested-types]");
            Arrays.stream(nested).map(Class::getName).sorted().forEach(name -> lines.add("  " + name));
            lines.add("");
        }
    }

    private static boolean isRenderRelevant(String name, String typeName) {
        String text = (name + " " + typeName).toLowerCase(Locale.ROOT);
        return text.contains("render")
                || text.contains("section")
                || text.contains("chunk")
                || text.contains("terrain")
                || text.contains("layer")
                || text.contains("buffer")
                || text.contains("camera")
                || text.contains("projection")
                || text.contains("matrix")
                || text.contains("frustum")
                || text.contains("visible")
                || text.contains("submit")
                || text.contains("draw");
    }

    private static String constructorSortKey(Constructor<?> constructor) {
        return constructor.getName() + Arrays.toString(constructor.getParameterTypes());
    }

    private static String methodSortKey(Method method) {
        return method.getName() + Arrays.toString(method.getParameterTypes()) + method.getReturnType().getName();
    }

    private static String describe(Constructor<?> constructor) {
        return Modifier.toString(constructor.getModifiers()) + " " + constructor.getDeclaringClass().getSimpleName()
                + "(" + joinTypes(constructor.getParameterTypes()) + ")";
    }

    private static String describe(Field field) {
        return Modifier.toString(field.getModifiers()) + " " + field.getType().getTypeName() + " " + field.getName();
    }

    private static String describe(Method method) {
        String throwsClause = method.getExceptionTypes().length == 0
                ? ""
                : " throws " + joinTypes(method.getExceptionTypes());
        return Modifier.toString(method.getModifiers()) + " " + method.getReturnType().getTypeName() + " "
                + method.getName() + "(" + joinTypes(method.getParameterTypes()) + ")" + throwsClause;
    }

    private static String joinTypes(Class<?>[] types) {
        return Arrays.stream(types).map(Class::getTypeName).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
