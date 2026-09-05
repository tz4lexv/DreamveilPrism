package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Resolves the logical root of a pack without accepting ambiguous nested archives. */
final class PrismPackRootLocator {
    private PrismPackRootLocator() {
    }

    static Path locate(Path extractedRoot) throws PrismPackLoadException {
        Path root = extractedRoot.toAbsolutePath().normalize();
        if (looksLikePackRoot(root)) {
            return root;
        }

        List<Path> candidates = new ArrayList<>();
        try (var children = Files.list(root)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                if (looksLikePackRoot(child)) {
                    candidates.add(child.toAbsolutePath().normalize());
                }
            }
        } catch (IOException exception) {
            throw new PrismPackLoadException(
                    "pack_root_read",
                    "Could not inspect shader-pack root: " + exception.getMessage(),
                    root.getFileName() == null ? root.toString() : root.getFileName().toString(),
                    exception);
        }

        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (candidates.size() > 1) {
            throw new PrismPackLoadException(
                    "pack_root_ambiguous",
                    "Archive/folder contains multiple shader-pack roots; keep one pack per ZIP/folder",
                    root.getFileName() == null ? root.toString() : root.getFileName().toString());
        }
        return root;
    }

    static boolean looksLikePackRoot(Path root) {
        return Files.isRegularFile(root.resolve("prism.json"))
                || PrismGlslFrontend.looksLike(root)
                || PrismNativePackLoader.looksLikeNativePack(root)
                || PrismLegacyPackInspector.looksLikeLegacyPack(root);
    }
}
