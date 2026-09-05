package dev.dreamveil.prism.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dreamveil.prism.graph.*;

public final class PrismTerrainView20ContractTest {
    public static void main(String[] args) throws Exception {
        verifyDepthOnlyPipeline();
        Path example = Path.of("examples", "PrismTerrainView20Template");
        var pack = PrismPackParser.parse(example);
        var prepared = PrismPipelineCompiler.prepareAsync(pack, List.of(), Runnable::run).join();
        require(pack.pipelines().getFirst().isSceneView(), "view program");
        String depth = PrismPackResources.toInternal("demo:depth");
        require(prepared.graph().resources().get(depth).textureDesc().format() == PrismTextureFormat.D32_FLOAT, "depth format");
        require(prepared.graph().transitions().stream().anyMatch(t -> t.resourceName().equals(depth)
                && t.fromState() == PrismResourceUsageState.DEPTH_ATTACHMENT_WRITE
                && t.toState() == PrismResourceUsageState.SAMPLED_READ && t.requiresSynchronization()), "depth write/sample barrier");
        require(prepared.graph().dependencies().get("inspect").contains("overhead"), "explicit dependency");
        JsonObject original = JsonParser.parseString(Files.readString(example.resolve("prism.json"))).getAsJsonObject();
        reject(example, original, j -> j.addProperty("prism_api", "1.19"));
        reject(example, original, j -> j.getAsJsonArray("requires").remove(1));
        reject(example, original, j -> j.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("format", "D24_UNORM_S8_UINT"));
        reject(example, original, j -> j.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("lifetime", "history"));
        reject(example, original, j -> j.getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("mip_levels", 2));
        reject(example, original, j -> j.getAsJsonArray("programs").get(0).getAsJsonObject().getAsJsonObject("view").addProperty("section_radius", 100));
        reject(example, original, j -> j.getAsJsonArray("resources").get(1).getAsJsonObject().addProperty("width", 256));
        reject(example, original, j -> j.getAsJsonArray("programs").get(1).getAsJsonObject().getAsJsonArray("after").set(0, new com.google.gson.JsonPrimitive("missing")));
        var graph = new PrismRenderGraph();
        graph.addPass(new PrismPass("second", List.of(), PrismPassExecutionType.GRAPHICS, List.of("first")));
        graph.addPass(new PrismPass("first", List.of()));
        require(graph.compile().orderedPasses().getFirst().name().equals("first"), "resource-free explicit ordering");
        var cycle = new PrismRenderGraph();
        cycle.addPass(new PrismPass("a", List.of(), PrismPassExecutionType.GRAPHICS, List.of("b")));
        cycle.addPass(new PrismPass("b", List.of(), PrismPassExecutionType.GRAPHICS, List.of("a")));
        try { cycle.compile(); throw new AssertionError("cycle accepted"); } catch (IllegalStateException expected) {}
        System.out.println("PRISM_TERRAIN_VIEW20_CONTRACT_PASS");
    }
    private static void verifyDepthOnlyPipeline() {
        var depthState = new com.mojang.blaze3d.pipeline.DepthStencilState(
                com.mojang.blaze3d.platform.CompareOp.GREATER_THAN, true);
        var template = com.mojang.blaze3d.pipeline.RenderPipeline.builder()
                .withLocation("prism/test_depth_only").withVertexShader("prism/test_vertex")
                .withFragmentShader("prism/test_fragment")
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.QUADS)
                .withDepthStencilState(depthState).withCull(false).build();
        var depthOnly = PrismDepthOnlyPipeline.from(template);
        require(depthOnly.getColorTargetStates().length == 0, "true zero color slots, not a null slot");
        require(template.getColorTargetStates().length == 1, "source pipeline is not mutated");
        require(depthOnly.getDepthStencilState() == depthState, "preserve depth compare/write");
        require(depthOnly.getLocation().equals(template.getLocation()), "preserve pipeline identity");
        require(depthOnly.getSortKey() == template.getSortKey(), "preserve sort key");
        require(depthOnly.getPrimitiveTopology() == template.getPrimitiveTopology(), "preserve topology");
        require(!depthOnly.isCull(), "preserve cull policy");
    }

    private static void reject(Path example, JsonObject original, java.util.function.Consumer<JsonObject> change) throws Exception {
        Path temp = Files.createTempDirectory("prism-view-contract-");
        try {
            Files.createDirectory(temp.resolve("shaders"));
            try (var shaders = Files.list(example.resolve("shaders"))) {
                for (Path shader : shaders.toList()) Files.copy(shader, temp.resolve("shaders").resolve(shader.getFileName()));
            }
            JsonObject changed = original.deepCopy();
            change.accept(changed);
            Files.writeString(temp.resolve("prism.json"), changed.toString());
            try { PrismPackParser.parse(temp); throw new AssertionError("invalid view accepted"); }
            catch (PrismPackLoadException expected) {}
        } finally {
            try (var files = Files.walk(temp)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
