package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import dev.dreamveil.prism.api.PrismApiVersion;
import dev.dreamveil.prism.api.PrismCapability;

/**
 * Manifest-less Prism Native scene loader.
 *
 * A pack may contain shaders/prism/<domain>.vsh + .fsh (or .vert + .frag). Optional
 * prism.properties supplies metadata. The result is normalized to the same pack definition used
 * by prism.json packs, so the GPU/runtime path is identical.
 */
final class PrismNativePackLoader {
    private static final String SHADER_DIR = "shaders/prism";

    private PrismNativePackLoader() {
    }

    static boolean looksLikeNativePack(Path root) {
        Path shaders = root.resolve(SHADER_DIR);
        if (!Files.isDirectory(shaders)) return false;
        for (PrismSceneDomain domain : PrismSceneDomain.values()) {
            if (findStage(shaders, domain.fileStem(), true) != null
                    || findStage(shaders, domain.fileStem(), false) != null) {
                return true;
            }
        }
        return false;
    }

    static PrismPackDefinition load(Path root, String sourceName) throws PrismPackLoadException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path shaders = normalizedRoot.resolve(SHADER_DIR).normalize();
        if (!Files.isDirectory(shaders)) {
            throw new PrismPackLoadException(
                    "native_shader_dir_missing",
                    "Manifest-less Prism Native packs require " + SHADER_DIR,
                    sourceName);
        }

        Properties metadata = readMetadata(normalizedRoot.resolve("prism.properties"));
        String fallbackStem = stripExtension(sourceName == null ? normalizedRoot.getFileName().toString() : sourceName);
        String id = sanitizeId(metadata.getProperty("id", fallbackStem));
        String name = nonBlank(metadata.getProperty("name"), humanize(fallbackStem));
        String version = nonBlank(metadata.getProperty("version"), "1.0.0");
        String author = nonBlank(metadata.getProperty("author"), "");
        String description = nonBlank(metadata.getProperty("description"), "Manifest-less Prism Native scene pack");
        // Legacy filename-based packs keep their published contract, never silently opt
        // into a future frontend's authorship semantics when the runtime is upgraded.
        PrismApiVersion api = parseApi(metadata.getProperty("prism_api", PrismApiVersion.V1_24.toString()), sourceName);
        if (api.compareTo(PrismApiVersion.V1_25) >= 0) {
            throw new PrismPackLoadException("explicit_manifest_required",
                    "API 1.25 requires prism.json with explicit program types, domains and source paths; filename routing is a legacy contract",
                    "prism.properties:prism_api");
        }

        List<PrismPipelineDefinition> programs = new ArrayList<>();
        for (PrismSceneDomain domain : PrismSceneDomain.values()) {
            Path vertex = findStage(shaders, domain.fileStem(), true);
            Path fragment = findStage(shaders, domain.fileStem(), false);
            if (vertex == null && fragment == null) continue;
            if (vertex == null || fragment == null) {
                throw new PrismPackLoadException(
                        "native_stage_pair",
                        "Native scene domain '" + domain.manifestName()
                                + "' must provide both vertex and fragment stages",
                        SHADER_DIR + "/" + domain.fileStem());
            }
            programs.add(new PrismPipelineDefinition(
                    domain.fileStem(),
                    "scene",
                    domain.manifestName(),
                    relative(normalizedRoot, vertex),
                    relative(normalizedRoot, fragment),
                    "",
                    "inherit",
                    false,
                    List.of(),
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    null));
        }

