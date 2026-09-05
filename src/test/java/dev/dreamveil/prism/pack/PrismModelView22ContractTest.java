package dev.dreamveil.prism.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import dev.dreamveil.prism.api.PrismCapability;
import dev.dreamveil.prism.backend.PrismMinecraft26_2Capabilities;

public final class PrismModelView22ContractTest {
    public static void main(String[] args) throws Exception {
        Path example = Path.of("examples", "PrismCapturedModels22Template");
        var pack = PrismPackParser.parse(example);
        var prepared = PrismPipelineCompiler.prepareAsync(pack, List.of(), Runnable::run).join();
        require(prepared.graph().orderedPasses().size() == 4, "terrain + entity + block + composite");
        require(pack.pipelines().get(1).sceneView().capturedModels(), "captured model geometry");
        require(pack.pipelines().get(1).sceneView().modelDomains().equals(List.of("entity")), "entity filter");
        require(pack.pipelines().get(2).sceneView().modelDomains().equals(List.of("block_entity")), "block-entity filter");
        require(prepared.graph().dependencies().get("block_entities").contains("entities"), "loaded target ordering");
        require(PrismModelViewReplay.FORMAT.getVertexSize() == PrismModelViewCapture.STRIDE, "CPU/GPU attribute stride");
        require(PrismModelViewReplay.GPU_BYTES == 4194304L, "bounded GPU budget");
        require(!PrismMinecraft26_2Capabilities.proven(false).contains(PrismCapability.CAPTURED_MODEL_VIEWS), "Vulkan-only gate");
        require(!PrismMinecraft26_2Capabilities.proven(true).contains(PrismCapability.AUXILIARY_SCENE_VIEWS), "not full auxiliary visibility");
        var original = JsonParser.parseString(Files.readString(example.resolve("prism.json"))).getAsJsonObject();
        reject(example, original, j -> j.addProperty("prism_api", "1.21"));
        reject(example, original, j -> j.getAsJsonArray("requires").remove(3));
        reject(example, original, j -> view(j).addProperty("geometry", "full_scene"));
        reject(example, original, j -> view(j).addProperty("section_radius", 4));
        reject(example, original, j -> { var layers = new JsonArray(); layers.add("solid"); view(j).add("layers", layers); });
        reject(example, original, j -> view(j).add("model_domains", new JsonArray()));
        reject(example, original, j -> { var domains = new JsonArray(); domains.add("particle"); view(j).add("model_domains", domains); });
        reject(example, original, j -> { var domains = new JsonArray(); domains.add("entity"); domains.add("entity"); view(j).add("model_domains", domains); });
        System.out.println("PRISM_MODEL_VIEW22_CONTRACT_PASS");
    }
    private static JsonObject view(JsonObject j) { return j.getAsJsonArray("programs").get(1).getAsJsonObject().getAsJsonObject("view"); }
    private static void reject(Path example, JsonObject original, java.util.function.Consumer<JsonObject> change) throws Exception {
        Path temp = Files.createTempDirectory("prism-model22-");
        try {
            Files.createDirectory(temp.resolve("shaders"));
            try (var shaders = Files.list(example.resolve("shaders"))) {
                for (Path shader : shaders.toList()) Files.copy(shader, temp.resolve("shaders").resolve(shader.getFileName()));
            }
            var json = original.deepCopy(); change.accept(json);
            Files.writeString(temp.resolve("prism.json"), json.toString());
            try { PrismPackParser.parse(temp); throw new AssertionError("invalid model view accepted"); }
            catch (PrismPackLoadException expected) {}
        } finally {
            try (var files = Files.walk(temp)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
