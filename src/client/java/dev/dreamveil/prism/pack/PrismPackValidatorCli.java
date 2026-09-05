package dev.dreamveil.prism.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import dev.dreamveil.prism.api.setting.PrismPackSetting;

/** Offline manifest/source/graph validator for pack creators; no GPU or Minecraft window required. */
public final class PrismPackValidatorCli {
    private PrismPackValidatorCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--limits")) {
            System.out.println(new com.google.gson.Gson().toJson(dev.dreamveil.prism.api.PrismLimits.LOADER));
            return;
        }
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("Usage: gradlew validatePack -Ppack=<folder-or-zip>");
        }

        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("Pack source does not exist: " + input);
        }

        Path temporary = null;
        try {
            Path materialized = input;
            String sourceName = input.getFileName().toString();
            if (Files.isRegularFile(input)
                    && sourceName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                temporary = Files.createTempDirectory("prism-pack-validator");
                materialized = new PrismZipPackCache(temporary).materialize(input);
            } else if (!Files.isDirectory(input)) {
                throw new IllegalArgumentException("Pack source must be a directory or .zip: " + input);
            }

            Path root = PrismPackRootLocator.locate(materialized);
            PrismPackDefinition pack = Files.isRegularFile(root.resolve("prism.json"))
                    ? PrismPackParser.parse(root)
                    : PrismGlslFrontend.looksLike(root) ? PrismGlslFrontend.load(root, sourceName)
                    : PrismNativePackLoader.load(root, sourceName);
            List<PrismPackSetting> defaults = pack.settings().stream()
                    .map(definition -> new PrismPackSetting(definition, definition.defaultValue()))
                    .toList();
            PrismPipelineCompiler.PreparedPack prepared = PrismPipelineCompiler
                    .prepareAsync(pack, defaults, Runnable::run)
                    .join();

            System.out.printf(
                    Locale.ROOT,
                    "PRISM_PACK_VALID id=%s version=%s api=%s programs=%d scene=%d fullscreen=%d resources=%d textures=%d settings=%d warnings=%d source=%s%n",
                    pack.id(), pack.version(), pack.requiredApi(),
                    pack.programSet().orderedPrograms().size(),
                    pack.programSet().scenePrograms().size(),
                    pack.programSet().postPrograms().size(),
                    pack.resources().size(), pack.textureAssets().size(), pack.settings().size(),
                    prepared.diagnostics().size(), input);
            for (var diagnostic : prepared.diagnostics()) {
                System.out.printf(
                        Locale.ROOT,
                        "PRISM_PACK_%s code=%s source=%s message=%s%n",
                        diagnostic.severity(), diagnostic.code(), diagnostic.source(), diagnostic.message());
            }
            if (pack.generatedSources().containsKey("$prism/inspector.txt")) {
                System.out.print(pack.generatedSources().get("$prism/inspector.txt"));
                prepared.graph().dependencies().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                        .forEach(entry -> System.out.println("Dependencies " + entry.getKey() + " after " + entry.getValue().stream().sorted().toList()));
                for (var transition : prepared.graph().transitions()) System.out.println("Transition " + transition);
            }
        } catch (RuntimeException exception) {
            Throwable failure = PrismPipelineCompiler.unwrapPreparationFailure(exception);
            if (failure instanceof PrismPackLoadException packFailure) {
                System.err.printf(
                        Locale.ROOT,
                        "PRISM_PACK_INVALID code=%s source=%s message=%s%n",
                        packFailure.code(), packFailure.source(), packFailure.getMessage());
            }
            throw exception;
        } finally {
            if (temporary != null) deleteTree(temporary);
        }
    }

    private static void deleteTree(Path root) {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
            // The OS may retain a short-lived handle after ZIP validation; the temp location remains bounded.
        }
    }
}