        if (programs.isEmpty()) {
            throw new PrismPackLoadException(
                    "native_programs_empty",
                    "No recognized Prism Native scene programs were found",
                    SHADER_DIR);
        }
        PrismProgramSet programSet = PrismProgramSet.from(programs);
        List<PrismCapability> required = new ArrayList<>();
        required.add(PrismCapability.NATIVE_PROGRAM_SETS);
        required.add(PrismCapability.MANIFESTLESS_NATIVE_PACKS);
        required.add(PrismCapability.WORLD_RENDER_PIPELINE);
        // Depth direction is a runtime clip convention, not a pack-format requirement.
        // Native scene programs inherit the active Blaze3D depth state; code that needs to
        // reconstruct or cull from depth must consume PrismFrameData.clipConvention().
        if (programSet.scenePrograms().keySet().stream().anyMatch(PrismSceneDomain::isTerrainDomain)) {
            required.add(PrismCapability.SCENE_TERRAIN_PIPELINES);
        }
        if (programSet.scenePrograms().keySet().stream().anyMatch(PrismSceneExecutionSupport::isDynamicFeature)) {
            required.add(PrismCapability.FEATURE_RENDER_MODEL_PIPELINES);
            required.add(PrismCapability.DYNAMIC_SCENE_PIPELINE_VARIANTS);
        }
        if (programSet.scenePrograms().containsKey(PrismSceneDomain.ENTITY_OPAQUE)
                || programSet.scenePrograms().containsKey(PrismSceneDomain.ENTITY_TRANSLUCENT)) {
            required.add(PrismCapability.ENTITY_SCENE_PIPELINES);
        }
        if (programSet.scenePrograms().containsKey(PrismSceneDomain.BLOCK_ENTITY)) {
            required.add(PrismCapability.BLOCK_ENTITY_SCENE_PIPELINES);
        }

        return new PrismPackDefinition(
                normalizedRoot,
                id,
                name,
                version,
                author,
                description,
                api,
                required,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                PrismDynamicResolutionDefinition.DISABLED,
                false,
                List.of(),
                programs);
    }

    private static Properties readMetadata(Path path) throws PrismPackLoadException {
        Properties properties = new Properties();
        if (!Files.isRegularFile(path)) return properties;
        try {
            if (Files.size(path) > 64L * 1024L) {
                throw new PrismPackLoadException(
                        "native_metadata_limit",
                        "prism.properties exceeds the 64 KiB metadata limit",
                        "prism.properties");
            }
        } catch (IOException exception) {
            throw new PrismPackLoadException(
                    "native_metadata_read",
                    "Could not stat prism.properties: " + exception.getMessage(),
                    "prism.properties",
                    exception);
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException | IllegalArgumentException exception) {
            throw new PrismPackLoadException(
                    "native_metadata_read",
                    "Could not read prism.properties: " + exception.getMessage(),
                    "prism.properties",
                    exception);
        }
    }

    private static PrismApiVersion parseApi(String raw, String source) throws PrismPackLoadException {
        final PrismApiVersion api;
        try {
            api = PrismApiVersion.parse(raw);
        } catch (IllegalArgumentException exception) {
            throw new PrismPackLoadException("prism_api", exception.getMessage(), source, exception);
        }
        if (!PrismApiVersion.CURRENT.isCompatibleWith(api)) {
            throw new PrismPackLoadException(
                    "prism_api_incompatible",
                    "Pack requires Prism API " + api + " but runtime provides " + PrismApiVersion.CURRENT,
                    source);
        }
        return api;
    }

    private static Path findStage(Path shaders, String stem, boolean vertex) {
        String[] extensions = vertex ? new String[] { ".vsh", ".vert" } : new String[] { ".fsh", ".frag" };
        for (String extension : extensions) {
            Path candidate = shaders.resolve(stem + extension);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String sanitizeId(String raw) {
        String lower = nonBlank(raw, "prism.pack").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_")
                .replaceAll("_+", "_");
        while (!lower.isEmpty() && !Character.isLetterOrDigit(lower.charAt(0))) lower = lower.substring(1);
        if (lower.isEmpty()) lower = "prism.pack";
        if (lower.length() > 64) lower = lower.substring(0, 64);
        return lower;
    }

    private static String humanize(String raw) {
        String value = nonBlank(raw, "Prism Native Pack").replace('_', ' ').replace('-', ' ').trim();
        if (value.isEmpty()) return "Prism Native Pack";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String stripExtension(String name) {
        if (name == null || name.isBlank()) return "prism_pack";
        int dot = name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name.length() - 4 : -1;
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
