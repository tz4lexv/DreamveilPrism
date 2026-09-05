package dev.dreamveil.prism.pack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Rejects fixed-function/OpenGL-era contracts before handing a scene stage to Blaze3D ShaderC. */
final class PrismSceneShaderContractValidator {
    private static final List<String> LEGACY_TOKENS = List.of(
            "gl_Vertex",
            "gl_ModelViewMatrix",
            "gl_ProjectionMatrix",
            "gl_TextureMatrix",
            "gl_MultiTexCoord",
            "gl_Color",
            "gl_Normal",
            "gl_NormalMatrix",
            "gl_FragData",
            "mc_Entity",
            "mc_midTexCoord",
            "at_tangent");

    private PrismSceneShaderContractValidator() {
    }

    static void validate(PrismPipelineDefinition definition, String vertexSource, String fragmentSource)
            throws PrismPackLoadException {
        if (!definition.isScene()) return;
        if (definition.inheritsVertexShader()
                && !PrismSceneExecutionSupport.isDynamicFeature(definition.sceneDomain())) {
            throw new PrismPackLoadException(
                    "scene_vertex_inherit_domain",
                    "vertex '$inherit' is only valid for dynamic Feature Rendering domains",
                    definition.vertex());
        }

        String cleanVertex = stripComments(vertexSource);
        String cleanFragment = stripComments(fragmentSource);
        Set<String> found = new LinkedHashSet<>();
        scan(cleanVertex, found);
        scan(cleanFragment, found);
        if (!found.isEmpty()) {
            throw new PrismPackLoadException(
                    "scene_legacy_shader_contract",
                    "Scene program '" + definition.id() + "' uses legacy shader inputs/state not supplied by "
                            + "Minecraft 26.2's Blaze3D RenderPipeline: " + String.join(", ", found)
                            + ". Port the program to Prism Native domain inputs instead of emulating OpenGL state.",
                    definition.vertex() + " + " + definition.fragment());
        }

        String vertexLower = cleanVertex.toLowerCase(Locale.ROOT);
        String fragmentLower = cleanFragment.toLowerCase(Locale.ROOT);
        if (containsLegacyQualifier(vertexLower) || containsLegacyQualifier(fragmentLower)) {
            throw new PrismPackLoadException(
                    "scene_legacy_shader_syntax",
                    "Scene program '" + definition.id()
                            + "' uses attribute/varying syntax; Prism Native requires GLSL 450 stage inputs/outputs",
                    definition.vertex() + " + " + definition.fragment());
        }
    }

    private static boolean containsLegacyQualifier(String source) {
        return source.matches("(?s).*\\battribute\\s+.*") || source.matches("(?s).*\\bvarying\\s+.*");
    }

    private static void scan(String source, Set<String> found) {
        for (String token : LEGACY_TOKENS) {
            if (source.contains(token)) found.add(token);
        }
    }

    /** Removes comments without disturbing strings/newlines enough to hide executable identifiers. */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        char quote = 0;
        boolean escaped = false;

        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';

            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append('\n');
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && n == '/') {
                    out.append("  ");
                    i++;
                    blockComment = false;
                } else {
                    out.append(c == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (string) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    string = false;
                }
                continue;
            }
            if (c == '/' && n == '/') {
                out.append("  ");
                i++;
                lineComment = true;
                continue;
            }
            if (c == '/' && n == '*') {
                out.append("  ");
                i++;
                blockComment = true;
                continue;
            }
            if (c == '"' || c == '\'') {
                string = true;
                quote = c;
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
