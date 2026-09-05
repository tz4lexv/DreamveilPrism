package dev.dreamveil.prism.pack;

import java.nio.file.Path;
import java.util.List;

import dev.dreamveil.prism.api.PrismApiVersion;
import dev.dreamveil.prism.api.PrismCapability;
import dev.dreamveil.prism.graph.PrismHostResources;

/** Parser/source/graph contract for API 1.18 on-demand hierarchical scene depth. */
public final class PrismHzb18ContractSmokeTest {
    private PrismHzb18ContractSmokeTest() {}

    public static void main(String[] args) throws Exception {
        PrismPackDefinition pack = PrismPackParser.parse(
                Path.of("examples", "PrismHzb18Template"));
        require(pack.requiredApi().equals(PrismApiVersion.V1_18), "API version");
        require(pack.requiredCapabilities().contains(PrismCapability.VULKAN_BACKEND),
                "Vulkan backend capability");
        require(pack.requiredCapabilities().contains(PrismCapability.SCENE_HIERARCHICAL_DEPTH),
                "scene HZB capability");
        require(pack.pipelines().size() == 1, "one diagnostic program");
        require(pack.pipelines().getFirst().samplers().stream()
                        .anyMatch(binding -> PrismPackResources.SCENE_HIERARCHICAL_DEPTH.equals(binding.resource())),
                "semantic HZB sampler");

        PrismPipelineCompiler.PreparedPack prepared = PrismPipelineCompiler
                .prepareAsync(pack, List.of(), Runnable::run).join();
        var descriptor = prepared.graph().resources().get(PrismHostResources.SCENE_HIERARCHICAL_DEPTH);
        require(descriptor != null && descriptor.imported(),
                "runtime-owned HZB imported into creator graph");
        require(prepared.graph().orderedPasses().size() == 1,
                "one executable graph pass");
        System.out.println("PRISM_HZB18_CONTRACT_SMOKE_OK");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError("Missing API 1.18 HZB contract: " + label);
    }
}
