package dev.dreamveil.prism.pack;

import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

/** CPU + shaderc validation for the API 1.15 Vulkan example without starting Minecraft. */
public final class PrismVulkan15ContractSmokeTest {
    private PrismVulkan15ContractSmokeTest() {}

    public static void main(String[] args) throws Exception {
        PrismPackDefinition pack = PrismPackParser.parse(
                Path.of("examples", "PrismVulkan15Template"));
        require(pack.requiredApi().equals(dev.dreamveil.prism.api.PrismApiVersion.V1_15), "API 1.15");
        require(pack.sceneJitter(), "scene jitter opt-in");
        require(pack.dynamicResolution().enabled(), "dynamic resolution policy");
        require(pack.programSet().postPrograms().stream().anyMatch(p -> p.outputs().size() == 4), "four MRT outputs");
        require(pack.programSet().computePrograms().size() == 1, "one compute program");

        PrismPipelineCompiler.PreparedPack prepared = PrismPipelineCompiler
                .prepareAsync(pack, List.of(), Runnable::run).join();
        require(prepared.graph().orderedPasses().size() == 3, "three ordered graph passes");
        PrismPipelineCompiler.PreparedPipeline compute = prepared.pipelines().stream()
                .filter(item -> item.definition().isCompute()).findFirst().orElseThrow();
        String source = PrismCompiledComputePipeline.injectAbiDefines(
                compute.sources().computeSource(), compute.definition());
        require(source.contains("#define PRISM_BINDING_GBUFFER0 0"), "binding macro 0");
        require(source.contains("#define PRISM_BINDING_FRAME 5"), "frame binding macro");
        var spirv = PrismCompiledComputePipeline.compileSpirv(source, compute.definition().compute())
                .order(ByteOrder.nativeOrder());
        require(spirv.remaining() > 20, "SPIR-V payload");
        require(spirv.getInt(0) == 0x07230203, "SPIR-V magic");
        System.out.println("PRISM_VULKAN15_CONTRACT_SMOKE_OK");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError("Missing API 1.15 contract: " + label);
    }
}
