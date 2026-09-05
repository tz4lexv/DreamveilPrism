package dev.dreamveil.prism.pack;

import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import dev.dreamveil.prism.api.*;
import dev.dreamveil.prism.graph.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** Opt-in GLSL authoring adapter. Produces the existing pack IR/runtime, never Vulkan calls. */
final class PrismGlslFrontend {
    private static final LinkedHashMap<String,PrismSpirvReflection.Module> MODULE_CACHE=new LinkedHashMap<>(32,0.75f,true);
    private static long moduleCompiles;
    static synchronized long moduleCompileCount() { return moduleCompiles; }
    private static synchronized PrismSpirvReflection.Module reflect(PrismAuthoringIR.Module module) throws PrismPackLoadException {
        // Expanded source contains all include contents. Process-local cache has one loaded
        // shaderc binary; API/stage/flags are part of the key, no timestamps or heuristic reuse.
        String key=token("api1.25|vulkan1.2|auto-bind|auto-location|debug|no-opt|"+module.kind()+"|"+module.path()+"|"+module.source());
        var cached=MODULE_CACHE.get(key); if(cached!=null) return cached;
        var bytes=PrismCompiledComputePipeline.compileSpirv(module.source(),module.path(),module.kind().equals("compute")?shaderc_compute_shader:shaderc_fragment_shader,true);
        var reflection=PrismSpirvReflection.read(bytes,module.path());
        MODULE_CACHE.put(key,reflection); moduleCompiles++;
        while(MODULE_CACHE.size()>128) MODULE_CACHE.remove(MODULE_CACHE.keySet().iterator().next());
        return reflection;
    }
    private static final String VERTEX = """
            #version 450
            layout(location=0) out vec2 prismFrontendUv;
            void main() {
                vec2 p=vec2((gl_VertexID << 1)&2,gl_VertexID&2);
                prismFrontendUv=p;
                gl_Position=vec4(p*2.0-1.0,0.0,1.0);
            }
            """;
    static boolean looksLike(Path root) {
        try { return "glsl".equals(metadata(root).getProperty("frontend")); }
        catch (Exception ignored) { return false; }
    }
    private static Properties metadata(Path root) throws Exception {
        Properties result=new Properties(); Path file=root.resolve("prism.properties");
        if(!Files.isRegularFile(file)) return result;
        PrismPackPathPolicy.requireContainedRegularFile(root.toAbsolutePath().normalize(),file.toAbsolutePath().normalize(),"frontend_path","frontend_missing","Frontend metadata","prism.properties");
        if(Files.size(file)>65536) throw new IllegalArgumentException("prism.properties exceeds 64 KiB");
        try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) { result.load(reader); }
        return result;
    }
    static PrismPackDefinition load(Path root,String sourceName) throws PrismPackLoadException {
        try {
            Properties properties=metadata(root);
            if(!"glsl".equals(properties.getProperty("frontend"))) throw fail("Declare frontend=glsl in prism.properties", "prism.properties");
            if(Files.isRegularFile(root.resolve("prism.json"))) throw fail("Remove the ambiguous second authoring route: prism.json and frontend=glsl cannot both select a pack", "prism.properties");
            String requested=properties.getProperty("prism_api","1.25");
            if(!requested.equals("1.25")) throw fail("This frontend requires prism_api=1.25", "prism.properties");
            Path directory=root.resolve("shaders");
            if(!Files.isDirectory(directory)) throw fail("Provide GLSL modules under shaders/", "shaders");
            List<Path> files;
            try(var stream=Files.walk(directory)) {
                files=stream.filter(Files::isRegularFile).filter(p->p.toString().matches(".*\\.(glsl|comp|frag|vert|fsh|vsh)$")).sorted().limit(513).toList();
            }
            if(files.size()>512) throw fail("At most 512 shader/include sources are supported", "shaders");
            var session=PrismShaderSourceLoader.session(root);
            List<PrismAuthoringIR.Module> modules=new ArrayList<>();
            for(Path file:files) {
                String path=root.relativize(file).toString().replace('\\','/');
                String source=session.load(path);
                String visible=PrismMetadataParser.withoutComments(source);
                // Discovery does not infer stage from extension; libraries without an entry point are not passes.
                if(!visible.matches("(?s).*#\\s*pragma\\s+prism\\b.*") && !visible.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")) continue;
                modules.add(PrismMetadataParser.parse(path,source));
            }
            if(modules.isEmpty() || modules.size()>32) throw fail("Declare 1..32 explicit pass modules", "shaders");
            Map<String,PrismAuthoringIR.Resource> resources=new TreeMap<>();
            Set<String> passes=new HashSet<>();
            for(var module:modules) {
                if(!passes.add(module.pass())) throw fail("Duplicate pass '"+module.pass()+"'",module.path());
                for(var resource:module.resources()) {
                    var previous=resources.putIfAbsent(resource.name(),resource);
                    if(previous!=null && !previous.equals(resource)) throw fail("Conflicting resource declaration '"+resource.name()+"'",module.path());
                }
            }
            if(resources.size()>64) throw fail("At most 64 resources", "shaders");
            return lower(root,properties,new PrismAuthoringIR(modules,List.copyOf(resources.values())));
        } catch(PrismPackLoadException failure) { throw failure; }
        catch(Exception failure) { throw new PrismPackLoadException("frontend_load", "GLSL frontend: "+failure.getMessage(),sourceName,failure); }
    }
    private static PrismPackDefinition lower(Path root,Properties metadata,PrismAuthoringIR ir) throws PrismPackLoadException {
        Map<String,PrismSpirvReflection.Module> reflected=new LinkedHashMap<>();
        Map<String,String> formats=new TreeMap<>();
        Map<String,PrismAuthoringIR.Resource> resourceByName=new TreeMap<>();
        for(var resource:ir.resources()) { resourceByName.put(resource.name(),resource); if(!resource.format().isEmpty()) formats.put(resource.name(),resource.format()); }
        for(var module:ir.modules()) {
            PrismShaderLanguageValidator.validate(module.source(),module.path());
            if(PrismMetadataParser.withoutComments(module.source()).matches("(?s).*\\blayout\\s*\\([^)]*\\b(?:set|binding)\\s*=.*")) {
                throw fail("Manual descriptor set/binding declarations are not supported by this frontend; remove them instead of allowing Prism to override them",module.path());
            }
            var reflection=reflect(module); reflected.put(module.pass(),reflection);
            if(!reflection.specializationIds().isEmpty()) throw fail("Specialization options are not wired in this frontend yet",module.path());
            for(var descriptor:reflection.descriptors()) {
                if(!descriptor.arraySizes().isEmpty() || descriptor.arrayed() || descriptor.multisampled() || descriptor.dimension()!=1
                        || !descriptor.sampledType().equals("float32")
                        || !Set.of(PrismSpirvReflection.Category.SAMPLED_IMAGE,PrismSpirvReflection.Category.STORAGE_IMAGE).contains(descriptor.category())) {
                    throw fail("Reflected "+descriptor.category()+" '"+descriptor.name()+"' needs a descriptor path not wired in this frontend; only scalar sampler2D/image2D is executable here",module.path());
                }
                if(!resourceByName.containsKey(descriptor.name())) throw fail("Resource '"+descriptor.name()+"' has no declaration/source. Declare it explicitly; symbol names do not imply host bindings",module.path());
                if(descriptor.category()==PrismSpirvReflection.Category.STORAGE_IMAGE) {
                    if(!module.kind().equals("compute")) throw fail("Graphics storage descriptors are not wired in this frontend",module.path());
                    String format=format(descriptor.imageFormat(),module.path());
                    String previous=formats.putIfAbsent(descriptor.name(),format);
                    if(previous!=null && !previous.equals(format)) throw fail("Reflected format "+format+" conflicts with declared "+previous+" for '"+descriptor.name()+"'",module.path());
                }
            }
        }
        List<PrismPackTextureDefinition> textures=new ArrayList<>();
        for(var resource:ir.resources()) {
            String format=formats.get(resource.name());
            if(format==null) throw fail("Resource '"+resource.name()+"' needs a format: no storage image proves it", "resource:"+resource.name());
            PrismTextureDesc descriptor=PrismTextureDesc.colorAttachment(extent(resource.extent()),textureFormat(format));
            textures.add(new PrismPackTextureDefinition(id(resource.name()),descriptor,PrismPackTextureLifetime.TRANSIENT));
        }
        List<PrismPipelineDefinition> pipelines=new ArrayList<>(); Map<String,String> sources=new TreeMap<>();
        StringBuilder inspector=new StringBuilder("Prism GLSL frontend 1.25 — load-time mechanical decisions\n");
        for(var resource:ir.resources()) inspector.append("Resource ").append(resource.name()).append(" -> ").append(id(resource.name()))
                .append(" format=").append(formats.get(resource.name())).append(" extent=").append(resource.extent()).append(" lifetime=").append(resource.lifetime()).append('\n');
        EnumSet<PrismCapability> capabilities=EnumSet.of(PrismCapability.VULKAN_BACKEND,PrismCapability.FULLSCREEN_SHADER_PASSES);
        Set<String> passNames=new HashSet<>(); ir.modules().forEach(m->passNames.add(m.pass()));
        for(var module:ir.modules()) {
            for(String requirement:module.requires()) {
                try {
                    var capability=PrismCapability.valueOf(requirement);
                    if(!dev.dreamveil.prism.backend.PrismMinecraft26_2Capabilities.proven(true).contains(capability)) {
                        throw fail("Capability '"+requirement+"' is not implemented in the current Vulkan runtime profile",module.path());
                    }
                    capabilities.add(capability);
                }
                catch(IllegalArgumentException failure) { throw fail("Unknown capability '"+requirement+"'",module.path()); }
            }
            for(String after:module.after()) if(!passNames.contains(after) || after.equals(module.pass())) throw fail("Unknown or self after dependency '"+after+"'",module.path());
            var reflection=reflected.get(module.pass());
            List<PrismSamplerBinding> samplers=new ArrayList<>(); List<PrismStorageBinding> storage=new ArrayList<>();
            for(var d:reflection.descriptors()) {
                var resource=resourceByName.get(d.name());
                if(d.category()==PrismSpirvReflection.Category.SAMPLED_IMAGE) {
                    if(!Set.of("nearest","linear").contains(resource.filter()) || !Set.of("clamp","repeat").contains(resource.wrap())) {
                        throw fail("Sampling '"+d.name()+"' requires filter=nearest|linear and wrap=clamp|repeat; Prism will not choose a filter",module.path());
                    }
                    samplers.add(new PrismSamplerBinding(d.name(),id(d.name()),resource.filter(),resource.wrap(),"current"));
                } else storage.add(new PrismStorageBinding(d.name(),id(d.name()),d.access(),"current"));
            }
            String passId=token(module.pass()), path="$prism/"+module.path(); sources.put(path,module.source());
            inspector.append("Pass ").append(module.pass()).append(" -> ").append(passId).append(" type=").append(module.kind()).append(" source=").append(module.path()).append('\n');
            int binding=0;
            for(var sampler:samplers) inspector.append("  READ ").append(sampler.name()).append(module.kind().equals("compute")?" set=0 binding=":" Blaze3D name-bound sampler index=").append(binding++).append(" filter=").append(sampler.filter()).append(" wrap=").append(sampler.wrap()).append('\n');
            for(var image:storage) inspector.append("  ").append(image.access()).append(' ').append(image.name()).append(" set=0 binding=").append(binding++).append('\n');
            if(module.kind().equals("compute")) inspector.append("  dispatch=ceil(").append(module.dispatch()).append(" / ").append(reflection.localSize()).append(")\n");
            if(module.kind().equals("compute")) {
                capabilities.add(PrismCapability.COMPUTE_PIPELINES); capabilities.add(PrismCapability.STORAGE_IMAGES);
                if(reflection.localSize().size()!=3) throw fail("A fixed reflected local_size is required; specialized workgroup sizes are not supported yet",module.path());
                var local=reflection.localSize();
                if(local.stream().anyMatch(v->v<1) || (long)local.get(0)*local.get(1)*local.get(2)>1024 || local.get(2)>64) throw fail("Workgroup exceeds loader limits",module.path());
                if(storage.stream().noneMatch(d->d.name().equals(module.dispatch()))) throw fail("Dispatch target '"+module.dispatch()+"' must be a declared storage image",module.path());
                Set<String> sampled=new HashSet<>(); samplers.forEach(s->sampled.add(s.resource()));
                if(storage.stream().anyMatch(s->sampled.contains(s.resource()))) throw fail("Illegal sampled/storage feedback; declare an explicit separate source",module.path());
                pipelines.add(new PrismPipelineDefinition(passId,"compute","","","","","opaque",false,samplers,path,List.of(),storage,List.of(),
                        new PrismComputeDispatch(id(module.dispatch()),local.get(0),local.get(1),local.get(2),0,0,0),null,module.after().stream().map(PrismGlslFrontend::token).toList()));
            } else {
                var outputs=reflection.interfaces().stream().filter(v->v.storage()==3 && v.location()>=0).toList();
                if(outputs.size()!=1 || outputs.getFirst().location()!=0) throw fail("Fullscreen currently requires exactly one color output at location 0",module.path());
                if(!outputs.getFirst().type().equals("vec4<float32>") || reflection.interfaces().stream().anyMatch(v->v.storage()==3 && v.location()<0)) throw fail("Fullscreen supports a vec4 color output, not depth/stencil or integer outputs",module.path());
                if(reflection.interfaces().stream().anyMatch(v->v.storage()==1 && v.location()>=0 && (v.location()!=0 || !v.type().equals("vec2<float32>")))) throw fail("Fullscreen provides only vec2 UV at input location 0",module.path());
                String output=module.output().equals("scene.color")?PrismPackResources.MAIN_COLOR:id(module.output());
                if(!module.output().equals("scene.color") && !resourceByName.containsKey(module.output())) throw fail("Unknown fullscreen output '"+module.output()+"'",module.path());
                if(samplers.stream().anyMatch(s->s.resource().equals(output))) throw fail("Illegal read/write feedback. No snapshot will be inserted automatically",module.path());
                String vertex="$prism/fullscreen.vsh"; sources.put(vertex,VERTEX);
                // fullscreen is defined as replacement of its explicit output, not implicit blending.
                pipelines.add(new PrismPipelineDefinition(passId,"fullscreen","",vertex,path,output,"opaque",false,samplers).withAfter(module.after().stream().map(PrismGlslFrontend::token).toList()));
            }
        }
        if(pipelines.size()>1) capabilities.add(PrismCapability.MULTIPASS_SHADER_PACKS);
        if(ir.modules().stream().anyMatch(m->!m.after().isEmpty())) capabilities.add(PrismCapability.EXPLICIT_PASS_DEPENDENCIES);
        sources.put("$prism/inspector.txt",inspector.toString());
        for(var module:ir.modules()) sources.put("$prism/pass-label/"+token(module.pass()),module.pass());
        for(var resource:ir.resources()) sources.put("$prism/resource-label/"+id(resource.name()),resource.name());
        String packId=metadata.getProperty("id");
        if(packId==null || !packId.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw fail("Declare a stable id in prism.properties", "prism.properties:id");
        return new PrismPackDefinition(root,packId,metadata.getProperty("name",packId),metadata.getProperty("version","1.0.0"),metadata.getProperty("author",""),
                "Explicit GLSL authoring frontend",PrismApiVersion.V1_25,List.copyOf(capabilities),List.of(),PrismPackParser.applyStorageTextureUsages(textures,pipelines),List.of(),List.of(),
                PrismDynamicResolutionDefinition.DISABLED,false,List.of(),pipelines,sources);
    }
    static PrismTextureExtent extent(String text) throws PrismPackLoadException {
        try {
            if(text.matches("[0-9]+x[0-9]+")) { String[] parts=text.split("x"); int w=Integer.parseInt(parts[0]),h=Integer.parseInt(parts[1]);
                if(w<1 || h<1 || w>16384 || h>16384) throw new IllegalArgumentException("dimensions 1..16384"); return PrismTextureExtent.absolute(w,h); }
            double scale=switch(text) { case "screen"->1; case "half"->0.5; case "quarter"->0.25; default-> {
                if(!text.matches("scale\\([^()]+\\)")) throw new IllegalArgumentException("extent syntax"); yield Double.parseDouble(text.substring(6,text.length()-1)); } };
            if(!Double.isFinite(scale) || scale<=0 || scale>2) throw new IllegalArgumentException("scale >0..2");
            return PrismTextureExtent.relative(scale);
        } catch(RuntimeException failure) { throw fail("Invalid extent '"+text+"': "+failure.getMessage(), "resource:extent"); }
    }
    private static String format(int value,String source) throws PrismPackLoadException {
        return switch(value) { case 1->"rgba32f"; case 2->"rgba16f"; case 3->"r32f"; case 4->"rgba8"; case 6->"rg32f"; case 7->"rg16f"; case 9->"r16f"; default->throw fail("Unsupported reflected storage image format "+value,source); };
    }
    private static PrismTextureFormat textureFormat(String value) throws PrismPackLoadException {
        return switch(value) { case "rgba32f"->PrismTextureFormat.RGBA32_FLOAT; case "rgba16f"->PrismTextureFormat.RGBA16_FLOAT;
            case "r32f"->PrismTextureFormat.R32_FLOAT; case "rgba8"->PrismTextureFormat.RGBA8_UNORM; case "rg32f"->PrismTextureFormat.RG32_FLOAT;
            case "rg16f"->PrismTextureFormat.RG16_FLOAT; case "r16f"->PrismTextureFormat.R16_FLOAT; default->throw fail("Unsupported format '"+value+"'","resource:format"); };
    }
    private static String token(String name) {
        try { return "r"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8))).substring(0,40); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String id(String name) { return "creator:"+token(name); }
    private static PrismPackLoadException fail(String message,String source) { return new PrismPackLoadException("PRISM_E1100",message,source); }
}
