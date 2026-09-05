package dev.dreamveil.prism.pack;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import dev.dreamveil.prism.api.PrismCapability;
import dev.dreamveil.prism.backend.PrismMinecraft26_2Capabilities;

public final class PrismResidentModel23ContractTest {
    public static void main(String[] args) throws Exception {
        Path example = Path.of("examples/PrismResidentModels23Template");
        var pack = PrismPackParser.parse(example);
        var prepared = PrismPipelineCompiler.prepareAsync(pack, List.of(), Runnable::run).join();
        require(prepared.graph().orderedPasses().size() == 4, "four bounded replay passes");
        require(pack.pipelines().get(1).sceneView().modelRadius() == 48, "resident radius parsed");
        require(PrismPackParser.parse(Path.of("examples/PrismCapturedModels22Template"))
                .pipelines().get(1).sceneView().modelRadius() == 0, "old packs never opt in implicitly");
        require(!PrismMinecraft26_2Capabilities.proven(false).contains(PrismCapability.RESIDENT_MODEL_VIEWS), "Vulkan gate");
        require(!PrismMinecraft26_2Capabilities.proven(true).contains(PrismCapability.AUXILIARY_SCENE_VIEWS), "not arbitrary full-scene replay");
        require(PrismResidentModelViews.inside(4096,64), "inclusive radius boundary");
        require(!PrismResidentModelViews.inside(4097,64), "outside bound");
        require(!PrismResidentModelViews.inside(Double.NaN,64) && !PrismResidentModelViews.inside(-1,64), "invalid distance rejected");
        require(!PrismResidentModelViews.inside(0,0), "disabled scope");
        var main = new PrismModelViewCapture.Draw(null,"entity",0,4,-1);
        var extra = new PrismModelViewCapture.Draw(null,"entity",4,4,400);
        require(main.includedBy(0), "main capture remains available");
        require(!extra.includedBy(0) && !extra.includedBy(19) && extra.includedBy(20), "per-pass auxiliary isolation");
        var states = new ArrayList<net.minecraft.client.renderer.entity.state.EntityRenderState>();
        var snapshot = new PrismResidentModelState.Snapshot(7,states,List.of());
        states.add(null);
        require(snapshot.entities().isEmpty(), "frame owns immutable list copy");
        var original = JsonParser.parseString(Files.readString(example.resolve("prism.json"))).getAsJsonObject();
        reject(example, original, j -> j.addProperty("prism_api","1.22"));
        reject(example, original, j -> j.getAsJsonArray("requires").remove(8));
        reject(example, original, j -> view(j).addProperty("model_radius",65));
        reject(example, original, j -> view(j).addProperty("model_radius",-1));
        reject(example, original, j -> view(j).addProperty("model_radius",1.5));
        reject(example, original, j -> j.getAsJsonArray("programs").get(0).getAsJsonObject().getAsJsonObject("view").addProperty("model_radius",16));
        System.out.println("PRISM_RESIDENT_MODEL23_CONTRACT_PASS");
    }
    private static JsonObject view(JsonObject j) { return j.getAsJsonArray("programs").get(1).getAsJsonObject().getAsJsonObject("view"); }
    private static void reject(Path example, JsonObject original, java.util.function.Consumer<JsonObject> change) throws Exception {
        Path temp = Files.createTempDirectory("prism-resident23-");
        try {
            Files.createDirectory(temp.resolve("shaders"));
            try (var shaders = Files.list(example.resolve("shaders"))) {
                for (Path shader : shaders.toList()) Files.copy(shader,temp.resolve("shaders").resolve(shader.getFileName()));
            }
            var json = original.deepCopy(); change.accept(json);
            Files.writeString(temp.resolve("prism.json"),json.toString());
            try { PrismPackParser.parse(temp); throw new AssertionError("invalid resident scope accepted"); }
            catch (PrismPackLoadException expected) {}
        } finally {
            try (var files = Files.walk(temp)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
