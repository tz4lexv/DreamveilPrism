package dev.dreamveil.prism.api.setting;

import java.util.Objects;

/** Current value paired with the creator-declared setting definition. */
public record PrismPackSetting(PrismPackSettingDefinition definition, String value) {
    public PrismPackSetting {
        definition = Objects.requireNonNull(definition, "definition");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        value = value.trim();
    }

    public boolean booleanValue() {
        return Boolean.parseBoolean(value);
    }

    public int integerValue() {
        return Integer.parseInt(value);
    }

    public double floatValue() {
        return Double.parseDouble(value);
    }
}
