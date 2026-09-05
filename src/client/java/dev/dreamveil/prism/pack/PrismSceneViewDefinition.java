package dev.dreamveil.prism.pack;

/** Declarative replay scope/state. Projection, deformation and material policy remain GLSL-owned. */
record PrismSceneViewDefinition(String depth, boolean reversedDepth, int sectionRadius,
        String atlasSampler, boolean cull, java.util.List<String> layers,
        boolean loadColor, boolean loadDepth, boolean depthWrite,
        String geometry, java.util.List<String> modelDomains, int modelRadius,
        dev.dreamveil.prism.api.visibility.PrismVisibilityVolume visibility) {
    PrismSceneViewDefinition {
        layers = java.util.List.copyOf(layers);
        modelDomains = java.util.List.copyOf(modelDomains);
    }
    PrismSceneViewDefinition(String depth, boolean reversedDepth, int sectionRadius,
            String atlasSampler, boolean cull) {
        this(depth, reversedDepth, sectionRadius, atlasSampler, cull,
                java.util.List.of("solid", "cutout"), false, false, true, "terrain", java.util.List.of(), 0, null);
    }
    boolean capturedModels() { return geometry.equals("captured_models"); }
}
