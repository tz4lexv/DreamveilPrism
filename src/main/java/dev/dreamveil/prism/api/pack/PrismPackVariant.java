package dev.dreamveil.prism.api.pack;

import java.util.List;

/**
 * One concrete installed source/version of a Prism shader pack.
 *
 * API 1.4 keeps {@link PrismPackApi#installed()} as the compatibility view with at most one
 * preferred entry per pack id, while this record exposes every valid installed version.
 */
public record PrismPackVariant(
        String id,
        String name,
        String version,
        PrismPackMetadata metadata,
        boolean preferred,
        boolean selected,
        List<PrismPackDiagnostic> diagnostics) {

    public PrismPackVariant {
        if (id == null || id.isBlank() || name == null || name.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("Pack variant id/name/version must not be blank");
        }
        metadata = metadata == null ? PrismPackMetadata.EMPTY : metadata;
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == PrismDiagnosticSeverity.ERROR);
    }

    /** Stable selector syntax persisted by Prism 0.8 for a concrete pack version. */
    public String selector() {
        return id + "@" + version;
    }
}
