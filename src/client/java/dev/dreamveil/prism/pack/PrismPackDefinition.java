package dev.dreamveil.prism.pack;

import java.nio.file.Path;
import java.util.List;

import dev.dreamveil.prism.api.PrismApiVersion;
import dev.dreamveil.prism.api.PrismCapability;
import dev.dreamveil.prism.api.setting.PrismPackSettingDefinition;
import dev.dreamveil.prism.api.render.PrismVanillaRenderFeature;

record PrismPackDefinition(
        Path root,
        String id,
        String name,
        String version,
        String author,
        String description,
        PrismApiVersion requiredApi,
        List<PrismCapability> requiredCapabilities,
        List<PrismPackSettingDefinition> settings,
        List<PrismPackTextureDefinition> resources,
        List<PrismPackBufferDefinition> buffers,
        List<PrismPackTextureAssetDefinition> textureAssets,
        PrismDynamicResolutionDefinition dynamicResolution,
        boolean sceneJitter,
        List<PrismVanillaRenderFeature> vanillaReplacements,
        List<PrismPipelineDefinition> pipelines,
        java.util.Map<String, String> generatedSources) {
    PrismPackDefinition {
        root = root.toAbsolutePath().normalize();
        author = author == null ? "" : author;
        description = description == null ? "" : description;
        requiredCapabilities = List.copyOf(requiredCapabilities);
        settings = List.copyOf(settings);
        resources = List.copyOf(resources);
        buffers = List.copyOf(buffers);
        textureAssets = List.copyOf(textureAssets);
        dynamicResolution = dynamicResolution == null
                ? PrismDynamicResolutionDefinition.DISABLED : dynamicResolution;
        vanillaReplacements = List.copyOf(vanillaReplacements);
        pipelines = List.copyOf(pipelines);
        generatedSources = java.util.Map.copyOf(generatedSources);
    }

    PrismPackDefinition(Path root, String id, String name, String version, String author, String description,
            PrismApiVersion requiredApi, List<PrismCapability> requiredCapabilities,
            List<PrismPackSettingDefinition> settings, List<PrismPackTextureDefinition> resources,
            List<PrismPackBufferDefinition> buffers, List<PrismPackTextureAssetDefinition> textureAssets,
            PrismDynamicResolutionDefinition dynamicResolution, boolean sceneJitter,
            List<PrismVanillaRenderFeature> vanillaReplacements, List<PrismPipelineDefinition> pipelines) {
        this(root, id, name, version, author, description, requiredApi, requiredCapabilities, settings,
                resources, buffers, textureAssets, dynamicResolution, sceneJitter, vanillaReplacements,
                pipelines, java.util.Map.of());
    }

    String source(String path, PrismShaderSourceLoader.Session session) throws PrismPackLoadException {
        String generated = generatedSources.get(path);
        return generated != null ? generated : session.load(path);
    }

    String diagnosticNames(String message) {
        String result = message;
        for (var entry : generatedSources.entrySet()) {
            for (String prefix : java.util.List.of("$prism/pass-label/", "$prism/resource-label/")) {
                if (entry.getKey().startsWith(prefix)) result = result.replace(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    PrismPackTextureDefinition resource(String id) {
        return resources.stream().filter(resource -> resource.id().equals(id)).findFirst().orElse(null);
    }

    PrismPackTextureAssetDefinition textureAsset(String id) {
        return textureAssets.stream().filter(texture -> texture.id().equals(id)).findFirst().orElse(null);
    }

    PrismPackBufferDefinition buffer(String id) {
        return buffers.stream().filter(buffer -> buffer.id().equals(id)).findFirst().orElse(null);
    }

    PrismProgramSet programSet() {
        try {
            return PrismProgramSet.from(pipelines);
        } catch (PrismPackLoadException exception) {
            throw new IllegalStateException("Pack definition lost ProgramSet invariants", exception);
        }
    }
}
