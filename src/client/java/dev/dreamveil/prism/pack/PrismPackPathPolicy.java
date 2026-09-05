package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Shared real-path containment checks for files supplied by shader packs. */
final class PrismPackPathPolicy {
    private PrismPackPathPolicy() {
    }

    static Path requireContainedRegularFile(
            Path packRoot,
            Path candidate,
            String escapeCode,
            String missingCode,
            String label,
            String source) throws PrismPackLoadException {
        final Path normalizedRoot;
        final Path normalizedCandidate;
        try {
            normalizedRoot = packRoot.toAbsolutePath().normalize();
            normalizedCandidate = candidate.toAbsolutePath().normalize();
        } catch (InvalidPathException | SecurityException exception) {
            throw new PrismPackLoadException(escapeCode, "Invalid " + label + " path", source, exception);
        }

        if (normalizedCandidate.equals(normalizedRoot) || !normalizedCandidate.startsWith(normalizedRoot)) {
            throw new PrismPackLoadException(escapeCode, label + " escapes the pack directory", source);
        }
        if (!Files.isRegularFile(normalizedCandidate)) {
            throw new PrismPackLoadException(
                    missingCode,
                    label + " does not exist or is not a regular file",
                    source);
        }

        try {
            Path realRoot = normalizedRoot.toRealPath();
            Path realCandidate = normalizedCandidate.toRealPath();
            if (realCandidate.equals(realRoot) || !realCandidate.startsWith(realRoot)) {
                throw new PrismPackLoadException(
                        escapeCode,
                        label + " resolves outside the pack directory (symbolic links may not escape a pack)",
                        source);
            }
        } catch (PrismPackLoadException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw new PrismPackLoadException(
                    missingCode,
                    "Could not safely resolve " + label + ": " + exception.getMessage(),
                    source,
                    exception);
        }
        return normalizedCandidate;
    }
}
