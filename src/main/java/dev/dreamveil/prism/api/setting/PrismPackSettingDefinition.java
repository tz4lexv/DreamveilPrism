package dev.dreamveil.prism.api.setting;

import java.util.List;
import java.util.Objects;

/** Immutable creator-declared setting metadata. Values are compiled into shader permutations. */
public record PrismPackSettingDefinition(
        String id,
        String label,
        PrismPackSettingType type,
        String defaultValue,
        String description,
        double min,
        double max,
        double step,
        List<String> values) {
    public PrismPackSettingDefinition {
        id = requireText(id, "id");
        label = requireText(label, "label");
        type = Objects.requireNonNull(type, "type");
        defaultValue = requireText(defaultValue, "defaultValue");
        description = description == null ? "" : description.trim();
        values = List.copyOf(values == null ? List.of() : values);

        if (type == PrismPackSettingType.INTEGER || type == PrismPackSettingType.FLOAT) {
            if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step)
                    || min > max || step <= 0.0) {
                throw new IllegalArgumentException("Numeric Prism setting bounds/step are invalid");
            }
        }
        if (type == PrismPackSettingType.ENUM && values.isEmpty()) {
            throw new IllegalArgumentException("Enum Prism settings require at least one value");
        }
    }

    public String macroName() {
        StringBuilder out = new StringBuilder("PRISM_SETTING_");
        for (int i = 0; i < id.length(); i++) {
            char c = Character.toUpperCase(id.charAt(i));
            out.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return out.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
