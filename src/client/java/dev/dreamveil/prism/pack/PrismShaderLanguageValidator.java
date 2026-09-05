package dev.dreamveil.prism.pack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prism shader-language contract for creator packs.
 *
 * Prism v0.12.x targets Minecraft's modern ShaderC path and requires GLSL 450+
 * so stage interfaces can use explicit locations consistently across the graphics
 * abstraction and Vulkan backend.
 */
final class PrismShaderLanguageValidator {
    static final int MIN_GLSL_VERSION = 450;

    private static final Pattern LEADING_VERSION = Pattern.compile(
            "\\A\\uFEFF?(?:(?:\\s+)|(?://[^\\r\\n]*(?:\\R|\\z))|(?:/\\*.*?\\*/))*"
                    + "#version\\s+(\\S+)(?:\\s+[A-Za-z_][A-Za-z0-9_]*)?\\s*(?://[^\\r\\n]*)?(?:\\R|\\z)",
            Pattern.DOTALL);
    private static final Pattern ANY_VERSION = Pattern.compile(
            "(?m)^\\s*#version\\b.*$");

    private PrismShaderLanguageValidator() {
    }

    static void validate(String source, String sourcePath) throws PrismPackLoadException {
        if (source == null || source.isBlank()) {
            throw new PrismPackLoadException(
                    "shader_empty",
                    "Shader source is empty",
                    sourcePath);
        }

        Matcher leading = LEADING_VERSION.matcher(source);
        if (!leading.find()) {
            Matcher any = ANY_VERSION.matcher(source);
            if (any.find()) {
                throw new PrismPackLoadException(
                        "shader_glsl_version_position",
                        "The GLSL #version directive must be the first directive in a Prism shader",
                        sourcePath);
            }
            throw new PrismPackLoadException(
                    "shader_glsl_version_missing",
                    "Prism Shader Language v1 requires an explicit '#version "
                            + MIN_GLSL_VERSION + "' (or newer) directive",
                    sourcePath);
        }

        final int version;
        try {
            version = Integer.parseInt(leading.group(1));
        } catch (NumberFormatException exception) {
            throw new PrismPackLoadException(
                    "shader_glsl_version_invalid",
                    "Invalid GLSL #version value '" + leading.group(1) + "'",
                    sourcePath,
                    exception);
        }

        if (version < MIN_GLSL_VERSION) {
            throw new PrismPackLoadException(
                    "shader_glsl_version_unsupported",
                    "Unsupported GLSL version " + version
                            + ". Prism Shader Language v1 requires GLSL "
                            + MIN_GLSL_VERSION + " or newer",
                    sourcePath);
        }
    }
}
