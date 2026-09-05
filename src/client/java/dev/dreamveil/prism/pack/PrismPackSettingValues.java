package dev.dreamveil.prism.pack;

import java.math.BigDecimal;
import java.util.Locale;

import dev.dreamveil.prism.api.setting.PrismPackSettingDefinition;
import dev.dreamveil.prism.api.setting.PrismPackSettingType;

final class PrismPackSettingValues {
    private PrismPackSettingValues() {
    }

    static String normalize(PrismPackSettingDefinition definition, String value) throws PrismPackLoadException {
        if (value == null) {
            throw invalid(definition, "null");
        }
        String text = value.trim();
        try {
            return switch (definition.type()) {
                case BOOLEAN -> normalizeBoolean(definition, text);
                case INTEGER -> normalizeInteger(definition, text);
                case FLOAT -> normalizeFloat(definition, text);
                case ENUM -> normalizeEnum(definition, text);
            };
        } catch (NumberFormatException exception) {
            throw new PrismPackLoadException(
                    "setting_value",
                    "Invalid value '" + text + "' for setting '" + definition.id() + "'",
                    "settings",
                    exception);
        }
    }

    private static String normalizeBoolean(PrismPackSettingDefinition definition, String text) throws PrismPackLoadException {
        if (text.equalsIgnoreCase("true") || text.equals("1")) {
            return "true";
        }
        if (text.equalsIgnoreCase("false") || text.equals("0")) {
            return "false";
        }
        throw invalid(definition, text);
    }

    private static String normalizeInteger(PrismPackSettingDefinition definition, String text) throws PrismPackLoadException {
        int value = Integer.parseInt(text);
        if (value < definition.min() || value > definition.max()) {
            throw invalid(definition, text);
        }
        double steps = (value - definition.min()) / definition.step();
        if (Math.abs(steps - Math.rint(steps)) > 1e-9) {
            throw invalid(definition, text);
        }
        return Integer.toString(value);
    }

    private static String normalizeFloat(PrismPackSettingDefinition definition, String text) throws PrismPackLoadException {
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value) || value < definition.min() - 1e-12 || value > definition.max() + 1e-12) {
            throw invalid(definition, text);
        }
        double steps = (value - definition.min()) / definition.step();
        double roundedSteps = Math.rint(steps);
        if (Math.abs(steps - roundedSteps) > 1e-7) {
            throw invalid(definition, text);
        }
        // Snap to the declared step using decimal arithmetic so values such as
        // 0.6000000000000001 are persisted/logged as 0.6.
        BigDecimal snapped = BigDecimal.valueOf(definition.min())
                .add(BigDecimal.valueOf(definition.step()).multiply(BigDecimal.valueOf((long) roundedSteps)));
        return canonicalFloat(snapped.doubleValue());
    }

    private static String normalizeEnum(PrismPackSettingDefinition definition, String text) throws PrismPackLoadException {
        return definition.values().stream()
                .filter(value -> value.equals(text))
                .findFirst()
                .orElseThrow(() -> invalid(definition, text));
    }

    static String canonicalFloat(double value) {
        String text = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        return text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0 ? text + ".0" : text;
    }

    static String display(PrismPackSettingDefinition definition, String value) {
        if (definition.type() == PrismPackSettingType.BOOLEAN) {
            return Boolean.parseBoolean(value) ? "On" : "Off";
        }
        return value;
    }

    private static PrismPackLoadException invalid(PrismPackSettingDefinition definition, String value) {
        return new PrismPackLoadException(
                "setting_value",
                String.format(Locale.ROOT, "Invalid value '%s' for setting '%s'", value, definition.id()),
                "settings");
    }
}
