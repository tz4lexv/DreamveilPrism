package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.dreamveil.prism.api.pack.PrismDiagnosticSeverity;
import dev.dreamveil.prism.api.pack.PrismPackDiagnostic;

/**
 * Compatibility-boundary inspector for OptiFine/Iris-style packs.
 *
 * It deliberately does not inject legacy OpenGL state into Prism. Instead it inventories programs
 * and reports the exact shader contract features that require a separate compatibility renderer.
 */
final class PrismLegacyPackInspector {
    private static final int MAX_SCANNED_SOURCES = 512;
    private static final long MAX_SCANNED_SOURCE_BYTES = 4L * 1024L * 1024L;
    private static final List<String> CONTRACT_TOKENS = List.of(
            "gl_Vertex",
            "gl_ModelViewMatrix",
            "gl_ProjectionMatrix",
            "gl_TextureMatrix",
            "gl_MultiTexCoord",
            "gl_Color",
            "gl_Normal",
            "gl_NormalMatrix",
            "gl_FragData",
            "mc_Entity",
            "mc_midTexCoord",
            "at_tangent",
            "colortex",
            "depthtex",
            "shadowtex");

    private PrismLegacyPackInspector() {
    }

    static boolean looksLikeLegacyPack(Path root) {
        Path shaders = root.resolve("shaders");
        if (!Files.isDirectory(shaders)) return false;
        if (Files.isRegularFile(shaders.resolve("shaders.properties"))) return true;
        if (containsClassicProgramPair(shaders)) return true;
        for (String dimension : List.of("world0", "world-1", "world1")) {
            if (containsClassicProgramPair(shaders.resolve(dimension))) return true;
        }
        return false;
    }

    static PrismPackDiagnostic diagnostic(Path root, String sourceName) {
        Inventory inventory = inspect(root);
        StringBuilder message = new StringBuilder(256)
                .append("Classic OptiFine/Iris shader pack detected: ")
                .append(inventory.programCount()).append(" program pairs across ")
                .append(inventory.dimensionCount()).append(" dimension set(s). ");

        if (inventory.blockers().isEmpty()) {
            message.append("No known legacy built-ins were found, but Prism 0.11 does not silently reinterpret classic program names. ")
                    .append("Port these stages to shaders/prism/<domain>.vsh/.fsh or use the separate legacy compatibility renderer when available.");
        } else {
            message.append("Execution requires the separate legacy compatibility renderer because sources depend on: ")
                    .append(String.join(", ", inventory.blockers())).append(". ")
                    .append("Prism core remains Blaze3D-oriented and will not emulate OpenGL texture units or fixed-function built-ins.");
        }
        return new PrismPackDiagnostic(
                PrismDiagnosticSeverity.ERROR,
                "legacy_scene_contract_unsupported",
                message.toString(),
                sourceName == null ? root.getFileName().toString() : sourceName);
    }

    static Inventory inspect(Path root) {
        Path shaders = root.resolve("shaders");
        int pairs = 0;
        int dimensions = 0;
        Set<String> blockers = new LinkedHashSet<>();
        List<Path> programRoots = new ArrayList<>();
        if (Files.isDirectory(shaders)) programRoots.add(shaders);
        for (String dimension : List.of("world0", "world-1", "world1")) {
            Path candidate = shaders.resolve(dimension);
            if (Files.isDirectory(candidate)) programRoots.add(candidate);
        }

        for (Path programRoot : programRoots) {
            int rootPairs = countClassicProgramPairs(programRoot);
            pairs += rootPairs;
            if (rootPairs > 0) dimensions++;
        }

        // Wrapper stages in real shader packs often contain only #include directives. Scan the
        // bounded source tree as well so the diagnostic reports the actual compatibility contract.
        if (Files.isDirectory(shaders)) {
            try (var files = Files.walk(shaders, 8)) {
                files.filter(Files::isRegularFile)
                        .filter(PrismLegacyPackInspector::isShaderSource)
                        .limit(MAX_SCANNED_SOURCES)
                        .forEach(path -> scanTokens(path, blockers));
            } catch (IOException ignored) {
                blockers.add("unreadable shader source tree");
            }
        }
        return new Inventory(pairs, dimensions, List.copyOf(blockers));
    }

    private static boolean containsClassicProgramPair(Path root) {
        return countClassicProgramPairs(root) > 0;
    }

    private static int countClassicProgramPairs(Path root) {
        if (!Files.isDirectory(root)) return 0;
        Set<String> vertex = new LinkedHashSet<>();
        Set<String> fragment = new LinkedHashSet<>();
        try (var files = Files.list(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".vsh")) {
                    String stem = name.substring(0, name.length() - 4);
                    if (isClassicProgramStem(stem)) vertex.add(stem);
                } else if (name.endsWith(".fsh")) {
                    String stem = name.substring(0, name.length() - 4);
                    if (isClassicProgramStem(stem)) fragment.add(stem);
                }
            }
        } catch (IOException ignored) {
            return 0;
        }
        vertex.retainAll(fragment);
        return vertex.size();
    }

    private static boolean isClassicProgramStem(String stem) {
        return stem.startsWith("gbuffers_")
                || stem.equals("shadow") || stem.startsWith("shadowcomp")
                || stem.startsWith("deferred")
                || stem.startsWith("composite")
                || stem.startsWith("prepare")
                || stem.equals("final");
    }

    private static boolean isShaderSource(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".vsh") || name.endsWith(".fsh") || name.endsWith(".glsl")
                || name.endsWith(".vert") || name.endsWith(".frag");
    }

    private static void scanTokens(Path path, Set<String> blockers) {
        try {
            if (Files.size(path) > MAX_SCANNED_SOURCE_BYTES) {
                blockers.add("oversized legacy shader source");
                return;
            }
            String source = Files.readString(path, StandardCharsets.UTF_8);
            String lower = source.toLowerCase(Locale.ROOT);
            if (lower.matches("(?s).*\\battribute\\s+.*")) blockers.add("legacy attribute declarations");
            if (lower.matches("(?s).*\\bvarying\\s+.*")) blockers.add("legacy varying declarations");
            if (lower.contains("texture2d(")) blockers.add("legacy texture2D syntax");
            for (String token : CONTRACT_TOKENS) {
                if (source.contains(token)) blockers.add(token);
            }
        } catch (IOException ignored) {
            blockers.add("unreadable shader sources");
        }
    }

    record Inventory(int programCount, int dimensionCount, List<String> blockers) {
        Inventory {
            blockers = List.copyOf(blockers);
        }
    }
}
