package dev.dreamveil.prism.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import dev.dreamveil.prism.api.PrismApiVersion;
import dev.dreamveil.prism.api.PrismCapability;
import dev.dreamveil.prism.graph.PrismHostResources;

/** Manifest and graph regression for the explicitly scoped model-motion resource. */
public final class PrismModelMotion19ContractSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("examples", "PrismModelMotion19Template");
        var pack = PrismPackParser.parse(root);
        require(pack.requiredApi().equals(PrismApiVersion.V1_19), "version");
        require(pack.requiredCapabilities().contains(PrismCapability.MODEL_DEFORMATION_MOTION), "capability");
        var prepared = PrismPipelineCompiler.prepareAsync(pack, List.of(), Runnable::run).join();
        var descriptor = prepared.graph().resources().get(PrismHostResources.MODEL_MOTION);
        require(descriptor != null && descriptor.imported(), "host-owned graph import");
        require(prepared.graph().orderedPasses().size() == 1, "executable consumer");
        require(PrismPackGpuBudget.modelMotionBytes(1920, 1080) == 1920L * 1080 * 8 + 7077888L,
                "motion target and bounded upload memory included");
        require(PrismPackGpuBudget.modelMotionBytes(Integer.MAX_VALUE, Integer.MAX_VALUE) == Long.MAX_VALUE,
                "memory estimate must saturate on overflow");
        String json = Files.readString(root.resolve("prism.json"));
        reject(root, json.replace("\"prism_api\": \"1.19\"", "\"prism_api\": \"1.18\""));
        reject(root, json.replace("\"MODEL_DEFORMATION_MOTION\", ", ""));
        System.out.println("PRISM_MODEL_MOTION19_CONTRACT_PASS");
    }
    private static void reject(Path root, String json) throws Exception {
        Path temporary = Files.createTempDirectory("prism-motion-contract-");
        try {
            Files.createDirectory(temporary.resolve("shaders"));
            Files.copy(root.resolve("shaders/fullscreen.vsh"), temporary.resolve("shaders/fullscreen.vsh"));
            Files.copy(root.resolve("shaders/motion.fsh"), temporary.resolve("shaders/motion.fsh"));
            Files.writeString(temporary.resolve("prism.json"), json);
            try {
                PrismPackParser.parse(temporary);
                throw new AssertionError("Invalid motion contract was accepted");
            } catch (PrismPackLoadException expected) {
                require(expected.getMessage().contains("MODEL_DEFORMATION_MOTION"), "specific contract diagnostic");
            }
        } finally {
            Files.deleteIfExists(temporary.resolve("prism.json"));
            Files.deleteIfExists(temporary.resolve("shaders/fullscreen.vsh"));
            Files.deleteIfExists(temporary.resolve("shaders/motion.fsh"));
            Files.deleteIfExists(temporary.resolve("shaders"));
            Files.deleteIfExists(temporary);
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
