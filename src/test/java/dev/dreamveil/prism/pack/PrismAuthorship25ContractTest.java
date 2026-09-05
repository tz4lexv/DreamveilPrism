package dev.dreamveil.prism.pack;

import com.google.gson.*;
import dev.dreamveil.prism.graph.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public final class PrismAuthorship25ContractTest {
    public static void main(String[] args) throws Exception {
        graphContract();
        Path example = Path.of("examples/PrismExplicit25Template");
        var pack = PrismPackParser.parse(example);
        var prepared = PrismPipelineCompiler.prepareAsync(pack, List.of(), Runnable::run).join();
        require(prepared.graph().orderedPasses().stream().map(PrismPass::name).toList()
                .equals(List.of("produce", "display")), "resource dependency, not declaration order");
        require(!pack.sceneJitter() && !pack.dynamicResolution().enabled(), "effects remain opt-in");
        var original = JsonParser.parseString(Files.readString(example.resolve("prism.json"))).getAsJsonObject();
        for (String field : List.of("scale", "format", "lifetime", "mip_levels")) reject(example, original, j -> resource(j).remove(field));
        reject(example, original, j -> resource(j).addProperty("scale_x", 0.5));
        reject(example, original, j -> { resource(j).remove("scale"); resource(j).addProperty("scale_x", 1); });
        for (String field : List.of("type", "blend", "output")) reject(example, original, j -> display(j).remove(field));
        for (String field : List.of("filter", "wrap")) reject(example, original, j -> sampler(j).remove(field));
        for (String field : List.of("local_size_x", "local_size_y", "local_size_z")) {
            reject(example, original, j -> j.getAsJsonArray("programs").get(1).getAsJsonObject().getAsJsonObject("dispatch").remove(field));
        }
        reject(example, original, j -> j.add("dynamic_resolution", new JsonObject()));
        var view = JsonParser.parseString("""
                {"programs":[{"type":"scene_view","blend":"opaque","outputs":[],"view":{
                "geometry":"captured_models","depth_direction":"reversed","depth_write":true,
                "depth_load":"clear","color_load":"clear","cull":false,"atlas_sampler":"Albedo",
                "model_domains":["entity"],"model_radius":0}}]}
                """).getAsJsonObject();
        PrismAuthorshipContract.validate(view);
        for (String field : List.of("model_domains", "model_radius", "geometry", "depth_direction", "cull")) {
            var changed = view.deepCopy(); changed.getAsJsonArray("programs").get(0).getAsJsonObject().getAsJsonObject("view").remove(field);
            try { PrismAuthorshipContract.validate(changed); throw new AssertionError("implicit view " + field); }
            catch (PrismPackLoadException expected) { require(expected.code().equals("PRISM_E2101"), "explicit view diagnostic"); }
        }
        // Names do not authorize history, filters, jitter or a different image extent.
        var namedHistory = original.deepCopy(); resource(namedHistory).addProperty("id", "demo:history");
        PrismAuthorshipContract.validate(namedHistory);
        require(resource(namedHistory).get("lifetime").getAsString().equals("transient"), "name cannot create history");
        // Established API 1.24 examples and legacy source conventions still parse.
        PrismPackParser.parse(Path.of("examples/PrismVisibility24Template"));
        System.out.println("PRISM_AUTHORSHIP25_CONTRACT_PASS");
    }

    private static void graphContract() {
        for (boolean reverse : List.of(false, true)) {
            var graph = transientGraph();
            var producer = pass("a", List.of(), "pepe", PrismResourceAccess.WRITE);
            var consumer = pass("b", List.of(), "pepe", PrismResourceAccess.READ);
            graph.addPass(reverse ? consumer : producer).addPass(reverse ? producer : consumer);
            require(graph.compileExplicit().orderedPasses().getFirst().name().equals("a"), "permutation-invariant producer");
        }
        var ambiguous = transientGraph().addPass(pass("a", List.of(), "pepe", PrismResourceAccess.WRITE))
                .addPass(pass("b", List.of(), "pepe", PrismResourceAccess.WRITE));
        failure(ambiguous, "E2104");
        require(ambiguous.compile().orderedPasses().size() == 2, "legacy compiler retained");
        var explicit = transientGraph().addPass(pass("a", List.of("b"), "pepe", PrismResourceAccess.WRITE))
                .addPass(pass("b", List.of(), "pepe", PrismResourceAccess.WRITE));
        require(explicit.compileExplicit().orderedPasses().getFirst().name().equals("b"), "explicit reverse write order");
        require(explicit.compileExplicit().transitions().stream().anyMatch(t -> t.hazard()==PrismResourceHazard.WRITE_AFTER_WRITE),"WAW transition retained");
        var imported = new PrismRenderGraph().importResource("color")
                .addPass(pass("a", List.of(), "color", PrismResourceAccess.READ))
                .addPass(pass("b", List.of(), "color", PrismResourceAccess.WRITE));
        failure(imported, "E2104");
        var war = new PrismRenderGraph().importResource("color")
                .addPass(pass("b", List.of("a"), "color", PrismResourceAccess.WRITE))
                .addPass(pass("a", List.of(), "color", PrismResourceAccess.READ));
        require(war.compileExplicit().transitions().stream().anyMatch(t -> t.hazard()==PrismResourceHazard.WRITE_AFTER_READ),"explicit WAR transition retained");
        var readers = new PrismRenderGraph().importResource("color")
                .addPass(pass("a", List.of(), "color", PrismResourceAccess.READ))
                .addPass(pass("b", List.of(), "color", PrismResourceAccess.READ));
        require(readers.compileExplicit().dependencies().values().stream().allMatch(Set::isEmpty), "independent reads stay independent");
        failure(transientGraph().addPass(pass("a", List.of(), "pepe", PrismResourceAccess.READ_WRITE)), "before it is produced");
        failure(transientGraph().addPass(pass("a", List.of("missing"), "pepe", PrismResourceAccess.WRITE)), "Unknown dependency");
        failure(transientGraph().addPass(pass("a", List.of("b"), "pepe", PrismResourceAccess.WRITE))
                .addPass(pass("b", List.of(), "pepe", PrismResourceAccess.READ)), "cycle");
        var transitive = transientGraph().addPass(pass("a", List.of(), "pepe", PrismResourceAccess.WRITE))
                .addPass(new PrismPass("middle", List.of(), PrismPassExecutionType.COMPUTE, List.of("a")))
                .addPass(pass("b", List.of("middle"), "pepe", PrismResourceAccess.WRITE));
        require(transitive.compileExplicit().orderedPasses().size() == 3, "transitive declared order proves conflict resolution");
    }
    private static PrismRenderGraph transientGraph() {
        return new PrismRenderGraph().declareResource(PrismResourceDescriptor.transientTexture("pepe",
                PrismTextureDesc.colorAttachment(PrismTextureExtent.relative(1), PrismTextureFormat.RGBA16_FLOAT)));
    }
    private static PrismPass pass(String id, List<String> after, String resource, PrismResourceAccess access) {
        return new PrismPass(id, List.of(new PrismResourceRef(resource, access)), PrismPassExecutionType.GRAPHICS, after);
    }
    private static void failure(PrismRenderGraph graph, String message) {
        try { graph.compileExplicit(); throw new AssertionError("invalid graph accepted"); }
        catch (IllegalArgumentException | IllegalStateException expected) { require(expected.getMessage().contains(message), expected.toString()); }
    }
    private static JsonObject resource(JsonObject root) { return root.getAsJsonArray("resources").get(0).getAsJsonObject(); }
    private static JsonObject display(JsonObject root) { return root.getAsJsonArray("programs").get(0).getAsJsonObject(); }
    private static JsonObject sampler(JsonObject root) { return display(root).getAsJsonArray("samplers").get(0).getAsJsonObject(); }
    private static void reject(Path example, JsonObject original, Consumer<JsonObject> change) throws Exception {
        Path temp = Files.createTempDirectory("prism-authorship25-");
        try {
            Files.createDirectory(temp.resolve("shaders"));
            try (var files = Files.list(example.resolve("shaders"))) {
                for (Path file : files.toList()) Files.copy(file, temp.resolve("shaders").resolve(file.getFileName()));
            }
            var json = original.deepCopy(); change.accept(json); Files.writeString(temp.resolve("prism.json"), json.toString());
            try { PrismPackParser.parse(temp); throw new AssertionError("implicit intent accepted"); }
            catch (PrismPackLoadException expected) {
                require(expected.code().equals("PRISM_E2101") && expected.source().startsWith("prism.json:"), expected.toString());
            }
        } finally {
            try (var files = Files.walk(temp)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
