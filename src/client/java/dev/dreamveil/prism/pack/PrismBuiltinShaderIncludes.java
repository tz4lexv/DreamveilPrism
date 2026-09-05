package dev.dreamveil.prism.pack;

import java.util.Map;

/** Versioned, engine-owned GLSL contracts available through angle-bracket includes. */
final class PrismBuiltinShaderIncludes {
    static final String FRAME_INCLUDE = "prism/frame.glsl";
    static final String DEPTH_INCLUDE = "prism/depth.glsl";
    static final String TEMPORAL_INCLUDE = "prism/temporal.glsl";
    static final String PBR_INCLUDE = "prism/pbr.glsl";
    static final String FRAME_BLOCK_NAME = "PrismFrame";

    private static final Map<String, String> SOURCES = Map.of(
            "prism/scene_view.glsl", """
                    #ifndef PRISM_SCENE_VIEW_GLSL
                    #define PRISM_SCENE_VIEW_GLSL 1
                    // Native 26.2 terrain input: section-local Position and raw atlas UV0.
                    // Host supplies only vertex origin. The creator owns all view/projection math.
                    layout(std140) uniform Globals {
                        ivec3 CameraBlockPos;
                        vec3 CameraOffset;
                        vec2 ScreenSize;
                        float GlintAlpha;
                        float GameTime;
                        int MenuBlurRadius;
                        int UseRgss;
                    };
                    int prismReplayFloorSection(int value) {
                        return value >= 0 ? value / 16 : -((-value + 15) / 16);
                    }
                    vec3 prismReplayCameraRelative(vec3 position, int instanceIndex) {
                        ivec3 delta = ivec3((instanceIndex & 127) - 64,
                                ((instanceIndex >> 7) & 255) - 128,
                                ((instanceIndex >> 15) & 127) - 64);
                        // Integer arithmetic preserves the block origin in distant worlds.
                        ivec3 cameraSection = ivec3(prismReplayFloorSection(CameraBlockPos.x),
                                prismReplayFloorSection(CameraBlockPos.y), prismReplayFloorSection(CameraBlockPos.z));
                        ivec3 sectionOrigin = (cameraSection + delta) * 16;
                        return position + vec3(sectionOrigin - CameraBlockPos) + CameraOffset;
                    }
                    #endif
                    """,
            "prism/common.glsl", """
                    #ifndef PRISM_COMMON_GLSL
                    #define PRISM_COMMON_GLSL 1
                    float prismSaturate(float value) { return clamp(value, 0.0, 1.0); }
                    vec2 prismSaturate(vec2 value) { return clamp(value, vec2(0.0), vec2(1.0)); }
                    vec3 prismSaturate(vec3 value) { return clamp(value, vec3(0.0), vec3(1.0)); }
                    float prismLuminance(vec3 linearColor) {
                        return dot(linearColor, vec3(0.2126, 0.7152, 0.0722));
                    }
                    vec3 prismSrgbToLinear(vec3 color) {
                        bvec3 cutoff = lessThanEqual(color, vec3(0.04045));
                        vec3 low = color / 12.92;
                        vec3 high = pow((color + 0.055) / 1.055, vec3(2.4));
                        return mix(high, low, cutoff);
                    }
                    vec3 prismLinearToSrgb(vec3 color) {
                        color = max(color, vec3(0.0));
                        bvec3 cutoff = lessThanEqual(color, vec3(0.0031308));
                        vec3 low = color * 12.92;
                        vec3 high = 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055;
                        return mix(high, low, cutoff);
                    }
                    float prismInterleavedGradientNoise(vec2 pixel, float frame) {
                        return fract(52.9829189 * fract(dot(pixel + frame * 0.06711056,
                                vec2(0.06711056, 0.00583715))));
                    }
                    #endif
                    """,
            FRAME_INCLUDE, """
                    #ifndef PRISM_FRAME_GLSL
                    #define PRISM_FRAME_GLSL 1

                    // Raw/raw Vulkan convention: prismUv and depth texture coordinates use the
                    // same orientation; depth is the backend value in [0,1]. Matrices are
                    // camera-relative to preserve precision in distant worlds.
                    #ifdef PRISM_COMPUTE
                    layout(std140, set = 0, binding = PRISM_BINDING_FRAME) uniform PrismFrame {
                    #else
                    layout(std140) uniform PrismFrame {
                    #endif
                        mat4 PrismProjection;
                        mat4 PrismInverseProjection;
                        mat4 PrismViewRotation;
                        mat4 PrismInverseViewRotation;
                        mat4 PrismViewProjection;
                        mat4 PrismInverseViewProjection;
                        mat4 PrismPreviousViewProjection;
                        mat4 PrismPreviousInverseViewProjection;
                        vec4 PrismCameraPositionHigh;
                        vec4 PrismCameraPositionLow;
                        vec4 PrismPreviousCameraPositionHigh;
                        vec4 PrismPreviousCameraPositionLow;
                        vec4 PrismViewport;          // width, height, 1/width, 1/height
                        vec4 PrismDynamicResolution; // scale, 1/scale, scaled width, scaled height
                        vec4 PrismTime;              // frame, game ticks, delta seconds, far plane
                        vec4 PrismSunDirection;      // xyz points from the world toward the sun
                        vec4 PrismMoonDirection;     // xyz points from the world toward the moon
                        vec4 PrismCelestial;         // sun angle, moon angle, day flag, selected |vertical|
                        vec4 PrismTemporalJitter;    // current xy and previous zw, in pixel units
                        ivec4 PrismFlags;            // reversed-Z, history valid, resized, camera jump
                        vec4 PrismFogColor;          // Minecraft linear/display fog RGBA
                        vec4 PrismFogDistances;      // environmental start/end, render start/end
                        vec4 PrismWeather;           // intensity, rain brightness, stars, cloud Y
                        vec4 PrismSkyColor;          // Minecraft sky color RGB, valid flag
                        vec4 PrismCloudColor;        // Minecraft cloud color RGB, valid flag
                        ivec4 PrismEnvironmentFlags; // water, lava, powder snow, atmospheric fog
                    };

                    bool prismDepthIsSky(float rawDepth) {
                        return PrismFlags.x != 0 ? rawDepth <= 0.000001 : rawDepth >= 0.999999;
                    }

                    vec3 prismReconstructCameraRelativePosition(vec2 uv, float rawDepth) {
                        vec4 position = PrismInverseViewProjection
                                * vec4(uv * 2.0 - 1.0, rawDepth, 1.0);
                        return position.xyz / max(abs(position.w), 1.0e-7) * sign(position.w);
                    }

                    vec3 prismCameraPosition() {
                        return PrismCameraPositionHigh.xyz + PrismCameraPositionLow.xyz;
                    }

                    bool prismHistoryValid() { return PrismFlags.y != 0; }
                    bool prismReversedDepth() { return PrismFlags.x != 0; }
                    #endif
                    """,
            DEPTH_INCLUDE, """
                    #ifndef PRISM_DEPTH_GLSL
                    #define PRISM_DEPTH_GLSL 1
                    #ifndef PRISM_FRAME_GLSL
                    #error Include <prism/frame.glsl> before <prism/depth.glsl>
                    #endif

                    vec3 prismCameraRelativeViewRay(vec2 uv) {
                        float farDepth = prismReversedDepth() ? 0.0001 : 0.9999;
                        return normalize(prismReconstructCameraRelativePosition(uv, farDepth));
                    }

                    // Depth-derived normals are intended for GTAO/SSR and other screen-space work.
                    // Use a native G-buffer normal when a future bridge advertises that capability.
                    vec3 prismReconstructCameraRelativeNormal(sampler2D depthTexture, vec2 uv) {
                        vec2 texel = PrismViewport.zw;
                        float centerDepth = texture(depthTexture, uv).r;
                        if (prismDepthIsSky(centerDepth)) return vec3(0.0);

                        vec3 center = prismReconstructCameraRelativePosition(uv, centerDepth);
                        vec2 leftUv = clamp(uv - vec2(texel.x, 0.0), vec2(0.0), vec2(1.0));
                        vec2 rightUv = clamp(uv + vec2(texel.x, 0.0), vec2(0.0), vec2(1.0));
                        vec2 downUv = clamp(uv - vec2(0.0, texel.y), vec2(0.0), vec2(1.0));
                        vec2 upUv = clamp(uv + vec2(0.0, texel.y), vec2(0.0), vec2(1.0));
                        float leftDepth = texture(depthTexture, leftUv).r;
                        float rightDepth = texture(depthTexture, rightUv).r;
                        float downDepth = texture(depthTexture, downUv).r;
                        float upDepth = texture(depthTexture, upUv).r;
                        // A sky sample reconstructs near infinity and would create a false
                        // silhouette normal. Collapse it to the center so the valid one-sided
                        // derivative wins instead.
                        vec3 left = prismDepthIsSky(leftDepth) ? center
                                : prismReconstructCameraRelativePosition(leftUv, leftDepth);
                        vec3 right = prismDepthIsSky(rightDepth) ? center
                                : prismReconstructCameraRelativePosition(rightUv, rightDepth);
                        vec3 down = prismDepthIsSky(downDepth) ? center
                                : prismReconstructCameraRelativePosition(downUv, downDepth);
                        vec3 up = prismDepthIsSky(upDepth) ? center
                                : prismReconstructCameraRelativePosition(upUv, upDepth);

                        vec3 dx = length(right - center) < length(center - left)
                                ? right - center : center - left;
                        vec3 dy = length(up - center) < length(center - down)
                                ? up - center : center - down;
                        vec3 normalUnnormalized = cross(dx, dy);
                        float normalLengthSquared = dot(normalUnnormalized, normalUnnormalized);
                        if (normalLengthSquared <= 1.0e-12) return vec3(0.0);
                        vec3 normal = normalUnnormalized * inversesqrt(normalLengthSquared);
                        return dot(normal, -center) < 0.0 ? -normal : normal;
                    }
                    #endif
                    """,
            TEMPORAL_INCLUDE, """
                    #ifndef PRISM_TEMPORAL_GLSL
                    #define PRISM_TEMPORAL_GLSL 1
                    #ifndef PRISM_FRAME_GLSL
                    #error Include <prism/frame.glsl> before <prism/temporal.glsl>
                    #endif

                    vec3 prismPreviousCameraPosition() {
                        return PrismPreviousCameraPositionHigh.xyz + PrismPreviousCameraPositionLow.xyz;
                    }

                    vec2 prismReprojectPreviousUv(vec3 currentCameraRelativePosition) {
                        vec3 previousCameraRelative = currentCameraRelativePosition
                                + prismCameraPosition() - prismPreviousCameraPosition();
                        vec4 previousClip = PrismPreviousViewProjection
                                * vec4(previousCameraRelative, 1.0);
                        if (previousClip.w <= 1.0e-7) return vec2(-1.0);
                        return previousClip.xy / previousClip.w * 0.5 + 0.5;
                    }

                    vec2 prismReprojectPreviousUv(vec2 uv, float rawDepth) {
                        return prismReprojectPreviousUv(
                                prismReconstructCameraRelativePosition(uv, rawDepth));
                    }

                    bool prismHistoryUvValid(vec2 uv) {
                        return prismHistoryValid()
                                && all(greaterThanEqual(uv, vec2(0.0)))
                                && all(lessThanEqual(uv, vec2(1.0)));
                    }

                    vec2 prismReprojectionMotion(vec2 uv, float rawDepth) {
                        return uv - prismReprojectPreviousUv(uv, rawDepth);
                    }

                    vec2 prismJitterUv(vec2 jitterPixels) {
                        return jitterPixels * PrismViewport.zw;
                    }

                    // Motion is current UV minus previous UV, including the real projection-jitter
                    // delta. Subtract this vector from current UV to sample previous history.
                    vec2 prismCameraMotionVector(vec2 uv, float rawDepth) {
                        if (prismDepthIsSky(rawDepth)) {
                            return prismJitterUv(PrismTemporalJitter.xy - PrismTemporalJitter.zw);
                        }
                        return prismReprojectionMotion(uv, rawDepth);
                    }

                    // API 1.19 model_motion: RG UV velocity, B validity, A coverage.
                    // Uncovered surfaces may use camera/depth motion. Covered invalid surfaces
                    // must reject history, rather than being mistaken for stationary geometry.
                    vec2 prismModelOrCameraMotion(vec4 modelMotion, vec2 uv, float rawDepth) {
                        return modelMotion.a > 0.5 ? modelMotion.rg : prismCameraMotionVector(uv, rawDepth);
                    }
                    bool prismModelMotionHistoryValid(vec4 modelMotion) {
                        return modelMotion.a < 0.5 || modelMotion.b > 0.5;
                    }

                    // Creator vertex deformation: evaluate both positions with their own time,
                    // object transform and camera origin before calling this helper.
                    vec2 prismDeformedMotion(vec4 currentClip, vec4 previousClip) {
                        if (currentClip.w <= 1e-7 || previousClip.w <= 1e-7) return vec2(0.0);
                        return 0.5 * (currentClip.xy / currentClip.w - previousClip.xy / previousClip.w);
                    }

                    float prismReactiveMask(
                            vec3 currentColor,
                            vec3 reprojectedColor,
                            float transparency,
                            float emissiveChange) {
                        float currentLuma = dot(max(currentColor, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
                        float historyLuma = dot(max(reprojectedColor, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
                        float relativeChange = abs(currentLuma - historyLuma)
                                / max(max(currentLuma, historyLuma), 0.05);
                        return clamp(max(max(relativeChange, transparency), emissiveChange), 0.0, 1.0);
                    }

                    vec3 prismRgbToYCoCg(vec3 color) {
                        return vec3(
                                dot(color, vec3(0.25, 0.5, 0.25)),
                                dot(color, vec3(0.5, 0.0, -0.5)),
                                dot(color, vec3(-0.25, 0.5, -0.25)));
                    }

                    vec3 prismYCoCgToRgb(vec3 value) {
                        return vec3(value.x + value.y - value.z,
                                value.x + value.z,
                                value.x - value.y - value.z);
                    }

                    vec3 prismClipHistoryYCoCg(
                            vec3 historyColor,
                            vec3 neighborhoodMin,
                            vec3 neighborhoodMax) {
                        vec3 history = prismRgbToYCoCg(historyColor);
                        vec3 minimum = prismRgbToYCoCg(neighborhoodMin);
                        vec3 maximum = prismRgbToYCoCg(neighborhoodMax);
                        vec3 low = min(minimum, maximum);
                        vec3 high = max(minimum, maximum);
                        return prismYCoCgToRgb(clamp(history, low, high));
                    }

                    float prismTemporalHistoryWeight(float baseWeight, float reactive, vec2 motion) {
                        float velocityRejection = clamp(length(motion * PrismViewport.xy) / 32.0, 0.0, 1.0);
                        return prismHistoryValid()
                                ? clamp(baseWeight * (1.0 - reactive) * (1.0 - velocityRejection), 0.0, 0.99)
                                : 0.0;
                    }

                    vec3 prismClampHistory(vec3 historyColor, vec3 neighborhoodMin, vec3 neighborhoodMax) {
                        return clamp(historyColor, neighborhoodMin, neighborhoodMax);
                    }
                    #endif
                    """,
            PBR_INCLUDE, """
                    #ifndef PRISM_PBR_GLSL
                    #define PRISM_PBR_GLSL 1

                    // Optional reference encoding, never a mandatory runtime material layout.
                    struct PrismMaterial {
                        vec3 baseColor;
                        float opacity;
                        vec3 normal;
                        float roughness;
                        float metallic;
                        float ambientOcclusion;
                        vec3 emissive;
                        uint flags;
                    };

                    vec2 prismEncodeOctahedralNormal(vec3 value) {
                        vec3 normal = value / max(abs(value.x) + abs(value.y) + abs(value.z), 1.0e-7);
                        vec2 encoded = normal.xy;
                        if (normal.z < 0.0) {
                            encoded = (1.0 - abs(encoded.yx)) * sign(encoded.xy);
                        }
                        return encoded * 0.5 + 0.5;
                    }

                    vec3 prismDecodeOctahedralNormal(vec2 value) {
                        vec2 encoded = value * 2.0 - 1.0;
                        vec3 normal = vec3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
                        if (normal.z < 0.0) {
                            normal.xy = (1.0 - abs(normal.yx)) * sign(normal.xy);
                        }
                        return normalize(normal);
                    }

                    // MRT convention:
                    // 0 = linear baseColor.rgb, opacity
                    // 1 = oct normal.xy, perceptual roughness, metallic
                    // 2 = linear emissive.rgb, ambient occlusion
                    // 3 = current-minus-previous UV motion.xy, reactive mask, flags / 255
                    void prismEncodeGBuffer(
                            PrismMaterial material,
                            vec2 motion,
                            float reactive,
                            out vec4 gbuffer0,
                            out vec4 gbuffer1,
                            out vec4 gbuffer2,
                            out vec4 gbuffer3) {
                        gbuffer0 = vec4(max(material.baseColor, vec3(0.0)), clamp(material.opacity, 0.0, 1.0));
                        gbuffer1 = vec4(prismEncodeOctahedralNormal(material.normal),
                                clamp(material.roughness, 0.045, 1.0), clamp(material.metallic, 0.0, 1.0));
                        gbuffer2 = vec4(max(material.emissive, vec3(0.0)),
                                clamp(material.ambientOcclusion, 0.0, 1.0));
                        gbuffer3 = vec4(motion, clamp(reactive, 0.0, 1.0),
                                float(material.flags & 255u) / 255.0);
                    }

                    PrismMaterial prismDecodeGBuffer(vec4 g0, vec4 g1, vec4 g2, vec4 g3) {
                        PrismMaterial material;
                        material.baseColor = max(g0.rgb, vec3(0.0));
                        material.opacity = clamp(g0.a, 0.0, 1.0);
                        material.normal = prismDecodeOctahedralNormal(g1.xy);
                        material.roughness = clamp(g1.z, 0.045, 1.0);
                        material.metallic = clamp(g1.w, 0.0, 1.0);
                        material.emissive = max(g2.rgb, vec3(0.0));
                        material.ambientOcclusion = clamp(g2.a, 0.0, 1.0);
                        material.flags = uint(round(clamp(g3.w, 0.0, 1.0) * 255.0));
                        return material;
                    }
                    #endif
                    """,
            "prism/lighting.glsl", """
                    #ifndef PRISM_LIGHTING_GLSL
                    #define PRISM_LIGHTING_GLSL 1
                    const float PRISM_PI = 3.14159265358979323846;

                    vec3 prismFresnelSchlick(float cosTheta, vec3 f0) {
                        float grazing = pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
                        return f0 + (vec3(1.0) - f0) * grazing;
                    }

                    float prismDistributionGgx(float nDotH, float perceptualRoughness) {
                        float alpha = max(perceptualRoughness * perceptualRoughness, 0.002025);
                        float alpha2 = alpha * alpha;
                        float denominator = nDotH * nDotH * (alpha2 - 1.0) + 1.0;
                        return alpha2 / max(PRISM_PI * denominator * denominator, 1.0e-6);
                    }

                    float prismVisibilitySmithGgxCorrelated(float nDotV, float nDotL, float perceptualRoughness) {
                        float alpha = max(perceptualRoughness * perceptualRoughness, 0.002025);
                        float gv = nDotL * sqrt(max(nDotV * nDotV * (1.0 - alpha * alpha) + alpha * alpha, 1.0e-6));
                        float gl = nDotV * sqrt(max(nDotL * nDotL * (1.0 - alpha * alpha) + alpha * alpha, 1.0e-6));
                        return 0.5 / max(gv + gl, 1.0e-6);
                    }

                    vec3 prismEvaluateDirectGgx(
                            vec3 baseColor,
                            float metallic,
                            float perceptualRoughness,
                            vec3 normal,
                            vec3 viewDirection,
                            vec3 lightDirection,
                            vec3 radiance) {
                        vec3 halfVector = normalize(viewDirection + lightDirection);
                        float nDotV = max(dot(normal, viewDirection), 0.0);
                        float nDotL = max(dot(normal, lightDirection), 0.0);
                        float nDotH = max(dot(normal, halfVector), 0.0);
                        float vDotH = max(dot(viewDirection, halfVector), 0.0);
                        vec3 f0 = mix(vec3(0.04), baseColor, clamp(metallic, 0.0, 1.0));
                        vec3 fresnel = prismFresnelSchlick(vDotH, f0);
                        float distribution = prismDistributionGgx(nDotH, perceptualRoughness);
                        float visibility = prismVisibilitySmithGgxCorrelated(
                                nDotV, nDotL, perceptualRoughness);
                        vec3 specular = fresnel * distribution * visibility;
                        vec3 diffuse = (vec3(1.0) - fresnel)
                                * (1.0 - clamp(metallic, 0.0, 1.0)) * baseColor / PRISM_PI;
                        return (diffuse + specular) * radiance * nDotL;
                    }

                    float prismInverseSquareAttenuation(float distanceSquared, float range) {
                        float safeRange = max(range, 1.0e-3);
                        float normalized = distanceSquared / (safeRange * safeRange);
                        float window = clamp(1.0 - normalized * normalized, 0.0, 1.0);
                        return window * window / max(distanceSquared, 0.01);
                    }

                    vec3 prismTonemapAces(vec3 color) {
                        const mat3 inputTransform = mat3(
                            0.59719, 0.35458, 0.04823,
                            0.07600, 0.90834, 0.01566,
                            0.02840, 0.13383, 0.83777);
                        const mat3 outputTransform = mat3(
                             1.60475, -0.53108, -0.07367,
                            -0.10208,  1.10813, -0.00605,
                            -0.00327, -0.07276,  1.07602);
                        vec3 value = inputTransform * max(color, vec3(0.0));
                        vec3 fitted = (value * (value + 0.0245786) - 0.000090537)
                                / (value * (0.983729 * value + 0.4329510) + 0.238081);
                        return clamp(outputTransform * fitted, vec3(0.0), vec3(1.0));
                    }
                    #endif
                    """,
            "prism/atmosphere.glsl", """
                    #ifndef PRISM_ATMOSPHERE_GLSL
                    #define PRISM_ATMOSPHERE_GLSL 1
                    float prismBeerLambert(float opticalDepth) {
                        return exp(-max(opticalDepth, 0.0));
                    }

                    vec3 prismBeerLambert(vec3 extinction, float distance) {
                        return exp(-max(extinction, vec3(0.0)) * max(distance, 0.0));
                    }

                    float prismRayleighPhase(float cosineAngle) {
                        return 0.0596831037 * (1.0 + cosineAngle * cosineAngle);
                    }

                    float prismHenyeyGreensteinPhase(float cosineAngle, float anisotropy) {
                        float g = clamp(anisotropy, -0.98, 0.98);
                        float g2 = g * g;
                        float denominator = max(1.0 + g2 - 2.0 * g * cosineAngle, 1.0e-4);
                        return (1.0 - g2) / (12.5663706144 * denominator * sqrt(denominator));
                    }

                    vec4 prismIntegrateHomogeneousVolume(
                            vec3 scattering,
                            vec3 extinction,
                            vec3 incidentLight,
                            float distance) {
                        vec3 transmittance = prismBeerLambert(extinction, distance);
                        vec3 integrated = incidentLight * scattering
                                * (vec3(1.0) - transmittance) / max(extinction, vec3(1.0e-5));
                        return vec4(integrated, 1.0 - dot(transmittance, vec3(0.3333333)));
                    }

                    float prismHeightFogOpticalDepth(
                            float cameraHeight,
                            float rayVertical,
                            float distance,
                            float baseDensity,
                            float heightFalloff) {
                        float falloff = max(heightFalloff, 1.0e-5);
                        float startDensity = max(baseDensity, 0.0) * exp(-cameraHeight * falloff);
                        float vertical = rayVertical * distance * falloff;
                        float integral = abs(vertical) < 1.0e-4
                                ? distance
                                : distance * (1.0 - exp(-vertical)) / vertical;
                        return max(startDensity * integral, 0.0);
                    }
                    #endif
                    """,
            "prism/water.glsl", """
                    #ifndef PRISM_WATER_GLSL
                    #define PRISM_WATER_GLSL 1
                    float prismWaterFresnel(float nDotV, float indexOfRefraction) {
                        float eta = max(indexOfRefraction, 1.0001);
                        float f0 = (1.0 - eta) / (1.0 + eta);
                        f0 *= f0;
                        return f0 + (1.0 - f0) * pow(clamp(1.0 - nDotV, 0.0, 1.0), 5.0);
                    }

                    vec3 prismWaterTransmittance(vec3 absorption, float thickness) {
                        return exp(-max(absorption, vec3(0.0)) * max(thickness, 0.0));
                    }

                    vec2 prismWaterRefractionUv(
                            vec2 uv,
                            vec3 viewNormal,
                            float thickness,
                            vec2 inverseViewport,
                            float strength) {
                        float boundedThickness = min(max(thickness, 0.0), 64.0);
                        return uv + viewNormal.xy * inverseViewport
                                * strength * (1.0 + sqrt(boundedThickness));
                    }

                    vec3 prismWaterComposite(
                            vec3 reflected,
                            vec3 transmitted,
                            vec3 absorption,
                            float thickness,
                            float fresnel) {
                        vec3 attenuated = transmitted * prismWaterTransmittance(absorption, thickness);
                        return mix(attenuated, reflected, clamp(fresnel, 0.0, 1.0));
                    }
                    #endif
                    """);

    private PrismBuiltinShaderIncludes() {}

    static String require(String id, String source) throws PrismPackLoadException {
        if (id.equals("prism/screen.glsl")) return """
                #ifndef PRISM_SCREEN_GLSL
                #define PRISM_SCREEN_GLSL
                layout(location=0) in vec2 prismFrontendUv;
                vec2 prismUV() { return prismFrontendUv; }
                #endif
                """;
        if (id.equals("prism/compute.glsl")) return """
                #ifndef PRISM_COMPUTE_GLSL
                #define PRISM_COMPUTE_GLSL
                ivec2 prismPixel() { return ivec2(gl_GlobalInvocationID.xy); }
                #define prismSize(resource) imageSize(resource)
                #define prismInside(resource) all(lessThan(prismPixel(), imageSize(resource)))
                #define prismUV(resource) ((vec2(prismPixel()) + 0.5) / vec2(imageSize(resource)))
                #endif
                """;
        String value = SOURCES.get(id);
        if (value == null) {
            throw new PrismPackLoadException(
                    "include_builtin_missing",
                    "Unknown Prism built-in shader include <" + id + ">",
                    source);
        }
        return value;
    }
}
