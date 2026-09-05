package dev.dreamveil.prism.pack;

import java.util.Locale;

/** Small, backend-neutral quality policy for the experimental built-in shadow renderer. */
enum PrismShadowQuality {
    BALANCED("balanced", false),
    QUALITY("quality", true);

    private static final String PROPERTY = "dreamveil.prism.shadowQuality";

    private final String id;
    private final boolean dualCascades;

    PrismShadowQuality(String id, boolean dualCascades) {
        this.id = id;
        this.dualCascades = dualCascades;
    }

    String id() {
        return id;
    }

    boolean dualCascades() {
        return dualCascades;
    }

    int cascadeCount() {
        return dualCascades ? 2 : 1;
    }

    static PrismShadowQuality configured() {
        return parse(System.getProperty(PROPERTY, PrismClientConfig.get().shadowQuality()));
    }

    static PrismShadowQuality parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "quality", "high", "dual", "2" -> QUALITY;
            default -> BALANCED;
        };
    }
}
