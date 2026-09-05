package dev.dreamveil.prism.api.pack;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * Deterministic shader-pack version ordering used by Shader Library 2.
 *
 * This is SemVer-friendly but intentionally tolerant of creator version strings that are not
 * strict semantic versions. Build metadata does not affect ordering; releases sort after
 * pre-releases with the same core.
 */
public final class PrismPackVersion {
    private PrismPackVersion() {
    }

    public static int compare(String left, String right) {
        VersionParts a = VersionParts.parse(left);
        VersionParts b = VersionParts.parse(right);

        int core = compareIdentifiers(a.core(), b.core(), true);
        if (core != 0) return core;

        if (a.preRelease().isEmpty() && b.preRelease().isEmpty()) return 0;
        if (a.preRelease().isEmpty()) return 1;
        if (b.preRelease().isEmpty()) return -1;
        return compareIdentifiers(a.preRelease(), b.preRelease(), false);
    }

    private static int compareIdentifiers(List<String> left, List<String> right, boolean missingAsZero) {
        int count = Math.max(left.size(), right.size());
        for (int index = 0; index < count; index++) {
            if (index >= left.size()) {
                if (missingAsZero && remainingIdentifiersAreZero(right, index)) return 0;
                return -1;
            }
            if (index >= right.size()) {
                if (missingAsZero && remainingIdentifiersAreZero(left, index)) return 0;
                return 1;
            }

            String a = left.get(index);
            String b = right.get(index);
            boolean aNumeric = numeric(a);
            boolean bNumeric = numeric(b);
            int comparison;
            if (aNumeric && bNumeric) {
                comparison = new BigInteger(a).compareTo(new BigInteger(b));
            } else if (aNumeric != bNumeric && !missingAsZero) {
                comparison = aNumeric ? -1 : 1;
            } else {
                comparison = a.compareToIgnoreCase(b);
            }
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static boolean remainingIdentifiersAreZero(List<String> values, int fromIndex) {
        for (int index = fromIndex; index < values.size(); index++) {
            String value = values.get(index);
            if (!numeric(value) || new BigInteger(value).signum() != 0) return false;
        }
        return true;
    }

    private static boolean numeric(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }

    private record VersionParts(List<String> core, List<String> preRelease) {
        static VersionParts parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            int build = value.indexOf('+');
            if (build >= 0) value = value.substring(0, build);

            String coreText = value;
            String preText = "";
            int dash = value.indexOf('-');
            if (dash >= 0) {
                coreText = value.substring(0, dash);
                preText = value.substring(dash + 1);
            }

            List<String> core = split(coreText);
            List<String> pre = preText.isBlank() ? List.of() : split(preText);
            return new VersionParts(core.isEmpty() ? List.of("0") : core, pre);
        }

        private static List<String> split(String value) {
            if (value == null || value.isBlank()) return List.of();
            return Arrays.stream(value.split("[._-]+"))
                    .filter(part -> !part.isBlank())
                    .toList();
        }
    }
}
