package dev.dreamveil.prism.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;

/** API 1.25 intent gate. Validates declarations; never supplies artistic defaults. */
final class PrismAuthorshipContract {
    private PrismAuthorshipContract() {}

    static void validate(JsonObject root) throws PrismPackLoadException {
        Set<String> histories = new HashSet<>();
        each(root, "resources", "prism.json", (resource, source) -> {
            require(resource, source, "format", "lifetime", "mip_levels");
            boolean fixed = resource.has("width") || resource.has("height");
            boolean uniform = resource.has("scale");
            boolean axes = resource.has("scale_x") || resource.has("scale_y");
            if ((fixed ? 1 : 0) + (uniform ? 1 : 0) + (axes ? 1 : 0) != 1) {
                throw missing(source, "Declare exactly one extent: width + height, scale, or scale_x + scale_y");
            }
            if (fixed) require(resource, source, "width", "height");
            if (axes) require(resource, source, "scale_x", "scale_y");
            if ("history".equals(word(resource, "lifetime"))) histories.add(word(resource, "id"));
        });
        each(root, "textures", "prism.json", (texture, source) ->
                require(texture, source, "dimension", "color_space", "generate_mips"));
        if (root.has("dynamic_resolution")) {
            JsonObject dynamic = object(root.get("dynamic_resolution"), "prism.json:dynamic_resolution");
            require(dynamic, "prism.json:dynamic_resolution", "enabled", "min_scale", "max_scale", "target_ms", "evaluation_frames");
        }
        for (String field : new String[] {"programs", "pipelines"}) {
            each(root, field, "prism.json", (pass, source) -> {
                require(pass, source, "type");
                String type = word(pass, "type");
                if (Set.of("fullscreen", "scene_view").contains(type)) require(pass, source, "blend");
                if (Set.of("fullscreen", "scene").contains(type) && !pass.has("output") && !pass.has("outputs")) {
                    throw missing(source, "Declare output or outputs; Prism will not choose a destination");
                }
                each(pass, "samplers", source, (sampler, at) -> {
                    require(sampler, at, "filter", "wrap");
                    if (histories.contains(word(sampler, "resource"))) require(sampler, at, "history");
                });
                for (String storageField : new String[] {"storage_images", "storage_buffers"}) {
                    each(pass, storageField, source, (storage, at) -> {
                        require(storage, at, "access");
                        if (histories.contains(word(storage, "resource"))) require(storage, at, "history");
                    });
                }
                if ("compute".equals(type)) {
                    require(pass, source, "dispatch");
                    JsonObject dispatch = object(pass.get("dispatch"), source + ":dispatch");
                    if (dispatch.has("resource")) require(dispatch, source + ":dispatch", "local_size_x", "local_size_y", "local_size_z");
                    else require(dispatch, source + ":dispatch", "groups_x", "groups_y", "groups_z");
                }
                if ("scene_view".equals(type)) {
                    require(pass, source, "view", "outputs");
                    JsonObject view = object(pass.get("view"), source + ":view");
                    String at = source + ":view";
                    require(view, at, "geometry", "depth_direction", "depth_write", "depth_load", "color_load", "cull", "atlas_sampler");
                    if ("captured_models".equals(word(view, "geometry"))) {
                        require(view, at, "model_domains");
                        if (!view.has("visibility") && !view.has("model_radius")) {
                            throw missing(at, "Declare visibility or model_radius (0 explicitly selects main capture only)");
                        }
                    } else if ("terrain".equals(word(view, "geometry"))) require(view, at, "layers", "section_radius");
                    if (view.has("visibility")) require(object(view.get("visibility"), at + ":visibility"), at + ":visibility", "type", "space");
                }
            });
        }
    }

    private static String word(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString().toLowerCase(java.util.Locale.ROOT) : "";
    }

    private static void require(JsonObject object, String source, String... fields) throws PrismPackLoadException {
        for (String field : fields) {
            if (!object.has(field) || object.get(field).isJsonNull()) {
                throw missing(source + ':' + field, "Declare '" + field + "' explicitly");
            }
        }
    }

    private static PrismPackLoadException missing(String source, String instruction) {
        return new PrismPackLoadException("PRISM_E2101", instruction
                + ". API 1.25 preserves authorship; no visual or execution intent is guessed.", source);
    }

    private static JsonObject object(JsonElement element, String source) throws PrismPackLoadException {
        if (element == null || !element.isJsonObject()) throw missing(source, "Expected an explicit object declaration");
        return element.getAsJsonObject();
    }

    private static void each(JsonObject parent, String field, String source, Check check) throws PrismPackLoadException {
        if (!parent.has(field)) return;
        JsonElement array = parent.get(field);
        if (!array.isJsonArray()) throw missing(source + ':' + field, "Expected an array");
        for (int i = 0; i < array.getAsJsonArray().size(); i++) {
            String at = source + ':' + field + '[' + i + ']';
            check.accept(object(array.getAsJsonArray().get(i), at), at);
        }
    }

    @FunctionalInterface private interface Check {
        void accept(JsonObject object, String source) throws PrismPackLoadException;
    }
}
