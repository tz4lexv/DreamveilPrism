package dev.dreamveil.prism.api.render;

import java.util.Locale;

/**
 * Late Minecraft world features that a validated Prism pack may own completely.
 *
 * <p>Replacement is transactional: vanilla is suppressed only while the generation that
 * requested the feature is active. Disabling the pack or rejecting a reload restores the
 * corresponding Minecraft pass automatically.</p>
 */
public enum PrismVanillaRenderFeature {
    SKY("sky"),
    CLOUDS("clouds"),
    WEATHER("weather");

    private final String manifestName;

    PrismVanillaRenderFeature(String manifestName) {
        this.manifestName = manifestName;
    }

    public String manifestName() {
        return manifestName;
    }

    public static PrismVanillaRenderFeature parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Vanilla replacement name must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "sky" -> SKY;
            case "cloud", "clouds" -> CLOUDS;
            case "weather", "rain", "snow" -> WEATHER;
            default -> throw new IllegalArgumentException(
                    "Unsupported vanilla replacement '" + value + "' (sky, clouds, weather)");
        };
    }
}
