package dev.dreamveil.prism.pack;

import java.nio.file.*;
import java.util.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Executes shaderc and structured reflection, not just metadata parsing. */
public final class PrismGlslFrontend25Test {
    public static void main(String[] args) throws Exception {
        String compact="#version 450\n#pragma prism compute Test\n#pragma prism dispatch Pepe\n#pragma prism resource Pepe r16f half transient\nvoid main() {}\n";
        String expanded=compact.replace("#pragma prism resource Pepe r16f half transient", "#pragma prism resource Pepe {\nformat = r16f\nsize = half\nlifetime = transient\n}");
        require(PrismMetadataParser.parse("a",compact).resources().equals(PrismMetadataParser.parse("b",expanded).resources()),"compact/expanded resource IR identical");
        bad(compact.replace(" half", ""), "extent");
        bad(compact.replace("#pragma prism dispatch Pepe", ""), "dispatch");
        bad(compact.replace("compute Test", "depht Test"), "Did you mean 'depth'");
        bad("#version 450\nvoid main() {}", "no explicit pass");
        bad(compact.replace("#pragma prism resource", "#if 1\n#pragma prism resource"), "Conditional");
        bad(compact.replace("#pragma prism resource Pepe r16f half transient", "#pragma prism snapshot scene.color Before"), "not executable");
        bad(compact.replace("#pragma prism resource Pepe r16f half transient", "#pragma prism history Pepe 2"), "not executable");
        for(String name:List.of("Pepe","ShadowDepth","History","CloudDensity")) {
            var module=PrismMetadataParser.parse("shadow.frag",compact.replace("Pepe",name));
            require(module.kind().equals("compute") && module.resources().getFirst().lifetime().equals("transient"),"names never create effect/history/stage");
        }
        reflectRealSpirv();
        Path example=Path.of("examples/PrismGlsl25Template");
        var pack=PrismGlslFrontend.load(example,"example");
        long count=PrismGlslFrontend.moduleCompileCount();
        var repeated=PrismGlslFrontend.load(example,"example");
        require(PrismGlslFrontend.moduleCompileCount()==count,"unchanged expanded modules reuse cached reflection");
        require(pack.equals(repeated),"identical sources yield identical normalized IR");
        require(!pack.sceneJitter() && !pack.dynamicResolution().enabled() && pack.settings().isEmpty(),"no inserted temporal/dynamic/options");
        require(pack.resources().getFirst().descriptor().format()==dev.dreamveil.prism.graph.PrismTextureFormat.RGBA16_FLOAT,"format comes from actual SPIR-V");
        var compute=pack.pipelines().stream().filter(PrismPipelineDefinition::isCompute).findFirst().orElseThrow();
        require(compute.dispatch().localSizeX()==8 && compute.dispatch().localSizeY()==8 && compute.dispatch().localSizeZ()==1,"reflected local size, z default defined by GLSL");
        var prepared=PrismPipelineCompiler.prepareAsync(pack,List.of(),Runnable::run).join();
        require(prepared.graph().orderedPasses().getFirst().name().equals(compute.id()),"real frontend routes to existing strict graph");
        require(pack.generatedSources().containsKey("$prism/fullscreen.vsh"),"explicit fullscreen synthesizes vertex work");
        var bytes=PrismCompiledComputePipeline.compileSpirv(pack.generatedSources().get(compute.compute()),compute.compute());
        PrismSpirvReflection.rebind(bytes,Map.of("Pepe",0),"test");
        require(PrismSpirvReflection.read(bytes,"test").descriptors().getFirst().binding()==0,"actual SPIR-V descriptor rewritten to runtime layout");
        testChangedSource(example);
        System.out.println("PRISM_GLSL_FRONTEND25_SPIRV_PASS");
    }
    private static void reflectRealSpirv() throws Exception {
        String source="""
                #version 450
                layout(local_size_x=8,local_size_y=4) in;
                uniform sampler2D Tex;
                layout(r16f) writeonly uniform image2D Image;
                layout(std140) uniform Params { vec4 tint; } U;
                layout(std430) buffer Data { float values[]; } B;
                layout(push_constant) uniform Push { float weight; } P;
                layout(constant_id=3) const int Mode=1;
                void main() { imageStore(Image,ivec2(0),texture(Tex,vec2(0))*U.tint*P.weight*Mode); B.values[0]=1; }
                """;
        var bytes=PrismCompiledComputePipeline.compileSpirv(source,"reflection.comp",shaderc_compute_shader,true);
        var reflection=PrismSpirvReflection.read(bytes,"reflection.comp");
        var categories=reflection.descriptors().stream().map(PrismSpirvReflection.Descriptor::category).collect(java.util.stream.Collectors.toSet());
        require(categories.containsAll(Set.of(PrismSpirvReflection.Category.SAMPLED_IMAGE,PrismSpirvReflection.Category.STORAGE_IMAGE,PrismSpirvReflection.Category.UBO,PrismSpirvReflection.Category.SSBO,PrismSpirvReflection.Category.PUSH_CONSTANT)),"actual descriptor categories");
        require(reflection.localSize().equals(List.of(8,4,1)),"real workgroup mode");
        require(reflection.specializationIds().containsValue(3),"specialization reflected, not inferred settings");
        var image=reflection.descriptors().stream().filter(d->d.name().equals("Image")).findFirst().orElseThrow();
        require(image.imageFormat()==9 && image.access().equals("write"),"R16F / NonReadable decoration");
        String bindingMacro="#version 450\n#define MANUAL binding\nlayout(local_size_x=1) in;\nlayout(MANUAL=4,rgba16f) writeonly uniform image2D X;\nvoid main(){imageStore(X,ivec2(0),vec4(1));}";
        try { PrismCompiledComputePipeline.compileSpirv(bindingMacro,"macro.comp",shaderc_compute_shader,true); throw new AssertionError("manual macro binding silently overridden"); }
        catch(PrismPackLoadException expected) { require(expected.code().equals("frontend_manual_binding"),expected.toString()); }
        String arraySource="#version 450\nlayout(local_size_x=1) in;\nuniform sampler2D Images[2];\nlayout(rgba16f) writeonly uniform image2D Dest;\nvoid main(){imageStore(Dest,ivec2(0),texture(Images[1],vec2(0)));}";
        var arrayReflection=PrismSpirvReflection.read(PrismCompiledComputePipeline.compileSpirv(arraySource,"array.comp",shaderc_compute_shader,true),"array.comp");
        require(arrayReflection.descriptors().stream().anyMatch(d->d.name().equals("Images") && d.arraySizes().equals(List.of(2))),"real descriptor array dimensions");
    }
    private static void testChangedSource(Path example) throws Exception {
        Path temp=Files.createTempDirectory("prism-frontend25-");
        try {
            Files.copy(example.resolve("prism.properties"),temp.resolve("prism.properties"));
            Files.createDirectory(temp.resolve("shaders"));
            for(String file:List.of("produce.glsl","display.glsl")) Files.copy(example.resolve("shaders/"+file),temp.resolve("shaders/"+file));
            var before=PrismGlslFrontend.load(temp,"test"); long count=PrismGlslFrontend.moduleCompileCount();
            Path source=temp.resolve("shaders/produce.glsl");
            String original=Files.readString(source); Files.writeString(source,original.replace("0.25", "0.5"));
            var changed=PrismGlslFrontend.load(temp,"test");
            require(PrismGlslFrontend.moduleCompileCount()==count+1,"only changed expanded module recompiled");
            require(!before.generatedSources().equals(changed.generatedSources()),"changed algorithm preserved, not normalized away");
            Files.writeString(source,original.replace("Pepe half", "Pepe r16f half"));
            try { PrismGlslFrontend.load(temp,"test"); throw new AssertionError("format mismatch accepted"); }
            catch(PrismPackLoadException expected) { require(expected.getMessage().contains("conflicts"),expected.toString()); }
            Files.writeString(source,original.replace("#pragma prism compute MakeImage", "#pragma prism compute MakeImage\n#pragma prism requires AUXILIARY_SCENE_VIEWS"));
            try { PrismGlslFrontend.load(temp,"test"); throw new AssertionError("unsupported capability accepted"); }
            catch(PrismPackLoadException expected) { require(expected.getMessage().contains("not implemented"),expected.toString()); }
            Files.writeString(temp.resolve("prism.json"),"{}");
            try { PrismPackParser.parse(temp); throw new AssertionError("ambiguous frontend selected silently"); }
            catch(PrismPackLoadException expected) { require(expected.code().equals("frontend_ambiguous"),expected.toString()); }
        } finally { try(var files=Files.walk(temp)) { for(Path file:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); } }
    }
    private static void bad(String text,String expected) throws Exception {
        try { PrismMetadataParser.parse("shaders/test.glsl",text); throw new AssertionError("invalid metadata accepted"); }
        catch(PrismPackLoadException failure) { require(failure.getMessage().contains(expected) && failure.source().matches("shaders/test.glsl:[0-9]+:1"),failure.toString()); }
    }
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
