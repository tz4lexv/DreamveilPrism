package dev.dreamveil.prism.api.pack;

import java.util.List;
import java.util.Objects;

/** Immutable public snapshot of one installed Prism shader pack. */
public record PrismPackInfo(
        String id,
        String name,
        String version,
        PrismPackStatus status,
        int pipelineCount,
        long generation,
        List<PrismPackDiagnostic> diagnostics) {
    public PrismPackInfo {
        id = requireText(id, "id");
        name = requireText(name, "name");
        version = requireText(version, "version");
        status = Objects.requireNonNull(status, "status");
        if (pipelineCount < 0) {
            throw new IllegalArgumentException("pipelineCount must be >= 0");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be >= 0");
        }
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == PrismDiagnosticSeverity.ERROR);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
