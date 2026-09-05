package dev.dreamveil.prism.pack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Backend-neutral normalized program set used by the runtime after pack discovery.
 *
 * File names and source formats are a loader concern. The renderer consumes semantic scene
 * domains plus ordered post/fullscreen programs so legacy naming never leaks into scene dispatch.
 */
final class PrismProgramSet {
    private final Map<PrismSceneDomain, PrismPipelineDefinition> scenePrograms;
    private final List<PrismPipelineDefinition> postPrograms;
    private final List<PrismPipelineDefinition> computePrograms;
    private final List<PrismPipelineDefinition> orderedPrograms;

    private PrismProgramSet(
            Map<PrismSceneDomain, PrismPipelineDefinition> scenePrograms,
            List<PrismPipelineDefinition> postPrograms,
            List<PrismPipelineDefinition> computePrograms,
            List<PrismPipelineDefinition> orderedPrograms) {
        this.scenePrograms = Map.copyOf(scenePrograms);
        this.postPrograms = List.copyOf(postPrograms);
        this.computePrograms = List.copyOf(computePrograms);
        this.orderedPrograms = List.copyOf(orderedPrograms);
    }

    static PrismProgramSet from(List<PrismPipelineDefinition> definitions) throws PrismPackLoadException {
        EnumMap<PrismSceneDomain, PrismPipelineDefinition> scene = new EnumMap<>(PrismSceneDomain.class);
        List<PrismPipelineDefinition> post = new ArrayList<>();
        List<PrismPipelineDefinition> compute = new ArrayList<>();
        List<PrismPipelineDefinition> ordered = new ArrayList<>(definitions.size());

        for (PrismPipelineDefinition definition : definitions) {
            ordered.add(definition);
            if (definition.isScene()) {
                PrismSceneDomain domain = definition.sceneDomain();
                PrismPipelineDefinition previous = scene.put(domain, definition);
                if (previous != null) {
                    throw new PrismPackLoadException(
                            "scene_domain_duplicate",
                            "Programs '" + previous.id() + "' and '" + definition.id()
                                    + "' both target scene domain '" + domain.manifestName() + "'",
                            "program-set");
                }
            } else if (definition.isGraphGraphics()) {
                post.add(definition);
            } else if (definition.isCompute()) {
                compute.add(definition);
            } else {
                throw new PrismPackLoadException(
                        "program_type_unsupported",
                        "Unsupported normalized program type '" + definition.type() + "'",
                        "program-set");
            }
        }
        return new PrismProgramSet(scene, post, compute, ordered);
    }

    Map<PrismSceneDomain, PrismPipelineDefinition> scenePrograms() {
        return scenePrograms;
    }

    PrismPipelineDefinition sceneProgram(PrismSceneDomain domain) {
        return scenePrograms.get(domain);
    }

    List<PrismPipelineDefinition> postPrograms() {
        return postPrograms;
    }

    List<PrismPipelineDefinition> computePrograms() {
        return computePrograms;
    }

    List<PrismPipelineDefinition> orderedPrograms() {
        return orderedPrograms;
    }

    boolean hasScenePrograms() {
        return !scenePrograms.isEmpty();
    }

    boolean hasPostPrograms() {
        return !postPrograms.isEmpty();
    }

    boolean hasComputePrograms() {
        return !computePrograms.isEmpty();
    }
}
