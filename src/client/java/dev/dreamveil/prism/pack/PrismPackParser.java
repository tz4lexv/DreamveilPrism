package dev.dreamveil.prism.pack;

import java.io.IOException;
import java.io.Reader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import dev.dreamveil.prism.api.PrismApiVersion;
import dev.dreamveil.prism.api.PrismCapability;
import dev.dreamveil.prism.api.setting.PrismPackSettingDefinition;
import dev.dreamveil.prism.api.setting.PrismPackSettingType;
import dev.dreamveil.prism.api.render.PrismVanillaRenderFeature;
import dev.dreamveil.prism.graph.PrismTextureDesc;
import dev.dreamveil.prism.graph.PrismTextureExtent;
import dev.dreamveil.prism.graph.PrismTextureFormat;
import dev.dreamveil.prism.graph.PrismTextureUsage;
import dev.dreamveil.prism.graph.PrismBufferDesc;
import dev.dreamveil.prism.graph.PrismBufferUsage;

final class PrismPackParser {
    static final int SCHEMA_VERSION = 1;
    private static final int MAX_SETTINGS = 64;
    private static final long MAX_MANIFEST_BYTES = 1L * 1024L * 1024L;
    private static final int MAX_RESOURCES = 64;
    private static final int MAX_BUFFERS = dev.dreamveil.prism.api.PrismLimits.LOADER.maxPackStorageBuffers();
    private static final long MAX_BUFFER_BYTES = dev.dreamveil.prism.api.PrismLimits.LOADER.maxPackStorageBufferBytes();
    private static final long MAX_TOTAL_BUFFER_BYTES = dev.dreamveil.prism.api.PrismLimits.LOADER.maxTotalPackStorageBufferBytes();
    private static final int MAX_TEXTURE_ASSETS = 64;
    private static final long MAX_TEXTURE_ASSET_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_TEXTURE_ASSET_DIMENSION = 4096;
    private static final long MAX_TEXTURE_ASSET_DECODED_BYTES = 128L * 1024L * 1024L;
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern BINDING_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern ENUM_VALUE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final Pattern RESOURCE_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,31}:[a-z0-9][a-z0-9/._-]{0,63}");

    private PrismPackParser() {
    }

    static PrismPackDefinition parse(Path packRoot) throws PrismPackLoadException {
        if (PrismGlslFrontend.looksLike(packRoot)) {
            throw new PrismPackLoadException("frontend_ambiguous", "Both prism.json and frontend=glsl select an authoring route. Keep one explicit route; Prism will not choose between them", "prism.properties:frontend");
        }
        Path normalizedRoot = packRoot.toAbsolutePath().normalize();
        Path manifestPath = PrismPackPathPolicy.requireContainedRegularFile(
                normalizedRoot,
                normalizedRoot.resolve("prism.json").normalize(),
                "manifest_path_escape",
                "manifest_missing",
                "Manifest prism.json",
                "prism.json");

        JsonObject root;
        try {
            long manifestBytes = Files.size(manifestPath);
            if (manifestBytes > MAX_MANIFEST_BYTES) {
                throw new PrismPackLoadException(
                        "manifest_limit",
                        "prism.json exceeds " + MAX_MANIFEST_BYTES + " bytes",
                        "prism.json");
            }
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (Reader reader = new InputStreamReader(Files.newInputStream(manifestPath), decoder)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    throw new PrismPackLoadException("manifest_type", "prism.json must contain a JSON object", "prism.json");
                }
                root = parsed.getAsJsonObject();
            }
        } catch (PrismPackLoadException exception) {
            throw exception;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new PrismPackLoadException("manifest_encoding", "prism.json must be valid UTF-8", "prism.json", exception);
        } catch (IOException | RuntimeException exception) {
            throw new PrismPackLoadException("manifest_read", "Could not read prism.json: " + exception.getMessage(), "prism.json", exception);
        }

        rejectUnknownFields(
                root,
                Set.of("schema_version", "id", "name", "version", "author", "description", "prism_api", "requires", "settings", "resources", "buffers", "textures", "dynamic_resolution", "scene_jitter", "vanilla_replacements", "programs", "pipelines"),
                "prism.json");

        int schema = integer(root, "schema_version");
        if (schema != SCHEMA_VERSION) {
            throw new PrismPackLoadException(
                    "schema_version",
                    "Unsupported Prism pack schema " + schema + "; expected " + SCHEMA_VERSION,
                    "prism.json");
        }

        String id = identifier(text(root, "id"), "id", "prism.json");
        String name = text(root, "name");
        String version = text(root, "version");
        String author = optionalText(root, "author", "");
        String description = optionalText(root, "description", "");
        PrismApiVersion api;
        try {
            api = PrismApiVersion.parse(text(root, "prism_api"));
        } catch (IllegalArgumentException exception) {
            throw new PrismPackLoadException("prism_api", exception.getMessage(), "prism.json", exception);
        }
        if (!PrismApiVersion.CURRENT.isCompatibleWith(api)) {
            throw new PrismPackLoadException(
                    "prism_api_incompatible",
                    "Pack requires Prism API " + api + " but runtime provides " + PrismApiVersion.CURRENT,
                    "prism.json");
        }

        if (api.compareTo(PrismApiVersion.V1_25) >= 0) PrismAuthorshipContract.validate(root);
        List<PrismCapability> requiredCapabilities = parseRequiredCapabilities(root);
        List<PrismPackSettingDefinition> settings = parseSettings(root);
        List<PrismPackTextureDefinition> resources = parseResources(root);
        if (resources.stream().anyMatch(r -> r.descriptor().format().hasDepthAspect())
                && (api.compareTo(PrismApiVersion.V1_20) < 0
                    || !requiredCapabilities.contains(PrismCapability.DEPTH_ONLY_RENDER_TARGETS))) {
            throw new PrismPackLoadException("depth_resource_contract",
                    "Creator depth targets require API 1.20 and DEPTH_ONLY_RENDER_TARGETS", "prism.json");
        }
        List<PrismPackBufferDefinition> buffers = parseBuffers(root, resources);
        List<PrismPackTextureAssetDefinition> textureAssets = parseTextureAssets(
                root, normalizedRoot, resources, buffers);
        PrismDynamicResolutionDefinition dynamicResolution = parseDynamicResolution(root);
        boolean sceneJitter = root.has("scene_jitter") && booleanJson(root, "scene_jitter");
        List<PrismVanillaRenderFeature> vanillaReplacements = parseVanillaReplacements(root);
        if (!vanillaReplacements.isEmpty() && api.compareTo(PrismApiVersion.V1_17) < 0) {
            throw new PrismPackLoadException(
                    "vanilla_replacement_api_version",
                    "vanilla_replacements requires prism_api 1.17 or newer",
                    "prism.json:vanilla_replacements");
        }
        if (!vanillaReplacements.isEmpty()
                && !requiredCapabilities.contains(PrismCapability.VANILLA_SCENE_REPLACEMENT)) {
            throw new PrismPackLoadException(
                    "vanilla_replacement_capability_required",
                    "vanilla_replacements requires VANILLA_SCENE_REPLACEMENT in requires[]",
                    "prism.json:requires");
        }
        boolean usesTemporalHistory = resources.stream().anyMatch(PrismPackTextureDefinition::history);
        if (usesTemporalHistory && api.compareTo(PrismApiVersion.V1_13) < 0) {
            throw new PrismPackLoadException(
                    "history_api_version",
                    "History textures require prism_api 1.13 or newer",
                    "prism.json:resources");
        }
        if (usesTemporalHistory && !requiredCapabilities.contains(PrismCapability.PACK_TEMPORAL_HISTORY)) {
            throw new PrismPackLoadException(
                    "history_capability_required",
                    "History textures require PACK_TEMPORAL_HISTORY in requires[]",
                    "prism.json:requires");
        }
        boolean usesSceneAttachments = resources.stream().anyMatch(PrismPackTextureDefinition::scene);
        if (usesSceneAttachments && api.compareTo(PrismApiVersion.V1_16) < 0) {
            throw new PrismPackLoadException(
                    "scene_attachment_api_version",
                    "Scene-lifetime G-buffer attachments require prism_api 1.16 or newer",
                    "prism.json:resources");
        }
        if (usesSceneAttachments
                && !requiredCapabilities.contains(PrismCapability.SCENE_GBUFFER_ATTACHMENTS)) {
            throw new PrismPackLoadException(
                    "scene_attachment_capability_required",
                    "Scene-lifetime resources require SCENE_GBUFFER_ATTACHMENTS in requires[]",
                    "prism.json:requires");
        }
        if (!textureAssets.isEmpty() && api.compareTo(PrismApiVersion.V1_14) < 0) {
            throw new PrismPackLoadException(
                    "texture_asset_api_version",
                    "Pack texture assets require prism_api 1.14 or newer",
                    "prism.json:textures");
        }
        if (!textureAssets.isEmpty()
                && !requiredCapabilities.contains(PrismCapability.PACK_TEXTURE_ASSETS)) {
            throw new PrismPackLoadException(
                    "texture_asset_capability_required",
                    "Pack texture assets require PACK_TEXTURE_ASSETS in requires[]",
                    "prism.json:requires");
        }
        boolean advancedTextureAssets = textureAssets.stream()
                .anyMatch(texture -> !"2d".equals(texture.dimension()) || texture.mipLevels() > 1);
        if (advancedTextureAssets && api.compareTo(PrismApiVersion.V1_15) < 0) {
            throw new PrismPackLoadException(
                    "advanced_texture_api_version",
                    "Cubemaps, arrays, 3D textures and generated mipmaps require prism_api 1.15 or newer",
                    "prism.json:textures");
        }
        if (textureAssets.stream().anyMatch(PrismPackTextureAssetDefinition::cubemap)
                && !requiredCapabilities.contains(PrismCapability.CUBEMAP_TEXTURES)) {
            throw new PrismPackLoadException(
                    "cubemap_capability_required",
                    "Cubemap texture assets require CUBEMAP_TEXTURES in requires[]",
                    "prism.json:requires");
        }
        if (textureAssets.stream().anyMatch(PrismPackTextureAssetDefinition::array)
                && !requiredCapabilities.contains(PrismCapability.TEXTURE_ARRAYS)) {
            throw new PrismPackLoadException(
                    "texture_array_capability_required",
                    "2D array texture assets require TEXTURE_ARRAYS in requires[]",
                    "prism.json:requires");
        }
        if (textureAssets.stream().anyMatch(PrismPackTextureAssetDefinition::volume)
                && !requiredCapabilities.contains(PrismCapability.TEXTURE_3D)) {
            throw new PrismPackLoadException(
                    "texture_3d_capability_required",
                    "3D texture assets require TEXTURE_3D in requires[]",
                    "prism.json:requires");
        }
        if (textureAssets.stream().anyMatch(texture -> texture.mipLevels() > 1)
                && !requiredCapabilities.contains(PrismCapability.MIPMAPPED_TEXTURES)) {
            throw new PrismPackLoadException(
                    "mipmap_capability_required",
                    "Generated texture mipmaps require MIPMAPPED_TEXTURES in requires[]",
                    "prism.json:requires");
        }
        if ((sceneJitter || dynamicResolution.enabled())
                && api.compareTo(PrismApiVersion.V1_15) < 0) {
            throw new PrismPackLoadException(
                    "temporal_runtime_api_version",
                    "Scene jitter and dynamic resolution require prism_api 1.15 or newer",
                    "prism.json");
        }
        if (sceneJitter && !requiredCapabilities.contains(PrismCapability.SCENE_PROJECTION_JITTER)) {
            throw new PrismPackLoadException(
                    "scene_jitter_capability_required",
                    "scene_jitter requires SCENE_PROJECTION_JITTER in requires[]",
                    "prism.json:requires");
        }
        if (dynamicResolution.enabled()
                && !requiredCapabilities.contains(PrismCapability.PACK_DYNAMIC_RESOLUTION)) {
            throw new PrismPackLoadException(
                    "dynamic_resolution_capability_required",
                    "dynamic_resolution requires PACK_DYNAMIC_RESOLUTION in requires[]",
                    "prism.json:requires");
        }
        Set<String> declaredResources = new HashSet<>();
        for (PrismPackTextureDefinition resource : resources) declaredResources.add(resource.id());
        Set<String> declaredBuffers = new HashSet<>();
        for (PrismPackBufferDefinition buffer : buffers) declaredBuffers.add(buffer.id());
        Set<String> declaredSamplerResources = new HashSet<>(declaredResources);
        for (PrismPackTextureAssetDefinition texture : textureAssets) {
            declaredSamplerResources.add(texture.id());
        }

        if (root.has("programs") && root.has("pipelines")) {
            throw new PrismPackLoadException(
                    "programs_conflict",
                    "Use either programs[] (preferred) or the legacy Prism pipelines[] alias, not both",
                    "prism.json");
        }
        String programField = root.has("programs") ? "programs" : "pipelines";
        JsonArray pipelinesJson = array(root, programField);
        if (pipelinesJson.size() == 0) {
            throw new PrismPackLoadException("programs_empty", "A Prism pack must declare at least one program", "prism.json");
        }

        List<PrismPipelineDefinition> pipelines = new ArrayList<>();
        Set<String> pipelineIds = new HashSet<>();
        Set<PrismSceneDomain> sceneDomains = new HashSet<>();
        for (int i = 0; i < pipelinesJson.size(); i++) {
            JsonElement element = pipelinesJson.get(i);
            String source = "prism.json:" + programField + "[" + i + "]";
            if (!element.isJsonObject()) {
                throw new PrismPackLoadException("pipeline_type", "Program entry must be a JSON object", source);
            }
            JsonObject pipeline = element.getAsJsonObject();
            rejectUnknownFields(
                    pipeline,
                    Set.of("id", "type", "domain", "vertex", "fragment", "compute", "output", "outputs", "blend", "frame_uniforms", "samplers", "storage_images", "storage_buffers", "dispatch", "view", "after"),
                    source);
            String pipelineId = identifier(text(pipeline, "id"), "pipeline id", source);
            if (!pipelineIds.add(pipelineId)) {
                throw new PrismPackLoadException("pipeline_duplicate", "Duplicate program id '" + pipelineId + "'", source);
            }

            String type = optionalText(pipeline, "type", "fullscreen").toLowerCase(Locale.ROOT);
            if (!Set.of("fullscreen", "scene", "compute", "scene_view").contains(type)) {
                throw new PrismPackLoadException(
                        "pipeline_type_unsupported",
                        "Unsupported program type '" + type + "' (fullscreen, scene, compute, scene_view)",
                        source);
            }

            if (pipeline.has("view") && !type.equals("scene_view")) {
                throw new PrismPackLoadException("view_program_type", "view is valid only for scene_view programs", source);
            }
            if (type.equals("scene_view")) {
                if (!pipeline.has("view") || !pipeline.get("view").isJsonObject()) {
                    throw new PrismPackLoadException("scene_view_missing", "scene_view requires a view object", source);
                }
                JsonObject view = pipeline.getAsJsonObject("view");
                String geometry = optionalText(view, "geometry", "terrain");
                if (!Set.of("terrain", "captured_models").contains(geometry)) {
                    throw new PrismPackLoadException("scene_view_geometry", "Use terrain or captured_models geometry", source);
                }
                boolean models = geometry.equals("captured_models");
                if (api.compareTo(PrismApiVersion.V1_20) < 0
                        || (!models && !requiredCapabilities.contains(PrismCapability.AUXILIARY_TERRAIN_VIEWS))
                        || !requiredCapabilities.contains(PrismCapability.VULKAN_BACKEND)) {
                    throw new PrismPackLoadException("scene_view_contract",
                            "scene_view requires API 1.20, VULKAN_BACKEND and AUXILIARY_TERRAIN_VIEWS", source);
                }
                if (pipeline.has("compute") || pipeline.has("storage_images") || pipeline.has("storage_buffers")
                        || pipeline.has("dispatch") || pipeline.has("domain")
                        || pipeline.has("output")) {
                    throw new PrismPackLoadException("scene_view_state",
                            "scene_view uses outputs[], view and creator graphics shaders, not scene/compute state", source);
                }
                rejectUnknownFields(view, Set.of("depth", "depth_direction", "section_radius", "atlas_sampler", "cull",
                        "layers", "color_load", "depth_load", "depth_write", "geometry", "model_domains", "model_radius", "visibility"), source + ":view");
                dev.dreamveil.prism.api.visibility.PrismVisibilityVolume visibility = null;
                if (view.has("visibility")) {
                    if (!models || view.has("model_radius") || api.compareTo(PrismApiVersion.V1_24) < 0
                            || !requiredCapabilities.contains(PrismCapability.MODEL_VISIBILITY_VOLUMES)
                            || !requiredCapabilities.contains(PrismCapability.RESIDENT_MODEL_VIEWS)) {
                        throw new PrismPackLoadException("model_visibility_contract", "visibility requires API 1.24, MODEL_VISIBILITY_VOLUMES, RESIDENT_MODEL_VIEWS and captured_models without model_radius", source);
                    }
                    visibility = parseVisibility(view.get("visibility"), source + ":view:visibility");
                }
                int modelRadius = view.has("model_radius") ? strictInteger(view, "model_radius", source) : 0;
                if (view.has("model_radius") && (!models || api.compareTo(PrismApiVersion.V1_23) < 0
                        || !requiredCapabilities.contains(PrismCapability.RESIDENT_MODEL_VIEWS))) {
                    throw new PrismPackLoadException("resident_model_contract", "model_radius requires captured_models, API 1.23 and RESIDENT_MODEL_VIEWS", source);
                }
                if (modelRadius < 0 || modelRadius > dev.dreamveil.prism.api.PrismLimits.LOADER.maxVisibilityRadius()) {
                    throw new PrismPackLoadException("resident_model_radius", "model_radius must be within 0..64 blocks (0 = main capture only)", source);
                }
                if ((view.has("geometry") || view.has("model_domains")) && api.compareTo(PrismApiVersion.V1_22) < 0) {
                    throw new PrismPackLoadException("model_view_api", "geometry/model_domains require API 1.22", source);
                }
                if (models && !requiredCapabilities.contains(PrismCapability.CAPTURED_MODEL_VIEWS)) {
                    throw new PrismPackLoadException("model_view_capability", "captured_models requires CAPTURED_MODEL_VIEWS", source);
                }
                if ((models && (view.has("layers") || view.has("section_radius"))) || (!models && view.has("model_domains"))) {
                    throw new PrismPackLoadException("model_view_scope", "Terrain layers/radius and model_domains belong to different geometry sources", source);
                }
                List<String> modelDomains = models ? (view.has("model_domains") ? textArray(view, "model_domains", source)
                        : List.of("entity", "block_entity")) : List.of();
                if (models && (modelDomains.isEmpty() || modelDomains.size() > 2
                        || new HashSet<>(modelDomains).size() != modelDomains.size()
                        || !Set.of("entity", "block_entity").containsAll(modelDomains))) {
                    throw new PrismPackLoadException("model_view_domains", "Use unique entity/block_entity model domains", source);
                }
                boolean layered = pipeline.has("blend") || view.has("layers") || view.has("color_load")
                        || view.has("depth_load") || view.has("depth_write");
                if (layered && !models && (api.compareTo(PrismApiVersion.V1_21) < 0
                        || !requiredCapabilities.contains(PrismCapability.LAYERED_TERRAIN_VIEWS))) {
                    throw new PrismPackLoadException("layered_view_contract",
                            "Layer/blend/load/depth-write controls require API 1.21 and LAYERED_TERRAIN_VIEWS", source);
                }
                List<String> layers = view.has("layers") ? textArray(view, "layers", source) : List.of("solid", "cutout");
                if (layers.isEmpty() || layers.size() > 3 || new HashSet<>(layers).size() != layers.size()
                        || !Set.of("solid", "cutout", "translucent").containsAll(layers)) {
                    throw new PrismPackLoadException("scene_view_layers", "Use 1..3 unique solid/cutout/translucent layers", source);
                }
                String colorLoad = optionalText(view, "color_load", "clear");
                String depthLoad = optionalText(view, "depth_load", "clear");
                if (!Set.of("clear", "load").contains(colorLoad) || !Set.of("clear", "load").contains(depthLoad)) {
                    throw new PrismPackLoadException("scene_view_load", "Attachment operations must be clear or load", source);
                }
                boolean depthWrite = !view.has("depth_write") || booleanJson(view, "depth_write");
                String viewBlend = optionalText(pipeline, "blend", "opaque");
                // Replay quads have no auxiliary-camera back-to-front sort. Do not imply that
                // alpha blending is correct; expose nearest-surface or additive/OIT data instead.
                if (!Set.of("opaque", "additive").contains(viewBlend)) {
                    throw new PrismPackLoadException("scene_view_blend", "Use opaque or additive; auxiliary alpha sorting is not provided", source);
                }
                String depth = text(view, "depth");
                PrismPackTextureDefinition target = resource(resources, depth);
                if (target == null || !target.descriptor().format().hasDepthAspect() || target.history() || target.scene()) {
                    throw new PrismPackLoadException("scene_view_depth", "view.depth must name a creator-owned transient depth target", source);
                }
                String direction = optionalText(view, "depth_direction", "reversed");
                if (!Set.of("reversed", "forward").contains(direction)) {
                    throw new PrismPackLoadException("scene_view_depth_direction", "Use reversed or forward depth_direction", source);
                }
                if (depthLoad.equals("load")) {
                    var previousView = pipelines.stream().filter(p -> p.isSceneView() && p.sceneView().depth().equals(depth))
                            .reduce((first, second) -> second).orElse(null);
                    if (previousView != null && previousView.sceneView().reversedDepth() != direction.equals("reversed")) {
                        throw new PrismPackLoadException("scene_view_loaded_depth_direction",
                                "Loaded depth must use the preceding view's depth direction", source);
                    }
                }
                int radius = view.has("section_radius") ? strictInteger(view, "section_radius", source) : 4;
                if (radius < 1 || radius > 12) {
                    throw new PrismPackLoadException("scene_view_radius", "section_radius must be within 1..12", source);
                }
                String atlas = optionalText(view, "atlas_sampler", models ? "PrismModelAlbedo" : "PrismTerrainAtlas");
                if (!atlas.matches("[A-Za-z_][A-Za-z0-9_]*") || Set.of("Globals", "PrismFrame").contains(atlas)) {
                    throw new PrismPackLoadException("scene_view_atlas_sampler", "Invalid or reserved atlas sampler name", source);
                }
                List<String> outputs = pipeline.has("outputs") ? textArray(pipeline, "outputs", source) : List.of();
                if (outputs.size() > 4 || new HashSet<>(outputs).size() != outputs.size()) {
                    throw new PrismPackLoadException("scene_view_outputs", "scene_view supports 0..4 unique color outputs", source);
                }
                if (outputs.isEmpty() && (colorLoad.equals("load") || !viewBlend.equals("opaque"))) {
                    throw new PrismPackLoadException("scene_view_color_state", "Color load/blend requires a color output", source);
                }
                for (String color : outputs) {
                    validateColorOutput(color, declaredResources, resources, source);
                    var colorTarget = resource(resources, color);
                    if (colorTarget == null || colorTarget.history() || colorTarget.scene()
                            || !colorTarget.descriptor().extent().equals(target.descriptor().extent())) {
                        throw new PrismPackLoadException("scene_view_extent",
                                "Replay color targets must be transient creator resources with the depth target's extent", source);
                    }
                }
                if (outputs.size() > 1 && !requiredCapabilities.contains(PrismCapability.MULTIPLE_RENDER_TARGETS)) {
                    throw new PrismPackLoadException("scene_view_mrt", "Multiple colors require MULTIPLE_RENDER_TARGETS", source);
                }
                boolean frameUniforms = pipeline.has("frame_uniforms") && booleanJson(pipeline, "frame_uniforms");
                Set<String> writes = new HashSet<>(outputs);
                writes.add(depth);
                Set<String> viewBindingNames = new HashSet<>();
                registerBindingName(viewBindingNames, atlas, frameUniforms);
                registerBindingName(viewBindingNames, "Globals", frameUniforms);
                List<PrismSamplerBinding> samplers = parseSamplers(pipeline, source, declaredSamplerResources,
                        resources, writes, frameUniforms, viewBindingNames);
                pipelines.add(new PrismPipelineDefinition(pipelineId, type, "",
                        safeRelativeFile(normalizedRoot, text(pipeline, "vertex"), source + ":vertex"),
                        safeRelativeFile(normalizedRoot, text(pipeline, "fragment"), source + ":fragment"),
                        outputs.isEmpty() ? "" : outputs.getFirst(), viewBlend, frameUniforms, samplers, "",
                        outputs.size() < 2 ? List.of() : outputs.subList(1, outputs.size()),
                        List.of(), List.of(), null,
                        new PrismSceneViewDefinition(depth, direction.equals("reversed"), radius, atlas,
                                view.has("cull") && booleanJson(view, "cull"), layers,
                                colorLoad.equals("load"), depthLoad.equals("load"), depthWrite, geometry, modelDomains, modelRadius, visibility), List.of()));
                continue;
            }

            if (type.equals("scene")) {
                if (pipeline.has("compute") || pipeline.has("blend") || pipeline.has("frame_uniforms")
                        || pipeline.has("samplers") || pipeline.has("storage_images")
                        || pipeline.has("storage_buffers") || pipeline.has("dispatch")) {
                    throw new PrismPackLoadException(
                            "scene_pipeline_state",
                            "Scene pipelines inherit Minecraft's blend/bind-group state and cannot declare fullscreen/compute fields",
                            source);
                }
                String vertexText = text(pipeline, "vertex");
                String fragmentText = text(pipeline, "fragment");
                String domainText = text(pipeline, "domain").toLowerCase(Locale.ROOT).replace('-', '_');
                PrismSceneDomain domain = PrismSceneDomain.parse(domainText, source + ":domain");
                String vertex;
                if (PrismPipelineDefinition.INHERIT_VERTEX_SHADER.equals(vertexText)) {
                    if (!PrismSceneExecutionSupport.isDynamicFeature(domain)) {
                        throw new PrismPackLoadException(
                                "scene_vertex_inherit_domain",
                                "vertex '$inherit' is only valid for dynamic Feature Rendering domains "
                                        + "(entity_opaque, entity_translucent, block_entity)",
                                source + ":vertex");
                    }
                    vertex = PrismPipelineDefinition.INHERIT_VERTEX_SHADER;
                } else {
                    vertex = safeRelativeFile(normalizedRoot, vertexText, source + ":vertex");
                }
                String fragment = safeRelativeFile(normalizedRoot, fragmentText, source + ":fragment");
                if (!sceneDomains.add(domain)) {
                    throw new PrismPackLoadException(
                            "scene_domain_duplicate",
                            "Only one scene pipeline may target domain '" + domain.manifestName() + "'",
                            source);
                }
                if (pipeline.has("output") && pipeline.has("outputs")) {
                    throw new PrismPackLoadException(
                            "scene_outputs_conflict", "Use output or outputs, not both", source);
                }
                List<String> outputs = pipeline.has("outputs")
                        ? textArray(pipeline, "outputs", source)
                        : List.of(optionalText(pipeline, "output", PrismPackResources.MAIN_COLOR));
                if (outputs.isEmpty() || outputs.size() > 4
                        || new HashSet<>(outputs).size() != outputs.size()) {
                    throw new PrismPackLoadException(
                            "scene_outputs", "Scene outputs must contain 1..4 unique texture ids", source);
                }
                if (!PrismPackResources.MAIN_COLOR.equals(outputs.getFirst())) {
                    throw new PrismPackLoadException(
                            "scene_output_zero",
                            "Scene output location 0 must be minecraft:main_color so Minecraft keeps its presentation target",
                            source);
                }
                for (String output : outputs) validateColorOutput(output, declaredResources, resources, source);
                validateMatchingOutputExtents(outputs, resources, source);
                for (int outputIndex = 1; outputIndex < outputs.size(); outputIndex++) {
                    PrismPackTextureDefinition resource = resource(resources, outputs.get(outputIndex));
                    if (resource == null || !resource.scene()) {
                        throw new PrismPackLoadException(
                                "scene_output_lifetime",
                                "Additional scene output '" + outputs.get(outputIndex)
                                        + "' must declare lifetime 'scene'",
                                source);
                    }
                }
                if (outputs.size() > 1) {
                    if (api.compareTo(PrismApiVersion.V1_16) < 0) {
                        throw new PrismPackLoadException(
                                "scene_mrt_api_version", "Scene MRT requires prism_api 1.16 or newer", source);
                    }
                    if (!requiredCapabilities.contains(PrismCapability.SCENE_GBUFFER_ATTACHMENTS)) {
                        throw new PrismPackLoadException(
                                "scene_mrt_capability_required",
                                "Scene MRT requires SCENE_GBUFFER_ATTACHMENTS in requires[]", source);
                    }
                }
                pipelines.add(new PrismPipelineDefinition(
                        pipelineId, type, domain.manifestName(), vertex, fragment,
                        outputs.getFirst(), "inherit", false, List.of(), "",
                        outputs.size() == 1 ? List.of() : List.copyOf(outputs.subList(1, outputs.size())),
                        List.of(), List.of(), null));
                continue;
            }

            if (type.equals("compute")) {
                if (api.compareTo(PrismApiVersion.V1_15) < 0) {
                    throw new PrismPackLoadException(
                            "compute_api_version", "Compute programs require prism_api 1.15 or newer", source);
                }
                if (!requiredCapabilities.contains(PrismCapability.COMPUTE_PIPELINES)) {
                    throw new PrismPackLoadException(
                            "compute_capability_required", "Compute programs require COMPUTE_PIPELINES in requires[]", source);
                }
                if (pipeline.has("vertex") || pipeline.has("fragment") || pipeline.has("domain")
                        || pipeline.has("output") || pipeline.has("outputs") || pipeline.has("blend")) {
                    throw new PrismPackLoadException(
                            "compute_pipeline_state", "Compute programs use compute/dispatch/descriptors and cannot declare graphics fields", source);
                }
                String compute = safeRelativeFile(normalizedRoot, text(pipeline, "compute"), source + ":compute");
                boolean frameUniforms = pipeline.has("frame_uniforms") && booleanJson(pipeline, "frame_uniforms");
                Set<String> bindingNames = new HashSet<>();
                List<PrismSamplerBinding> samplers = parseSamplers(
                        pipeline, source, declaredSamplerResources, resources, Set.of(), frameUniforms, bindingNames);
                List<PrismStorageBinding> storageImages = parseStorageBindings(
                        pipeline, "storage_images", source, declaredResources, resources,
                        frameUniforms, bindingNames, true);
                for (PrismStorageBinding storage : storageImages) {
                    PrismPackTextureDefinition texture = resource(resources, storage.resource());
                    if (texture != null && texture.scene() && storage.writes()) {
                        throw new PrismPackLoadException(
                                "scene_resource_compute_write",
                                "Scene-lifetime resource '" + storage.resource()
                                        + "' is produced only by scene programs; compute may sample/read it but cannot write it",
                                source);
                    }
                }
                List<PrismStorageBinding> storageBuffers = parseStorageBindings(
                        pipeline, "storage_buffers", source, declaredBuffers, resources,
                        frameUniforms, bindingNames, false);
                if (!storageImages.isEmpty()
                        && !requiredCapabilities.contains(PrismCapability.STORAGE_IMAGES)) {
                    throw new PrismPackLoadException(
                            "storage_images_capability_required", "storage_images require STORAGE_IMAGES in requires[]", source);
                }
                if (!storageBuffers.isEmpty()
                        && !requiredCapabilities.contains(PrismCapability.STORAGE_BUFFERS)) {
                    throw new PrismPackLoadException(
                            "storage_buffers_capability_required", "storage_buffers require STORAGE_BUFFERS in requires[]", source);
                }
                for (PrismStorageBinding storage : storageImages) {
                    if (!storage.writes() || storage.previousHistory()) continue;
                    for (PrismSamplerBinding sampler : samplers) {
                        if (sampler.resource().equals(storage.resource()) && !sampler.previousHistory()) {
                            throw new PrismPackLoadException(
                                    "compute_texture_feedback",
                                    "Compute program cannot sample and storage-write the same current texture '"
                                            + storage.resource() + "'",
                                    source);
                        }
                    }
                }
                PrismComputeDispatch dispatch = parseComputeDispatch(
                        pipeline, source, declaredResources, storageImages);
                PrismPipelineDefinition definition = new PrismPipelineDefinition(
                        pipelineId, type, "", "", "", "", "opaque", frameUniforms,
                        samplers, compute, List.of(), storageImages, storageBuffers, dispatch);
                validateUniqueProgramResources(definition, source);
                PrismResourceBudget.validatePipeline(definition);
                pipelines.add(definition);
                continue;
            }

            String vertexText = text(pipeline, "vertex");
            String fragmentText = text(pipeline, "fragment");
            if (pipeline.has("compute") || pipeline.has("storage_images")
                    || pipeline.has("storage_buffers") || pipeline.has("dispatch")) {
                throw new PrismPackLoadException(
                        "fullscreen_compute_state", "Fullscreen programs cannot declare compute/storage/dispatch fields", source);
            }

            if (PrismPipelineDefinition.INHERIT_VERTEX_SHADER.equals(vertexText)) {
                throw new PrismPackLoadException(
                        "vertex_inherit_fullscreen",
                        "vertex '$inherit' is only valid for dynamic scene Feature Rendering programs",
                        source + ":vertex");
            }
            String vertex = safeRelativeFile(normalizedRoot, vertexText, source + ":vertex");
            String fragment = safeRelativeFile(normalizedRoot, fragmentText, source + ":fragment");

            if (pipeline.has("domain")) {
                throw new PrismPackLoadException(
                        "fullscreen_domain",
                        "Fullscreen pipelines must not declare a scene domain",
                        source);
            }

            if (pipeline.has("output") && pipeline.has("outputs")) {
                throw new PrismPackLoadException("pipeline_outputs_conflict", "Use output or outputs, not both", source);
            }
            List<String> outputs = pipeline.has("outputs")
                    ? textArray(pipeline, "outputs", source)
                    : List.of(optionalText(pipeline, "output", PrismPackResources.MAIN_COLOR));
            if (outputs.isEmpty() || outputs.size() > 4 || new HashSet<>(outputs).size() != outputs.size()) {
                throw new PrismPackLoadException(
                        "pipeline_outputs", "Fullscreen outputs must contain 1..4 unique texture ids", source);
            }
            for (String output : outputs) validateColorOutput(output, declaredResources, resources, source);
            for (String output : outputs) {
                PrismPackTextureDefinition texture = resource(resources, output);
                if (texture != null && texture.scene()) {
                    throw new PrismPackLoadException(
                            "scene_resource_post_write",
                            "Scene-lifetime resource '" + output
                                    + "' is produced only by scene programs and cannot be a fullscreen output",
                            source);
                }
            }
            validateMatchingOutputExtents(outputs, resources, source);
            if (outputs.size() > 1) {
                if (api.compareTo(PrismApiVersion.V1_15) < 0) {
                    throw new PrismPackLoadException("mrt_api_version", "Multiple outputs require prism_api 1.15 or newer", source);
                }
                if (!requiredCapabilities.contains(PrismCapability.MULTIPLE_RENDER_TARGETS)) {
                    throw new PrismPackLoadException(
                            "mrt_capability_required", "Multiple outputs require MULTIPLE_RENDER_TARGETS in requires[]", source);
                }
            }
            String output = outputs.getFirst();
            List<String> additionalOutputs = outputs.size() == 1
                    ? List.of() : List.copyOf(outputs.subList(1, outputs.size()));

            String blend = optionalText(pipeline, "blend", "opaque").toLowerCase(Locale.ROOT);
            if (!Set.of("opaque", "alpha", "additive").contains(blend)) {
                throw new PrismPackLoadException(
                        "pipeline_blend",
                        "Unsupported blend mode '" + blend + "' (opaque, alpha, additive)",
                        source);
            }

            if (outputs.stream().anyMatch(candidate -> !candidate.equals(PrismPackResources.MAIN_COLOR))
                    && !blend.equals("opaque")) {
                throw new PrismPackLoadException(
                        "pipeline_transient_blend",
                        "Pack-owned transient outputs must use opaque blending because their initial contents are undefined",
                        source);
            }

            boolean frameUniforms = pipeline.has("frame_uniforms")
                    && booleanJson(pipeline, "frame_uniforms");

            List<PrismSamplerBinding> samplers = parseSamplers(
                    pipeline, source, declaredSamplerResources, resources,
                    Set.copyOf(outputs), frameUniforms, new HashSet<>());

            PrismPipelineDefinition definition = new PrismPipelineDefinition(
                    pipelineId, type, "", vertex, fragment, output, blend, frameUniforms,
                    samplers, "", additionalOutputs, List.of(), List.of(), null);
            validateUniqueProgramResources(definition, source);
            PrismResourceBudget.validatePipeline(definition);
            pipelines.add(definition);
        }

        validateSceneOutputTopology(pipelines, resources);

        for (int i = 0; i < pipelines.size(); i++) {
            JsonObject entry = pipelinesJson.get(i).getAsJsonObject();
            if (!entry.has("after")) continue;
            if (api.compareTo(PrismApiVersion.V1_20) < 0
                    || !requiredCapabilities.contains(PrismCapability.EXPLICIT_PASS_DEPENDENCIES)) {
                throw new PrismPackLoadException("after_contract", "after requires API 1.20 and EXPLICIT_PASS_DEPENDENCIES", "prism.json");
            }
            List<String> after = textArray(entry, "after", "prism.json:after");
            PrismPipelineDefinition definition = pipelines.get(i);
            if (definition.isScene() || new HashSet<>(after).size() != after.size()) {
                throw new PrismPackLoadException("after_invalid", "after must be unique and belongs only to graph programs", "prism.json");
            }
            for (String dependency : after) {
                if (dependency.equals(definition.id()) || pipelines.stream().noneMatch(p -> p.id().equals(dependency) && !p.isScene())) {
                    throw new PrismPackLoadException("after_unknown", "Invalid graph dependency '" + dependency + "'", "prism.json");
                }
            }
            pipelines.set(i, definition.withAfter(after));
        }
        if (pipelines.stream().filter(PrismPipelineDefinition::isSceneView).count() > dev.dreamveil.prism.api.PrismLimits.LOADER.maxSceneViews()) {
            throw new PrismPackLoadException("scene_view_limit", "At most four terrain views per pack are supported", "prism.json");
        }
        boolean usesSceneHzb = pipelines.stream()
                .flatMap(program -> program.samplers().stream())
                .anyMatch(binding -> PrismPackResources.SCENE_HIERARCHICAL_DEPTH.equals(binding.resource()));
        boolean usesModelMotion = pipelines.stream().flatMap(p -> p.samplers().stream())
                .anyMatch(b -> PrismPackResources.MODEL_MOTION.equals(b.resource()));
        if (usesModelMotion && (api.compareTo(PrismApiVersion.V1_19) < 0
                || !requiredCapabilities.contains(PrismCapability.MODEL_DEFORMATION_MOTION))) {
            throw new PrismPackLoadException("model_motion_contract",
                    "minecraft:model_motion requires API 1.19 and MODEL_DEFORMATION_MOTION", "prism.json");
        }
        if (usesSceneHzb && api.compareTo(PrismApiVersion.V1_18) < 0) {
            throw new PrismPackLoadException(
                    "scene_hzb_api_version",
                    "minecraft:scene_hzb requires prism_api 1.18 or newer",
                    "prism.json:programs");
        }
        if (usesSceneHzb
                && !requiredCapabilities.contains(PrismCapability.SCENE_HIERARCHICAL_DEPTH)) {
            throw new PrismPackLoadException(
                    "scene_hzb_capability_required",
                    "minecraft:scene_hzb requires SCENE_HIERARCHICAL_DEPTH in requires[]",
                    "prism.json:requires");
        }

        // Normalize once at load time so the renderer never dispatches by legacy file/program names.
        PrismProgramSet.from(pipelines);

        resources = applyStorageTextureUsages(resources, pipelines);
        return new PrismPackDefinition(
                normalizedRoot, id, name, version, author, description, api,
                requiredCapabilities, settings, resources, buffers, textureAssets,
                dynamicResolution, sceneJitter, vanillaReplacements, pipelines);
    }

    static List<PrismPackTextureDefinition> applyStorageTextureUsages(
            List<PrismPackTextureDefinition> resources,
            List<PrismPipelineDefinition> pipelines) {
        Set<String> storageIds = new HashSet<>();
        pipelines.stream().flatMap(pipeline -> pipeline.storageImages().stream())
                .map(PrismStorageBinding::resource).forEach(storageIds::add);
        if (storageIds.isEmpty()) return resources;
        List<PrismPackTextureDefinition> result = new ArrayList<>(resources.size());
        for (PrismPackTextureDefinition resource : resources) {
            if (!storageIds.contains(resource.id())) {
                result.add(resource);
                continue;
            }
            var usages = java.util.EnumSet.copyOf(resource.descriptor().usages());
            usages.add(PrismTextureUsage.STORAGE);
            PrismTextureDesc descriptor = new PrismTextureDesc(
                    resource.descriptor().extent(), resource.descriptor().format(), usages,
                    resource.descriptor().mipLevels());
            result.add(new PrismPackTextureDefinition(resource.id(), descriptor, resource.lifetime()));
        }
        return List.copyOf(result);
    }

    private static List<String> textArray(JsonObject object, String key, String source)
            throws PrismPackLoadException {
        JsonArray array = array(object, key);
        List<String> result = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement value = array.get(i);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || value.getAsString().isBlank()) {
                throw new PrismPackLoadException(
                        "manifest_field", "Field '" + key + "' must contain non-blank strings", source);
            }
            result.add(value.getAsString().trim());
        }
        return List.copyOf(result);
    }

    private static void validateColorOutput(
            String output,
            Set<String> declaredResources,
            List<PrismPackTextureDefinition> resources,
            String source) throws PrismPackLoadException {
        if (output.equals(PrismPackResources.MAIN_DEPTH)) {
            throw new PrismPackLoadException(
                    "pipeline_output_depth", "Color pipelines cannot write the host depth buffer", source);
        }
        if (!output.equals(PrismPackResources.MAIN_COLOR) && !declaredResources.contains(output)) {
            throw new PrismPackLoadException(
                    "pipeline_output_unknown",
                    "Pipeline output must be minecraft:main_color or a texture declared in resources[]: '"
                            + output + "'",
                    source);
        }
        PrismPackTextureDefinition resource = resources.stream()
                .filter(candidate -> candidate.id().equals(output)).findFirst().orElse(null);
        if (resource != null && !resource.descriptor().format().isColor()) {
            throw new PrismPackLoadException(
                    "pipeline_output_depth", "Pipeline output must use a color texture", source);
        }
    }

    private static void validateMatchingOutputExtents(
            List<String> outputs,
            List<PrismPackTextureDefinition> resources,
            String source) throws PrismPackLoadException {
        PrismTextureExtent expected = outputExtent(outputs.getFirst(), resources);
        for (int i = 1; i < outputs.size(); i++) {
            PrismTextureExtent actual = outputExtent(outputs.get(i), resources);
            if (!expected.equals(actual)) {
                throw new PrismPackLoadException(
                        "pipeline_output_extent",
                        "Every MRT output must resolve to the same extent; '" + outputs.getFirst()
                                + "' and '" + outputs.get(i) + "' differ",
                        source);
            }
        }
    }

    private static PrismTextureExtent outputExtent(
            String output,
            List<PrismPackTextureDefinition> resources) {
        if (output.equals(PrismPackResources.MAIN_COLOR)) return PrismTextureExtent.relative(1.0);
        return resources.stream().filter(resource -> resource.id().equals(output))
                .findFirst().orElseThrow().descriptor().extent();
    }

    private static List<PrismSamplerBinding> parseSamplers(
            JsonObject pipeline,
            String source,
            Set<String> declaredSamplerResources,
            List<PrismPackTextureDefinition> resources,
            Set<String> currentOutputs,
            boolean frameUniforms,
            Set<String> bindingNames) throws PrismPackLoadException {
        List<PrismSamplerBinding> samplers = new ArrayList<>();
        JsonArray samplerArray = optionalArray(pipeline, "samplers");
        for (int samplerIndex = 0; samplerIndex < samplerArray.size(); samplerIndex++) {
            JsonElement samplerElement = samplerArray.get(samplerIndex);
            String samplerSource = source + ":samplers[" + samplerIndex + "]";
            if (!samplerElement.isJsonObject()) {
                throw new PrismPackLoadException("sampler_type", "Sampler entry must be a JSON object", samplerSource);
            }
            JsonObject sampler = samplerElement.getAsJsonObject();
            rejectUnknownFields(sampler, Set.of("name", "resource", "filter", "wrap", "history"), samplerSource);
            String samplerName = text(sampler, "name");
            if (!registerBindingName(bindingNames, samplerName, frameUniforms)) {
                throw new PrismPackLoadException(
                        "sampler_name", "Invalid, reserved, duplicated, or macro-colliding GLSL sampler name '"
                                + samplerName + "'", samplerSource);
            }
            String resource = text(sampler, "resource");
            if (!resource.equals(PrismPackResources.MAIN_DEPTH)
                    && !resource.equals(PrismPackResources.MAIN_COLOR)
                    && !resource.equals(PrismPackResources.SCENE_HIERARCHICAL_DEPTH)
                    && !resource.equals(PrismPackResources.MODEL_MOTION)
                    && !declaredSamplerResources.contains(resource)) {
                throw new PrismPackLoadException(
                        "sampler_resource_unknown",
                        "Sampler resource must be a host resource or a declared texture: '" + resource + "'",
                        samplerSource);
            }
            PrismPackTextureDefinition samplerResource = resources.stream()
                    .filter(candidate -> candidate.id().equals(resource)).findFirst().orElse(null);
            String history = optionalText(sampler, "history", "current").toLowerCase(Locale.ROOT);
            if (!Set.of("current", "previous").contains(history)) {
                throw new PrismPackLoadException(
                        "sampler_history", "Unsupported history selector '" + history + "' (current, previous)", samplerSource);
            }
            if (sampler.has("history") && (samplerResource == null || !samplerResource.history())) {
                throw new PrismPackLoadException(
                        "sampler_history_resource",
                        "Sampler history selection is valid only for a resource with lifetime 'history'",
                        samplerSource);
            }
            boolean previousHistory = history.equals("previous");
            if (previousHistory && !frameUniforms) {
                throw new PrismPackLoadException(
                        "sampler_history_frame_uniforms",
                        "A pass sampling previous history must enable frame_uniforms", samplerSource);
            }
            if (currentOutputs.contains(resource) && !previousHistory) {
                throw new PrismPackLoadException(
                        "sampler_feedback", "A pass cannot sample its current output resource '" + resource + "'", samplerSource);
            }
            String defaultFilter = resource.equals(PrismPackResources.MAIN_DEPTH)
                    || resource.equals(PrismPackResources.SCENE_HIERARCHICAL_DEPTH)
                    || resource.equals(PrismPackResources.MODEL_MOTION)
                    || (samplerResource != null && samplerResource.descriptor().format().hasDepthAspect())
                    ? "nearest" : "linear";
            String filter = optionalText(sampler, "filter", defaultFilter).toLowerCase(Locale.ROOT);
            if (!Set.of("nearest", "linear").contains(filter)) {
                throw new PrismPackLoadException(
                        "sampler_filter", "Unsupported sampler filter '" + filter + "' (nearest, linear)", samplerSource);
            }
            String wrap = optionalText(sampler, "wrap", "clamp").toLowerCase(Locale.ROOT);
            if (!Set.of("clamp", "repeat").contains(wrap)) {
                throw new PrismPackLoadException(
                        "sampler_wrap", "Unsupported sampler wrap mode '" + wrap + "' (clamp, repeat)", samplerSource);
            }
            samplers.add(new PrismSamplerBinding(samplerName, resource, filter, wrap, history));
        }
        return List.copyOf(samplers);
    }

    private static List<PrismStorageBinding> parseStorageBindings(
            JsonObject pipeline,
            String key,
            String source,
            Set<String> available,
            List<PrismPackTextureDefinition> textures,
            boolean frameUniforms,
            Set<String> bindingNames,
            boolean image) throws PrismPackLoadException {
        JsonArray array = optionalArray(pipeline, key);
        List<PrismStorageBinding> result = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String bindingSource = source + ':' + key + '[' + i + ']';
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                throw new PrismPackLoadException("storage_binding_type", "Storage binding must be an object", bindingSource);
            }
            JsonObject object = element.getAsJsonObject();
            rejectUnknownFields(object, image
                    ? Set.of("name", "resource", "access", "history")
                    : Set.of("name", "resource", "access"), bindingSource);
            String name = text(object, "name");
            if (!registerBindingName(bindingNames, name, frameUniforms)) {
                throw new PrismPackLoadException(
                        "storage_binding_name", "Invalid, reserved, duplicated, or macro-colliding storage binding name '"
                                + name + "'", bindingSource);
            }
            String resource = text(object, "resource");
            if (!available.contains(resource)) {
                throw new PrismPackLoadException(
                        "storage_resource_unknown", "Unknown " + (image ? "storage image" : "storage buffer")
                                + " resource '" + resource + "'", bindingSource);
            }
            String access = optionalText(object, "access", "read_write").toLowerCase(Locale.ROOT);
            if (image && textures.stream().anyMatch(t -> t.id().equals(resource) && t.descriptor().format().hasDepthAspect())) {
                throw new PrismPackLoadException("storage_depth_format", "Depth targets are sampled textures, not storage images", bindingSource);
            }
            if (!Set.of("read", "write", "read_write").contains(access)) {
                throw new PrismPackLoadException(
                        "storage_access", "Unsupported storage access '" + access + "'", bindingSource);
            }
            String history = image ? optionalText(object, "history", "current").toLowerCase(Locale.ROOT) : "current";
            if (!Set.of("current", "previous").contains(history)) {
                throw new PrismPackLoadException(
                        "storage_history", "Unsupported storage history selector '" + history + "'", bindingSource);
            }
            if (image && object.has("history")) {
                PrismPackTextureDefinition texture = textures.stream()
                        .filter(candidate -> candidate.id().equals(resource)).findFirst().orElse(null);
                if (texture == null || !texture.history()) {
                    throw new PrismPackLoadException(
                            "storage_history_resource", "Storage history requires a history texture", bindingSource);
                }
                if ("previous".equals(history) && !"read".equals(access)) {
                    throw new PrismPackLoadException(
                            "storage_history_write", "Previous history is immutable and may only be read", bindingSource);
                }
                if ("previous".equals(history) && !frameUniforms) {
                    throw new PrismPackLoadException(
                            "storage_history_frame_uniforms", "Previous history requires frame_uniforms", bindingSource);
                }
            }
            result.add(new PrismStorageBinding(name, resource, access, history));
        }
        return List.copyOf(result);
    }

    private static PrismComputeDispatch parseComputeDispatch(
            JsonObject pipeline,
            String source,
            Set<String> declaredTextures,
            List<PrismStorageBinding> storageImages) throws PrismPackLoadException {
        JsonElement value = pipeline.get("dispatch");
        if (value == null || !value.isJsonObject()) {
            throw new PrismPackLoadException("compute_dispatch", "Compute program requires a dispatch object", source);
        }
        JsonObject object = value.getAsJsonObject();
        String dispatchSource = source + ":dispatch";
        rejectUnknownFields(object,
                Set.of("resource", "local_size_x", "local_size_y", "local_size_z", "groups_x", "groups_y", "groups_z"),
                dispatchSource);
        boolean byResource = object.has("resource");
        if (byResource) {
            if (object.has("groups_x") || object.has("groups_y") || object.has("groups_z")) {
                throw new PrismPackLoadException(
                        "compute_dispatch", "Resource-driven dispatch cannot declare explicit groups", dispatchSource);
            }
            String resource = text(object, "resource");
            if (!declaredTextures.contains(resource)
                    || storageImages.stream().noneMatch(binding -> binding.resource().equals(resource))) {
                throw new PrismPackLoadException(
                        "compute_dispatch_resource", "Dispatch resource must be a bound storage image", dispatchSource);
            }
            int x = object.has("local_size_x") ? strictInteger(object, "local_size_x", dispatchSource) : 8;
            int y = object.has("local_size_y") ? strictInteger(object, "local_size_y", dispatchSource) : 8;
            int z = object.has("local_size_z") ? strictInteger(object, "local_size_z", dispatchSource) : 1;
            validateWorkgroup(x, y, z, dispatchSource);
            return new PrismComputeDispatch(resource, x, y, z, 0, 0, 0);
        }
        if (object.has("local_size_x") || object.has("local_size_y") || object.has("local_size_z")) {
            throw new PrismPackLoadException(
                    "compute_dispatch", "Explicit dispatch groups cannot declare local sizes", dispatchSource);
        }
        int x = strictInteger(object, "groups_x", dispatchSource);
        int y = object.has("groups_y") ? strictInteger(object, "groups_y", dispatchSource) : 1;
        int z = object.has("groups_z") ? strictInteger(object, "groups_z", dispatchSource) : 1;
        if (x < 1 || y < 1 || z < 1 || x > 65535 || y > 65535 || z > 65535) {
            throw new PrismPackLoadException(
                    "compute_dispatch_groups", "Dispatch group counts must be within 1..65535", dispatchSource);
        }
        return new PrismComputeDispatch("", 0, 0, 0, x, y, z);
    }

    private static void validateWorkgroup(int x, int y, int z, String source) throws PrismPackLoadException {
        if (x < 1 || y < 1 || z < 1 || x > 1024 || y > 1024 || z > 64
                || (long) x * y * z > 1024L) {
            throw new PrismPackLoadException(
                    "compute_local_size", "Portable compute local size must be positive, z <= 64 and total <= 1024", source);
        }
    }

    /** Names become PRISM_BINDING_* macros in compute shaders, so case-only aliases must collide. */
    private static boolean registerBindingName(
            Set<String> bindingNames,
            String candidate,
            boolean frameUniforms) {
        if (!BINDING_PATTERN.matcher(candidate).matches()) return false;
        String macro = bindingMacro(candidate);
        if (frameUniforms && (PrismPackFrameUniforms.BINDING_NAME.equals(candidate)
                || "FRAME".equals(macro))) return false;
        for (String existing : bindingNames) {
            if (bindingMacro(existing).equals(macro)) return false;
        }
        return bindingNames.add(candidate);
    }

    private static String bindingMacro(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    /** A graph pass carries one combined access declaration per concrete resource view. */
    private static void validateUniqueProgramResources(
            PrismPipelineDefinition definition,
            String source) throws PrismPackLoadException {
        Set<String> references = new HashSet<>();
        for (String output : definition.outputs()) {
            registerProgramResource(references, PrismPackResources.toInternal(output), definition, source);
        }
        for (PrismSamplerBinding sampler : definition.samplers()) {
            registerProgramResource(references,
                    PrismPackResources.toInternal(sampler.resource(), sampler.previousHistory()), definition, source);
        }
        for (PrismStorageBinding storage : definition.storageImages()) {
            registerProgramResource(references,
                    PrismPackResources.toInternal(storage.resource(), storage.previousHistory()), definition, source);
        }
        for (PrismStorageBinding storage : definition.storageBuffers()) {
            registerProgramResource(references,
                    PrismPackResources.toInternal(storage.resource()), definition, source);
        }
    }

    private static void registerProgramResource(
            Set<String> references,
            String resource,
            PrismPipelineDefinition definition,
            String source) throws PrismPackLoadException {
        if (!references.add(resource)) {
            throw new PrismPackLoadException(
                    "pipeline_resource_alias",
                    "Program '" + definition.id() + "' binds resource view '" + resource
                            + "' more than once; combine it into one descriptor/access declaration",
                    source);
        }
    }

    private static List<PrismCapability> parseRequiredCapabilities(JsonObject root) throws PrismPackLoadException {
        JsonArray array = optionalArray(root, "requires");
        if (array.size() > 64) {
            throw new PrismPackLoadException("capability_limit", "requires may contain at most 64 capabilities", "prism.json:requires");
        }
        List<PrismCapability> result = new ArrayList<>();
        Set<PrismCapability> seen = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            String source = "prism.json:requires[" + i + "]";
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new PrismPackLoadException("capability_type", "Required capability must be a string", source);
            }
            String raw = element.getAsString().trim();
            PrismCapability capability;
            try {
                capability = PrismCapability.valueOf(raw.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException exception) {
                throw new PrismPackLoadException(
                        "capability_unknown",
                        "Unknown Prism capability '" + raw + "'",
                        source,
                        exception);
            }
            if (seen.add(capability)) result.add(capability);
        }
        return List.copyOf(result);
    }

    private static List<PrismVanillaRenderFeature> parseVanillaReplacements(JsonObject root)
            throws PrismPackLoadException {
        JsonArray array = optionalArray(root, "vanilla_replacements");
        if (array.size() > PrismVanillaRenderFeature.values().length) {
            throw new PrismPackLoadException(
                    "vanilla_replacement_limit",
                    "vanilla_replacements may contain at most "
                            + PrismVanillaRenderFeature.values().length + " entries",
                    "prism.json:vanilla_replacements");
        }
        List<PrismVanillaRenderFeature> result = new ArrayList<>();
        Set<PrismVanillaRenderFeature> seen = new HashSet<>();
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            String source = "prism.json:vanilla_replacements[" + index + "]";
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new PrismPackLoadException(
                        "vanilla_replacement_type",
                        "Vanilla replacement must be a string",
                        source);
            }
            final PrismVanillaRenderFeature feature;
            try {
                feature = PrismVanillaRenderFeature.parse(element.getAsString());
            } catch (IllegalArgumentException exception) {
                throw new PrismPackLoadException(
                        "vanilla_replacement_unknown", exception.getMessage(), source, exception);
            }
            if (!seen.add(feature)) {
                throw new PrismPackLoadException(
                        "vanilla_replacement_duplicate",
                        "Duplicate vanilla replacement '" + feature.manifestName() + "'",
                        source);
            }
            result.add(feature);
        }
        return List.copyOf(result);
    }

    private static List<PrismPackTextureDefinition> parseResources(JsonObject root) throws PrismPackLoadException {
        JsonArray array = optionalArray(root, "resources");
        if (array.size() > MAX_RESOURCES) {
            throw new PrismPackLoadException("resources_limit", "A Prism pack may declare at most " + MAX_RESOURCES + " textures", "prism.json:resources");
        }
        List<PrismPackTextureDefinition> resources = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String source = "prism.json:resources[" + i + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) throw new PrismPackLoadException("resource_type", "Resource entry must be an object", source);
            JsonObject object = element.getAsJsonObject();
            rejectUnknownFields(object, Set.of("id", "format", "scale", "scale_x", "scale_y", "width", "height", "mip_levels", "lifetime", "dynamic"), source);
            String id = text(object, "id");
            if (!RESOURCE_ID_PATTERN.matcher(id).matches()) {
                throw new PrismPackLoadException("resource_id", "Invalid resource id '" + id + "' (expected namespace:path)", source);
            }
            if (id.equals(PrismPackResources.MAIN_COLOR) || id.equals(PrismPackResources.MAIN_DEPTH) || !ids.add(id)) {
                throw new PrismPackLoadException("resource_duplicate", "Resource id is reserved or duplicated: '" + id + "'", source);
            }
            final PrismTextureFormat format;
            try {
                format = PrismTextureFormat.valueOf(text(object, "format").trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException exception) {
                throw new PrismPackLoadException("resource_format", "Unsupported texture format '" + text(object, "format") + "'", source, exception);
            }
            boolean hasScale = object.has("scale") || object.has("scale_x") || object.has("scale_y");
            boolean hasAbsolute = object.has("width") || object.has("height");
            boolean dynamic = object.has("dynamic") && booleanJson(object, "dynamic");
            if (hasScale && hasAbsolute) throw new PrismPackLoadException("resource_extent", "Use relative scale or absolute width/height, not both", source);
            if (dynamic && hasAbsolute) throw new PrismPackLoadException("resource_extent", "Dynamic resources must use a main-target-relative extent", source);
            PrismTextureExtent extent;
            if (hasAbsolute) {
                if (!object.has("width") || !object.has("height")) throw new PrismPackLoadException("resource_extent", "Absolute texture extent requires width and height", source);
                int width = strictInteger(object, "width", source);
                int height = strictInteger(object, "height", source);
                if (width < 1 || height < 1 || width > 16384 || height > 16384) throw new PrismPackLoadException("resource_extent", "Texture dimensions must be within 1..16384", source);
                extent = PrismTextureExtent.absolute(width, height);
            } else {
                double scale = object.has("scale") ? number(object, "scale", source) : 1.0;
                double sx = object.has("scale_x") ? number(object, "scale_x", source) : scale;
                double sy = object.has("scale_y") ? number(object, "scale_y", source) : scale;
                if (!(sx > 0.0 && sy > 0.0 && sx <= 2.0 && sy <= 2.0)) throw new PrismPackLoadException("resource_extent", "Relative scales must be > 0 and <= 2", source);
                extent = dynamic
                        ? PrismTextureExtent.dynamicRelative(sx, sy)
                        : PrismTextureExtent.relative(sx, sy);
            }
            int mipLevels = object.has("mip_levels") ? strictInteger(object, "mip_levels", source) : 1;
            if (mipLevels < 1 || mipLevels > 16) throw new PrismPackLoadException("resource_mips", "mip_levels must be within 1..16", source);
            if (!format.isColor() && (format.hasStencilAspect() || mipLevels != 1)) {
                throw new PrismPackLoadException(
                        "resource_depth_pack_unsupported",
                        "Creator depth targets currently support D16_UNORM/D32_FLOAT, mip_levels 1; stencil remains gated",
                        source);
            }
            String lifetimeText = optionalText(object, "lifetime", "transient").toLowerCase(Locale.ROOT);
            PrismPackTextureLifetime lifetime = switch (lifetimeText) {
                case "transient" -> PrismPackTextureLifetime.TRANSIENT;
                case "history" -> PrismPackTextureLifetime.HISTORY;
                case "scene" -> PrismPackTextureLifetime.SCENE;
                default -> throw new PrismPackLoadException(
                        "resource_lifetime",
                        "Unsupported resource lifetime '" + lifetimeText + "' (transient, history, scene)",
                        source);
            };
            if (lifetime == PrismPackTextureLifetime.SCENE
                    && (extent.mode() != PrismTextureExtent.Mode.RELATIVE_TO_MAIN_TARGET
                            || Double.compare(extent.scaleX(), 1.0) != 0
                            || Double.compare(extent.scaleY(), 1.0) != 0
                            || mipLevels != 1)) {
                throw new PrismPackLoadException(
                        "scene_resource_extent",
                        "Scene-lifetime attachments must use scale 1.0, mip_levels 1 and cannot use dynamic or absolute extents",
                        source);
            }
            if (!format.isColor() && lifetime != PrismPackTextureLifetime.TRANSIENT) {
                throw new PrismPackLoadException("depth_lifetime", "Depth targets currently require transient lifetime", source);
            }
            PrismTextureDesc descriptor = format.isColor() ? PrismTextureDesc.colorAttachment(extent, format)
                    : PrismTextureDesc.depthAttachment(extent, format);
            if (mipLevels != 1) descriptor = new PrismTextureDesc(descriptor.extent(), descriptor.format(), descriptor.usages(), mipLevels);
            resources.add(new PrismPackTextureDefinition(id, descriptor, lifetime));
        }
        return List.copyOf(resources);
    }

    private static PrismPackTextureDefinition resource(
            List<PrismPackTextureDefinition> resources,
            String id) {
        return resources.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
    }

    /** Opaque terrain solid/cutout share one Minecraft render pass and therefore one MRT layout. */
    private static void validateSceneOutputTopology(
            List<PrismPipelineDefinition> pipelines,
            List<PrismPackTextureDefinition> resources) throws PrismPackLoadException {
        java.util.Map<PrismSceneDomain, List<String>> outputsByDomain = new java.util.EnumMap<>(PrismSceneDomain.class);
        Set<String> producedSceneResources = new HashSet<>();
        for (PrismPipelineDefinition pipeline : pipelines) {
            if (!pipeline.isScene()) continue;
            PrismSceneDomain domain = pipeline.sceneDomain();
            outputsByDomain.put(domain, pipeline.outputs());
            pipeline.outputs().stream().skip(1).forEach(producedSceneResources::add);
        }

        List<String> solid = outputsByDomain.get(PrismSceneDomain.TERRAIN_SOLID);
        List<String> cutout = outputsByDomain.get(PrismSceneDomain.TERRAIN_CUTOUT);
        boolean opaqueTerrainUsesMrt = (solid != null && solid.size() > 1)
                || (cutout != null && cutout.size() > 1);
        if (opaqueTerrainUsesMrt && (solid == null || cutout == null || !solid.equals(cutout))) {
            throw new PrismPackLoadException(
                    "scene_opaque_group_outputs",
                    "terrain_opaque and terrain_cutout share one Minecraft render pass; when either uses scene MRT, both programs must exist and declare identical outputs",
                    "prism.json:programs");
        }

        for (PrismPackTextureDefinition texture : resources) {
            if (texture.scene() && !producedSceneResources.contains(texture.id())) {
                throw new PrismPackLoadException(
                        "scene_resource_unwritten",
                        "Scene-lifetime resource '" + texture.id()
                                + "' is never written by a scene program",
                        "prism.json:resources");
            }
        }
    }

    private static List<PrismPackBufferDefinition> parseBuffers(
            JsonObject root,
            List<PrismPackTextureDefinition> textures) throws PrismPackLoadException {
        JsonArray array = optionalArray(root, "buffers");
        if (array.size() > MAX_BUFFERS) {
            throw new PrismPackLoadException(
                    "buffers_limit", "A Prism pack may declare at most " + MAX_BUFFERS + " buffers",
                    "prism.json:buffers");
        }
        Set<String> ids = new HashSet<>();
        for (PrismPackTextureDefinition texture : textures) ids.add(texture.id());
        List<PrismPackBufferDefinition> buffers = new ArrayList<>();
        long totalBytes = 0L;
        for (int i = 0; i < array.size(); i++) {
            String source = "prism.json:buffers[" + i + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                throw new PrismPackLoadException("buffer_type", "Buffer entry must be an object", source);
            }
            JsonObject object = element.getAsJsonObject();
            rejectUnknownFields(object, Set.of("id", "size_bytes", "copy_src", "copy_dst", "indirect"), source);
            String id = text(object, "id");
            if (!RESOURCE_ID_PATTERN.matcher(id).matches() || !ids.add(id)) {
                throw new PrismPackLoadException("buffer_id", "Invalid or duplicated buffer id '" + id + "'", source);
            }
            long sizeBytes = strictLong(object, "size_bytes", source);
            if (sizeBytes < 16L || sizeBytes > MAX_BUFFER_BYTES) {
                throw new PrismPackLoadException(
                        "buffer_size", "Storage buffer size must be within 16.." + MAX_BUFFER_BYTES + " bytes", source);
            }
            totalBytes = Math.addExact(totalBytes, sizeBytes);
            if (totalBytes > MAX_TOTAL_BUFFER_BYTES) {
                throw new PrismPackLoadException(
                        "buffers_total_size", "Pack storage buffers exceed " + MAX_TOTAL_BUFFER_BYTES + " bytes", source);
            }
            java.util.EnumSet<PrismBufferUsage> usages = java.util.EnumSet.of(PrismBufferUsage.STORAGE);
            if (!object.has("copy_src") || booleanJson(object, "copy_src")) usages.add(PrismBufferUsage.COPY_SRC);
            if (!object.has("copy_dst") || booleanJson(object, "copy_dst")) usages.add(PrismBufferUsage.COPY_DST);
            if (object.has("indirect") && booleanJson(object, "indirect")) usages.add(PrismBufferUsage.INDIRECT);
            buffers.add(new PrismPackBufferDefinition(id, new PrismBufferDesc(sizeBytes, usages)));
        }
        return List.copyOf(buffers);
    }

    private static PrismDynamicResolutionDefinition parseDynamicResolution(JsonObject root)
            throws PrismPackLoadException {
        if (!root.has("dynamic_resolution")) return PrismDynamicResolutionDefinition.DISABLED;
        JsonElement element = root.get("dynamic_resolution");
        if (!element.isJsonObject()) {
            throw new PrismPackLoadException(
                    "dynamic_resolution_type", "dynamic_resolution must be an object",
                    "prism.json:dynamic_resolution");
        }
        JsonObject object = element.getAsJsonObject();
        String source = "prism.json:dynamic_resolution";
        rejectUnknownFields(object, Set.of("enabled", "min_scale", "max_scale", "target_ms", "evaluation_frames"), source);
        boolean enabled = !object.has("enabled") || booleanJson(object, "enabled");
        double min = object.has("min_scale") ? number(object, "min_scale", source) : 0.5;
        double max = object.has("max_scale") ? number(object, "max_scale", source) : 1.0;
        double target = object.has("target_ms") ? number(object, "target_ms", source) : 16.667;
        int frames = object.has("evaluation_frames") ? strictInteger(object, "evaluation_frames", source) : 30;
        try {
            return new PrismDynamicResolutionDefinition(enabled, min, max, target, frames);
        } catch (IllegalArgumentException exception) {
            throw new PrismPackLoadException("dynamic_resolution_value", exception.getMessage(), source, exception);
        }
    }

    private static List<PrismPackTextureAssetDefinition> parseTextureAssets(
            JsonObject root,
            Path packRoot,
            List<PrismPackTextureDefinition> resources,
            List<PrismPackBufferDefinition> buffers) throws PrismPackLoadException {
        JsonArray array = optionalArray(root, "textures");
        if (array.size() > MAX_TEXTURE_ASSETS) {
            throw new PrismPackLoadException(
                    "texture_assets_limit",
                    "A Prism pack may declare at most " + MAX_TEXTURE_ASSETS + " texture assets",
                    "prism.json:textures");
        }

        Set<String> ids = new HashSet<>();
        for (PrismPackTextureDefinition resource : resources) ids.add(resource.id());
        for (PrismPackBufferDefinition buffer : buffers) ids.add(buffer.id());
        List<PrismPackTextureAssetDefinition> textures = new ArrayList<>();
        long decodedBytes = 0L;
        for (int i = 0; i < array.size(); i++) {
            String source = "prism.json:textures[" + i + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                throw new PrismPackLoadException(
                        "texture_asset_type", "Texture asset entry must be an object", source);
            }
            JsonObject object = element.getAsJsonObject();
            rejectUnknownFields(object, Set.of("id", "source", "faces", "layers", "dimension", "color_space", "generate_mips"), source);
            String id = text(object, "id");
            if (!RESOURCE_ID_PATTERN.matcher(id).matches()) {
                throw new PrismPackLoadException(
                        "texture_asset_id", "Invalid texture asset id '" + id + "'", source);
            }
            if (id.equals(PrismPackResources.MAIN_COLOR)
                    || id.equals(PrismPackResources.MAIN_DEPTH)
                    || !ids.add(id)) {
                throw new PrismPackLoadException(
                        "texture_asset_duplicate",
                        "Texture asset id is reserved or duplicates another texture resource: '" + id + "'",
                        source);
            }
            String dimension = optionalText(object, "dimension", "2d").toLowerCase(Locale.ROOT);
            if (!Set.of("2d", "cube", "2d_array", "3d").contains(dimension)) {
                throw new PrismPackLoadException(
                        "texture_asset_dimension",
                        "Unsupported texture dimension '" + dimension + "' (2d, cube, 2d_array, 3d)", source);
            }
            List<String> sourceFiles = new ArrayList<>();
            if ("2d".equals(dimension)) {
                if (!object.has("source") || object.has("faces") || object.has("layers")) {
                    throw new PrismPackLoadException(
                            "texture_asset_source", "2D textures require source and must not declare faces/layers", source);
                }
                sourceFiles.add(portablePng(packRoot, text(object, "source"), source + ":source"));
            } else if ("cube".equals(dimension)) {
                if (object.has("source") || object.has("layers")
                        || !object.has("faces") || !object.get("faces").isJsonObject()) {
                    throw new PrismPackLoadException(
                            "texture_asset_faces", "Cubemaps require a faces object and must not declare source/layers", source);
                }
                JsonObject faces = object.getAsJsonObject("faces");
                List<String> names = List.of("positive_x", "negative_x", "positive_y", "negative_y", "positive_z", "negative_z");
                rejectUnknownFields(faces, Set.copyOf(names), source + ":faces");
                for (String face : names) {
                    sourceFiles.add(portablePng(packRoot, text(faces, face), source + ":faces:" + face));
                }
            } else {
                if (object.has("source") || object.has("faces")
                        || !object.has("layers") || !object.get("layers").isJsonArray()) {
                    throw new PrismPackLoadException(
                            "texture_asset_layers",
                            "2D array and 3D textures require layers[] and must not declare source/faces",
                            source);
                }
                JsonArray layers = object.getAsJsonArray("layers");
                if (layers.size() < 2 || layers.size() > 256) {
                    throw new PrismPackLoadException(
                            "texture_asset_layer_count", "Texture layers must contain 2..256 PNG slices", source);
                }
                for (int layer = 0; layer < layers.size(); layer++) {
                    JsonElement path = layers.get(layer);
                    if (!path.isJsonPrimitive() || !path.getAsJsonPrimitive().isString()) {
                        throw new PrismPackLoadException(
                                "texture_asset_layer_path", "Texture layer paths must be strings", source + ":layers[" + layer + "]");
                    }
                    sourceFiles.add(portablePng(
                            packRoot, path.getAsString(), source + ":layers[" + layer + "]"));
                }
            }
            String colorSpace = optionalText(object, "color_space", "linear").toLowerCase(Locale.ROOT);
            if (!Set.of("linear", "srgb").contains(colorSpace)) {
                throw new PrismPackLoadException(
                        "texture_asset_color_space",
                        "Unsupported texture color_space '" + colorSpace + "' (linear, srgb)",
                        source);
            }
            TextureDimensions dimensions = null;
            for (String sourceFile : sourceFiles) {
                TextureDimensions faceDimensions = validatePngHeader(packRoot.resolve(sourceFile), sourceFile);
                if (dimensions == null) dimensions = faceDimensions;
                else if (dimensions.width() != faceDimensions.width()
                        || dimensions.height() != faceDimensions.height()) {
                    throw new PrismPackLoadException(
                            "texture_asset_slice_dimensions",
                            "Every cubemap face/array layer/volume slice must have identical dimensions", source);
                }
            }
            boolean generateMips = object.has("generate_mips") && booleanJson(object, "generate_mips");
            int largestDimension = "3d".equals(dimension)
                    ? Math.max(Math.max(dimensions.width(), dimensions.height()), sourceFiles.size())
                    : Math.max(dimensions.width(), dimensions.height());
            int mipLevels = generateMips
                    ? 32 - Integer.numberOfLeadingZeros(largestDimension)
                    : 1;
            try {
                decodedBytes = Math.addExact(decodedBytes, decodedMipBytes(
                        dimensions.width(), dimensions.height(), mipLevels, sourceFiles.size()));
            } catch (ArithmeticException exception) {
                throw new PrismPackLoadException(
                        "texture_assets_decoded_limit", "Pack texture mip allocation overflows its byte budget",
                        "prism.json:textures", exception);
            }
            if (decodedBytes > MAX_TEXTURE_ASSET_DECODED_BYTES) {
                throw new PrismPackLoadException(
                        "texture_assets_decoded_limit",
                        "Pack texture assets exceed the " + MAX_TEXTURE_ASSET_DECODED_BYTES
                                + " byte decoded RGBA mip budget",
                        "prism.json:textures");
            }
            textures.add(new PrismPackTextureAssetDefinition(
                    id, sourceFiles, dimension, colorSpace,
                    dimensions.width(), dimensions.height(), mipLevels));
        }
        return List.copyOf(textures);
    }

    private static String portablePng(Path packRoot, String path, String source)
            throws PrismPackLoadException {
        String relative = safeTextureFile(packRoot, path, source);
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new PrismPackLoadException(
                    "texture_asset_format", "Pack texture assets must use portable PNG files", source);
        }
        return relative;
    }

    private static TextureDimensions validatePngHeader(Path path, String source)
            throws PrismPackLoadException {
        try {
            long encodedBytes = Files.size(path);
            if (encodedBytes < 24L || encodedBytes > MAX_TEXTURE_ASSET_BYTES) {
                throw new PrismPackLoadException(
                        "texture_asset_size",
                        "PNG texture asset must be 24.." + MAX_TEXTURE_ASSET_BYTES + " bytes",
                        source);
            }
            byte[] header;
            try (InputStream input = Files.newInputStream(path)) {
                header = input.readNBytes(24);
            }
            if (header.length != 24) {
                throw new PrismPackLoadException(
                        "texture_asset_header", "PNG texture header is truncated", source);
            }
            for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                if (header[i] != PNG_SIGNATURE[i]) {
                    throw new PrismPackLoadException(
                            "texture_asset_header", "Texture asset is not a PNG file", source);
                }
            }
            if (header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
                throw new PrismPackLoadException(
                        "texture_asset_header", "PNG texture is missing its leading IHDR chunk", source);
            }
            ByteBuffer dimensions = ByteBuffer.wrap(header, 16, 8);
            int width = dimensions.getInt();
            int height = dimensions.getInt();
            if (width < 1 || height < 1
                    || width > MAX_TEXTURE_ASSET_DIMENSION
                    || height > MAX_TEXTURE_ASSET_DIMENSION) {
                throw new PrismPackLoadException(
                        "texture_asset_dimensions",
                        "PNG texture dimensions must be within 1.." + MAX_TEXTURE_ASSET_DIMENSION
                                + "; received " + width + "x" + height,
                        source);
            }
            return new TextureDimensions(width, height);
        } catch (PrismPackLoadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new PrismPackLoadException(
                    "texture_asset_read",
                    "Could not inspect PNG texture asset: " + exception.getMessage(),
                    source,
                    exception);
        }
    }

    private static List<PrismPackSettingDefinition> parseSettings(JsonObject root) throws PrismPackLoadException {
        JsonArray array = optionalArray(root, "settings");
        if (array.size() > MAX_SETTINGS) {
            throw new PrismPackLoadException(
                    "settings_limit",
                    "A Prism pack may declare at most " + MAX_SETTINGS + " settings",
                    "prism.json:settings");
        }

        List<PrismPackSettingDefinition> settings = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> macros = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String source = "prism.json:settings[" + i + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                throw new PrismPackLoadException("setting_type", "Setting entry must be a JSON object", source);
            }
            JsonObject object = element.getAsJsonObject();
            rejectUnknownFields(
                    object,
                    Set.of("id", "label", "type", "default", "description", "min", "max", "step", "values"),
                    source);

            String id = identifier(text(object, "id"), "setting id", source);
            if (!ids.add(id)) {
                throw new PrismPackLoadException("setting_duplicate", "Duplicate setting id '" + id + "'", source);
            }
            String label = text(object, "label");
            String description = optionalText(object, "description", "");
            PrismPackSettingType type = settingType(text(object, "type"), source);

            PrismPackSettingDefinition definition = switch (type) {
                case BOOLEAN -> new PrismPackSettingDefinition(
                        id, label, type, booleanJson(object, "default") ? "true" : "false",
                        description, Double.NaN, Double.NaN, Double.NaN, List.of());
                case INTEGER -> numericIntegerSetting(object, id, label, description, source);
                case FLOAT -> numericFloatSetting(object, id, label, description, source);
                case ENUM -> enumSetting(object, id, label, description, source);
            };

            String macro = definition.macroName();
            if (!macros.add(macro)) {
                throw new PrismPackLoadException(
                        "setting_macro_collision",
                        "Setting id '" + id + "' collides with another setting macro '" + macro + "'",
                        source);
            }
            settings.add(definition);
        }
        return List.copyOf(settings);
    }

    private static PrismPackSettingDefinition numericIntegerSetting(
            JsonObject object,
            String id,
            String label,
            String description,
            String source) throws PrismPackLoadException {
        int min = strictInteger(object, "min", source);
        int max = strictInteger(object, "max", source);
        int step = strictInteger(object, "step", source);
        int defaultValue = strictInteger(object, "default", source);
        if (min > max || step <= 0) {
            throw new PrismPackLoadException("setting_range", "Integer setting requires min <= max and step > 0", source);
        }
        PrismPackSettingDefinition provisional = new PrismPackSettingDefinition(
                id, label, PrismPackSettingType.INTEGER, Integer.toString(defaultValue), description,
                min, max, step, List.of());
        String normalized = PrismPackSettingValues.normalize(provisional, Integer.toString(defaultValue));
        return new PrismPackSettingDefinition(
                id, label, PrismPackSettingType.INTEGER, normalized, description, min, max, step, List.of());
    }

    private static PrismPackSettingDefinition numericFloatSetting(
            JsonObject object,
            String id,
            String label,
            String description,
            String source) throws PrismPackLoadException {
        double min = number(object, "min", source);
        double max = number(object, "max", source);
        double step = number(object, "step", source);
        double defaultValue = number(object, "default", source);
        if (min > max || step <= 0.0) {
            throw new PrismPackLoadException("setting_range", "Float setting requires min <= max and step > 0", source);
        }
        PrismPackSettingDefinition provisional = new PrismPackSettingDefinition(
                id, label, PrismPackSettingType.FLOAT, PrismPackSettingValues.canonicalFloat(defaultValue), description,
                min, max, step, List.of());
        String normalized = PrismPackSettingValues.normalize(provisional, Double.toString(defaultValue));
        return new PrismPackSettingDefinition(
                id, label, PrismPackSettingType.FLOAT, normalized, description, min, max, step, List.of());
    }

    private static PrismPackSettingDefinition enumSetting(
            JsonObject object,
            String id,
            String label,
            String description,
            String source) throws PrismPackLoadException {
        JsonArray valuesJson = array(object, "values");
        if (valuesJson.size() == 0) {
            throw new PrismPackLoadException("setting_enum_values", "Enum setting must declare values", source);
        }
        List<String> values = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        Set<String> macros = new HashSet<>();
        for (JsonElement element : valuesJson) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new PrismPackLoadException("setting_enum_values", "Enum values must be strings", source);
            }
            String value = element.getAsString().trim();
            if (!ENUM_VALUE_PATTERN.matcher(value).matches()) {
                throw new PrismPackLoadException("setting_enum_value", "Invalid enum value '" + value + "'", source);
            }
            if (!unique.add(value)) {
                throw new PrismPackLoadException("setting_enum_duplicate", "Duplicate enum value '" + value + "'", source);
            }
            String macro = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
            if (!macros.add(macro)) {
                throw new PrismPackLoadException("setting_enum_macro_collision", "Enum values collide after macro normalization", source);
            }
            values.add(value);
        }
        String defaultValue = text(object, "default");
        if (!values.contains(defaultValue)) {
            throw new PrismPackLoadException(
                    "setting_default",
                    "Default enum value '" + defaultValue + "' is not present in values",
                    source);
        }
        return new PrismPackSettingDefinition(
                id, label, PrismPackSettingType.ENUM, defaultValue, description,
                Double.NaN, Double.NaN, Double.NaN, values);
    }

    private static PrismPackSettingType settingType(String value, String source) throws PrismPackLoadException {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "boolean" -> PrismPackSettingType.BOOLEAN;
            case "integer", "int" -> PrismPackSettingType.INTEGER;
            case "float", "decimal" -> PrismPackSettingType.FLOAT;
            case "enum" -> PrismPackSettingType.ENUM;
            default -> throw new PrismPackLoadException(
                    "setting_kind",
                    "Unsupported setting type '" + value + "' (boolean, integer, float, enum)",
                    source);
        };
    }

    private static void rejectUnknownFields(JsonObject object, Set<String> allowed, String source)
            throws PrismPackLoadException {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw new PrismPackLoadException(
                        "manifest_unknown_field",
                        "Unknown field '" + key + "' for Prism pack schema " + SCHEMA_VERSION,
                        source);
            }
        }
    }

    private static String safeRelativeFile(Path packRoot, String relative, String source) throws PrismPackLoadException {
        if (relative.isBlank() || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0) {
            throw new PrismPackLoadException(
                    "shader_path",
                    "Shader paths must be portable relative paths using forward slashes",
                    source);
        }
        Path normalizedRoot = packRoot.toAbsolutePath().normalize();
        final Path resolved;
        try {
            resolved = normalizedRoot.resolve(relative).normalize();
        } catch (java.nio.file.InvalidPathException exception) {
            throw new PrismPackLoadException("shader_path", "Invalid shader path: " + relative, source, exception);
        }
        PrismPackPathPolicy.requireContainedRegularFile(
                normalizedRoot,
                resolved,
                "shader_path_escape",
                "shader_missing",
                "Shader file '" + relative + "'",
                source);
        return normalizedRoot.relativize(resolved).toString().replace('\\', '/');
    }

    private static String safeTextureFile(Path packRoot, String relative, String source)
            throws PrismPackLoadException {
        if (relative.isBlank() || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0) {
            throw new PrismPackLoadException(
                    "texture_path",
                    "Texture paths must be portable relative paths using forward slashes",
                    source);
        }
        Path normalizedRoot = packRoot.toAbsolutePath().normalize();
        final Path resolved;
        try {
            resolved = normalizedRoot.resolve(relative).normalize();
        } catch (java.nio.file.InvalidPathException exception) {
            throw new PrismPackLoadException(
                    "texture_path", "Invalid texture path: " + relative, source, exception);
        }
        PrismPackPathPolicy.requireContainedRegularFile(
                normalizedRoot,
                resolved,
                "texture_path_escape",
                "texture_missing",
                "Texture file '" + relative + "'",
                source);
        return normalizedRoot.relativize(resolved).toString().replace('\\', '/');
    }

    private record TextureDimensions(int width, int height) {}

    private static long decodedMipBytes(int width, int height, int levels, int layers) {
        long total = 0L;
        for (int level = 0; level < levels; level++) {
            total = Math.addExact(total, Math.multiplyExact((long) width * height * 4L, layers));
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
        return total;
    }

    private static String identifier(String value, String field, String source) throws PrismPackLoadException {
        if (!ID_PATTERN.matcher(value).matches()) {
            throw new PrismPackLoadException("invalid_id", "Invalid " + field + " '" + value + "'", source);
        }
        return value;
    }

    private static String text(JsonObject object, String key) throws PrismPackLoadException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new PrismPackLoadException("manifest_field", "Missing or invalid string field '" + key + "'", "prism.json");
        }
        String text = value.getAsString().trim();
        if (text.isEmpty()) {
            throw new PrismPackLoadException("manifest_field", "Field '" + key + "' must not be blank", "prism.json");
        }
        return text;
    }

    private static String optionalText(JsonObject object, String key, String defaultValue) throws PrismPackLoadException {
        if (!object.has(key)) {
            return defaultValue;
        }
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new PrismPackLoadException("manifest_field", "Invalid string field '" + key + "'", "prism.json");
        }
        String text = value.getAsString().trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private static int integer(JsonObject object, String key) throws PrismPackLoadException {
        return strictInteger(object, key, "prism.json");
    }

    private static int strictInteger(JsonObject object, String key, String source) throws PrismPackLoadException {
        JsonElement value = object.get(key);
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException();
            }
            double number = value.getAsDouble();
            if (!Double.isFinite(number) || number != Math.rint(number) || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                throw new IllegalArgumentException();
            }
            return (int) number;
        } catch (RuntimeException exception) {
            throw new PrismPackLoadException("manifest_field", "Missing or invalid integer field '" + key + "'", source, exception);
        }
    }

    private static long strictLong(JsonObject object, String key, String source) throws PrismPackLoadException {
        JsonElement value = object.get(key);
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException();
            }
            java.math.BigDecimal number = value.getAsBigDecimal();
            return number.longValueExact();
        } catch (RuntimeException exception) {
            throw new PrismPackLoadException(
                    "manifest_field", "Missing or invalid integer field '" + key + "'", source, exception);
        }
    }

    private static double number(JsonObject object, String key, String source) throws PrismPackLoadException {
        JsonElement value = object.get(key);
        try {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException();
            }
            double number = value.getAsDouble();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException();
            }
            return number;
        } catch (RuntimeException exception) {
            throw new PrismPackLoadException("manifest_field", "Missing or invalid numeric field '" + key + "'", source, exception);
        }
    }

    private static boolean booleanJson(JsonObject object, String key) throws PrismPackLoadException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            throw new PrismPackLoadException("manifest_field", "Missing or invalid boolean field '" + key + "'", "prism.json");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isBoolean()) {
            throw new PrismPackLoadException("manifest_field", "Missing or invalid boolean field '" + key + "'", "prism.json");
        }
        return primitive.getAsBoolean();
    }

    private static JsonArray array(JsonObject object, String key) throws PrismPackLoadException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new PrismPackLoadException("manifest_field", "Missing or invalid array field '" + key + "'", "prism.json");
        }
        return value.getAsJsonArray();
    }

    private static dev.dreamveil.prism.api.visibility.PrismVisibilityVolume parseVisibility(JsonElement element, String source) throws PrismPackLoadException {
        if (!element.isJsonObject()) throw new PrismPackLoadException("model_visibility", "visibility must be an object", source);
        var object = element.getAsJsonObject();
        rejectUnknownFields(object, Set.of("type", "space", "center", "radius", "half_extent"), source);
        String type = optionalText(object, "type", "sphere");
        String space = optionalText(object, "space", "camera_relative");
        if (!Set.of("sphere", "aabb").contains(type) || !Set.of("world", "camera_relative").contains(space)
                || (type.equals("sphere") && object.has("half_extent")) || (type.equals("aabb") && object.has("radius"))) {
            throw new PrismPackLoadException("model_visibility", "Use sphere/radius or aabb/half_extent, in world or camera_relative space", source);
        }
        double[] center = visibilityVector(object.get("center"), source + ":center");
        double[] extent;
        if (type.equals("sphere")) {
            double radius = visibilityNumber(object.get("radius"), source + ":radius");
            extent = new double[]{radius,radius,radius};
        } else extent = visibilityVector(object.get("half_extent"), source + ":half_extent");
        try {
            return new dev.dreamveil.prism.api.visibility.PrismVisibilityVolume(
                    type.equals("sphere") ? dev.dreamveil.prism.api.visibility.PrismVisibilityVolume.Shape.SPHERE
                            : dev.dreamveil.prism.api.visibility.PrismVisibilityVolume.Shape.AABB,
                    space.equals("world") ? dev.dreamveil.prism.api.visibility.PrismVisibilityVolume.Space.WORLD
                            : dev.dreamveil.prism.api.visibility.PrismVisibilityVolume.Space.CAMERA_RELATIVE,
                    center[0],center[1],center[2],extent[0],extent[1],extent[2]);
        } catch (IllegalArgumentException invalid) {
            throw new PrismPackLoadException("model_visibility", invalid.getMessage(), source);
        }
    }
    private static double[] visibilityVector(JsonElement element, String source) throws PrismPackLoadException {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3)
            throw new PrismPackLoadException("model_visibility", "Expected exactly three numeric coordinates", source);
        var array = element.getAsJsonArray();
        return new double[]{visibilityNumber(array.get(0),source),visibilityNumber(array.get(1),source),visibilityNumber(array.get(2),source)};
    }
    private static double visibilityNumber(JsonElement element, String source) throws PrismPackLoadException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
            throw new PrismPackLoadException("model_visibility", "Expected finite number", source);
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) throw new PrismPackLoadException("model_visibility", "Expected finite number", source);
        return value;
    }

    private static JsonArray optionalArray(JsonObject object, String key) throws PrismPackLoadException {
        if (!object.has(key)) {
            return new JsonArray();
        }
        JsonElement value = object.get(key);
        if (!value.isJsonArray()) {
            throw new PrismPackLoadException("manifest_field", "Invalid array field '" + key + "'", "prism.json");
        }
        return value.getAsJsonArray();
    }
}
