package dev.dreamveil.prism.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dreamveil.prism.api.PrismCapability;
import dev.dreamveil.prism.backend.PrismMinecraft26_2Capabilities;
import dev.dreamveil.prism.graph.*;

/** No GPU required: validate layered replay's manifest, dependency and lifetime contract. */
public final class PrismLayeredTerrain21ContractTest {
    private static final Path EXAMPLE = Path.of("examples", "PrismLayeredTerrain21Template");
    public static void main(String[] args) throws Exception {
        var pack = PrismPackParser.parse(EXAMPLE);
        var prepared = PrismPipelineCompiler.prepareAsync(pack, List.of(), Runnable::run).join();
        var view = pack.pipelines().get(1).sceneView();
        require(view.layers().equals(List.of("translucent")), "translucent layer selection");
        require(view.loadColor() && view.loadDepth() && view.depthWrite(), "attachment preservation");
        require(prepared.graph().dependencies().get("surfaces").contains("opaque"), "producer dependency");
        String depth = PrismPackResources.toInternal("demo:depth");
        String color = PrismPackResources.toInternal("demo:color");
        require(prepared.graph().orderedPasses().get(1).resources().stream().anyMatch(r ->
                r.name().equals(depth) && r.access() == PrismResourceAccess.READ_WRITE
                && r.requiredState() == PrismResourceUsageState.DEPTH_ATTACHMENT_READ_WRITE), "depth load hazard");
        require(prepared.graph().orderedPasses().get(1).resources().stream().anyMatch(r ->
                r.name().equals(color) && r.access() == PrismResourceAccess.READ_WRITE), "color load hazard");
        require(prepared.graph().transitions().stream().anyMatch(t -> t.resourceName().equals(depth)
                && t.toState() == PrismResourceUsageState.DEPTH_ATTACHMENT_READ_WRITE && t.requiresSynchronization()), "depth synchronization");
        require(!PrismMinecraft26_2Capabilities.proven(false).contains(PrismCapability.LAYERED_TERRAIN_VIEWS), "Vulkan gate");
        require(!PrismMinecraft26_2Capabilities.proven(true).contains(PrismCapability.AUXILIARY_SCENE_VIEWS), "not full-scene replay");
        JsonObject json = JsonParser.parseString(Files.readString(EXAMPLE.resolve("prism.json"))).getAsJsonObject();
        variant(json, j -> j.addProperty("prism_api", "1.20"), false);
        variant(json, j -> j.getAsJsonArray("requires").remove(2), false);
        variant(json, j -> secondView(j).add("layers", strings("water")), false);
        variant(json, j -> secondView(j).add("layers", strings("translucent", "translucent")), false);
        variant(json, j -> secondView(j).add("layers", new JsonArray()), false);
        variant(json, j -> secondView(j).addProperty("depth_load", "discard"), false);
        variant(json, j -> secondView(j).addProperty("depth_direction", "forward"), false);
        variant(json, j -> second(j).addProperty("blend", "alpha"), false);
        variant(json, j -> secondView(j).addProperty("depth_write", "false"), false);
        // LOAD cannot initialize a transient attachment, even with a declared explicit dependency.
        variant(json, j -> j.getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonObject("view").addProperty("depth_load", "load"), false);
        variant(json, j -> j.getAsJsonArray("programs").get(0).getAsJsonObject()
                .getAsJsonObject("view").addProperty("color_load", "load"), false);
        variant(json, j -> { secondView(j).addProperty("depth_write", false); second(j).addProperty("blend", "additive"); }, true);
        // API 1.20 keeps its original solid/cutout, clear and depth-write defaults.
        var legacy = PrismPackParser.parse(Path.of("examples", "PrismTerrainView20Template")).pipelines().getFirst().sceneView();
        require(legacy.layers().equals(List.of("solid", "cutout")) && !legacy.loadDepth() && !legacy.loadColor()
                && legacy.depthWrite(), "legacy view defaults");
        System.out.println("PRISM_LAYERED_TERRAIN21_CONTRACT_PASS");
    }
    private static JsonObject second(JsonObject j) { return j.getAsJsonArray("programs").get(1).getAsJsonObject(); }
    private static JsonObject secondView(JsonObject j) { return second(j).getAsJsonObject("view"); }
    private static JsonArray strings(String... values) { var a = new JsonArray(); for (String value : values) a.add(value); return a; }
    private static void variant(JsonObject original, Consumer<JsonObject> change, boolean accept) throws Exception {
        Path temp = Files.createTempDirectory("prism-layered21-");
        try {
            Files.createDirectory(temp.resolve("shaders"));
            try (var shaders = Files.list(EXAMPLE.resolve("shaders"))) {
                for (Path shader : shaders.toList()) Files.copy(shader, temp.resolve("shaders").resolve(shader.getFileName()));
            }
            var json = original.deepCopy(); change.accept(json);
            Files.writeString(temp.resolve("prism.json"), json.toString());
            boolean accepted;
            try {
                var pack = PrismPackParser.parse(temp);
                PrismPipelineCompiler.prepareAsync(pack, List.of(), Runnable::run).join();
                accepted = true;
            } catch (PrismPackLoadException expected) {
                accepted = false;
            } catch (CompletionException expected) {
                if (!(expected.getCause() instanceof IllegalStateException)
                        && !(expected.getCause() instanceof PrismPackLoadException)) throw expected;
                accepted = false;
            }
            require(accepted == accept, "variant accepted=" + accepted + " expected=" + accept);
        } finally {
            try (var files = Files.walk(temp)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
