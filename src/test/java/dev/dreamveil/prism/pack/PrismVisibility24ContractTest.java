package dev.dreamveil.prism.pack;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import dev.dreamveil.prism.api.*;
import dev.dreamveil.prism.api.visibility.PrismVisibilityVolume;
import dev.dreamveil.prism.backend.PrismMinecraft26_2Capabilities;
import static dev.dreamveil.prism.api.visibility.PrismVisibilityVolume.Shape.*;
import static dev.dreamveil.prism.api.visibility.PrismVisibilityVolume.Space.*;

public final class PrismVisibility24ContractTest {
    public static void main(String[] args) throws Exception {
        var relative = new PrismVisibilityVolume(SPHERE,CAMERA_RELATIVE,100,0,0,8,8,8);
        var sphere = relative.resolve(1000,64,-200);
        require(sphere.x()==1100 && sphere.y()==64 && sphere.z()==-200,"offset resolved in world axes");
        require(sphere.intersects(1108,64,-200,1110,65,-199),"inclusive sphere boundary");
        require(!sphere.intersects(1108.01,64,-200,1110,65,-199),"outside sphere");
        require(!sphere.intersects(Double.NaN,0,0,1,1,1),"invalid bounds");
        var fixed = new PrismVisibilityVolume(AABB,WORLD,-100,20,30,4,8,16);
        require(fixed.resolve(999,888,777).equals(fixed.resolve(0,0,0)),"world center does not follow camera");
        require(fixed.resolve(0,0,0).intersects(-104,12,14,-104,12,14),"negative world coordinates and corner");
        var bounds = new PrismModelViewCapture.Bounds(99,0,-1,101,1,1);
        require(bounds.intersects(sphere,1000,64,-200),"camera-relative geometry resolves to world query");
        require(!bounds.intersects(sphere,0,0,0),"wrong origin cannot match");
        require(!new PrismModelViewCapture.Bounds(0,0,0,1,1,1).intersects(sphere,1000,64,-200),"main-camera geometry also filtered");
        require(PrismLimits.LOADER.maxCapturedModelVertices()==PrismModelViewCapture.MAX_VERTICES,"shared vertex limit");
        require(PrismLimits.LOADER.maxResidentModelsPerDomain()==PrismResidentModelViews.MAX_PER_DOMAIN,"shared extraction limit");
        var api = new dev.dreamveil.prism.runtime.api.PrismApiImpl("test","26.2",
                new dev.dreamveil.prism.backend.PrismBackendInfo("test","test",Set.of()));
        require(api.limits().orElseThrow().equals(PrismLimits.LOADER),"runtime exposes policy limits without fabricating device support");
        var retained = new dev.dreamveil.prism.api.pack.PrismPackInfo("retained","Retained","1.0",
                dev.dreamveil.prism.api.pack.PrismPackStatus.STALE,4,7,List.of());
        var infos = new ArrayList<dev.dreamveil.prism.api.pack.PrismPackInfo>();
        PrismShaderPackManager.appendMissingSelectedInfo(infos,retained);
        PrismShaderPackManager.appendMissingSelectedInfo(infos,retained);
        PrismShaderPackManager.appendMissingSelectedInfo(infos,null);
        require(infos.equals(List.of(retained)),"retained generation remains discoverable exactly once after invalid manifest");
        require(!PrismMinecraft26_2Capabilities.proven(false).contains(PrismCapability.MODEL_VISIBILITY_VOLUMES),"Vulkan only");
        require(!PrismMinecraft26_2Capabilities.proven(true).contains(PrismCapability.AUXILIARY_SCENE_VIEWS),"full scene remains unavailable");
        var candidates = new PrismNearestCandidates<Integer>(128);
        var random = new Random(124);
        var distances = new HashMap<Integer,Double>();
        for (int i=0;i<4096;i++) { double d=random.nextInt(100); distances.put(i,d); candidates.offer(i,d,i); require(candidates.size()<=128,"bounded candidate retention"); }
        candidates.offer(-1,Double.NaN,-1); candidates.offer(-2,Double.POSITIVE_INFINITY,-2);
        var expected=distances.keySet().stream().sorted(Comparator.<Integer>comparingDouble(distances::get).thenComparingInt(i->i)).limit(128).toList();
        require(candidates.values().equals(expected),"heap matches complete reference sort including ties");
        Path example=Path.of("examples/PrismVisibility24Template");
        var pack=PrismPackParser.parse(example);
        var prepared=PrismPipelineCompiler.prepareAsync(pack,List.of(),Runnable::run).join();
        require(prepared.graph().orderedPasses().size()==4,"four view/composite passes");
        require(pack.pipelines().get(1).sceneView().visibility().shape()==SPHERE,"sphere parsed");
        require(pack.pipelines().get(2).sceneView().visibility().shape()==AABB,"box parsed");
        var original=JsonParser.parseString(Files.readString(example.resolve("prism.json"))).getAsJsonObject();
        reject(example,original,j->j.addProperty("prism_api","1.23"));
        reject(example,original,j->j.getAsJsonArray("requires").remove(9));
        reject(example,original,j->j.getAsJsonArray("requires").remove(8));
        reject(example,original,j->view(j).addProperty("model_radius",0));
        reject(example,original,j->visibility(j).addProperty("type","mirror"));
        reject(example,original,j->visibility(j).addProperty("radius",65));
        reject(example,original,j->visibility(j).addProperty("radius",0));
        reject(example,original,j->visibility(j).addProperty("radius","24"));
        reject(example,original,j->visibility(j).addProperty("radius",1e300));
        reject(example,original,j->visibility(j).getAsJsonArray("center").set(0,new JsonPrimitive(513)));
        reject(example,original,j->visibility(j).addProperty("center","creator:view_origin"));
        reject(example,original,j->visibility(j).addProperty("space","view"));
        reject(example,original,j->visibility(j).addProperty("half_extent",4));
        reject(example,original,j->j.getAsJsonArray("programs").get(0).getAsJsonObject().getAsJsonObject("view").add("visibility",visibility(j).deepCopy()));
        System.out.println("PRISM_VISIBILITY24_CONTRACT_PASS");
    }
    private static JsonObject view(JsonObject j) { return j.getAsJsonArray("programs").get(1).getAsJsonObject().getAsJsonObject("view"); }
    private static JsonObject visibility(JsonObject j) { return view(j).getAsJsonObject("visibility"); }
    private static void reject(Path example,JsonObject original,java.util.function.Consumer<JsonObject> change) throws Exception {
        Path temp=Files.createTempDirectory("prism-visibility24-");
        try {
            Files.createDirectory(temp.resolve("shaders"));
            try (var files=Files.list(example.resolve("shaders"))) {
                for (Path file:files.toList()) Files.copy(file,temp.resolve("shaders").resolve(file.getFileName()));
            }
            var json=original.deepCopy(); change.accept(json); Files.writeString(temp.resolve("prism.json"),json.toString());
            try { PrismPackParser.parse(temp); throw new AssertionError("invalid visibility accepted"); }
            catch (PrismPackLoadException expected) {}
        } finally {
            try (var files=Files.walk(temp)) { for (Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
    private static void require(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
}
