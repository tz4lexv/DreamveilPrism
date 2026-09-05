package dev.dreamveil.prism.api.pack;

/**
 * Version-aware Shader Library 2 selector.
 * Legacy selectors contain only an id; Prism 0.8 persists concrete selections as id@version.
 */
public record PrismPackSelector(String id, String version) {
    public PrismPackSelector {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Pack selector id must not be blank");
        }
        version = version == null ? "" : version.trim();
    }

    public boolean versioned() {
        return !version.isEmpty();
    }

    public String persisted() {
        return versioned() ? id + "@" + version : id;
    }

    public static PrismPackSelector parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Pack selector must not be blank");
        }
        String normalized = value.trim();
        int separator = normalized.lastIndexOf('@');
        if (separator <= 0 || separator == normalized.length() - 1) {
            return new PrismPackSelector(normalized, "");
        }
        return new PrismPackSelector(
                normalized.substring(0, separator),
                normalized.substring(separator + 1));
    }
}
