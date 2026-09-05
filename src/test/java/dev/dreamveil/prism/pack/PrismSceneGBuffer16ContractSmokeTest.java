package dev.dreamveil.prism.pack;

import java.nio.file.Path;
import java.util.List;

import dev.dreamveil.prism.api.PrismApiVersion;
import dev.dreamveil.prism.api.PrismCapability;

/** Parser/source/graph contract for API 1.16 real scene attachments. */
public final class PrismSceneGBuffer16ContractSmokeTest {
    private PrismSceneGBuffer16ContractSmokeTest() {}

    public static void main(String[] args) throws Exception {
        PrismPackDefinition pack = PrismPackParser.parse(
                Path.of("examples", "PrismSceneGBuffer16Template"));
        require(pack.requiredApi().equals(PrismApiVersion.V1_16), "API 1.16");
        require(pack.requiredCapabilities().contains(PrismCapability.SCENE_GBUFFER_ATTACHMENTS),
                "scene G-buffer capability");
        require(pack.resources().stream().filter(PrismPackTextureDefinition::scene).count() == 3,
                "three scene-lifetime attachments");
        var scene = pack.programSet().scenePrograms();
        require(scene.size() == 2, "opaque and cutout scene programs");
        PrismPipelineDefinition opaque = scene.get(PrismSceneDomain.TERRAIN_SOLID);
        PrismPipelineDefinition cutout = scene.get(PrismSceneDomain.TERRAIN_CUTOUT);
        require(opaque.outputs().size() == 4, "main color plus three G-buffer targets");
        require(opaque.outputs().equals(cutout.outputs()),
                "shared opaque terrain render-pass layout");

        PrismPipelineCompiler.PreparedPack prepared = PrismPipelineCompiler
                .prepareAsync(pack, List.of(), Runnable::run).join();
        require(prepared.graph().orderedPasses().size() == 2, "two final fullscreen passes");
        for (PrismPackTextureDefinition resource : pack.resources().stream()
                .filter(PrismPackTextureDefinition::scene).toList()) {
            var descriptor = prepared.graph().resources().get(PrismPackResources.toInternal(resource.id()));
            require(descriptor != null && descriptor.imported(),
                    resource.id() + " imported from real scene drawing");
        }
        System.out.println("PRISM_SCENE_GBUFFER16_CONTRACT_SMOKE_OK");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError("Missing API 1.16 scene G-buffer contract: " + label);
    }
}
