package dev.dreamveil.prism.pack;

import dev.dreamveil.prism.api.resource.PrismResources;
import dev.dreamveil.prism.graph.PrismHostResources;

/** Maps stable creator resource IDs to Minecraft-version-independent internal host bindings. */
final class PrismPackResources {
    static final String MAIN_COLOR = PrismResources.MAIN_COLOR.id().toString();
    static final String MAIN_DEPTH = PrismResources.MAIN_DEPTH.id().toString();
    static final String SCENE_HIERARCHICAL_DEPTH = PrismResources.SCENE_HIERARCHICAL_DEPTH.id().toString();
    static final String MODEL_MOTION = PrismResources.MODEL_MOTION.id().toString();

    private PrismPackResources() {
    }

    static String toInternal(String creatorResource) {
        if (MODEL_MOTION.equals(creatorResource)) return PrismHostResources.MODEL_MOTION;
        if (MAIN_COLOR.equals(creatorResource)) {
            return PrismHostResources.MAIN_COLOR;
        }
        if (MAIN_DEPTH.equals(creatorResource)) {
            return PrismHostResources.MAIN_DEPTH;
        }
        if (SCENE_HIERARCHICAL_DEPTH.equals(creatorResource)) {
            return PrismHostResources.SCENE_HIERARCHICAL_DEPTH;
        }
        if (creatorResource == null || creatorResource.isBlank()) {
            throw new IllegalArgumentException("Prism resource id must not be blank");
        }
        return "prism.pack.resource." + creatorResource;
    }

    static String toInternal(String creatorResource, boolean previousHistory) {
        String current = toInternal(creatorResource);
        return previousHistory ? current + "#previous" : current;
    }

    static String previousHistory(String creatorResource) {
        return toInternal(creatorResource, true);
    }
}
