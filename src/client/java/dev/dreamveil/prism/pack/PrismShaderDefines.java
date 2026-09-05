package dev.dreamveil.prism.pack;

import java.util.List;
import java.util.Locale;

import dev.dreamveil.prism.api.setting.PrismPackSetting;
import dev.dreamveil.prism.api.setting.PrismPackSettingType;

/** Injects creator settings as compile-time macros immediately after the validated GLSL #version line. */
final class PrismShaderDefines {
    private PrismShaderDefines() {
    }

    static String inject(String source, List<PrismPackSetting> settings) {
        if (settings.isEmpty()) {
            return source;
        }
        List<PrismPackSetting> referenced = referencedBy(source, settings);
        if (referenced.isEmpty()) {
            return source;
        }
        int version = source.indexOf("#version");
        int newline = version < 0 ? -1 : source.indexOf('\n', version);
        int insertAt = newline < 0 ? source.length() : newline + 1;

        StringBuilder defines = new StringBuilder(256);
        defines.append("// Dreamveil Prism compile-time pack settings\n");
        for (PrismPackSetting setting : referenced) {
            String macro = setting.definition().macroName();
            switch (setting.definition().type()) {
                case BOOLEAN -> defines.append("#define ").append(macro).append(' ')
                        .append(setting.booleanValue() ? '1' : '0').append('\n');
                case INTEGER -> defines.append("#define ").append(macro).append(' ')
                        .append(setting.integerValue()).append('\n');
                case FLOAT -> defines.append("#define ").append(macro).append(' ')
                        .append(PrismPackSettingValues.canonicalFloat(setting.floatValue())).append('\n');
                case ENUM -> appendEnum(defines, macro, setting);
            }
        }
        defines.append("// End Dreamveil Prism pack settings\n");
        return source.substring(0, insertAt) + defines + source.substring(insertAt);
    }

    static String injectTextureMetadata(
            String source,
            List<PrismSamplerBinding> samplers,
            List<PrismPackTextureAssetDefinition> textures) {
        if (samplers.isEmpty() || textures.isEmpty()) return source;
        java.util.Map<String, PrismPackTextureAssetDefinition> byId = new java.util.HashMap<>();
        for (PrismPackTextureAssetDefinition texture : textures) byId.put(texture.id(), texture);

        StringBuilder defines = new StringBuilder(192);
        for (PrismSamplerBinding sampler : samplers) {
            PrismPackTextureAssetDefinition texture = byId.get(sampler.resource());
            if (texture == null) continue;
            String prefix = "PRISM_SAMPLER_" + sanitize(sampler.name());
            defines.append("#define ").append(prefix).append("_SRGB ")
                    .append(texture.srgb() ? '1' : '0').append('\n');
            defines.append("#define ").append(prefix).append("_LINEAR ")
                    .append(texture.srgb() ? '0' : '1').append('\n');
            defines.append("#define ").append(prefix).append("_CUBE ")
                    .append(texture.cubemap() ? '1' : '0').append('\n');
            defines.append("#define ").append(prefix).append("_2D ")
                    .append("2d".equals(texture.dimension()) ? '1' : '0').append('\n');
            defines.append("#define ").append(prefix).append("_2D_ARRAY ")
                    .append(texture.array() ? '1' : '0').append('\n');
            defines.append("#define ").append(prefix).append("_3D ")
                    .append(texture.volume() ? '1' : '0').append('\n');
            defines.append("#define ").append(prefix).append("_LAYERS ")
                    .append(texture.sources().size()).append('\n');
            defines.append("#define ").append(prefix).append("_MIP_LEVELS ")
                    .append(texture.mipLevels()).append('\n');
        }
        if (defines.isEmpty()) return source;

        int version = source.indexOf("#version");
        int newline = version < 0 ? -1 : source.indexOf('\n', version);
        int insertAt = newline < 0 ? source.length() : newline + 1;
        return source.substring(0, insertAt)
                + "// Dreamveil Prism pack texture metadata\n"
                + defines
                + "// End Dreamveil Prism pack texture metadata\n"
                + source.substring(insertAt);
    }


    /**
     * Returns only settings referenced literally by this fully-expanded shader stage.
     * This keeps unrelated creator settings from invalidating otherwise reusable pipelines.
     * Token-pasting around the Prism setting prefix is treated conservatively and injects all settings.
     */
    private static List<PrismPackSetting> referencedBy(String source, List<PrismPackSetting> settings) {
        if (source.contains("##") && source.contains("PRISM_SETTING_")) {
            return settings;
        }
        java.util.ArrayList<PrismPackSetting> referenced = new java.util.ArrayList<>();
        for (PrismPackSetting setting : settings) {
            if (source.contains(setting.definition().macroName())) {
                referenced.add(setting);
            }
        }
        return referenced.isEmpty() ? List.of() : List.copyOf(referenced);
    }

    private static void appendEnum(StringBuilder out, String macro, PrismPackSetting setting) {
        List<String> values = setting.definition().values();
        int selected = values.indexOf(setting.value());
        out.append("#define ").append(macro).append(' ').append(selected).append('\n');
        for (int i = 0; i < values.size(); i++) {
            out.append("#define ").append(macro).append('_')
                    .append(sanitize(values.get(i))).append(' ')
                    .append(i == selected ? '1' : '0').append('\n');
        }
    }

    private static String sanitize(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toUpperCase(value.charAt(i));
            out.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return out.toString().toUpperCase(Locale.ROOT);
    }
}
