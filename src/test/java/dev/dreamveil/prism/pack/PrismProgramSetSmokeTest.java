package dev.dreamveil.prism.pack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Standalone loader/ProgramSet validation; does not create a GPU device. */
public final class PrismProgramSetSmokeTest {
    private PrismProgramSetSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("prism-programset-smoke");
        try {
            testManifestlessNativePack(temp.resolve("native"));
            testNestedPackRoot(temp.resolve("archive-root"));
            testManifestlessZip(temp.resolve("zip-case"));
            testArchiveSecurity(temp.resolve("zip-security"));
            testArchiveContentAddressedRefresh(temp.resolve("zip-refresh"));
            testIncludeRealPathContainment(temp.resolve("include-security"));
            testCreatorFrameUniformContract(temp.resolve("frame-uniforms"));
            testTemporalHistoryContract(temp.resolve("temporal-history"));
            testTextureAssetContract(temp.resolve("texture-assets"));
            testVulkan15TextureAndComputeContract(temp.resolve("vulkan15-assets"));
            testApi17EnvironmentAndReplacementContract(temp.resolve("api17-environment"));
            testGpuBudgetConfigurationBounds();
            testManifestlessFeaturePack(temp.resolve("feature-native"));
            testManifestVertexInheritance(temp.resolve("inherit-manifest"));
            testSceneContractValidation();
            testLegacyInspection(temp.resolve("legacy"));
            testExpandedDomainRecognition();
            System.out.println("Dreamveil Prism native ProgramSet/loader smoke tests: PASS");
        } finally {
            deleteTree(temp);
        }
    }

    private static void testApi17EnvironmentAndReplacementContract(Path root) throws Exception {
        Files.createDirectories(root.resolve("shaders"));
        Files.writeString(root.resolve("shaders/fullscreen.vsh"),
                "#version 450\nvoid main(){gl_Position=vec4(0.0);}\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("shaders/environment.fsh"), """
                #version 450
                #include <prism/frame.glsl>
                #include <prism/lighting.glsl>
                #include <prism/atmosphere.glsl>
                #include <prism/water.glsl>
                layout(location=0) out vec4 color;
                void main(){
                    float proof = PrismWeather.x + PrismFogColor.x + PrismSkyColor.x
                            + float(PrismEnvironmentFlags.x);
                    proof += prismWaterFresnel(1.0, 1.333);
                    proof += prismRayleighPhase(0.0);
                    proof += prismTonemapAces(vec3(0.0)).x;
                    color = vec4(proof * 0.0, 0.0, 0.0, 1.0);
                }
                """, StandardCharsets.UTF_8);
        String manifest = """
                {
                  "schema_version":1,
                  "id":"smoke.api17",
                  "name":"API 1.17 Environment",
                  "version":"1.0.0",
                  "prism_api":"1.17",
                  "requires":["PACK_FRAME_UNIFORMS","VANILLA_SCENE_REPLACEMENT"],
                  "vanilla_replacements":["sky","clouds","weather"],
                  "programs":[{
                    "id":"environment","type":"fullscreen",
                    "vertex":"shaders/fullscreen.vsh","fragment":"shaders/environment.fsh",
                    "output":"minecraft:main_color","frame_uniforms":true
                  }]
                }
                """;
        Files.writeString(root.resolve("prism.json"), manifest, StandardCharsets.UTF_8);

        PrismPackDefinition pack = PrismPackParser.parse(root);
        require(pack.vanillaReplacements().size() == 3,
                "API 1.17 vanilla replacements were not parsed");
        String expanded = PrismShaderSourceLoader.session(root).load("shaders/environment.fsh");
        require(expanded.contains("PrismEnvironmentFlags")
                        && expanded.contains("prismEvaluateDirectGgx")
                        && expanded.contains("prismHeightFogOpticalDepth")
                        && expanded.contains("prismWaterComposite"),
                "API 1.17 environmental/AAA helper includes were not expanded");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace(",\"VANILLA_SCENE_REPLACEMENT\"", ""), StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "vanilla_replacement_capability_required");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace("\"prism_api\":\"1.17\"", "\"prism_api\":\"1.16\""),
                StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "vanilla_replacement_api_version");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace("[\"sky\",\"clouds\",\"weather\"]", "[\"clouds\",\"cloud\"]"),
                StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "vanilla_replacement_duplicate");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace("[\"sky\",\"clouds\",\"weather\"]", "[\"clouds\",\"ocean\"]"),
                StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "vanilla_replacement_unknown");
    }

    private static void testGpuBudgetConfigurationBounds() {
        String property = "dreamveil.prism.maxPackGpuMiB";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, Long.toString(Long.MAX_VALUE));
            require(PrismPackGpuBudget.configuredMaxBytes() == 4096L * 1024L * 1024L,
                    "GPU budget must clamp huge configuration without integer overflow");
            System.setProperty(property, "-1");
            require(PrismPackGpuBudget.configuredMaxBytes() == 128L * 1024L * 1024L,
                    "GPU budget must preserve the minimum safety floor");
            System.clearProperty(property);
            require(PrismPackGpuBudget.configuredMaxBytes() == PrismPackGpuBudget.DEFAULT_MAX_BYTES,
                    "GPU budget default must remain deterministic");
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    private static void testManifestlessNativePack(Path root) throws Exception {
        Path shaders = root.resolve("shaders/prism");
        Files.createDirectories(shaders);
        Files.writeString(root.resolve("prism.properties"), """
                id=smoke.native
                name=Native ProgramSet Smoke
                version=1.0.0
                prism_api=1.9
                """, StandardCharsets.UTF_8);
        Files.writeString(shaders.resolve("terrain_opaque.vsh"), "#version 450\nvoid main(){gl_Position=vec4(0.0);}\n");
        Files.writeString(shaders.resolve("terrain_opaque.fsh"), "#version 450\nlayout(location=0) out vec4 c; void main(){c=vec4(1.0);}\n");

        PrismPackDefinition pack = PrismNativePackLoader.load(root, "native.zip");
        PrismProgramSet set = pack.programSet();
        require(pack.id().equals("smoke.native"), "native metadata id mismatch");
        require(set.scenePrograms().size() == 1, "native ProgramSet scene count mismatch");
        require(set.sceneProgram(PrismSceneDomain.TERRAIN_SOLID) != null,
                "terrain_opaque must normalize to terrain scene domain");
        require(set.postPrograms().isEmpty(), "manifest-less scene pack unexpectedly created post programs");
    }

    private static void testNestedPackRoot(Path root) throws Exception {
        Path nested = root.resolve("OnlyPack");
        Files.createDirectories(nested.resolve("shaders/prism"));
        Files.writeString(nested.resolve("shaders/prism/terrain_cutout.vsh"), "#version 450\nvoid main(){}\n");
        Files.writeString(nested.resolve("shaders/prism/terrain_cutout.fsh"), "#version 450\nvoid main(){}\n");
        require(PrismPackRootLocator.locate(root).equals(nested.toAbsolutePath().normalize()),
                "single nested pack root was not resolved");
    }

    private static void testManifestlessZip(Path root) throws Exception {
        Files.createDirectories(root);
        Path zip = root.resolve("native-auto.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            writeZip(out, "Pack/prism.properties", "id=smoke.zip\nname=ZIP Smoke\nversion=1.0.0\nprism_api=1.9\n");
            writeZip(out, "Pack/shaders/prism/terrain_opaque.vsh", "#version 450\nvoid main(){gl_Position=vec4(0.0);}\n");
            writeZip(out, "Pack/shaders/prism/terrain_opaque.fsh", "#version 450\nlayout(location=0) out vec4 c; void main(){c=vec4(1.0);}\n");
        }
        PrismZipPackCache cache = new PrismZipPackCache(root.resolve("game"));
        Path materialized = cache.materialize(zip);
        Path logicalRoot = PrismPackRootLocator.locate(materialized);
        PrismPackDefinition pack = PrismNativePackLoader.load(logicalRoot, zip.getFileName().toString());
        require(pack.id().equals("smoke.zip"), "manifest-less ZIP metadata id mismatch");
        require(pack.programSet().sceneProgram(PrismSceneDomain.TERRAIN_SOLID) != null,
                "manifest-less ZIP scene program was not normalized");
        require(cache.materialize(zip).equals(materialized), "unchanged ZIP must reuse materialized cache");
    }

    private static void testArchiveSecurity(Path root) throws Exception {
        Files.createDirectories(root);
        PrismZipPackCache cache = new PrismZipPackCache(root.resolve("game"));

        Path traversal = root.resolve("traversal.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(traversal))) {
            writeZip(out, "../escape.glsl", "outside");
        }
        expectPackError(() -> cache.materialize(traversal), "archive_path_escape");
        require(!Files.exists(root.resolve("escape.glsl")), "ZIP traversal wrote outside the cache");

        Path duplicate = root.resolve("duplicate.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(duplicate))) {
            writeZip(out, "Shaders/main.glsl", "first");
            writeZip(out, "shaders/main.glsl", "second");
        }
        expectPackError(() -> cache.materialize(duplicate), "archive_duplicate_path");

        Path platformSpecific = root.resolve("platform-specific.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(platformSpecific))) {
            writeZip(out, "shaders/main.glsl:alternate", "unsafe");
        }
        expectPackError(() -> cache.materialize(platformSpecific), "archive_path_escape");
    }

    private static void testArchiveContentAddressedRefresh(Path root) throws Exception {
        Files.createDirectories(root);
        Path zip = root.resolve("refresh.zip");
        writeStoredZip(zip, "shader.glsl", "AAAA");
        long originalSize = Files.size(zip);
        FileTime originalModified = Files.getLastModifiedTime(zip);

        PrismZipPackCache cache = new PrismZipPackCache(root.resolve("game"));
        Path materialized = cache.materialize(zip);
        require(Files.readString(materialized.resolve("shader.glsl")).equals("AAAA"),
                "initial content-addressed ZIP extraction mismatch");

        writeStoredZip(zip, "shader.glsl", "BBBB");
        Files.setLastModifiedTime(zip, originalModified);
        require(Files.size(zip) == originalSize, "stored ZIP refresh fixture changed size");
        Path refreshed = cache.materialize(zip);
        require(refreshed.equals(materialized), "same archive path must retain its cache location");
        require(Files.readString(refreshed.resolve("shader.glsl")).equals("BBBB"),
                "ZIP cache reused stale content when size and timestamp were unchanged");
    }

    private static void testIncludeRealPathContainment(Path root) throws Exception {
        Files.createDirectories(root);
        Path outside = root.getParent().resolve("outside-include.glsl");
        Files.writeString(outside, "const float escaped = 1.0;\n", StandardCharsets.UTF_8);
        Path link = root.resolve("escaped.glsl");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            return;
        }
        expectPackError(() -> PrismShaderSourceLoader.load(root, "escaped.glsl"), "include_path_escape");
    }

    private static void testCreatorFrameUniformContract(Path root) throws Exception {
        Files.createDirectories(root.resolve("shaders"));
        Files.writeString(root.resolve("shaders/fullscreen.vsh"), """
                #version 450
                layout(location=0) out vec2 prismUv;
                void main(){
                    prismUv=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
                    gl_Position=vec4(prismUv*2.0-1.0,0.0,1.0);
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("shaders/frame.fsh"), """
                #version 450
                #include <prism/frame.glsl>
                uniform sampler2D SceneDepth;
                layout(location=0) in vec2 prismUv;
                layout(location=0) out vec4 color;
                void main(){
                    float d=texture(SceneDepth,prismUv).r;
                    color=vec4(prismReconstructCameraRelativePosition(prismUv,d),1.0);
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("prism.json"), """
                {
                  "schema_version": 1,
                  "id": "smoke.frame",
                  "name": "Creator Frame Smoke",
                  "version": "1.0.0",
                  "prism_api": "1.12",
                  "requires": ["PACK_FRAME_UNIFORMS"],
                  "programs": [{
                    "id": "frame",
                    "type": "fullscreen",
                    "vertex": "shaders/fullscreen.vsh",
                    "fragment": "shaders/frame.fsh",
                    "output": "minecraft:main_color",
                    "frame_uniforms": true,
                    "samplers": [{
                      "name": "SceneDepth",
                      "resource": "minecraft:main_depth",
                      "filter": "nearest"
                    }]
                  }]
                }
                """, StandardCharsets.UTF_8);

        PrismPackDefinition pack = PrismPackParser.parse(root);
        PrismPipelineDefinition program = pack.programSet().postPrograms().getFirst();
        require(program.frameUniforms(), "frame_uniforms flag was not parsed");
        require(program.samplers().getFirst().nearest(), "explicit nearest sampler filter was not parsed");
        String expanded = PrismShaderSourceLoader.load(root, "shaders/frame.fsh");
        require(expanded.contains("uniform PrismFrame"), "built-in PrismFrame block was not expanded");
        require(expanded.contains("prismReconstructCameraRelativePosition"),
                "built-in frame reconstruction helper was not expanded");

        PrismPipelineCompiler.PreparedPack prepared = PrismPipelineCompiler.prepareAsync(
                pack, java.util.List.of(), Runnable::run).join();
        require(prepared.graph().orderedPasses().size() == 1, "frame-uniform pack graph was not prepared");

        String invalid = Files.readString(root.resolve("prism.json"), StandardCharsets.UTF_8)
                .replace("\"frame_uniforms\": true,", "");
        Files.writeString(root.resolve("prism.json"), invalid, StandardCharsets.UTF_8);
        PrismPackDefinition invalidPack = PrismPackParser.parse(root);
        try {
            PrismPipelineCompiler.prepareAsync(invalidPack, java.util.List.of(), Runnable::run).join();
            throw new AssertionError("PrismFrame declaration without frame_uniforms must be rejected");
        } catch (RuntimeException failure) {
            Throwable unwrapped = PrismPipelineCompiler.unwrapPreparationFailure(failure);
            require(unwrapped instanceof PrismPackLoadException,
                    "frame uniform failure must retain its structured diagnostic");
            require(((PrismPackLoadException) unwrapped).code().equals("frame_uniform_contract"),
                    "unexpected frame uniform diagnostic: " + ((PrismPackLoadException) unwrapped).code());
        }
    }

    private static void testTemporalHistoryContract(Path root) throws Exception {
        Files.createDirectories(root.resolve("shaders"));
        Files.writeString(root.resolve("shaders/fullscreen.vsh"), """
                #version 450
                layout(location=0) out vec2 prismUv;
                void main(){
                    prismUv=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
                    gl_Position=vec4(prismUv*2.0-1.0,0.0,1.0);
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("shaders/temporal.fsh"), """
                #version 450
                #include <prism/frame.glsl>
                #include <prism/depth.glsl>
                #include <prism/temporal.glsl>
                uniform sampler2D SceneColor;
                uniform sampler2D SceneDepth;
                uniform sampler2D PreviousHistory;
                layout(location=0) in vec2 prismUv;
                layout(location=0) out vec4 color;
                void main(){
                    float depth=texture(SceneDepth,prismUv).r;
                    vec2 previousUv=prismReprojectPreviousUv(prismUv,depth);
                    vec4 current=texture(SceneColor,prismUv);
                    vec4 previous=prismHistoryUvValid(previousUv)
                            ? texture(PreviousHistory,previousUv) : current;
                    color=mix(current,previous,0.0);
                }
                """, StandardCharsets.UTF_8);
        String manifest = """
                {
                  "schema_version": 1,
                  "id": "smoke.temporal",
                  "name": "Temporal History Smoke",
                  "version": "1.0.0",
                  "prism_api": "1.13",
                  "requires": ["PACK_FRAME_UNIFORMS", "PACK_TEMPORAL_HISTORY"],
                  "resources": [{
                    "id": "smoke:history",
                    "format": "RGBA16_FLOAT",
                    "scale": 0.5,
                    "lifetime": "history"
                  }],
                  "programs": [{
                    "id": "temporal",
                    "type": "fullscreen",
                    "vertex": "shaders/fullscreen.vsh",
                    "fragment": "shaders/temporal.fsh",
                    "output": "smoke:history",
                    "frame_uniforms": true,
                    "samplers": [
                      {"name":"SceneColor","resource":"minecraft:main_color","filter":"linear"},
                      {"name":"SceneDepth","resource":"minecraft:main_depth","filter":"nearest"},
                      {"name":"PreviousHistory","resource":"smoke:history","history":"previous"}
                    ]
                  }]
                }
                """;
        Files.writeString(root.resolve("prism.json"), manifest, StandardCharsets.UTF_8);

        PrismPackDefinition pack = PrismPackParser.parse(root);
        require(pack.resources().getFirst().history(), "history resource lifetime was not parsed");
        PrismPipelineDefinition program = pack.programSet().postPrograms().getFirst();
        require(program.samplers().getLast().previousHistory(), "previous history sampler was not parsed");
        String expanded = PrismShaderSourceLoader.load(root, "shaders/temporal.fsh");
        require(expanded.contains("prismReconstructCameraRelativeNormal"),
                "depth helper include was not expanded");
        require(expanded.contains("prismReprojectPreviousUv"),
                "temporal helper include was not expanded");

        PrismPipelineCompiler.PreparedPack prepared = PrismPipelineCompiler.prepareAsync(
                pack, java.util.List.of(), Runnable::run).join();
        String current = PrismPackResources.toInternal("smoke:history");
        String previous = PrismPackResources.previousHistory("smoke:history");
        require(prepared.graph().requireResource(current).imported(),
                "current history view must be an imported single-frame graph resource");
        require(prepared.graph().requireResource(previous).imported(),
                "previous history view must be an imported single-frame graph resource");
        require(prepared.graph().transientPlan().slotCount() == 0,
                "history pairs must not alias transient slots");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace(", \"PACK_TEMPORAL_HISTORY\"", ""), StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "history_capability_required");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace("\"frame_uniforms\": true,", ""), StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "sampler_history_frame_uniforms");
    }

    private static void testTextureAssetContract(Path root) throws Exception {
        Files.createDirectories(root.resolve("shaders"));
        Files.createDirectories(root.resolve("textures"));
        Files.write(root.resolve("textures/noise.png"), java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M/wHwAF/gL+XW0Y5QAAAABJRU5ErkJggg=="));
        Files.writeString(root.resolve("shaders/fullscreen.vsh"), """
                #version 450
                layout(location=0) out vec2 prismUv;
                void main(){
                    prismUv=vec2((gl_VertexID<<1)&2,gl_VertexID&2);
                    gl_Position=vec4(prismUv*2.0-1.0,0.0,1.0);
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("shaders/texture.fsh"), """
                #version 450
                #include <prism/common.glsl>
                uniform sampler2D NoiseTexture;
                layout(location=0) in vec2 prismUv;
                layout(location=0) out vec4 color;
                void main(){
                    vec3 encoded=texture(NoiseTexture,prismUv).rgb;
                    color=vec4(prismSrgbToLinear(encoded),1.0);
                }
                """, StandardCharsets.UTF_8);
        String manifest = """
                {
                  "schema_version": 1,
                  "id": "smoke.texture_asset",
                  "name": "Texture Asset Smoke",
                  "version": "1.0.0",
                  "prism_api": "1.14",
                  "requires": ["PACK_TEXTURE_ASSETS"],
                  "textures": [{
                    "id": "smoke:noise",
                    "source": "textures/noise.png",
                    "color_space": "srgb"
                  }],
                  "programs": [{
                    "id": "texture",
                    "type": "fullscreen",
                    "vertex": "shaders/fullscreen.vsh",
                    "fragment": "shaders/texture.fsh",
                    "output": "minecraft:main_color",
                    "samplers": [{
                      "name": "NoiseTexture",
                      "resource": "smoke:noise",
                      "filter": "linear",
                      "wrap": "repeat"
                    }]
                  }]
                }
                """;
        Files.writeString(root.resolve("prism.json"), manifest, StandardCharsets.UTF_8);

        PrismPackDefinition pack = PrismPackParser.parse(root);
        PrismPackTextureAssetDefinition texture = pack.textureAssets().getFirst();
        require(texture.width() == 1 && texture.height() == 1 && texture.srgb(),
                "PNG header/color-space metadata was not parsed");
        PrismSamplerBinding sampler = pack.programSet().postPrograms().getFirst().samplers().getFirst();
        require(sampler.repeat() && sampler.samplerKey().equals("linear:repeat"),
                "repeat sampler policy was not parsed");

        PrismPipelineCompiler.PreparedPack prepared = PrismPipelineCompiler.prepareAsync(
                pack, java.util.List.of(), Runnable::run).join();
        require(prepared.graph().requireResource(PrismPackResources.toInternal("smoke:noise")).imported(),
                "pack texture asset must be an imported graph resource");
        String fragment = prepared.pipelines().getFirst().sources().fragmentSource();
        require(fragment.contains("#define PRISM_SAMPLER_NOISETEXTURE_SRGB 1"),
                "sRGB sampler metadata macro was not injected");
        require(fragment.contains("prismSrgbToLinear"),
                "built-in color-space helper was not expanded");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace("\"requires\": [\"PACK_TEXTURE_ASSETS\"],", "\"requires\": [],"),
                StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "texture_asset_capability_required");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace("\"wrap\": \"repeat\"", "\"wrap\": \"mirror\""),
                StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "sampler_wrap");
    }

    private static void testVulkan15TextureAndComputeContract(Path root) throws Exception {
        Files.createDirectories(root.resolve("textures"));
        Files.createDirectories(root.resolve("shaders"));
        var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        image.setRGB(1, 0, 0xff00ff00);
        image.setRGB(0, 1, 0xff0000ff);
        image.setRGB(1, 1, 0xffffffff);
        for (String face : java.util.List.of("px", "nx", "py", "ny", "pz", "nz")) {
            javax.imageio.ImageIO.write(image, "png", root.resolve("textures/" + face + ".png").toFile());
        }
        Files.writeString(root.resolve("shaders/clear.comp"), """
                #version 450
                layout(local_size_x=PRISM_LOCAL_SIZE_X,local_size_y=PRISM_LOCAL_SIZE_Y) in;
                layout(set=0,binding=PRISM_BINDING_OUTPUTIMAGE,rgba16f) uniform writeonly image2D OutputImage;
                layout(std430,set=0,binding=PRISM_BINDING_DATABUFFER) buffer Data { uint values[]; } DataBuffer;
                void main(){ imageStore(OutputImage,ivec2(gl_GlobalInvocationID.xy),vec4(0.0)); }
                """, StandardCharsets.UTF_8);
        String manifest = """
                {
                  "schema_version":1,
                  "id":"smoke.vulkan15",
                  "name":"Vulkan 1.15 Contract",
                  "version":"1.0.0",
                  "prism_api":"1.15",
                  "requires":["PACK_TEXTURE_ASSETS","CUBEMAP_TEXTURES","TEXTURE_ARRAYS","TEXTURE_3D","MIPMAPPED_TEXTURES","COMPUTE_PIPELINES","STORAGE_IMAGES","STORAGE_BUFFERS"],
                  "resources":[{"id":"smoke:output","format":"RGBA16_FLOAT","scale":1.0}],
                  "buffers":[{"id":"smoke:data","size_bytes":1024}],
                  "textures":[{
                    "id":"smoke:sky",
                    "dimension":"cube",
                    "faces":{
                      "positive_x":"textures/px.png","negative_x":"textures/nx.png",
                      "positive_y":"textures/py.png","negative_y":"textures/ny.png",
                      "positive_z":"textures/pz.png","negative_z":"textures/nz.png"
                    },
                    "generate_mips":true
                  },{
                    "id":"smoke:tiles","dimension":"2d_array",
                    "layers":["textures/px.png","textures/nx.png"],
                    "generate_mips":true
                  },{
                    "id":"smoke:fog","dimension":"3d",
                    "layers":["textures/py.png","textures/ny.png"],
                    "generate_mips":true
                  }],
                  "programs":[{
                    "id":"clear","type":"compute","compute":"shaders/clear.comp",
                    "storage_images":[{"name":"OutputImage","resource":"smoke:output","access":"write"}],
                    "storage_buffers":[{"name":"DataBuffer","resource":"smoke:data","access":"read_write"}],
                    "dispatch":{"resource":"smoke:output","local_size_x":8,"local_size_y":8}
                  }]
                }
                """;
        Files.writeString(root.resolve("prism.json"), manifest, StandardCharsets.UTF_8);

        PrismPackDefinition pack = PrismPackParser.parse(root);
        PrismPackTextureAssetDefinition cube = pack.textureAssets().getFirst();
        require(cube.cubemap() && cube.sources().size() == 6 && cube.mipLevels() == 2,
                "API 1.15 cubemap faces/generated mip chain were not parsed");
        PrismPackTextureAssetDefinition array = pack.textureAssets().get(1);
        PrismPackTextureAssetDefinition volume = pack.textureAssets().get(2);
        require(array.array() && array.sources().size() == 2 && array.mipLevels() == 2,
                "API 1.15 2D-array texture was not parsed");
        require(volume.volume() && volume.sources().size() == 2 && volume.mipLevels() == 2,
                "API 1.15 3D texture was not parsed");
        require(pack.buffers().getFirst().descriptor().sizeBytes() == 1024L,
                "storage buffer declaration was not parsed");
        require(pack.programSet().computePrograms().size() == 1,
                "compute ProgramSet entry was not normalized");
        String metadata = PrismShaderDefines.injectTextureMetadata(
                "#version 450\nvoid main(){}\n",
                java.util.List.of(new PrismSamplerBinding("Sky", "smoke:sky", "linear", "clamp", "current")),
                pack.textureAssets());
        require(metadata.contains("PRISM_SAMPLER_SKY_CUBE 1")
                        && metadata.contains("PRISM_SAMPLER_SKY_MIP_LEVELS 2"),
                "cubemap/mip sampler metadata was not injected");

        String dimensionMetadata = PrismShaderDefines.injectTextureMetadata(
                "#version 450\nvoid main(){}\n",
                java.util.List.of(
                        new PrismSamplerBinding("Tiles", "smoke:tiles", "linear", "clamp", "current"),
                        new PrismSamplerBinding("Fog", "smoke:fog", "linear", "clamp", "current")),
                pack.textureAssets());
        require(dimensionMetadata.contains("PRISM_SAMPLER_TILES_2D_ARRAY 1")
                        && dimensionMetadata.contains("PRISM_SAMPLER_FOG_3D 1")
                        && dimensionMetadata.contains("PRISM_SAMPLER_FOG_LAYERS 2"),
                "array/3D sampler metadata was not injected");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace("\"DataBuffer\"", "\"outputimage\""), StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "storage_binding_name");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace(",\"CUBEMAP_TEXTURES\"", ""), StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "cubemap_capability_required");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace(",\"TEXTURE_ARRAYS\"", ""), StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "texture_array_capability_required");

        Files.writeString(root.resolve("prism.json"),
                manifest.replace(",\"TEXTURE_3D\"", ""), StandardCharsets.UTF_8);
        expectPackError(() -> PrismPackParser.parse(root), "texture_3d_capability_required");
    }


    private static void testManifestlessFeaturePack(Path root) throws Exception {
        Path shaders = root.resolve("shaders/prism");
        Files.createDirectories(shaders);
        Files.writeString(root.resolve("prism.properties"), "id=smoke.feature\nname=Feature ProgramSet Smoke\nversion=1.0.0\nprism_api=1.9\n");
        for (String stem : java.util.List.of("entity_opaque", "entity_translucent", "block_entity")) {
            Files.writeString(shaders.resolve(stem + ".vsh"), "#version 450\nvoid main(){gl_Position=vec4(0.0);}\n");
            Files.writeString(shaders.resolve(stem + ".fsh"), "#version 450\nlayout(location=0) out vec4 c; void main(){c=vec4(1.0);}\n");
        }
        PrismPackDefinition pack = PrismNativePackLoader.load(root, "feature-native.zip");
        PrismProgramSet set = pack.programSet();
        require(set.sceneProgram(PrismSceneDomain.ENTITY_OPAQUE) != null, "entity_opaque must be discovered");
        require(set.sceneProgram(PrismSceneDomain.ENTITY_TRANSLUCENT) != null, "entity_translucent must be discovered");
        require(set.sceneProgram(PrismSceneDomain.BLOCK_ENTITY) != null, "block_entity must be discovered");
        require(pack.requiredCapabilities().contains(dev.dreamveil.prism.api.PrismCapability.FEATURE_RENDER_MODEL_PIPELINES),
                "feature pack must declare Feature Rendering runtime requirement");
        require(pack.requiredCapabilities().contains(dev.dreamveil.prism.api.PrismCapability.DYNAMIC_SCENE_PIPELINE_VARIANTS),
                "feature pack must declare dynamic variant requirement");
    }

    private static void testManifestVertexInheritance(Path root) throws Exception {
        Files.createDirectories(root.resolve("shaders"));
        Files.writeString(root.resolve("shaders/red.fsh"),
                "#version 450\nlayout(location=0) out vec4 c; void main(){c=vec4(1.0,0.0,0.0,1.0);}\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("prism.json"), """
                {
                  "schema_version": 1,
                  "id": "smoke.inherit",
                  "name": "Inherited Vertex Smoke",
                  "version": "1.0.0",
                  "prism_api": "1.11",
                  "programs": [
                    {
                      "id": "entity",
                      "type": "scene",
                      "domain": "entity_opaque",
                      "vertex": "$inherit",
                      "fragment": "shaders/red.fsh"
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        PrismPackDefinition pack = PrismPackParser.parse(root);
        PrismPipelineDefinition definition = pack.programSet().sceneProgram(PrismSceneDomain.ENTITY_OPAQUE);
        require(definition != null && definition.inheritsVertexShader(),
                "dynamic Feature Rendering program must preserve the $inherit vertex policy");

        String invalidManifest = Files.readString(root.resolve("prism.json"), StandardCharsets.UTF_8)
                .replace("entity_opaque", "terrain_opaque");
        Files.writeString(root.resolve("prism.json"), invalidManifest, StandardCharsets.UTF_8);
        try {
            PrismPackParser.parse(root);
            throw new AssertionError("terrain_opaque must reject vertex $inherit");
        } catch (PrismPackLoadException expected) {
            require(expected.code().equals("scene_vertex_inherit_domain"),
                    "unexpected inherited-vertex domain diagnostic: " + expected.code());
        }
    }

    private static void writeZip(ZipOutputStream out, String name, String text) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static void writeStoredZip(Path zip, String name, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(entry);
            out.write(bytes);
            out.closeEntry();
        }
    }

    private static void expectPackError(ThrowingAction action, String expectedCode) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected Prism pack error " + expectedCode);
        } catch (PrismPackLoadException expected) {
            require(expected.code().equals(expectedCode),
                    "expected " + expectedCode + " but received " + expected.code());
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void testSceneContractValidation() throws Exception {
        PrismPipelineDefinition program = new PrismPipelineDefinition(
                "terrain", "scene", "terrain_opaque", "terrain.vsh", "terrain.fsh", "", "inherit", false, java.util.List.of());
        PrismSceneShaderContractValidator.validate(
                program,
                "#version 450\n// gl_Vertex is mentioned only in documentation\nvoid main(){gl_Position=vec4(0.0);}",
                "#version 450\nlayout(location=0) out vec4 c; void main(){c=vec4(1.0);}");
        try {
            PrismSceneShaderContractValidator.validate(
                    program,
                    "#version 450\nvoid main(){gl_Position=gl_Vertex;}",
                    "#version 450\nvoid main(){}");
            throw new AssertionError("executable legacy scene input must be rejected");
        } catch (PrismPackLoadException expected) {
            require(expected.code().equals("scene_legacy_shader_contract"), "unexpected scene contract diagnostic");
        }
    }

    private static void testLegacyInspection(Path root) throws Exception {
        Path shaders = root.resolve("shaders/world0");
        Files.createDirectories(shaders);
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
                #version 130
                attribute vec4 mc_Entity;
                void main(){ gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex; }
                """);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
                #version 130
                uniform sampler2D colortex0;
                void main(){ gl_FragData[0] = texture2D(colortex0, vec2(0.0)); }
                """);
        PrismLegacyPackInspector.Inventory inventory = PrismLegacyPackInspector.inspect(root);
        require(inventory.programCount() == 1, "legacy program pair count mismatch");
        require(inventory.dimensionCount() == 1, "legacy dimension count mismatch");
        require(inventory.blockers().contains("gl_Vertex"), "legacy built-in blocker was not detected");
        require(inventory.blockers().contains("mc_Entity"), "legacy material attribute blocker was not detected");
    }

    private static void testExpandedDomainRecognition() throws Exception {
        require(PrismSceneDomain.parse("entity_opaque", "smoke") == PrismSceneDomain.ENTITY_OPAQUE,
                "entity_opaque semantic domain must be recognized");
        require(PrismSceneDomain.parse("block_entities", "smoke") == PrismSceneDomain.BLOCK_ENTITY,
                "block_entities alias must normalize to block_entity");
        require(PrismSceneExecutionSupport.supports(PrismSceneDomain.ENTITY_OPAQUE)
                        && PrismSceneExecutionSupport.isDynamicFeature(PrismSceneDomain.ENTITY_OPAQUE),
                "entity domain must execute through scoped dynamic Feature Rendering variants");
        require(PrismSceneExecutionSupport.supports(PrismSceneDomain.BLOCK_ENTITY)
                        && PrismSceneExecutionSupport.isDynamicFeature(PrismSceneDomain.BLOCK_ENTITY),
                "block_entity must execute through scoped dynamic Feature Rendering variants");
        require(!PrismSceneExecutionSupport.supports(PrismSceneDomain.WATER),
                "water must remain unavailable until its execution path is verified");
        require(PrismSceneExecutionSupport.supports(PrismSceneDomain.TERRAIN_SOLID),
                "terrain_opaque must remain executable");
        require(!PrismSceneExecutionSupport.supports(PrismSceneDomain.SHADOW_CASTER),
                "shadow_caster must remain GPU-unavailable until a dedicated auxiliary replay path is verified");
        try {
            PrismSceneExecutionSupport.requireSupported(PrismSceneDomain.SHADOW_CASTER, "smoke-shadow");
            throw new AssertionError("shadow_caster should have been rejected by the 26.2 bridge guard");
        } catch (PrismPackLoadException expected) {
            require(expected.code().equals(PrismSceneExecutionSupport.SHADOW_AUXILIARY_VIEW_BLOCKER),
                    "shadow_caster must report the precise auxiliary-view blocker");
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
