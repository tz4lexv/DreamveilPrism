package dev.dreamveil.prism.api.pack;

import java.util.Objects;

/** Creator-facing diagnostic produced while discovering, validating or compiling a pack. */
public record PrismPackDiagnostic(
        PrismDiagnosticSeverity severity,
        String code,
        String message,
        String source) {
    public PrismPackDiagnostic {
        severity = Objects.requireNonNull(severity, "severity");
        code = requireText(code, "code");
        message = requireText(message, "message");
        source = source == null ? "" : source;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
