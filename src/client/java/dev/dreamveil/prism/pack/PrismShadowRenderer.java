package dev.dreamveil.prism.pack;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.api.frame.PrismMatrix4;
import dev.dreamveil.prism.api.shadow.PrismShadowExecutionSnapshot;
import dev.dreamveil.prism.runtime.api.PrismPerformanceApiImpl;
import dev.dreamveil.prism.runtime.api.PrismWorldRenderApiImpl;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * Minecraft 26.2 directional shadow-view + independent visibility bring-up.
 *
 * <p>alpha.3 keeps the explicit prepared-terrain replay proven by r9.1, but replaces the
 * camera-view terrain vertex transform with a Prism-owned shadow-caster vertex stage. The caster
 * consumes Minecraft's existing Globals + ChunkSection ABI and overrides only the Projection UBO
 * inside the shadow RenderPass with a camera-relative directional-light view-projection matrix.
 * Minecraft's global projection state is never mutated and {@code prepareChunkRenders()} is never
 * called a second time.</p>
 *
 * <p>alpha.4 adds a separate extraction-time terrain-section visibility snapshot. Candidate sections
 * come from the resident ViewArea grid dimensions and are culled against Prism's light frustum;
 * {@code LevelRenderer.visibleSections()} is read only as a diagnostic and is never the candidate
 * source. Alpha.5 resolves accepted coordinates back to renderer-owned resident RenderSection objects
 * without retaining them, and alpha.5.3 proved independent indexed terrain draws. Alpha.6 removes the
 * main-camera fallback, snaps the light-space center to the shadow texel grid, adds conservative
 * reversed-Z caster bias, and publishes delayed GPU timing for the terrain shadow pass. Alpha.7
 * adds the first visible hard receiver, preserves completed draw metrics across extraction, reuses
 * yaw-only visibility snapshots, and bit-scans resident sections during command recording. Alpha.7.2
 * captures the exact LevelRenderer model-view used by the world pass and establishes the pre-translucent
 * live scene-depth lifetime. Alpha.7.2.5.1 replaces the fixed bring-up light with Minecraft 26.2
 * SUN_ANGLE/MOON_ANGLE attributes, keeps caster/receiver on one interpolated celestial state, and makes
 * visibility caching light-direction aware. Alpha.7.2.6 added receiver-aware filtering and rotating-section identity guards. Alpha.7.2.7
 * replaces the camera-visible receiver footprint as a culling authority with a fixed-size, section-
 * quantized light-space caster volume plus guard band, keeps receiver bounds diagnostic-only, and
 * makes caster and receiver consume the exact same per-frame shadow view-projection/camera origin. Alpha.7.2.8
 * replaces the hard one-tap receiver edge with deterministic percentage-closer filtering. The
 * hardened build extends that path with slope-aware acne protection, continuous quadratic 3x3 PCF
 * and balanced/quality cascade profiles while preserving raw D32 sampling, reversed-Z comparison,
 * stable volumes and exact texel snap.</p>
 */
public final class PrismShadowRenderer {
    static final String BLOCKER_CODE = PrismSceneExecutionSupport.SHADOW_AUXILIARY_VIEW_BLOCKER;

    private static final int SHADOW_RESOLUTION = 1024;
    private static final String DEBUG_FLAG = "prism-shadow-depth-debug.enabled";

    // Alpha.7.2.5.1 removes the fixed alpha.3 bring-up light. Minecraft 26.2's camera
    // EnvironmentAttributes are now authoritative for sun/moon motion. The path rotation stays at
    // vanilla's zero-degree default until shader packs expose it as a creator setting.
    private static final float SHADOW_SUN_PATH_ROTATION_DEGREES = 0.0f;
    // This bucket invalidates only the coarse caster-selection cache. Caster and receiver matrices
    // always use the continuous celestial angle; quantizing the projection caused visible
    // advance/retreat steps even though both passes agreed with each other.
    private static final float SHADOW_CULLING_LIGHT_STEP_DEGREES = 0.10f;
    // Fade extremely long horizon shadows before the SUN/MOON source flips by 180 degrees. The
    // handover therefore happens at zero opacity instead of sliding the entire shadow field.
    private static final float SHADOW_HORIZON_FADE_START = 0.07f;
    private static final float SHADOW_HORIZON_FULL_STRENGTH = 0.30f;
    // Stable caster selection is intentionally looser than the actual 512x512 shadow projection.
    // The 32-block guard lets the visual projection move by texel increments while the coarse caster
    // set changes only on whole-section cells, preventing boundary thrash/ghost popping.
    private static final double SHADOW_STABLE_CASTER_GUARD_BLOCKS = 32.0;
    private static final double SHADOW_CASTER_ANCHOR_STEP_BLOCKS = 16.0;
    private static final float SHADOW_RECEIVER_EDGE_FADE_UV = 0.04f;
    private static final float SHADOW_NEAR_CASCADE_HALF_EXTENT = 128.0f;
    private static final float SHADOW_HALF_EXTENT = 256.0f;
    private static final float SHADOW_LIGHT_DISTANCE = 512.0f;
    private static final float SHADOW_NEAR = 1.0f;
    private static final float SHADOW_FAR = 1024.0f;
    private static final double SHADOW_WORLD_UNITS_PER_TEXEL =
            (SHADOW_HALF_EXTENT * 2.0) / SHADOW_RESOLUTION;
    private static final double SHADOW_NEAR_WORLD_UNITS_PER_TEXEL =
            (SHADOW_NEAR_CASCADE_HALF_EXTENT * 2.0) / SHADOW_RESOLUTION;
    private static final float SHADOW_CASCADE_BLEND_START_UV = 0.02f;
    private static final float SHADOW_CASCADE_FULL_NEAR_UV = 0.08f;
    // Reversed-Z caster bias. Positive values move stored caster depth farther from the light
    // by subtracting from gl_FragCoord.z. Receiver-plane correction handles the per-tap slope,
    // allowing this raster bias to stay small enough to preserve contact shadows.
    private static final float SHADOW_CONSTANT_DEPTH_BIAS = PrismShadowBiasMath.CONSTANT_DEPTH_BIAS;
    private static final float SHADOW_SLOPE_DEPTH_BIAS = PrismShadowBiasMath.SLOPE_DEPTH_BIAS;
    private static final float SHADOW_MAX_DEPTH_BIAS = PrismShadowBiasMath.MAX_DEPTH_BIAS;
    private static final int SHADOW_GPU_QUERY_RING = 4;
    private static final int RECEIVER_GPU_QUERY_RING = 4;
    // Keep the independent caster set conservative, but avoid resolving every empty resident
    // section/layer on every render frame. Only Prism-owned linear indices are cached; renderer
    // objects and GPU slices are still resolved and identity-checked immediately before drawing.
    private static final int SHADOW_DRAWABLE_INDEX_REFRESH_FRAMES = 8;
    private static final int PROJECTION_UBO_BYTES = 16 * Float.BYTES;
    private static final int RECEIVER_UBO_BYTES = PrismShadowReceiverMath.UNIFORM_BYTES;
    private static final int DUMMY_CHUNK_SECTION_UBO_BYTES = 96;
    private static final float SHADOW_RECEIVER_STRENGTH = 0.58f;
    private static final float SHADOW_RECEIVER_COMPARE_BIAS = PrismShadowBiasMath.RECEIVER_COMPARE_BIAS;
    private static final PrismShadowQuality SHADOW_QUALITY = PrismShadowQuality.configured();
    private static final boolean SHADOW_DUAL_CASCADES = SHADOW_QUALITY.dualCascades();
    private static final int SHADOW_RECEIVER_PCF_TAPS = PrismShadowReceiverMath.PCF_TAPS_3X3;
    private static final String SHADOW_RECEIVER_MODE = SHADOW_DUAL_CASCADES
            ? "dual_stable_cascade_pcf3x3_pretrans"
            : "balanced_stable_pcf3x3_pretrans";
    private static final float MAIN_DEPTH_CLEAR_EPSILON = 0.000001f;
    private static final double SECTION_SIZE = 16.0;

    private static final String SHADOW_CASTER_VERTEX_SOURCE = """
            #version 450

            layout(std140) uniform Globals {
                ivec3 CameraBlockPos;
                vec3 CameraOffset;
                vec2 ScreenSize;
                float GlintAlpha;
                float GameTime;
                int MenuBlurRadius;
                int UseRgss;
            };

            layout(std140) uniform ChunkSection {
                mat4 ModelViewMat;
                float ChunkVisibility;
                ivec2 TextureSize;
                ivec3 ChunkPosition;
            };

            // For this shadow pipeline Projection contains Prism's camera-relative light VP, not
            // Minecraft's main-camera projection. It is overridden per RenderPass.
            layout(std140) uniform Projection {
                mat4 ProjMat;
            };

            layout(location = 0) in vec3 Position;
            layout(location = 2) in vec2 UV0;
            layout(location = 0) out vec2 texCoord0;

            int floorDiv16(int value) {
                return value >= 0 ? value / 16 : -((-value + 15) / 16);
            }

            void main() {
                // alpha.6 encodes the section offset relative to the camera section in
                // firstInstance. This removes the main-camera ChunkSection UBO dependency while
                // preserving the vanilla terrain vertex format and renderer-owned mesh buffers.
                int packed = gl_InstanceIndex;
                int dx = (packed & 127) - 64;
                int dy = ((packed >> 7) & 255) - 128;
                int dz = ((packed >> 15) & 127) - 64;

                ivec3 cameraSection = ivec3(
                        floorDiv16(CameraBlockPos.x),
                        floorDiv16(CameraBlockPos.y),
                        floorDiv16(CameraBlockPos.z));
                ivec3 chunkPosition = (cameraSection + ivec3(dx, dy, dz)) * 16;

                vec3 cameraRelative = Position
                        + vec3(chunkPosition - CameraBlockPos)
                        + CameraOffset;
                gl_Position = ProjMat * vec4(cameraRelative, 1.0);
                texCoord0 = UV0;
            }
            """;

    private static final String SHADOW_CASTER_FRAGMENT_SOURCE = String.format(Locale.ROOT, """
            #version 450
            uniform sampler2D Sampler0;
            layout(location = 0) in vec2 texCoord0;
            layout(location = 0) out vec4 fragColor;
            void main() {
                // Derivatives are evaluated before alpha-test divergence.
                float slope = max(abs(dFdx(gl_FragCoord.z)), abs(dFdy(gl_FragCoord.z)));

                // Keep alpha-tested terrain holes. Solid terrain takes the same path with alpha=1.
                if (texture(Sampler0, texCoord0).a < 0.1) {
                    discard;
                }

                // Reversed-Z: near=1, far=0. Move caster depth slightly farther from the light.
                // The slope term is bounded so grazing surfaces cannot receive an unbounded offset.
                float bias = min(%1$.8f, %2$.8f + slope * %3$.8f);
                gl_FragDepth = clamp(gl_FragCoord.z - bias, 0.0, 1.0);
                fragColor = vec4(0.0);
            }
            """,
                    SHADOW_MAX_DEPTH_BIAS,
                    SHADOW_CONSTANT_DEPTH_BIAS,
                    SHADOW_SLOPE_DEPTH_BIAS);

    private static final String SHADOW_RECEIVER_VERTEX_SOURCE = """
            #version 450
            layout(location = 0) out vec2 texCoord;
            void main() {
                vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                texCoord = uv;
                gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String SHADOW_RECEIVER_FRAGMENT_SOURCE = """
            #version 450
            uniform sampler2D MainDepth;
            uniform sampler2D ShadowDepthNear;
            uniform sampler2D ShadowDepthFar;

            layout(std140) uniform ShadowReceiver {
                mat4 InvMainViewProjection;
                mat4 NearShadowViewProjection;
                mat4 FarShadowViewProjection;
                vec4 ReceiverParams;
            };

            layout(location = 0) in vec2 texCoord;
            layout(location = 0) out vec4 fragColor;

            vec4 probeReceiver(vec2 sourceUv, bool flipDepthY, bool flipNdcY) {
                vec2 depthUv = vec2(sourceUv.x, flipDepthY ? 1.0 - sourceUv.y : sourceUv.y);
                float mainDepth = texture(MainDepth, depthUv).r;
                if (mainDepth <= ReceiverParams.z) {
                    // Blue: no scene receiver at this source pixel (normally sky / clear depth).
                    return vec4(0.05, 0.20, 1.00, 0.82);
                }

                vec2 ndcUv = vec2(sourceUv.x, flipNdcY ? 1.0 - sourceUv.y : sourceUv.y);
                vec2 ndcXY = ndcUv * 2.0 - 1.0;
                vec4 cameraRelativeH = InvMainViewProjection * vec4(ndcXY, mainDepth, 1.0);
                if (abs(cameraRelativeH.w) < 1.0e-7) {
                    return vec4(1.00, 0.00, 1.00, 0.88);
                }
                vec3 cameraRelative = cameraRelativeH.xyz / cameraRelativeH.w;

                vec4 shadowClip = FarShadowViewProjection * vec4(cameraRelative, 1.0);
                if (abs(shadowClip.w) < 1.0e-7) {
                    return vec4(1.00, 0.00, 1.00, 0.88);
                }
                vec3 shadowNdc = shadowClip.xyz / shadowClip.w;
                vec2 shadowUv = shadowNdc.xy * 0.5 + 0.5;
                bool inShadowVolume = shadowUv.x > 0.0 && shadowUv.y > 0.0
                        && shadowUv.x < 1.0 && shadowUv.y < 1.0
                        && shadowNdc.z >= 0.0 && shadowNdc.z <= 1.0;
                if (!inShadowVolume) {
                    // Magenta: main depth reconstructed, but the point does not land in shadow volume.
                    return vec4(1.00, 0.00, 0.85, 0.84);
                }

                float casterDepth = texture(ShadowDepthFar, shadowUv).r;
                if (casterDepth <= ReceiverParams.z) {
                    // Orange: valid shadow coordinate but this shadow-map texel is still clear.
                    return vec4(1.00, 0.48, 0.02, 0.84);
                }

                // Reversed-Z: larger depth is closer to the light. Red means the exact hard compare
                // says shadowed; green means all coordinate stages are valid but the pixel is lit.
                bool shadowed = casterDepth > shadowNdc.z + ReceiverParams.y;
                return shadowed
                        ? vec4(1.00, 0.04, 0.02, 0.90)
                        : vec4(0.03, 0.88, 0.15, 0.78);
            }

            float compareShadowTap(
                    sampler2D shadowMap,
                    ivec2 texelCoord,
                    ivec2 shadowSize,
                    vec2 centerUv,
                    float receiverDepth,
                    vec2 receiverDepthGradient) {
                if (texelCoord.x < 0 || texelCoord.y < 0
                        || texelCoord.x >= shadowSize.x || texelCoord.y >= shadowSize.y) {
                    // Samples outside a finite cascade are sunlight, never a clamped copy of its
                    // last depth texel. Cascade blending/outer edge fade handle the transitions.
                    return 0.0;
                }
                float casterDepth = texelFetch(shadowMap, texelCoord, 0).r;
                vec2 sampleUv = (vec2(texelCoord) + vec2(0.5)) / vec2(shadowSize);
                float planeCorrection = clamp(
                        dot(receiverDepthGradient, sampleUv - centerUv),
                        -%1$.8f, %1$.8f);
                float tapReceiverDepth = receiverDepth + planeCorrection;
                // Reversed-Z: a larger stored depth is closer to the directional light.
                return casterDepth > tapReceiverDepth + ReceiverParams.y ? 1.0 : 0.0;
            }

            vec2 receiverPlaneDepthGradient(vec2 shadowUv, float receiverDepth) {
                vec2 uvDx = dFdx(shadowUv);
                vec2 uvDy = dFdy(shadowUv);
                float depthDx = dFdx(receiverDepth);
                float depthDy = dFdy(receiverDepth);
                float determinant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
                if (abs(determinant) < 1.0e-10) {
                    return vec2(0.0);
                }
                vec2 gradient = vec2(
                        depthDx * uvDy.y - depthDy * uvDx.y,
                        uvDx.x * depthDy - uvDy.x * depthDx) / determinant;
                if (any(isnan(gradient)) || any(isinf(gradient))) {
                    return vec2(0.0);
                }
                return gradient;
            }

            float shadowPcf3x3(
                    sampler2D shadowMap,
                    vec2 shadowUv,
                    float receiverDepth,
                    vec2 receiverDepthGradient) {
                ivec2 shadowSize = textureSize(shadowMap, 0);
                vec2 texelPosition = shadowUv * vec2(shadowSize) - vec2(0.5);
                ivec2 base = ivec2(floor(texelPosition));
                vec2 phase = fract(texelPosition);

                // Quadratic B-spline weights vary continuously across texel boundaries. Compared
                // with a hard 3x3 kernel this prevents a rotating sun from making the edge pop,
                // while retaining a bounded nine-fetch cost and deterministic output.
                vec2 inversePhase = vec2(1.0) - phase;
                vec3 wx = vec3(
                        0.5 * inversePhase.x * inversePhase.x,
                        0.75 - (phase.x - 0.5) * (phase.x - 0.5),
                        0.5 * phase.x * phase.x);
                vec3 wy = vec3(
                        0.5 * inversePhase.y * inversePhase.y,
                        0.75 - (phase.y - 0.5) * (phase.y - 0.5),
                        0.5 * phase.y * phase.y);
                float coverage = 0.0;
                for (int y = 0; y < 3; y++) {
                    for (int x = 0; x < 3; x++) {
                        coverage += compareShadowTap(
                                shadowMap, base + ivec2(x - 1, y - 1), shadowSize,
                                shadowUv, receiverDepth, receiverDepthGradient)
                                * wx[x] * wy[y];
                    }
                }
                return coverage;
            }

            void main() {
                if (texCoord.x < 0.0 || texCoord.y < 0.0 || texCoord.x > 1.0 || texCoord.y > 1.0) {
                    discard;
                }

                // Alpha.7.2.2 truth probe. Each quadrant replays the entire screen so the same
                // geometry can be compared under four Y-coordinate conventions without readback:
                //   Q0: depth Y raw,  NDC Y raw      (current Prism path)
                //   Q1: depth Y flip, NDC Y raw
                //   Q2: depth Y raw,  NDC Y flip
                //   Q3: depth Y flip, NDC Y flip
                // Vulkan clip Z remains [0,1] in every panel; we do not import Iris/OpenGL's
                // legacy [-1,1] depth remap into the Vulkan path.
                if (ReceiverParams.w > 0.5) {
                    bool right = texCoord.x >= 0.5;
                    bool secondRow = texCoord.y >= 0.5;
                    vec2 sourceUv = vec2(
                            right ? (texCoord.x - 0.5) * 2.0 : texCoord.x * 2.0,
                            secondRow ? (texCoord.y - 0.5) * 2.0 : texCoord.y * 2.0);
                    int variant = (secondRow ? 2 : 0) + (right ? 1 : 0);
                    bool flipDepthY = (variant & 1) != 0;
                    bool flipNdcY = (variant & 2) != 0;
                    vec4 result = probeReceiver(sourceUv, flipDepthY, flipNdcY);

                    // Thin white cross keeps the four full-screen replays visually separable.
                    if (abs(texCoord.x - 0.5) < 0.0015 || abs(texCoord.y - 0.5) < 0.0025) {
                        fragColor = vec4(1.0);
                    } else if (sourceUv.x < 0.045 && sourceUv.y < 0.045) {
                        // Tiny per-panel identity marker: Q0 white, Q1 cyan, Q2 yellow, Q3 violet.
                        fragColor = variant == 0 ? vec4(1.00, 1.00, 1.00, 1.0)
                                : variant == 1 ? vec4(0.00, 1.00, 1.00, 1.0)
                                : variant == 2 ? vec4(1.00, 1.00, 0.00, 1.0)
                                : vec4(0.70, 0.20, 1.00, 1.0);
                    } else {
                        fragColor = result;
                    }
                    return;
                }

                // Normal receiver path uses the authoritative raw-depth/raw-NDC convention.
                float mainDepth = texture(MainDepth, texCoord).r;
                if (mainDepth <= ReceiverParams.z) {
                    discard;
                }

                vec2 ndcXY = texCoord * 2.0 - 1.0;
                vec4 cameraRelativeH = InvMainViewProjection * vec4(ndcXY, mainDepth, 1.0);
                if (abs(cameraRelativeH.w) < 1.0e-7) {
                    discard;
                }
                vec3 cameraRelative = cameraRelativeH.xyz / cameraRelativeH.w;

                vec4 farClip = FarShadowViewProjection * vec4(cameraRelative, 1.0);
                if (abs(farClip.w) < 1.0e-7) {
                    discard;
                }
                vec3 farNdc = farClip.xyz / farClip.w;
                vec2 farUv = farNdc.xy * 0.5 + 0.5;
                if (farUv.x <= 0.0 || farUv.y <= 0.0
                        || farUv.x >= 1.0 || farUv.y >= 1.0
                        || farNdc.z < 0.0 || farNdc.z > 1.0) {
                    discard;
                }

                // Two concentric, independently texel-snapped cascades. The 256-block near map
                // provides 0.25 block/texel detail; its outer transition blends into the stable
                // 512-block far map instead of producing a hard resolution seam.
                vec4 nearClip = NearShadowViewProjection * vec4(cameraRelative, 1.0);
                vec3 nearNdc = nearClip.xyz / nearClip.w;
                vec2 nearUv = nearNdc.xy * 0.5 + 0.5;
                // Solve receiver-plane depth gradients before the cascade branch so derivative
                // evaluation stays uniform across neighboring fragments. Each PCF tap then compares
                // against the receiver plane at that tap instead of the center depth. This lets the
                // caster bias stay small and prevents detached/missing shadows on steep block faces.
                vec2 farDepthGradient = receiverPlaneDepthGradient(farUv, farNdc.z);
                vec2 nearDepthGradient = receiverPlaneDepthGradient(nearUv, nearNdc.z);
                bool inNear = abs(nearClip.w) >= 1.0e-7
                        && nearUv.x > 0.0 && nearUv.y > 0.0
                        && nearUv.x < 1.0 && nearUv.y < 1.0
                        && nearNdc.z >= 0.0 && nearNdc.z <= 1.0;

                float shadowCoverage;
                if (ReceiverParams.w > 0.0 && inNear) {
                    float nearEdge = min(min(nearUv.x, 1.0 - nearUv.x),
                                         min(nearUv.y, 1.0 - nearUv.y));
                    float nearWeight = smoothstep(
                            ReceiverParams.w * 0.25, ReceiverParams.w, nearEdge);
                    float nearCoverage = shadowPcf3x3(
                            ShadowDepthNear, nearUv, nearNdc.z, nearDepthGradient);
                    if (nearWeight >= 0.999) {
                        shadowCoverage = nearCoverage;
                    } else {
                        float farCoverage = shadowPcf3x3(
                                ShadowDepthFar, farUv, farNdc.z, farDepthGradient);
                        shadowCoverage = mix(farCoverage, nearCoverage, nearWeight);
                    }
                } else {
                    shadowCoverage = shadowPcf3x3(
                            ShadowDepthFar, farUv, farNdc.z, farDepthGradient);
                }
                if (shadowCoverage <= 0.001) {
                    discard;
                }

                // Only the finite far-map boundary fades to unshadowed terrain. The near boundary
                // is already blended into the far cascade above.
                float xyEdge = min(min(farUv.x, 1.0 - farUv.x),
                                   min(farUv.y, 1.0 - farUv.y));
                float zEdge = min(farNdc.z, 1.0 - farNdc.z);
                float edgeCoverage = smoothstep(0.0, 0.04, xyEdge)
                        * smoothstep(0.0, 0.02, zEdge);
                if (edgeCoverage <= 0.001) {
                    discard;
                }

                fragColor = vec4(0.0, 0.0, 0.0, ReceiverParams.x * shadowCoverage * edgeCoverage);
            }
            """.formatted(PrismShadowBiasMath.MAX_RECEIVER_PLANE_CORRECTION);

    private static final String DEBUG_VERTEX_SOURCE = """
            #version 450
            layout(location = 0) out vec2 texCoord;
            void main() {
                // Keep the proven 3-vertex fullscreen-triangle ABI, but map its unit-square region
                // into a compact bottom-right diagnostic inset. Keeping it below the F3 text makes
                // shadow metrics readable while preserving chat, menus and the center hotbar.
                vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                texCoord = uv;
                const vec2 insetMin = vec2(0.64, -0.94);
                const vec2 insetSize = vec2(0.33, 0.58);
                gl_Position = vec4(insetMin + uv * insetSize, 0.0, 1.0);
            }
            """;

    private static final String DEBUG_FRAGMENT_SOURCE = """
            #version 450
            uniform sampler2D ShadowDepth;
            layout(location = 0) in vec2 texCoord;
            layout(location = 0) out vec4 fragColor;
            void main() {
                // The oversized triangle is clipped logically to the inset without requiring a
                // version-sensitive RenderArea/scissor API. Everything outside remains vanilla.
                if (texCoord.x < 0.0 || texCoord.y < 0.0 || texCoord.x > 1.0 || texCoord.y > 1.0) {
                    discard;
                }
                float depth = clamp(texture(ShadowDepth, texCoord).r, 0.0, 1.0);
                // Minecraft 26.2 / Prism shadow bring-up is reversed-Z: near = 1, clear/far = 0.
                float visible = pow(depth, 0.35);
                fragColor = vec4(vec3(visible), 1.0);
            }
            """;

    private static PrismWorldRenderApiImpl api;
    private static PrismPerformanceApiImpl performanceApi;
    private static boolean debugEnabled;
    private static boolean depthDebugEnabled;
    private static volatile boolean shaderResourceReloadInProgress;
    private static boolean replayActive;
    private static boolean failureLogged;
    private static boolean preparedDrawGroupsObserved;
    private static boolean emptyDepthClearObserved;
    private static boolean terrainReplayObserved;
    private static boolean independentTerrainDrawObserved;
    private static boolean terrainReplayRecordedThisFrame;
    private static boolean debugDrawObserved;
    private static long frameIndex;
    private static long visibilityFrameIndex;
    private static long lastDrawMetricsLogFrame = Long.MIN_VALUE;
    private static GpuQueryPool shadowGpuQueryPool;
    private static boolean shadowGpuQueriesUnavailable;
    private static long shadowGpuQuerySequence;
    private static float shadowGpuTimestampPeriod = 1.0f;
    private static long latestShadowGpuNanos;
    private static boolean latestShadowGpuTimeAvailable;
    private static GpuQueryPool receiverGpuQueryPool;
    private static boolean receiverGpuQueriesUnavailable;
    private static long receiverGpuQuerySequence;
    private static float receiverGpuTimestampPeriod = 1.0f;
    private static long latestReceiverGpuNanos;
    private static boolean latestReceiverGpuTimeAvailable;
    private static long lastReceiverMetricsLogFrame = Long.MIN_VALUE;
    private static long lastReceiverAppliedFrame = Long.MIN_VALUE;
    private static boolean receiverDrawObserved;
    private static boolean exactWorldMatrixCaptureLogged;
    private static boolean sceneDepthSnapshotLogged;
    private static boolean sceneDepthCopyUnsupportedLogged;
    private static long shadowLightSampleRevision;
    private static int visibilityLightBucket = Integer.MIN_VALUE;
    private static long visibilityLightRevision = -1L;
    private static String visibilityLightKind = "unavailable";
    private static boolean celestialLightLogged;
    private static String lastLoggedLightKind = "unavailable";
    private static volatile ShadowLightState shadowLightState = ShadowLightState.EMPTY;
    private static volatile PrismShadowSectionFrameData shadowSectionFrame = PrismShadowSectionFrameData.EMPTY;
    private static volatile ShadowReceiverFrameData shadowReceiverFrame = ShadowReceiverFrameData.EMPTY;
    private static boolean visibilityCaptureLogged;
    private static boolean visibilityFailureLogged;
    private static boolean stationaryVisibilityReuseLogged;
    private static int lastVisibilityCenterX = Integer.MIN_VALUE;
    private static int lastVisibilityCenterY = Integer.MIN_VALUE;
    private static int lastVisibilityCenterZ = Integer.MIN_VALUE;

    // ViewArea is renderer-owned rotating storage. Keep only a weak identity reference and a Prism
    // bit-mask cache for the current logical grid; never retain RenderSection instances.
    private static WeakReference<ViewArea> residentCacheViewArea = new WeakReference<>(null);
    private static int residentCacheMinX = Integer.MIN_VALUE;
    private static int residentCacheMinY = Integer.MIN_VALUE;
    private static int residentCacheMinZ = Integer.MIN_VALUE;
    private static int residentCacheHorizontalDiameter;
    private static int residentCacheVerticalSections;
    private static long[] residentGridMask = new long[0];
    private static WeakReference<SectionRenderDispatcher> residentDispatcher = new WeakReference<>(null);
    // Receiver bounds remain diagnostic-only in alpha.7.2.7; they no longer decide caster eligibility.
    private static PrismReceiverAwareCasterMath.Bounds receiverCasterBounds = PrismReceiverAwareCasterMath.Bounds.EMPTY;
    private static PrismStableCasterVolumeMath.Volume stableCasterVolume = PrismStableCasterVolumeMath.Volume.EMPTY;
    private static PrismStableCasterVolumeMath.Volume nearStableCasterVolume = PrismStableCasterVolumeMath.Volume.EMPTY;
    private static PrismStableCasterVolumeMath.Volume visibilityStableCasterVolume = PrismStableCasterVolumeMath.Volume.EMPTY;
    private static int stableVolumeRejectedCandidates;
    private static int residentIdentityRejects;
    private static int drawIdentityRejects;
    private static ShadowDrawableIndexCache shadowDrawableIndexCache = ShadowDrawableIndexCache.EMPTY;


    private static GpuTexture shadowColor;
    private static GpuTextureView shadowColorView;
    private static GpuTexture shadowDepth;
    private static GpuTextureView shadowDepthView;
    private static GpuTexture shadowNearDepth;
    private static GpuTextureView shadowNearDepthView;
    private static GpuSampler debugSampler;
    private static RenderPipeline debugPipeline;
    private static GpuFormat debugOutputFormat;
    private static GpuSampler receiverSampler;
    private static RenderPipeline receiverPipeline;
    private static GpuFormat receiverOutputFormat;
    private static GpuTexture sceneDepthSnapshot;
    private static GpuTextureView sceneDepthSnapshotView;
    private static GpuFormat sceneDepthSnapshotFormat;
    private static int sceneDepthSnapshotWidth;
    private static int sceneDepthSnapshotHeight;

    private static GpuBuffer shadowProjectionBuffer;
    private static GpuBuffer shadowNearProjectionBuffer;
    private static GpuBuffer shadowReceiverBuffer;
    private static GpuBuffer shadowDummyChunkSectionBuffer;
    private static final EnumMap<ChunkSectionLayer, RenderPipeline> SHADOW_CASTER_PIPELINES =
            new EnumMap<>(ChunkSectionLayer.class);


    private record ShadowLightState(
            long revision,
            boolean sun,
            float sunAngleDegrees,
            float moonAngleDegrees,
            float selectedAngleDegrees,
            float rayX,
            float rayY,
            float rayZ,
            float upX,
            float upY,
            float upZ,
            float strengthScale,
            int cullingBucket) {
        private static final ShadowLightState EMPTY = new ShadowLightState(
                -1L, true, Float.NaN, Float.NaN, Float.NaN,
                0.0f, -1.0f, 0.0f,
                0.0f, 0.0f, -1.0f,
                0.0f,
                Integer.MIN_VALUE);

        boolean available() {
            return revision >= 0L;
        }

        String kind() {
            return sun ? "SUN" : "MOON";
        }
    }



    private record ShadowReceiverFrameData(
            long visibilityFrameIndex,
            PrismMatrix4 inverseMainViewProjection,
            PrismMatrix4 nearShadowViewProjection,
            PrismMatrix4 farShadowViewProjection,
            double cameraX,
            double cameraY,
            double cameraZ,
            float shadowStrength,
            String matrixSource) {
        private static final ShadowReceiverFrameData EMPTY = new ShadowReceiverFrameData(
                -1L, PrismMatrix4.identity(), PrismMatrix4.identity(), PrismMatrix4.identity(),
                0.0, 0.0, 0.0, 0.0f, "unavailable");

        boolean available() {
            return visibilityFrameIndex >= 0L;
        }
    }

    private record ReceiverDepthInput(GpuTextureView view, String source) {}

    private record ShadowDrawableIndexCache(
            long visibilityFrameIndex,
            long refreshFrameIndex,
            int residentLayerEntries,
            int drawableLayerEntries,
            EnumMap<ChunkSectionLayer, int[]> indicesByLayer) {
        private static final ShadowDrawableIndexCache EMPTY = new ShadowDrawableIndexCache(
                -1L, Long.MIN_VALUE, 0, 0, new EnumMap<>(ChunkSectionLayer.class));

        ShadowDrawableIndexCache {
            indicesByLayer = new EnumMap<>(indicesByLayer);
        }

        boolean canReuse(long visibilityIndex, long currentFrame, ChunkSectionLayer[] layers) {
            if (visibilityFrameIndex != visibilityIndex
                    || currentFrame < refreshFrameIndex
                    || currentFrame - refreshFrameIndex >= SHADOW_DRAWABLE_INDEX_REFRESH_FRAMES) {
                return false;
            }
            for (ChunkSectionLayer layer : layers) {
                if (!indicesByLayer.containsKey(layer)) return false;
            }
            return true;
        }

        int[] indices(ChunkSectionLayer layer) {
            int[] indices = indicesByLayer.get(layer);
            return indices == null ? new int[0] : indices;
        }
    }

    private record CascadeDrawStats(
            int drawCount,
            int readySections,
            int missingAtDraw,
            int identityRejected,
            int staleEmptyLayers,
            int volumeCulled,
            long submittedIndexCount) {}


    private PrismShadowRenderer() {
    }

    static synchronized void initialize(PrismWorldRenderApiImpl worldApi, PrismPerformanceApiImpl metricsApi) {
        api = java.util.Objects.requireNonNull(worldApi, "worldApi");
        performanceApi = java.util.Objects.requireNonNull(metricsApi, "metricsApi");
        debugEnabled = PrismClientConfig.get().builtInShadows();
        depthDebugEnabled = Boolean.getBoolean(DEBUG_FLAG);

        publishUnavailable(debugEnabled
                ? "shadow_independent_terrain_waiting"
                : "shadow_depth_debug_disabled",
                debugEnabled
                        ? "Prism independent terrain shadow pass is armed; waiting for a resident light-selected mesh and the opaque terrain execution boundary."
                        : "Prism built-in shadows are disabled in Performance settings.");

        PrismMod.LOGGER.info(
                "Prism beta terrain shadow receiver initialized: enabled={}, shadowQuality={}, cascades={}, cascadeMode={}, resolutionPerCascade={}x{}, nearTexelWorldSize={}, farTexelWorldSize={}, directionalLightView=true, independentVisibility=true, independentTerrainDraws=true, texelSnapping=floor_exact, reversedZBias=true, receiverPlaneBias=true, pcfReceiver=true, pcfMode=quadratic_bspline_3x3, pcfTaps={}, receiverTruthProbe=false, exactWorldModelView=true, preTranslucentLiveDepth=true, sceneDepthSnapshotFallback=true, dynamicCelestialLight=true, continuousCelestialProjection=true, sunMoonAttributes=true, cullingLightStepDegrees={}, horizonFadeAbsRayY=[{},{}], stableCasterVolume=true, casterAnchorStepBlocks={}, casterGuardBlocks={}, receiverBoundsDiagnosticOnly=true, sharedShadowFrameMatrix=true, cascadeBlendUv=[{},{}], edgeFadeUv={}, rotatingSectionIdentityGuard=true, mainCameraFallback=false",
                debugEnabled, SHADOW_QUALITY.id(), SHADOW_QUALITY.cascadeCount(),
                SHADOW_DUAL_CASCADES ? "concentric_stable" : "single_stable",
                SHADOW_RESOLUTION, SHADOW_RESOLUTION,
                SHADOW_NEAR_WORLD_UNITS_PER_TEXEL, SHADOW_WORLD_UNITS_PER_TEXEL,
                SHADOW_RECEIVER_PCF_TAPS, SHADOW_CULLING_LIGHT_STEP_DEGREES,
                SHADOW_HORIZON_FADE_START, SHADOW_HORIZON_FULL_STRENGTH,
                SHADOW_CASTER_ANCHOR_STEP_BLOCKS,
                SHADOW_STABLE_CASTER_GUARD_BLOCKS,
                SHADOW_CASCADE_BLEND_START_UV, SHADOW_CASCADE_FULL_NEAR_UV,
                SHADOW_RECEIVER_EDGE_FADE_UV);
        PrismMod.LOGGER.info(
                "Prism 26.2 shadow policy: renderer-owned SectionMesh/SectionDraw/GPU slices are resolved only while recording the shadow pass; Prism does not recompile, close, retain, or fall back to main-camera terrain draw groups");
    }

    /**
     * Builds an independent, immutable light-frustum visibility snapshot during Fabric extraction.
     *
     * <p>This does not call prepareChunkRenders(), does not mutate SectionOcclusionGraph, and does
     * never uses LevelRenderer.visibleSections() as the caster source. Alpha.7.2.6 used those sections
     * only as conservative RECEIVERS: their light-space footprint is expanded by a guard band, while
     * the caster candidates still come from the independent resident ViewArea grid. This preserves
     * off-screen casters that can project onto visible terrain.</p>
     */
    public static void captureIndependentVisibility(LevelExtractionContext context) {
        if (!debugEnabled || shaderResourceReloadInProgress || failureLogged || context == null) {
            return;
        }
        try {
            var cameraState = context.levelState().cameraRenderState;
            if (cameraState == null || cameraState.pos == null) {
                return;
            }

            ShadowLightState lightState = captureCelestialLightState();
            if (!lightState.available()) {
                clearVisibilityMetrics();
                return;
            }
            shadowLightState = lightState;
            logCelestialLightState(lightState);

            ViewArea viewArea = context.levelRenderer().viewArea();
            if (viewArea == null) {
                clearVisibilityMetrics();
                return;
            }
            residentDispatcher = new WeakReference<>(context.levelRenderer().sectionRenderDispatcher());

            // Minecraft 26.2 exposes these ViewArea accessors publicly. Direct calls are important:
            // Loom can remap them for production, unlike string-based reflective method lookup.
            int storageSize = viewArea.size();
            int verticalSections = viewArea.sectionCount();
            int minSectionY = viewArea.minSectionY();
            int maxSectionYDiagnostic = viewArea.maxSectionY();
            if (storageSize <= 0 || verticalSections <= 0 || storageSize % verticalSections != 0) {
                throw new IllegalStateException(
                        "Invalid ViewArea dimensions: size=" + storageSize
                                + ", sectionCount=" + verticalSections);
            }

            int horizontalSlots = storageSize / verticalSections;
            int horizontalDiameter = (int) Math.round(Math.sqrt(horizontalSlots));
            if (horizontalDiameter <= 0 || horizontalDiameter * horizontalDiameter != horizontalSlots) {
                throw new IllegalStateException(
                        "ViewArea horizontal storage is not a square grid: size=" + storageSize
                                + ", sectionCount=" + verticalSections
                                + ", horizontalSlots=" + horizontalSlots);
            }

            double cameraX = cameraState.pos.x;
            double cameraY = cameraState.pos.y;
            double cameraZ = cameraState.pos.z;

            Vector3f lightRay = new Vector3f(
                    lightState.rayX(), lightState.rayY(), lightState.rayZ());
            Vector3f lightUp = new Vector3f(
                    lightState.upX(), lightState.upY(), lightState.upZ());
            Vector3f lightRight = new Vector3f(lightRay).cross(lightUp).normalize();

            // Visible receiver bounds are telemetry only. Alpha.7.2.6 used this footprint to reject
            // casters, which made the eligible set breathe as visibleSections() changed with camera
            // movement. Alpha.7.2.7 uses a fixed-size light-space volume instead.
            PrismReceiverAwareCasterMath.Bounds receiverBounds = buildReceiverCasterBounds(
                    context.levelRenderer().visibleSections(),
                    cameraX, cameraY, cameraZ,
                    lightRight, lightUp);
            receiverCasterBounds = receiverBounds;

            PrismStableCasterVolumeMath.Volume casterVolume = PrismStableCasterVolumeMath.volume(
                    cameraX, cameraY, cameraZ,
                    lightRight.x, lightRight.y, lightRight.z,
                    lightUp.x, lightUp.y, lightUp.z,
                    lightRay.x, lightRay.y, lightRay.z,
                    SHADOW_HALF_EXTENT,
                    SHADOW_LIGHT_DISTANCE - SHADOW_NEAR,
                    SHADOW_FAR - SHADOW_LIGHT_DISTANCE,
                    SHADOW_STABLE_CASTER_GUARD_BLOCKS,
                    SHADOW_CASTER_ANCHOR_STEP_BLOCKS);
            stableCasterVolume = casterVolume;
            nearStableCasterVolume = PrismStableCasterVolumeMath.volume(
                    cameraX, cameraY, cameraZ,
                    lightRight.x, lightRight.y, lightRight.z,
                    lightUp.x, lightUp.y, lightUp.z,
                    lightRay.x, lightRay.y, lightRay.z,
                    SHADOW_NEAR_CASCADE_HALF_EXTENT,
                    SHADOW_LIGHT_DISTANCE - SHADOW_NEAR,
                    SHADOW_FAR - SHADOW_LIGHT_DISTANCE,
                    SHADOW_STABLE_CASTER_GUARD_BLOCKS,
                    SHADOW_CASTER_ANCHOR_STEP_BLOCKS);

            int cameraSectionX = floorSection(cameraX);
            int cameraSectionY = floorSection(cameraY);
            int cameraSectionZ = floorSection(cameraZ);

            // Reuse only within the same world section and stable light-space anchor. The visual
            // matrix is still republished every frame and is shared verbatim by caster+receiver.
            PrismShadowSectionFrameData previousVisibility = shadowSectionFrame;
            if (previousVisibility.frameIndex() >= 0L
                    && residentCacheViewArea.get() == viewArea
                    && floorSection(previousVisibility.cameraX()) == cameraSectionX
                    && floorSection(previousVisibility.cameraY()) == cameraSectionY
                    && floorSection(previousVisibility.cameraZ()) == cameraSectionZ
                    && visibilityLightBucket == lightState.cullingBucket()
                    && visibilityLightKind.equals(lightState.kind())
                    && PrismStableCasterVolumeMath.sameKey(visibilityStableCasterVolume, casterVolume)) {
                publishShadowReceiverFrame(
                        previousVisibility.frameIndex(),
                        cameraState.projectionMatrix,
                        cameraState.viewRotationMatrix,
                        cameraX, cameraY, cameraZ,
                        "extraction_camera_state_fallback");
                if (!stationaryVisibilityReuseLogged) {
                    stationaryVisibilityReuseLogged = true;
                    PrismMod.LOGGER.info(
                            "Prism beta stable shadow cache active: caster selection changes only on world-section/stable-light-anchor/celestial-bucket boundaries; anchorStepBlocks={}, guardBlocks={}",
                            SHADOW_CASTER_ANCHOR_STEP_BLOCKS, SHADOW_STABLE_CASTER_GUARD_BLOCKS);
                }
                return;
            }

            int horizontalRadius = horizontalDiameter / 2;
            int minSectionX = cameraSectionX - horizontalRadius;
            int minSectionZ = cameraSectionZ - horizontalRadius;
            int candidates = Math.multiplyExact(
                    Math.multiplyExact(horizontalDiameter, horizontalDiameter),
                    verticalSections);
            long[] acceptedMask = new long[(candidates + Long.SIZE - 1) / Long.SIZE];

            int accepted = 0;
            int rejectedByStableVolume = 0;
            int linearIndex = 0;
            for (int zIndex = 0; zIndex < horizontalDiameter; zIndex++) {
                int sectionZ = minSectionZ + zIndex;
                for (int xIndex = 0; xIndex < horizontalDiameter; xIndex++) {
                    int sectionX = minSectionX + xIndex;
                    for (int yIndex = 0; yIndex < verticalSections; yIndex++, linearIndex++) {
                        int sectionY = minSectionY + yIndex;
                        double worldMinX = sectionX * SECTION_SIZE;
                        double worldMinY = sectionY * SECTION_SIZE;
                        double worldMinZ = sectionZ * SECTION_SIZE;
                        double worldMaxX = worldMinX + SECTION_SIZE;
                        double worldMaxY = worldMinY + SECTION_SIZE;
                        double worldMaxZ = worldMinZ + SECTION_SIZE;
                        if (PrismStableCasterVolumeMath.overlaps(
                                casterVolume,
                                worldMinX, worldMinY, worldMinZ,
                                worldMaxX, worldMaxY, worldMaxZ,
                                lightRight.x, lightRight.y, lightRight.z,
                                lightUp.x, lightUp.y, lightUp.z,
                                lightRay.x, lightRay.y, lightRay.z)) {
                            acceptedMask[linearIndex >>> 6] |= 1L << (linearIndex & 63);
                            accepted++;
                        } else {
                            rejectedByStableVolume++;
                        }
                    }
                }
            }

            int culled = candidates - accepted;
            stableVolumeRejectedCandidates = rejectedByStableVolume;

            long[] residentMask = resolveResidentGrid(
                    viewArea, minSectionX, minSectionY, minSectionZ,
                    horizontalDiameter, verticalSections, candidates);
            int residentResolved = 0;
            for (int word = 0; word < residentMask.length; word++) {
                residentMask[word] &= acceptedMask[word];
                residentResolved += Long.bitCount(residentMask[word]);
            }
            int residentMissing = accepted - residentResolved;
            int mainVisibleDiagnostic = context.levelRenderer().visibleSections().size();
            long visibilityIndex = visibilityFrameIndex++;
            shadowSectionFrame = new PrismShadowSectionFrameData(
                    visibilityIndex,
                    cameraX, cameraY, cameraZ,
                    minSectionX, minSectionY, minSectionZ,
                    horizontalDiameter, verticalSections,
                    candidates, accepted, culled,
                    residentResolved, residentMissing,
                    mainVisibleDiagnostic,
                    acceptedMask, residentMask);
            visibilityLightBucket = lightState.cullingBucket();
            visibilityLightRevision = lightState.revision();
            visibilityLightKind = lightState.kind();
            visibilityStableCasterVolume = casterVolume;
            publishShadowReceiverFrame(
                    visibilityIndex,
                    cameraState.projectionMatrix,
                    cameraState.viewRotationMatrix,
                    cameraX, cameraY, cameraZ,
                    "extraction_camera_state_fallback");

            // Final ABI proof before independent GPU submission: resolve one accepted resident
            // section through the public mesh -> SectionDraw -> GPU-slice chain. The probe does not
            // retain renderer-owned objects and stops permanently after the first successful capture.
            PrismShadowDrawMetadataProbe.captureFirstReady(
                    context.levelRenderer().sectionRenderDispatcher(),
                    viewArea,
                    shadowSectionFrame);

            PrismPerformanceApiImpl metrics = performanceApi;
            if (metrics != null) {
                // Extraction publishes the light-frustum counters first. The drawing phase updates
                // this same frame index with the actual number of independent terrain draws recorded.
                metrics.recordShadowVisibilityFrame(
                        visibilityIndex, candidates, accepted, culled);
            }

            boolean cameraSectionChanged = cameraSectionX != lastVisibilityCenterX
                    || cameraSectionY != lastVisibilityCenterY
                    || cameraSectionZ != lastVisibilityCenterZ;
            if (!visibilityCaptureLogged || cameraSectionChanged) {
                visibilityCaptureLogged = true;
                lastVisibilityCenterX = cameraSectionX;
                lastVisibilityCenterY = cameraSectionY;
                lastVisibilityCenterZ = cameraSectionZ;
                PrismMod.LOGGER.info(
                        "Prism independent shadow visibility: frame={}, residentCandidates={}, stableVolumeRejected={}, accepted={}, culled={}, resolvedRenderSections={}, missingRenderSections={}, residentIdentityRejects={}, receiverSectionsDiagnostic={}, receiverBoundsR=[{},{}], receiverBoundsU=[{},{}], stableAnchorCell=({},{},{}), anchorStepBlocks={}, guardBlocks={}, independentDraws=pending_draw_phase, mainVisibleDiagnostic={}, grid={}x{}x{}, minSectionY={}, maxSectionYDiagnostic={}, cameraSection=({}, {}, {}), light={}, lightAngle={}, lightRevision={}, cullingBucket={}",
                        visibilityIndex, candidates, rejectedByStableVolume, accepted, culled,
                        residentResolved, residentMissing, residentIdentityRejects,
                        receiverBounds.receiverSections(),
                        formatBound(receiverBounds.minRight()), formatBound(receiverBounds.maxRight()),
                        formatBound(receiverBounds.minUp()), formatBound(receiverBounds.maxUp()),
                        casterVolume.anchorRightCell(), casterVolume.anchorUpCell(), casterVolume.anchorDepthCell(),
                        SHADOW_CASTER_ANCHOR_STEP_BLOCKS, SHADOW_STABLE_CASTER_GUARD_BLOCKS,
                        mainVisibleDiagnostic,
                        horizontalDiameter, verticalSections, horizontalDiameter,
                        minSectionY, maxSectionYDiagnostic,
                        cameraSectionX, cameraSectionY, cameraSectionZ,
                        lightState.kind(), formatAngle(lightState.selectedAngleDegrees()), lightState.revision(), lightState.cullingBucket());
            }
        } catch (RuntimeException | LinkageError exception) {
            clearVisibilityMetrics();
            if (!visibilityFailureLogged) {
                visibilityFailureLogged = true;
                PrismMod.LOGGER.error(
                        "Prism alpha.5 independent shadow visibility/section resolution disabled; alpha.3 directional shadow replay remains available",
                        exception);
            }
        }
    }

    private static long[] resolveResidentGrid(
            ViewArea viewArea,
            int minSectionX,
            int minSectionY,
            int minSectionZ,
            int horizontalDiameter,
            int verticalSections,
            int candidates) {
        ViewArea cachedViewArea = residentCacheViewArea.get();
        boolean cacheHit = cachedViewArea == viewArea
                && residentCacheMinX == minSectionX
                && residentCacheMinY == minSectionY
                && residentCacheMinZ == minSectionZ
                && residentCacheHorizontalDiameter == horizontalDiameter
                && residentCacheVerticalSections == verticalSections
                && residentGridMask.length == (candidates + Long.SIZE - 1) / Long.SIZE;
        if (cacheHit) {
            return residentGridMask.clone();
        }

        long[] rebuilt = new long[(candidates + Long.SIZE - 1) / Long.SIZE];
        BlockPos.MutableBlockPos sectionOrigin = new BlockPos.MutableBlockPos();
        int linearIndex = 0;
        int resolved = 0;
        int identityRejected = 0;
        for (int zIndex = 0; zIndex < horizontalDiameter; zIndex++) {
            int sectionZ = minSectionZ + zIndex;
            for (int xIndex = 0; xIndex < horizontalDiameter; xIndex++) {
                int sectionX = minSectionX + xIndex;
                for (int yIndex = 0; yIndex < verticalSections; yIndex++, linearIndex++) {
                    int sectionY = minSectionY + yIndex;
                    sectionOrigin.set(sectionX << 4, sectionY << 4, sectionZ << 4);
                    var renderSection = viewArea.getRenderSectionAt(sectionOrigin);
                    if (renderSection != null) {
                        if (!renderSectionMatches(renderSection, sectionX, sectionY, sectionZ)) {
                            identityRejected++;
                            continue;
                        }
                        rebuilt[linearIndex >>> 6] |= 1L << (linearIndex & 63);
                        resolved++;
                        PrismShadowRenderSectionProbe.capture(viewArea, renderSection);
                        PrismShadowMeshSliceProbe.capture(viewArea, renderSection);
                    }
                }
            }
        }

        residentCacheViewArea = new WeakReference<>(viewArea);
        residentCacheMinX = minSectionX;
        residentCacheMinY = minSectionY;
        residentCacheMinZ = minSectionZ;
        residentCacheHorizontalDiameter = horizontalDiameter;
        residentCacheVerticalSections = verticalSections;
        residentGridMask = rebuilt.clone();
        residentIdentityRejects = identityRejected;
        PrismMod.LOGGER.debug(
                "Prism beta resident RenderSection grid resolved: resolved={}/{}, identityRejected={}, grid={}x{}x{}, origin=({}, {}, {})",
                resolved, candidates, identityRejected,
                horizontalDiameter, verticalSections, horizontalDiameter,
                minSectionX, minSectionY, minSectionZ);
        return rebuilt;
    }


    /**
     * Diagnostic-only bounds of the main-camera visible receivers. Alpha.7.2.7 deliberately does
     * not use these bounds for caster eligibility because that made the caster set breathe with
     * camera visibility changes in alpha.7.2.6.
     */
    private static PrismReceiverAwareCasterMath.Bounds buildReceiverCasterBounds(
            Iterable<SectionRenderDispatcher.RenderSection> visibleReceivers,
            double cameraX,
            double cameraY,
            double cameraZ,
            Vector3f right,
            Vector3f up) {
        if (visibleReceivers == null) {
            return PrismReceiverAwareCasterMath.Bounds.EMPTY;
        }

        double minRight = Double.POSITIVE_INFINITY;
        double maxRight = Double.NEGATIVE_INFINITY;
        double minUp = Double.POSITIVE_INFINITY;
        double maxUp = Double.NEGATIVE_INFINITY;
        int receivers = 0;

        for (SectionRenderDispatcher.RenderSection receiver : visibleReceivers) {
            if (receiver == null) {
                continue;
            }
            var bb = receiver.getBoundingBox();
            if (bb == null) {
                continue;
            }

            double minX = bb.minX - cameraX;
            double minY = bb.minY - cameraY;
            double minZ = bb.minZ - cameraZ;
            double maxX = bb.maxX - cameraX;
            double maxY = bb.maxY - cameraY;
            double maxZ = bb.maxZ - cameraZ;

            minRight = Math.min(minRight, PrismReceiverAwareCasterMath.projectAabbMin(
                    minX, minY, minZ, maxX, maxY, maxZ,
                    right.x, right.y, right.z));
            maxRight = Math.max(maxRight, PrismReceiverAwareCasterMath.projectAabbMax(
                    minX, minY, minZ, maxX, maxY, maxZ,
                    right.x, right.y, right.z));
            minUp = Math.min(minUp, PrismReceiverAwareCasterMath.projectAabbMin(
                    minX, minY, minZ, maxX, maxY, maxZ,
                    up.x, up.y, up.z));
            maxUp = Math.max(maxUp, PrismReceiverAwareCasterMath.projectAabbMax(
                    minX, minY, minZ, maxX, maxY, maxZ,
                    up.x, up.y, up.z));
            receivers++;
        }

        if (receivers == 0
                || !Double.isFinite(minRight) || !Double.isFinite(maxRight)
                || !Double.isFinite(minUp) || !Double.isFinite(maxUp)) {
            return PrismReceiverAwareCasterMath.Bounds.EMPTY;
        }

        // Quantize the guard-expanded bounds to whole section units. This prevents tiny camera
        // translations from moving the caster boundary every frame while keeping a two-section
        // safety margin for off-screen occluders whose light rays land on visible receiver terrain.
        return PrismReceiverAwareCasterMath.guardAndQuantize(
                receivers, minRight, maxRight, minUp, maxUp,
                SHADOW_STABLE_CASTER_GUARD_BLOCKS, SECTION_SIZE);
    }

    private static boolean renderSectionMatches(
            SectionRenderDispatcher.RenderSection renderSection,
            int sectionX,
            int sectionY,
            int sectionZ) {
        BlockPos origin = renderSection.getRenderOrigin();
        return origin != null
                && origin.getX() == (sectionX << 4)
                && origin.getY() == (sectionY << 4)
                && origin.getZ() == (sectionZ << 4);
    }

    private static ShadowLightState captureCelestialLightState() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.gameRenderer == null) {
            return ShadowLightState.EMPTY;
        }

        float tickDelta = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
        float rawSun = probe.getValue(EnvironmentAttributes.SUN_ANGLE, tickDelta);
        float rawMoon = probe.getValue(EnvironmentAttributes.MOON_ANGLE, tickDelta);
        if (!Float.isFinite(rawSun) || !Float.isFinite(rawMoon)) {
            return ShadowLightState.EMPTY;
        }

        float sunAngle = PrismCelestialShadowMath.adjustedAngleDegrees(rawSun);
        float moonAngle = PrismCelestialShadowMath.adjustedAngleDegrees(rawMoon);
        boolean sun = PrismCelestialShadowMath.isDayFromRawSunAngle(rawSun);
        float selectedRaw = sun ? rawSun : rawMoon;
        float selectedAngle = sun ? sunAngle : moonAngle;
        PrismCelestialShadowMath.LightBasis basis = PrismCelestialShadowMath.lightBasis(
                selectedRaw, SHADOW_SUN_PATH_ROTATION_DEGREES);
        float strengthScale = PrismCelestialShadowMath.horizonStrength(
                basis.rayY(), SHADOW_HORIZON_FADE_START, SHADOW_HORIZON_FULL_STRENGTH);
        int bucket = PrismCelestialShadowMath.cullingBucket(
                selectedAngle, SHADOW_CULLING_LIGHT_STEP_DEGREES);
        long revision = shadowLightSampleRevision++;
        return new ShadowLightState(
                revision, sun, sunAngle, moonAngle, selectedAngle,
                basis.rayX(), basis.rayY(), basis.rayZ(),
                basis.upX(), basis.upY(), basis.upZ(),
                strengthScale,
                bucket);
    }

    private static void logCelestialLightState(ShadowLightState lightState) {
        boolean kindChanged = !lastLoggedLightKind.equals(lightState.kind());
        if (!celestialLightLogged || kindChanged) {
            celestialLightLogged = true;
            lastLoggedLightKind = lightState.kind();
            PrismMod.LOGGER.info(
                    "Prism beta celestial shadow light: kind={}, sunAngle={}, moonAngle={}, selectedAngle={}, strengthScale={}, dir=({}, {}, {}), pathRotation={}, lightRevision={}, cullingBucket={}",
                    lightState.kind(),
                    formatAngle(lightState.sunAngleDegrees()),
                    formatAngle(lightState.moonAngleDegrees()),
                    formatAngle(lightState.selectedAngleDegrees()),
                    formatDirection(lightState.strengthScale()),
                    formatDirection(lightState.rayX()),
                    formatDirection(lightState.rayY()),
                    formatDirection(lightState.rayZ()),
                    SHADOW_SUN_PATH_ROTATION_DEGREES,
                    lightState.revision(),
                    lightState.cullingBucket());
        }
    }

    private static String formatAngle(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String formatDirection(float value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }


    private static String formatBound(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    public static String receiverCasterDebugLine() {
        PrismReceiverAwareCasterMath.Bounds bounds = receiverCasterBounds;
        PrismStableCasterVolumeMath.Volume volume = stableCasterVolume;
        return String.format(
                Locale.ROOT,
                "Shadow stable volume: cell %d/%d/%d | reject %d | recvDiag %d | identity %d/%d",
                volume.anchorRightCell(), volume.anchorUpCell(), volume.anchorDepthCell(),
                stableVolumeRejectedCandidates, bounds.receiverSections(),
                residentIdentityRejects, drawIdentityRejects);
    }

    public static String receiverSamplingDebugLine() {
        return "Shadow sampling: PCF 3x3 quadratic | 9 taps | motion-stable";
    }

    public static String celestialDebugLine() {
        ShadowLightState light = shadowLightState;
        if (!light.available()) {
            return "Shadow light: waiting for celestial state";
        }
        return String.format(
                Locale.ROOT,
                "Shadow light: %s %.2f deg | dir %.2f %.2f %.2f",
                light.kind(), light.selectedAngleDegrees(),
                light.rayX(), light.rayY(), light.rayZ());
    }

    public static String celestialCacheDebugLine() {
        ShadowLightState light = shadowLightState;
        if (!light.available()) {
            return "Shadow light rev: unavailable";
        }
        return String.format(
                Locale.ROOT,
                "Shadow light rev: %d | visibility %d | bucket %d",
                light.revision(), visibilityLightRevision, visibilityLightBucket);
    }

    private static void publishShadowReceiverFrame(
            long visibilityIndex,
            Matrix4fc mainProjection,
            Matrix4fc mainModelView,
            double cameraX,
            double cameraY,
            double cameraZ,
            String matrixSource) {
        if (mainProjection == null || mainModelView == null) {
            shadowReceiverFrame = ShadowReceiverFrameData.EMPTY;
            return;
        }

        Matrix4f mainViewProjection = new Matrix4f(mainProjection).mul(mainModelView);
        float determinant = mainViewProjection.determinant();
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0e-12f) {
            shadowReceiverFrame = ShadowReceiverFrameData.EMPTY;
            return;
        }

        float[] inverse = new Matrix4f(mainViewProjection).invert().get(new float[16]);
        float[] nearShadow = directionalLightViewProjection(
                cameraX, cameraY, cameraZ,
                SHADOW_NEAR_CASCADE_HALF_EXTENT,
                SHADOW_NEAR_WORLD_UNITS_PER_TEXEL).get(new float[16]);
        float[] farShadow = directionalLightViewProjection(
                cameraX, cameraY, cameraZ,
                SHADOW_HALF_EXTENT,
                SHADOW_WORLD_UNITS_PER_TEXEL).get(new float[16]);
        if (!allFinite(inverse) || !allFinite(nearShadow) || !allFinite(farShadow)) {
            shadowReceiverFrame = ShadowReceiverFrameData.EMPTY;
            return;
        }

        shadowReceiverFrame = new ShadowReceiverFrameData(
                visibilityIndex,
                new PrismMatrix4(inverse),
                new PrismMatrix4(nearShadow),
                new PrismMatrix4(farShadow),
                cameraX, cameraY, cameraZ,
                SHADOW_RECEIVER_STRENGTH * shadowLightState.strengthScale(),
                matrixSource);
    }

    /**
     * Captures the matrices at the exact LevelRenderer.render entry used by Minecraft 26.2.
     * This follows Iris' proven rule: receiver reconstruction must use the model-view from the
     * actual world render invocation, not a later camera-state approximation.
     */
    public static void captureMainWorldRenderState(CameraRenderState cameraState, Matrix4fc modelViewMatrix) {
        if (!debugEnabled || shaderResourceReloadInProgress || failureLogged
                || cameraState == null || cameraState.pos == null
                || cameraState.projectionMatrix == null || modelViewMatrix == null) {
            return;
        }

        PrismShadowSectionFrameData visibility = shadowSectionFrame;
        if (visibility.frameIndex() < 0L) {
            return;
        }

        publishShadowReceiverFrame(
                visibility.frameIndex(),
                cameraState.projectionMatrix,
                modelViewMatrix,
                cameraState.pos.x, cameraState.pos.y, cameraState.pos.z,
                "level_renderer_render_head");

        if (!exactWorldMatrixCaptureLogged && shadowReceiverFrame.available()) {
            exactWorldMatrixCaptureLogged = true;
            float delta = cameraState.viewRotationMatrix == null
                    ? Float.NaN
                    : maxAbsMatrixDifference(modelViewMatrix, cameraState.viewRotationMatrix);
            PrismMod.LOGGER.info(
                    "Prism beta exact world model-view captured: matrixSource=level_renderer_render_head, projectionSource=cameraState.projectionMatrix, previousViewRotationMaxAbsDelta={}",
                    Float.isFinite(delta) ? String.format(Locale.ROOT, "%.8f", delta) : "unavailable");
        }
    }

    private static float maxAbsMatrixDifference(Matrix4fc a, Matrix4fc b) {
        float[] left = new Matrix4f(a).get(new float[16]);
        float[] right = new Matrix4f(b).get(new float[16]);
        float max = 0.0f;
        for (int i = 0; i < left.length; i++) {
            max = Math.max(max, Math.abs(left[i] - right[i]));
        }
        return max;
    }

    private static boolean allFinite(float[] values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static int floorSection(double blockCoordinate) {
        return (int) Math.floor(blockCoordinate / SECTION_SIZE);
    }

    private static void clearVisibilityMetrics() {
        shadowSectionFrame = PrismShadowSectionFrameData.EMPTY;
        shadowReceiverFrame = ShadowReceiverFrameData.EMPTY;
        visibilityLightBucket = Integer.MIN_VALUE;
        visibilityLightRevision = -1L;
        visibilityLightKind = "unavailable";
        receiverCasterBounds = PrismReceiverAwareCasterMath.Bounds.EMPTY;
        stableCasterVolume = PrismStableCasterVolumeMath.Volume.EMPTY;
        nearStableCasterVolume = PrismStableCasterVolumeMath.Volume.EMPTY;
        visibilityStableCasterVolume = PrismStableCasterVolumeMath.Volume.EMPTY;
        stableVolumeRejectedCandidates = 0;
        residentIdentityRejects = 0;
        drawIdentityRejects = 0;
        residentCacheViewArea = new WeakReference<>(null);
        residentCacheMinX = Integer.MIN_VALUE;
        residentCacheMinY = Integer.MIN_VALUE;
        residentCacheMinZ = Integer.MIN_VALUE;
        residentCacheHorizontalDiameter = 0;
        residentCacheVerticalSections = 0;
        residentGridMask = new long[0];
        residentDispatcher = new WeakReference<>(null);
        shadowDrawableIndexCache = ShadowDrawableIndexCache.EMPTY;
        PrismPerformanceApiImpl metrics = performanceApi;
        if (metrics != null) {
            metrics.clearShadowCullingFrame();
        }
    }

    static PrismShadowSectionFrameData shadowSectionFrame() {
        return shadowSectionFrame;
    }

    /** Scene-pipeline guard used while the explicit terrain shadow pass binds vanilla terrain PSOs. */
    static boolean isReplayActive() {
        return replayActive;
    }

    /**
     * Applies alpha.7.2.8's visible terrain shadow receiver at Fabric's documented
     * BEFORE_TRANSLUCENT_TERRAIN boundary. At this point opaque terrain and solid feature geometry
     * have been written to the main target and the world reversed-Z depth is still live.
     *
     * <p>This is deliberately earlier than first-person rendering: the alpha.7.2.2 truth probe
     * proved that the pre-hand main depth has already been cleared/reused (0.0 everywhere for
     * reversed-Z world pixels).</p>
     */
    public static void applyTerrainShadowReceiverBeforeTranslucentTerrain() {
        applyTerrainShadowReceiver("before_translucent_terrain", true);
    }

    /** Fallback only; normally the pre-translucent event has already latched this frame. */
    public static void applyTerrainShadowReceiverBeforeItemInHand() {
        applyTerrainShadowReceiver("pre_hand_fallback", false);
    }

    public static void applyTerrainShadowReceiverAfterLevelRenderFallback() {
        applyTerrainShadowReceiver("render_level_return_fallback", false);
    }

    private static void applyTerrainShadowReceiver(String hook, boolean useLiveWorldDepth) {
        if (!debugEnabled || shaderResourceReloadInProgress || failureLogged
                || !terrainReplayRecordedThisFrame) {
            return;
        }

        ShadowReceiverFrameData receiverFrame = shadowReceiverFrame;
        if (!receiverFrame.available() || frameIndex == lastReceiverAppliedFrame) {
            return;
        }

        try {
            ensureTargets();
            RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            GpuTextureView mainColor = mainTarget.getColorTextureView();
            GpuTextureView mainDepth = mainTarget.getDepthTextureView();
            if (mainColor == null || mainDepth == null) {
                throw new IllegalStateException("Minecraft main target must expose color and depth views for terrain shadow receiving");
            }

            ensureReceiverPipeline(mainColor.texture().getFormat());
            ensureShadowReceiverBuffer();

            CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
            // The pre-translucent boundary is the authoritative world-depth lifetime. Sample it
            // directly to avoid both a full-screen copy and any ambiguity about later depth reuse.
            // Legacy pre-hand/return hooks remain fallback-only and retain the snapshot path.
            ReceiverDepthInput depthInput = useLiveWorldDepth
                    ? new ReceiverDepthInput(mainDepth, "live_pre_translucent")
                    : snapshotSceneDepth(commandEncoder, mainDepth);
            writeShadowReceiverUniforms(commandEncoder, receiverFrame);
            int gpuQueryBegin = -1;
            try (RenderPass pass = commandEncoder.createRenderPass(
                    () -> "Dreamveil Prism beta pre-translucent visible terrain shadow receiver",
                    mainColor,
                    Optional.empty())) {
                gpuQueryBegin = beginReceiverGpuTiming(pass);
                pass.setPipeline(receiverPipeline);
                pass.setUniform("ShadowReceiver", shadowReceiverBuffer.slice());
                pass.bindTexture("MainDepth", depthInput.view(), receiverSampler);
                pass.bindTexture("ShadowDepthNear", shadowNearDepthView, receiverSampler);
                pass.bindTexture("ShadowDepthFar", shadowDepthView, receiverSampler);
                PrismFullscreenDraw.draw(pass);
                endReceiverGpuTiming(pass, gpuQueryBegin);
            }
            commandEncoder.submit();
            // A mod may invoke GameRenderer.renderLevel more than once before presentation.
            // Never alpha-blend the proof shadow twice for the same recorded shadow frame.
            lastReceiverAppliedFrame = frameIndex;

            if (!receiverDrawObserved) {
                receiverDrawObserved = true;
                PrismMod.LOGGER.info(
                        "Prism beta first visible terrain shadow receiver submitted: hook={}, matrixSource={}, depthSource={}, mode={}, yConvention=raw_raw, cascades={}, shadowTaps={}, strength={}, compareBias={}, reversedZ=true, visibilityFrame={}, light={}, lightAngle={}, lightRevision={}",
                        hook, receiverFrame.matrixSource(), depthInput.source(),
                        SHADOW_RECEIVER_MODE, SHADOW_QUALITY.cascadeCount(), SHADOW_RECEIVER_PCF_TAPS,
                        receiverFrame.shadowStrength(), SHADOW_RECEIVER_COMPARE_BIAS,
                        receiverFrame.visibilityFrameIndex(), shadowLightState.kind(),
                        formatAngle(shadowLightState.selectedAngleDegrees()), shadowLightState.revision());
            }

            if (lastReceiverMetricsLogFrame == Long.MIN_VALUE
                    || frameIndex - lastReceiverMetricsLogFrame >= 120L) {
                lastReceiverMetricsLogFrame = frameIndex;
                String gpuText = latestReceiverGpuTimeAvailable
                        ? String.format(Locale.ROOT, "%.3f", latestReceiverGpuNanos / 1_000_000.0)
                        : "pending";
                PrismMod.LOGGER.info(
                        "Prism terrain shadow receiver metrics: frame={}, visibilityFrame={}, gpuMs={}, hook={}, matrixSource={}, depthSource={}, mode={}, yConvention=raw_raw, cascades={}, shadowTaps={}, textureFetchesPerPixel={}, strength={}, compareBias={}, light={}, lightAngle={}, lightRevision={}, visibilityLightRevision={}",
                        frameIndex, receiverFrame.visibilityFrameIndex(), gpuText, hook,
                        receiverFrame.matrixSource(), depthInput.source(),
                        SHADOW_RECEIVER_MODE, SHADOW_QUALITY.cascadeCount(), SHADOW_RECEIVER_PCF_TAPS,
                        SHADOW_DUAL_CASCADES ? "10_or_19_blend" : "10",
                        receiverFrame.shadowStrength(), SHADOW_RECEIVER_COMPARE_BIAS,
                        shadowLightState.kind(), formatAngle(shadowLightState.selectedAngleDegrees()),
                        shadowLightState.revision(), visibilityLightRevision);
            }
        } catch (RuntimeException | LinkageError exception) {
            fail("shadow_receiver_failed", "beta terrain shadow receiver failed", exception);
        }
    }

    /**
     * Draws the shadow-depth diagnostic inset immediately before Minecraft copies the main target into {@code GpuSurface}.
     *
     * <p>Fabric's END_MAIN event is intentionally earlier than clouds/weather/late debug and the
     * Fabulous framebuffer combine. Drawing there is not a valid final-output probe because later
     * vanilla work may replace the main color target. The GpuSurface blit boundary is used only for
     * this temporary bring-up visualization. Alpha.7 keeps a compact bottom-right inset so F3 shadow
     * metrics, chat, menus, and the center hotbar remain readable.</p>
     */
    public static void drawDebugBeforeSurfaceBlit(CommandEncoder commandEncoder, GpuTextureView presentedColor) {
        if (!debugEnabled || !depthDebugEnabled || shaderResourceReloadInProgress || failureLogged) {
            return;
        }
        try {
            ensureTargets();
            ensureDebugPipeline();
            if (presentedColor == null) {
                throw new IllegalStateException("Minecraft presentation blit has no source color view");
            }

            // The D32 target proof already passed in r8. From alpha.6 onward a frame without
            // independent terrain draws is explicitly cleared to reversed-Z far (0) instead of
            // showing a synthetic proof value or stale depth from the previous frame.
            if (!terrainReplayRecordedThisFrame) {
                clearShadowDepth(commandEncoder);
            }

            try (RenderPass pass = commandEncoder
                    .createRenderPass(
                            () -> "Dreamveil Prism shadow.depth bottom-right grayscale debug inset",
                            presentedColor,
                            Optional.empty())) {
                pass.setPipeline(debugPipeline);
                pass.bindTexture("ShadowDepth", shadowDepthView, debugSampler);
                PrismFullscreenDraw.draw(pass);
            }

            if (!debugDrawObserved) {
                debugDrawObserved = true;
                PrismMod.LOGGER.info(
                        "Prism shadow.depth debug inset draw recorded before GpuSurface.blitFromTexture; independentTerrainDrawObserved={}, emptyDepthClearObserved={}, terrainReplayObserved={}",
                        independentTerrainDrawObserved, emptyDepthClearObserved, terrainReplayObserved);
                publishProofStatus();
            }
            // Consume the per-frame replay marker. The next level frame must explicitly record fresh
            // independent terrain or presentation clears shadow.depth to reversed-Z far.
            terrainReplayRecordedThisFrame = false;
        } catch (RuntimeException | LinkageError exception) {
            fail("shadow_depth_debug_draw_failed", "shadow.depth debug visualization failed", exception);
        }
    }

    /**
     * Records Prism's terrain shadow pass at the proven opaque-terrain execution boundary.
     * Alpha.7 records only light-selected renderer-owned SectionMesh draws. The main-camera
     * drawGroups fallback is intentionally removed so a missing shadow mesh can never silently
     * reintroduce camera-dependent coverage.
     */
    public static void capturePreparedOpaqueGroup(
            ChunkSectionsToRender sections,
            ChunkSectionLayerGroup group,
            GpuSampler sampler) {
        if (!debugEnabled || shaderResourceReloadInProgress || failureLogged
                || sections == null || group == null || sampler == null) {
            return;
        }

        try {
            ensureTargets();
            frameIndex++;
            terrainReplayRecordedThisFrame = false;

            int preparedSectionCount = sections.chunkSectionInfos().length;
            int maxIndicesRequired = sections.maxIndicesRequired();
            if (frameIndex == 1L) {
                PrismMod.LOGGER.info(
                        "Prism shadow terrain interception reached first frame: preparedSections={}, maxIndicesRequired={}, explicitReplay=true",
                        preparedSectionCount,
                        maxIndicesRequired);
            }

            if (!preparedDrawGroupsObserved && (preparedSectionCount > 0 || maxIndicesRequired > 0)) {
                preparedDrawGroupsObserved = true;
                PrismShadowDrawGroupProbe.capture(sections, group);
                PrismMod.LOGGER.info(
                        "Prism shadow captured first non-empty prepared terrain group: frame={}, preparedSections={}, maxIndicesRequired={}",
                        frameIndex,
                        preparedSectionCount,
                        maxIndicesRequired);
            }

            terrainReplayRecordedThisFrame = recordIndependentTerrainDepth(sections, group, sampler);
            if (terrainReplayRecordedThisFrame && !terrainReplayObserved) {
                terrainReplayObserved = true;
                PrismMod.LOGGER.info(
                        "Prism shadow.depth first terrain draw submission recorded: frame={}, preparedSections={}, maxIndicesRequired={}, independentVisibilityFrame={}",
                        frameIndex, preparedSectionCount, maxIndicesRequired, shadowSectionFrame.frameIndex());
                publishProofStatus();
            }
        } catch (RuntimeException | LinkageError exception) {
            terrainReplayRecordedThisFrame = false;
            fail("shadow_terrain_submission_failed", "26.2 independent terrain shadow submission failed", exception);
        }
    }

    private static ShadowDrawableIndexCache drawableIndices(
            PrismShadowSectionFrameData visibility,
            ViewArea viewArea,
            ChunkSectionLayerGroup group) {
        ShadowDrawableIndexCache cached = shadowDrawableIndexCache;
        if (cached.canReuse(visibility.frameIndex(), frameIndex, group.layers())) {
            return cached;
        }

        EnumMap<ChunkSectionLayer, int[]> indicesByLayer = new EnumMap<>(ChunkSectionLayer.class);
        BlockPos.MutableBlockPos sectionOrigin = new BlockPos.MutableBlockPos();
        int residentLayerEntries = 0;
        int drawableLayerEntries = 0;

        for (ChunkSectionLayer layer : group.layers()) {
            int[] scratch = new int[visibility.residentResolved()];
            int count = 0;
            for (int linearIndex = visibility.nextResidentIndex(0);
                    linearIndex >= 0;
                    linearIndex = visibility.nextResidentIndex(linearIndex + 1)) {
                residentLayerEntries++;
                int sectionX = visibility.sectionX(linearIndex);
                int sectionY = visibility.sectionY(linearIndex);
                int sectionZ = visibility.sectionZ(linearIndex);
                sectionOrigin.set(sectionX << 4, sectionY << 4, sectionZ << 4);
                var renderSection = viewArea.getRenderSectionAt(sectionOrigin);
                if (renderSection == null
                        || !renderSectionMatches(renderSection, sectionX, sectionY, sectionZ)) {
                    continue;
                }
                SectionMesh mesh = renderSection.getSectionMesh();
                if (mesh == null || mesh.isEmpty(layer)) {
                    continue;
                }
                SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
                if (draw == null || draw.indexCount() <= 0) {
                    continue;
                }
                scratch[count++] = linearIndex;
            }
            int[] compact = java.util.Arrays.copyOf(scratch, count);
            indicesByLayer.put(layer, compact);
            drawableLayerEntries += count;
        }

        ShadowDrawableIndexCache rebuilt = new ShadowDrawableIndexCache(
                visibility.frameIndex(), frameIndex,
                residentLayerEntries, drawableLayerEntries,
                indicesByLayer);
        shadowDrawableIndexCache = rebuilt;
        return rebuilt;
    }

    private static CascadeDrawStats recordCascadeDepth(
            RenderPass renderPass,
            GpuBuffer projectionBuffer,
            boolean nearCascade,
            PrismShadowSectionFrameData visibility,
            ShadowReceiverFrameData receiverFrame,
            ShadowDrawableIndexCache drawableIndices,
            ViewArea viewArea,
            SectionRenderDispatcher dispatcher,
            ChunkSectionsToRender sections,
            ChunkSectionLayerGroup group,
            GpuSampler chunkSampler) {
        RenderSystem.bindDefaultUniforms(renderPass);
        renderPass.setUniform("Projection", projectionBuffer.slice());
        renderPass.setUniform("ChunkSection", shadowDummyChunkSectionBuffer.slice());
        renderPass.bindTexture("Sampler2", sections.textureView(), chunkSampler);

        int cameraSectionX = floorSection(receiverFrame.cameraX());
        int cameraSectionY = floorSection(receiverFrame.cameraY());
        int cameraSectionZ = floorSection(receiverFrame.cameraZ());
        BlockPos.MutableBlockPos sectionOrigin = new BlockPos.MutableBlockPos();
        int drawCount = 0;
        int readySections = 0;
        int missingAtDraw = 0;
        int identityRejected = 0;
        int staleEmptyLayers = 0;
        int volumeCulled = 0;
        long submittedIndexCount = 0L;

        ShadowLightState light = shadowLightState;
        Vector3f lightRay = new Vector3f(light.rayX(), light.rayY(), light.rayZ());
        Vector3f lightUp = new Vector3f(light.upX(), light.upY(), light.upZ());
        Vector3f lightRight = new Vector3f(lightRay).cross(lightUp).normalize();

        for (ChunkSectionLayer layer : group.layers()) {
            RenderPipeline vanillaPipeline = layer.pipeline();
            RenderPipeline shadowPipeline = shadowCasterPipeline(layer, vanillaPipeline);
            renderPass.setPipeline(shadowPipeline);
            renderPass.bindTexture("Sampler0", sections.textureView(), chunkSampler);
            var sequentialBuffer = RenderSystem.getSequentialBuffer(vanillaPipeline.getPrimitiveTopology());

            for (int linearIndex : drawableIndices.indices(layer)) {
                int sectionX = visibility.sectionX(linearIndex);
                int sectionY = visibility.sectionY(linearIndex);
                int sectionZ = visibility.sectionZ(linearIndex);
                if (nearCascade && !PrismStableCasterVolumeMath.overlaps(
                        nearStableCasterVolume,
                        sectionX * SECTION_SIZE, sectionY * SECTION_SIZE, sectionZ * SECTION_SIZE,
                        (sectionX + 1) * SECTION_SIZE,
                        (sectionY + 1) * SECTION_SIZE,
                        (sectionZ + 1) * SECTION_SIZE,
                        lightRight.x, lightRight.y, lightRight.z,
                        lightUp.x, lightUp.y, lightUp.z,
                        lightRay.x, lightRay.y, lightRay.z)) {
                    volumeCulled++;
                    continue;
                }

                sectionOrigin.set(sectionX << 4, sectionY << 4, sectionZ << 4);
                var renderSection = viewArea.getRenderSectionAt(sectionOrigin);
                if (renderSection == null) {
                    missingAtDraw++;
                    continue;
                }
                if (!renderSectionMatches(renderSection, sectionX, sectionY, sectionZ)) {
                    identityRejected++;
                    continue;
                }

                SectionMesh mesh = renderSection.getSectionMesh();
                if (mesh == null || mesh.isEmpty(layer)) {
                    staleEmptyLayers++;
                    continue;
                }
                SectionMesh.SectionDraw draw = mesh.getSectionDraw(layer);
                if (draw == null || draw.indexCount() <= 0) {
                    staleEmptyLayers++;
                    continue;
                }
                SectionRenderDispatcher.RenderSectionBufferSlice slice =
                        dispatcher.getRenderSectionSlice(mesh, layer);
                if (slice == null || slice.vertexBuffer() == null) {
                    missingAtDraw++;
                    continue;
                }

                int vertexSize = layer.vertexFormat().getVertexSize();
                int baseVertex = PrismShadowDrawEncoding.baseVertex(
                        slice.vertexBufferOffset(), vertexSize);
                int packedSection = PrismShadowDrawEncoding.packRelativeSection(
                        sectionX - cameraSectionX,
                        sectionY - cameraSectionY,
                        sectionZ - cameraSectionZ);

                GpuBuffer indexBuffer;
                com.mojang.blaze3d.IndexType indexType;
                int firstIndex;
                if (draw.hasCustomIndexBuffer()) {
                    indexBuffer = slice.indexBuffer();
                    if (indexBuffer == null) {
                        missingAtDraw++;
                        continue;
                    }
                    indexType = draw.indexType();
                    firstIndex = PrismShadowDrawEncoding.firstIndex(
                            slice.indexBufferOffset(), indexType.bytes);
                } else {
                    indexBuffer = sequentialBuffer.getBuffer(draw.indexCount());
                    indexType = sequentialBuffer.type();
                    firstIndex = 0;
                }

                renderPass.setVertexBuffer(0, slice.vertexBuffer().slice());
                renderPass.setIndexBuffer(indexBuffer, indexType);
                renderPass.drawIndexed(
                        draw.indexCount(), 1, firstIndex, baseVertex, packedSection);
                drawCount++;
                readySections++;
                submittedIndexCount += draw.indexCount();
            }
        }

        return new CascadeDrawStats(
                drawCount, readySections, missingAtDraw, identityRejected,
                staleEmptyLayers, volumeCulled, submittedIndexCount);
    }

    private static boolean recordIndependentTerrainDepth(
            ChunkSectionsToRender sections,
            ChunkSectionLayerGroup group,
            GpuSampler chunkSampler) {
        PrismShadowSectionFrameData visibility = shadowSectionFrame;
        ViewArea viewArea = residentCacheViewArea.get();
        SectionRenderDispatcher dispatcher = residentDispatcher.get();
        if (visibility.frameIndex() < 0L || visibility.residentResolved() == 0
                || viewArea == null || dispatcher == null) {
            return false;
        }

        ShadowReceiverFrameData receiverFrame = shadowReceiverFrame;
        if (!receiverFrame.available()) {
            return false;
        }

        ShadowDrawableIndexCache drawableIndices = drawableIndices(visibility, viewArea, group);

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        ensureShadowProjectionBuffer();
        ensureDummyChunkSectionBuffer();
        // Each cascade owns immutable projection bytes for the duration of this command buffer.
        writeDirectionalProjection(
                commandEncoder, shadowNearProjectionBuffer,
                receiverFrame.nearShadowViewProjection());
        writeDirectionalProjection(
                commandEncoder, shadowProjectionBuffer,
                receiverFrame.farShadowViewProjection());

        CascadeDrawStats nearStats = new CascadeDrawStats(0, 0, 0, 0, 0, 0, 0L);
        CascadeDrawStats farStats;
        int gpuQueryBegin;
        replayActive = true;
        try {
            gpuQueryBegin = -1;
            if (SHADOW_DUAL_CASCADES) {
                try (RenderPass nearPass = commandEncoder.createRenderPass(
                        () -> "Dreamveil Prism near stabilized terrain shadow.depth",
                        shadowColorView,
                        Optional.empty(),
                        shadowNearDepthView,
                        OptionalDouble.of(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE))) {
                    gpuQueryBegin = beginShadowGpuTiming(nearPass);
                    nearStats = recordCascadeDepth(
                            nearPass, shadowNearProjectionBuffer, true,
                            visibility, receiverFrame, drawableIndices,
                            viewArea, dispatcher, sections, group, chunkSampler);
                }
            }
            try (RenderPass farPass = commandEncoder.createRenderPass(
                    () -> "Dreamveil Prism far stabilized terrain shadow.depth",
                    shadowColorView,
                    Optional.empty(),
                    shadowDepthView,
                    OptionalDouble.of(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE))) {
                if (gpuQueryBegin < 0) {
                    gpuQueryBegin = beginShadowGpuTiming(farPass);
                }
                farStats = recordCascadeDepth(
                        farPass, shadowProjectionBuffer, false,
                        visibility, receiverFrame, drawableIndices,
                        viewArea, dispatcher, sections, group, chunkSampler);
                endShadowGpuTiming(farPass, gpuQueryBegin);
            }
        } finally {
            replayActive = false;
        }

        commandEncoder.submit();
        int drawCount = nearStats.drawCount() + farStats.drawCount();
        int readySections = nearStats.readySections() + farStats.readySections();
        int missingAtDraw = nearStats.missingAtDraw() + farStats.missingAtDraw();
        int drawIdentityRejected = nearStats.identityRejected() + farStats.identityRejected();
        int staleEmptyLayers = nearStats.staleEmptyLayers() + farStats.staleEmptyLayers();
        int occupancySkippedPairs = drawableIndices.residentLayerEntries()
                - drawableIndices.drawableLayerEntries();
        long submittedIndexCount = nearStats.submittedIndexCount() + farStats.submittedIndexCount();
        drawIdentityRejects = drawIdentityRejected;

        PrismPerformanceApiImpl metrics = performanceApi;
        if (metrics != null) {
            metrics.recordShadowCullingFrame(
                    visibility.frameIndex(),
                    visibility.candidates(),
                    visibility.accepted(),
                    visibility.culled(),
                    drawCount,
                    latestShadowGpuTimeAvailable,
                    latestShadowGpuNanos);
        }

        if (drawCount > 0
                && (lastDrawMetricsLogFrame == Long.MIN_VALUE
                        || frameIndex - lastDrawMetricsLogFrame >= 120L)) {
            lastDrawMetricsLogFrame = frameIndex;
            String gpuText = latestShadowGpuTimeAvailable
                    ? String.format(Locale.ROOT, "%.3f", latestShadowGpuNanos / 1_000_000.0)
                    : "pending";
            PrismMod.LOGGER.info(
                    "Prism shadow terrain metrics: visibilityFrame={}, quality={}, cascades={}, nearDraws={}, farDraws={}, totalDraws={}, nearVolumeCulled={}, indices={}, accepted={}, culled={}, residentResolved={}, missingAtDraw={}, occupancySkippedPairs={}, staleEmptyLayers={}, drawableCacheEntries={}, drawableCacheRefreshFrames={}, stableVolumeRejected={}, residentIdentityRejects={}, drawIdentityRejects={}, gpuMs={}, nearTexelWorldSize={}, farTexelWorldSize={}, biasConstant={}, biasSlope={}",
                    visibility.frameIndex(), SHADOW_QUALITY.id(), SHADOW_QUALITY.cascadeCount(),
                    nearStats.drawCount(), farStats.drawCount(), drawCount,
                    nearStats.volumeCulled(), submittedIndexCount,
                    visibility.accepted(), visibility.culled(), visibility.residentResolved(),
                    missingAtDraw, occupancySkippedPairs, staleEmptyLayers,
                    drawableIndices.drawableLayerEntries(),
                    SHADOW_DRAWABLE_INDEX_REFRESH_FRAMES,
                    stableVolumeRejectedCandidates, residentIdentityRejects, drawIdentityRejects,
                    gpuText, SHADOW_NEAR_WORLD_UNITS_PER_TEXEL, SHADOW_WORLD_UNITS_PER_TEXEL,
                    SHADOW_CONSTANT_DEPTH_BIAS, SHADOW_SLOPE_DEPTH_BIAS);
        }

        if (drawCount > 0) {
            PrismMod.LOGGER.debug(
                    "Prism independent terrain shadow frame={}: visibilityFrame={}, cascades={}, nearDraws={}, farDraws={}, totalDraws={}, indices={}, readyLayerSections={}, missingAtDraw={}, occupancySkippedPairs={}, staleEmptyLayers={}, accepted={}, residentResolved={}",
                    frameIndex, visibility.frameIndex(), SHADOW_QUALITY.cascadeCount(), nearStats.drawCount(), farStats.drawCount(),
                    drawCount, submittedIndexCount,
                    readySections, missingAtDraw, occupancySkippedPairs, staleEmptyLayers,
                    visibility.accepted(), visibility.residentResolved());
            if (!independentTerrainDrawObserved) {
                independentTerrainDrawObserved = true;
                PrismMod.LOGGER.info(
                        "Prism first independent light-selected terrain shadow draws recorded: visibilityFrame={}, cascades={}, nearDraws={}, farDraws={}, totalDraws={}, indices={}, accepted={}, residentResolved={}",
                        visibility.frameIndex(), SHADOW_QUALITY.cascadeCount(), nearStats.drawCount(), farStats.drawCount(),
                        drawCount, submittedIndexCount,
                        visibility.accepted(), visibility.residentResolved());
            }
        }
        return drawCount > 0;
    }

    private static int beginReceiverGpuTiming(RenderPass renderPass) {
        ensureReceiverGpuQueries();
        if (receiverGpuQueryPool == null) {
            return -1;
        }
        int slot = (int) (receiverGpuQuerySequence % RECEIVER_GPU_QUERY_RING);
        int begin = slot * 2;
        int end = begin + 1;
        if (receiverGpuQuerySequence >= RECEIVER_GPU_QUERY_RING) {
            pollReceiverGpuTiming(begin, end);
        }
        try {
            renderPass.writeTimestamp(receiverGpuQueryPool, begin);
            return begin;
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("Prism shadow receiver GPU timestamp begin write failed", exception);
            return -1;
        }
    }

    private static void endReceiverGpuTiming(RenderPass renderPass, int begin) {
        if (receiverGpuQueryPool == null || begin < 0) {
            return;
        }
        try {
            renderPass.writeTimestamp(receiverGpuQueryPool, begin + 1);
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("Prism shadow receiver GPU timestamp end write failed", exception);
        } finally {
            receiverGpuQuerySequence++;
        }
    }

    private static void ensureReceiverGpuQueries() {
        if (receiverGpuQueryPool != null || receiverGpuQueriesUnavailable) {
            return;
        }
        try {
            var device = RenderSystem.getDevice();
            receiverGpuTimestampPeriod = Math.max(0.000001f, device.getDeviceInfo().timestampPeriod());
            receiverGpuQueryPool = device.createTimestampQueryPool(RECEIVER_GPU_QUERY_RING * 2);
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("Prism shadow receiver GPU timestamp queries unavailable", exception);
            receiverGpuQueryPool = null;
            receiverGpuQueriesUnavailable = true;
        }
    }

    private static void pollReceiverGpuTiming(int begin, int end) {
        if (receiverGpuQueryPool == null) {
            return;
        }
        try {
            OptionalLong start = receiverGpuQueryPool.getValue(begin);
            OptionalLong finish = receiverGpuQueryPool.getValue(end);
            if (start.isPresent() && finish.isPresent() && finish.getAsLong() >= start.getAsLong()) {
                double nanos = (finish.getAsLong() - start.getAsLong()) * (double) receiverGpuTimestampPeriod;
                if (Double.isFinite(nanos) && nanos >= 0.0 && nanos <= Long.MAX_VALUE) {
                    latestReceiverGpuNanos = Math.round(nanos);
                    latestReceiverGpuTimeAvailable = true;
                }
            }
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("Prism shadow receiver GPU timestamp query read failed", exception);
        }
    }

    private static void closeReceiverGpuQueries() {
        if (receiverGpuQueryPool != null) {
            try {
                receiverGpuQueryPool.close();
            } catch (RuntimeException exception) {
                PrismMod.LOGGER.debug("Prism shadow receiver GPU query pool cleanup failed", exception);
            }
            receiverGpuQueryPool = null;
        }
        receiverGpuQuerySequence = 0L;
        receiverGpuQueriesUnavailable = false;
        receiverGpuTimestampPeriod = 1.0f;
        latestReceiverGpuNanos = 0L;
        latestReceiverGpuTimeAvailable = false;
    }

    private static int beginShadowGpuTiming(RenderPass renderPass) {
        ensureShadowGpuQueries();
        if (shadowGpuQueryPool == null) {
            return -1;
        }
        int slot = (int) (shadowGpuQuerySequence % SHADOW_GPU_QUERY_RING);
        int begin = slot * 2;
        int end = begin + 1;
        if (shadowGpuQuerySequence >= SHADOW_GPU_QUERY_RING) {
            pollShadowGpuTiming(begin, end);
        }
        try {
            renderPass.writeTimestamp(shadowGpuQueryPool, begin);
            return begin;
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("Prism shadow GPU timestamp begin write failed", exception);
            return -1;
        }
    }

    private static void endShadowGpuTiming(RenderPass renderPass, int begin) {
        if (shadowGpuQueryPool == null || begin < 0) {
            return;
        }
        try {
            renderPass.writeTimestamp(shadowGpuQueryPool, begin + 1);
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("Prism shadow GPU timestamp end write failed", exception);
        } finally {
            shadowGpuQuerySequence++;
        }
    }

    private static void ensureShadowGpuQueries() {
        if (shadowGpuQueryPool != null || shadowGpuQueriesUnavailable) {
            return;
        }
        try {
            var device = RenderSystem.getDevice();
            shadowGpuTimestampPeriod = Math.max(0.000001f, device.getDeviceInfo().timestampPeriod());
            shadowGpuQueryPool = device.createTimestampQueryPool(SHADOW_GPU_QUERY_RING * 2);
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("Prism shadow GPU timestamp queries unavailable", exception);
            shadowGpuQueryPool = null;
            shadowGpuQueriesUnavailable = true;
        }
    }

    private static void pollShadowGpuTiming(int begin, int end) {
        if (shadowGpuQueryPool == null) {
            return;
        }
        try {
            OptionalLong start = shadowGpuQueryPool.getValue(begin);
            OptionalLong finish = shadowGpuQueryPool.getValue(end);
            if (start.isPresent() && finish.isPresent() && finish.getAsLong() >= start.getAsLong()) {
                double nanos = (finish.getAsLong() - start.getAsLong()) * (double) shadowGpuTimestampPeriod;
                if (Double.isFinite(nanos) && nanos >= 0.0 && nanos <= Long.MAX_VALUE) {
                    latestShadowGpuNanos = Math.round(nanos);
                    latestShadowGpuTimeAvailable = true;
                }
            }
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.debug("Prism shadow GPU timestamp query read failed", exception);
        }
    }

    private static void closeShadowGpuQueries() {
        if (shadowGpuQueryPool != null) {
            try {
                shadowGpuQueryPool.close();
            } catch (RuntimeException exception) {
                PrismMod.LOGGER.debug("Prism shadow GPU query pool cleanup failed", exception);
            }
            shadowGpuQueryPool = null;
        }
        shadowGpuQuerySequence = 0L;
        shadowGpuQueriesUnavailable = false;
        shadowGpuTimestampPeriod = 1.0f;
        latestShadowGpuNanos = 0L;
        latestShadowGpuTimeAvailable = false;
    }

    private static void ensureDummyChunkSectionBuffer() {
        if (shadowDummyChunkSectionBuffer != null) {
            return;
        }
        shadowDummyChunkSectionBuffer = RenderSystem.getDevice().createBuffer(
                () -> "Dreamveil Prism unused ChunkSection compatibility UBO",
                GpuBuffer.USAGE_UNIFORM,
                DUMMY_CHUNK_SECTION_UBO_BYTES);
    }

    private static ReceiverDepthInput snapshotSceneDepth(
            CommandEncoder commandEncoder,
            GpuTextureView liveDepth) {
        GpuTexture source = liveDepth.texture();
        if ((source.usage() & GpuTexture.USAGE_COPY_SRC) == 0) {
            if (!sceneDepthCopyUnsupportedLogged) {
                sceneDepthCopyUnsupportedLogged = true;
                PrismMod.LOGGER.warn(
                        "Prism beta main depth lacks COPY_SRC; receiver is using the live pre-hand depth view. sourceUsage=0x{}",
                        Integer.toHexString(source.usage()));
            }
            return new ReceiverDepthInput(liveDepth, "live_pre_hand_no_copy_src");
        }

        ensureSceneDepthSnapshot(liveDepth);
        int width = liveDepth.getWidth(0);
        int height = liveDepth.getHeight(0);
        commandEncoder.copyTextureToTexture(
                source, sceneDepthSnapshot,
                0,
                0, 0,
                0, 0,
                width, height);

        if (!sceneDepthSnapshotLogged) {
            sceneDepthSnapshotLogged = true;
            PrismMod.LOGGER.info(
                    "Prism beta scene depth snapshot active: depthSource=prism_scene_depth_pre_hand, format={}, size={}x{}, sourceUsage=0x{}",
                    source.getFormat(), width, height, Integer.toHexString(source.usage()));
        }
        return new ReceiverDepthInput(sceneDepthSnapshotView, "prism_scene_depth_pre_hand");
    }

    private static void ensureSceneDepthSnapshot(GpuTextureView sourceView) {
        GpuFormat format = sourceView.texture().getFormat();
        int width = sourceView.getWidth(0);
        int height = sourceView.getHeight(0);
        if (sceneDepthSnapshot != null
                && sceneDepthSnapshotFormat == format
                && sceneDepthSnapshotWidth == width
                && sceneDepthSnapshotHeight == height) {
            return;
        }

        closeSceneDepthSnapshot();
        int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
        sceneDepthSnapshot = RenderSystem.getDevice().createTexture(
                () -> "Dreamveil Prism scene depth pre-hand snapshot",
                usage,
                format,
                width, height,
                1, 1);
        sceneDepthSnapshotView = RenderSystem.getDevice().createTextureView(sceneDepthSnapshot);
        sceneDepthSnapshotFormat = format;
        sceneDepthSnapshotWidth = width;
        sceneDepthSnapshotHeight = height;
    }

    private static void closeSceneDepthSnapshot() {
        closeView(sceneDepthSnapshotView, "scene depth snapshot view");
        sceneDepthSnapshotView = null;
        closeTexture(sceneDepthSnapshot, "scene depth snapshot");
        sceneDepthSnapshot = null;
        sceneDepthSnapshotFormat = null;
        sceneDepthSnapshotWidth = 0;
        sceneDepthSnapshotHeight = 0;
    }

    private static void ensureShadowReceiverBuffer() {
        if (shadowReceiverBuffer != null) {
            return;
        }
        shadowReceiverBuffer = RenderSystem.getDevice().createBuffer(
                () -> "Dreamveil Prism terrain ShadowReceiver UBO",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                RECEIVER_UBO_BYTES);
    }

    private static void writeShadowReceiverUniforms(
            CommandEncoder commandEncoder,
            ShadowReceiverFrameData receiverFrame) {
        ByteBuffer encoded = ByteBuffer.allocateDirect(RECEIVER_UBO_BYTES)
                .order(ByteOrder.nativeOrder());
        putMatrix(encoded, receiverFrame.inverseMainViewProjection());
        putMatrix(encoded, receiverFrame.nearShadowViewProjection());
        putMatrix(encoded, receiverFrame.farShadowViewProjection());
        encoded.putFloat(receiverFrame.shadowStrength());
        encoded.putFloat(SHADOW_RECEIVER_COMPARE_BIAS);
        encoded.putFloat(MAIN_DEPTH_CLEAR_EPSILON);
        encoded.putFloat(SHADOW_DUAL_CASCADES ? SHADOW_CASCADE_FULL_NEAR_UV : 0.0f);
        encoded.flip();
        commandEncoder.writeToBuffer(shadowReceiverBuffer.slice(), encoded);
    }

    private static void putMatrix(ByteBuffer target, PrismMatrix4 matrix) {
        for (float value : matrix.toArray()) {
            target.putFloat(value);
        }
    }

    private static void ensureShadowProjectionBuffer() {
        if (shadowProjectionBuffer != null && shadowNearProjectionBuffer != null) {
            return;
        }
        if (shadowProjectionBuffer == null) {
            shadowProjectionBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Dreamveil Prism far directional shadow Projection UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    PROJECTION_UBO_BYTES);
        }
        if (shadowNearProjectionBuffer == null) {
            shadowNearProjectionBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "Dreamveil Prism near directional shadow Projection UBO",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    PROJECTION_UBO_BYTES);
        }
    }

    private static void writeDirectionalProjection(
            CommandEncoder commandEncoder,
            GpuBuffer projectionBuffer,
            PrismMatrix4 shadowViewProjection) {
        float[] matrix = shadowViewProjection.toArray();
        ByteBuffer encoded = ByteBuffer.allocateDirect(PROJECTION_UBO_BYTES)
                .order(ByteOrder.nativeOrder());
        for (float value : matrix) {
            encoded.putFloat(value);
        }
        encoded.flip();
        commandEncoder.writeToBuffer(projectionBuffer.slice(), encoded);
    }

    private static Matrix4f directionalLightViewProjection(
            double cameraX,
            double cameraY,
            double cameraZ,
            float halfExtent,
            double worldUnitsPerTexel) {
        ShadowLightState lightState = shadowLightState;
        if (!lightState.available()) {
            throw new IllegalStateException("Directional shadow matrix requested before celestial light capture");
        }
        Vector3f rayDirection = new Vector3f(
                lightState.rayX(), lightState.rayY(), lightState.rayZ());
        Vector3f eye = new Vector3f(rayDirection).mul(-SHADOW_LIGHT_DISTANCE);

        // The celestial-path tangent is a stable up vector even when the sun/moon is at zenith.
        // Using world +Y here would make lookAt degenerate exactly when the light becomes vertical.
        Vector3f up = new Vector3f(lightState.upX(), lightState.upY(), lightState.upZ());
        Vector3f right = new Vector3f(rayDirection).cross(up).normalize();
        double cameraLightX = right.x * cameraX + right.y * cameraY + right.z * cameraZ;
        double cameraLightY = up.x * cameraX + up.y * cameraY + up.z * cameraZ;
        float snapTranslateX = (float) PrismShadowStabilization.viewTranslation(
                cameraLightX, worldUnitsPerTexel);
        float snapTranslateY = (float) PrismShadowStabilization.viewTranslation(
                cameraLightY, worldUnitsPerTexel);

        Matrix4f lightView = new Matrix4f().lookAt(
                eye.x, eye.y, eye.z,
                0.0f, 0.0f, 0.0f,
                up.x, up.y, up.z);
        lightView.m30(lightView.m30() + snapTranslateX);
        lightView.m31(lightView.m31() + snapTranslateY);

        Matrix4f lightProjection = reversedZeroToOneOrtho(
                halfExtent, SHADOW_NEAR, SHADOW_FAR);
        return lightProjection.mul(lightView);
    }

    /**
     * Right-handed orthographic projection for Vulkan/Blaze3D zero-to-one clip depth with
     * reversed-Z. View-space points in front of the light camera use negative Z: -near -> 1 and
     * -far -> 0, matching Minecraft 26.2's GREATER-style depth convention and clear value 0.
     */
    private static Matrix4f reversedZeroToOneOrtho(float halfExtent, float nearPlane, float farPlane) {
        if (!(halfExtent > 0.0f) || !(nearPlane > 0.0f) || !(farPlane > nearPlane)) {
            throw new IllegalArgumentException("Invalid directional shadow projection volume");
        }
        float inverseExtent = 1.0f / halfExtent;
        float inverseDepthRange = 1.0f / (farPlane - nearPlane);
        return new Matrix4f()
                .zero()
                .m00(inverseExtent)
                .m11(inverseExtent)
                .m22(inverseDepthRange)
                .m32(farPlane * inverseDepthRange)
                .m33(1.0f);
    }

    private static RenderPipeline shadowCasterPipeline(
            ChunkSectionLayer layer,
            RenderPipeline vanillaPipeline) {
        RenderPipeline cached = SHADOW_CASTER_PIPELINES.get(layer);
        if (cached != null) {
            return cached;
        }

        String suffix = layer.name().toLowerCase(Locale.ROOT);
        Identifier vertexId = Identifier.fromNamespaceAndPath(
                PrismMod.MOD_ID, "shadow/directional_terrain_vertex");
        Identifier fragmentId = Identifier.fromNamespaceAndPath(
                PrismMod.MOD_ID, "shadow/directional_terrain_fragment");
        Identifier pipelineId = Identifier.fromNamespaceAndPath(
                PrismMod.MOD_ID, "shadow/directional_terrain_" + suffix);

        RenderPipeline pipeline = PrismRenderPipelineDeriver.derive(
                vanillaPipeline, pipelineId, vertexId, fragmentId);
        ShaderSource source = (id, type) -> {
            if (type == ShaderType.VERTEX && id.equals(vertexId)) return SHADOW_CASTER_VERTEX_SOURCE;
            if (type == ShaderType.FRAGMENT && id.equals(fragmentId)) return SHADOW_CASTER_FRAGMENT_SOURCE;
            return null;
        };
        CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(pipeline, source);
        if (!compiled.isValid()) {
            throw new IllegalStateException(
                    "directional shadow caster pipeline did not compile for " + layer
                            + "; inspect latest.log");
        }
        SHADOW_CASTER_PIPELINES.put(layer, pipeline);
        PrismMod.LOGGER.info(
                "Prism directional shadow caster pipeline ready: layer={}, template={}, pipeline={}",
                layer, vanillaPipeline.getLocation(), pipeline.getLocation());
        return pipeline;
    }


    /** Clears shadow.depth to reversed-Z far when no independent terrain batch was ready this frame. */
    private static void clearShadowDepth(CommandEncoder commandEncoder) {
        try (RenderPass ignored = commandEncoder.createRenderPass(
                () -> "Dreamveil Prism empty near shadow.depth clear",
                shadowColorView,
                Optional.empty(),
                shadowNearDepthView,
                OptionalDouble.of(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE))) {
            // Attachment clear only.
        }
        try (RenderPass ignored = commandEncoder.createRenderPass(
                () -> "Dreamveil Prism empty far shadow.depth clear",
                shadowColorView,
                Optional.empty(),
                shadowDepthView,
                OptionalDouble.of(RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE))) {
            // Attachment clear only.
        }
        if (!emptyDepthClearObserved) {
            emptyDepthClearObserved = true;
            PrismMod.LOGGER.info(
                    "Prism shadow.depth empty-frame clear recorded: reversedZClear={}",
                    RenderSystem.DEFAULT_DEPTH_CLEAR_VALUE);
        }
    }

    private static void ensureTargets() {
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView mainColor = mainTarget.getColorTextureView();
        if (mainColor == null) {
            throw new IllegalStateException("Minecraft main target has no color view");
        }
        GpuFormat requiredColorFormat = mainColor.texture().getFormat();

        if (shadowColor != null && shadowDepth != null && shadowNearDepth != null
                && shadowColor.getFormat() == requiredColorFormat) {
            return;
        }
        closeTargets();

        int colorUsage = GpuTexture.USAGE_RENDER_ATTACHMENT;
        int depthUsage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        shadowColor = RenderSystem.getDevice().createTexture(
                () -> "Dreamveil Prism shadow dummy color",
                colorUsage,
                requiredColorFormat,
                SHADOW_RESOLUTION,
                SHADOW_RESOLUTION,
                1,
                1);
        shadowColorView = RenderSystem.getDevice().createTextureView(shadowColor);

        shadowDepth = RenderSystem.getDevice().createTexture(
                () -> "Dreamveil Prism shadow.depth",
                depthUsage,
                GpuFormat.D32_FLOAT,
                SHADOW_RESOLUTION,
                SHADOW_RESOLUTION,
                1,
                1);
        shadowDepthView = RenderSystem.getDevice().createTextureView(shadowDepth);

        shadowNearDepth = RenderSystem.getDevice().createTexture(
                () -> "Dreamveil Prism near shadow.depth",
                depthUsage,
                GpuFormat.D32_FLOAT,
                SHADOW_RESOLUTION,
                SHADOW_RESOLUTION,
                1,
                1);
        shadowNearDepthView = RenderSystem.getDevice().createTextureView(shadowNearDepth);

        PrismMod.LOGGER.info(
                "Prism shadow targets allocated: quality={}, cascades={}, near.depth=D32_FLOAT {}x{} ({} blocks/texel), far.depth=D32_FLOAT {}x{} ({} blocks/texel), dummyColorFormat={}",
                SHADOW_QUALITY.id(), SHADOW_QUALITY.cascadeCount(),
                SHADOW_RESOLUTION, SHADOW_RESOLUTION, SHADOW_NEAR_WORLD_UNITS_PER_TEXEL,
                SHADOW_RESOLUTION, SHADOW_RESOLUTION, SHADOW_WORLD_UNITS_PER_TEXEL,
                requiredColorFormat);
    }

    private static void ensureReceiverPipeline(GpuFormat outputFormat) {
        if (receiverPipeline != null
                && receiverOutputFormat == outputFormat
                && receiverSampler != null) {
            return;
        }

        closeReceiverPipelineResources();

        Identifier vertexId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "shadow/terrain_receiver_vertex");
        Identifier fragmentId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "shadow/terrain_receiver_fragment");
        Identifier pipelineId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "shadow/terrain_receiver_pipeline");

        BindGroupLayout receiverLayout = BindGroupLayout.builder()
                .withSampler("MainDepth")
                .withSampler("ShadowDepthNear")
                .withSampler("ShadowDepthFar")
                .withUniform("ShadowReceiver", UniformType.UNIFORM_BUFFER)
                .build();

        receiverPipeline = RenderPipeline.builder()
                .withLocation(pipelineId)
                .withVertexShader(vertexId)
                .withFragmentShader(fragmentId)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(Optional.empty())
                .withColorTargetState(new ColorTargetState(
                        Optional.of(BlendFunction.TRANSLUCENT),
                        outputFormat,
                        ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(receiverLayout)
                .build();

        ShaderSource source = (id, type) -> {
            if (type == ShaderType.VERTEX && id.equals(vertexId)) return SHADOW_RECEIVER_VERTEX_SOURCE;
            if (type == ShaderType.FRAGMENT && id.equals(fragmentId)) return SHADOW_RECEIVER_FRAGMENT_SOURCE;
            return null;
        };
        CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(receiverPipeline, source);
        if (!compiled.isValid()) {
            receiverPipeline = null;
            throw new IllegalStateException("terrain shadow receiver pipeline did not compile; inspect latest.log");
        }

        receiverSampler = RenderSystem.getDevice().createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.empty());
        receiverOutputFormat = outputFormat;
    }

    private static void ensureDebugPipeline() {
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuTextureView mainColor = mainTarget.getColorTextureView();
        if (mainColor == null) {
            throw new IllegalStateException("Minecraft main target has no color view");
        }
        GpuFormat outputFormat = mainColor.texture().getFormat();
        if (debugPipeline != null && debugOutputFormat == outputFormat && debugSampler != null) {
            return;
        }

        closeDebugPipelineResources();

        Identifier vertexId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "shadow/debug_depth_vertex");
        Identifier fragmentId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "shadow/debug_depth_fragment");
        Identifier pipelineId = Identifier.fromNamespaceAndPath(PrismMod.MOD_ID, "shadow/debug_depth_pipeline");

        debugPipeline = RenderPipeline.builder()
                .withLocation(pipelineId)
                .withVertexShader(vertexId)
                .withFragmentShader(fragmentId)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(Optional.empty())
                .withColorTargetState(new ColorTargetState(Optional.empty(), outputFormat, ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("ShadowDepth").build())
                .build();

        ShaderSource source = (id, type) -> {
            if (type == ShaderType.VERTEX && id.equals(vertexId)) return DEBUG_VERTEX_SOURCE;
            if (type == ShaderType.FRAGMENT && id.equals(fragmentId)) return DEBUG_FRAGMENT_SOURCE;
            return null;
        };
        CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(debugPipeline, source);
        if (!compiled.isValid()) {
            debugPipeline = null;
            throw new IllegalStateException("shadow.depth grayscale debug pipeline did not compile; inspect latest.log");
        }

        debugSampler = RenderSystem.getDevice().createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.empty());
        debugOutputFormat = outputFormat;
    }

    private static void publishProofStatus() {
        PrismWorldRenderApiImpl sink = api;
        if (sink == null) {
            return;
        }
        if (terrainReplayObserved && (!depthDebugEnabled || debugDrawObserved)) {
            sink.publishShadowExecution(new PrismShadowExecutionSnapshot(
                    true,
                    true,
                    true,
                    "",
                    ""));
        } else {
            String waitingFor = !preparedDrawGroupsObserved
                    ? "opaque_terrain_execution_boundary"
                    : !terrainReplayObserved
                            ? "independent_light_selected_terrain_draws"
                            : "opt_in_presentation_debug_draw";
            sink.publishShadowExecution(new PrismShadowExecutionSnapshot(
                    true,
                    false,
                    true,
                    "shadow_independent_terrain_waiting",
                    "Prism shadow.depth is waiting for " + waitingFor + "."));
        }
    }

    private static void publishUnavailable(String code, String message) {
        PrismWorldRenderApiImpl sink = api;
        if (sink != null) {
            sink.publishShadowExecution(new PrismShadowExecutionSnapshot(true, false, true, code, message));
        }
    }

    private static void fail(String code, String message, Throwable exception) {
        if (!failureLogged) {
            failureLogged = true;
            PrismMod.LOGGER.error("Prism 0.14 shadow bring-up disabled: {}", message, exception);
        }
        publishUnavailable(code, message + ": " + exception.getMessage());
    }

    static PrismShadowExecutionSnapshot status() {
        PrismWorldRenderApiImpl sink = api;
        return sink == null ? PrismShadowExecutionSnapshot.UNAVAILABLE : sink.shadowExecutionSnapshot();
    }

    /**
     * Prevents any internal shadow pipeline from being submitted while Minecraft replaces its
     * shader cache. RenderPipeline is only a logical descriptor; Minecraft's resource reload drops
     * the native object cached behind it, so retaining the descriptor would make the next draw ask
     * Vanilla's ShaderManager for Prism's in-memory-only shader identifiers.
     */
    static synchronized void beginShaderResourceReload() {
        shaderResourceReloadInProgress = true;
        replayActive = false;
        invalidateShaderPipelines();
        terrainReplayRecordedThisFrame = false;
        clearVisibilityMetrics();
        PrismMod.LOGGER.info(
                "Minecraft shader-resource reload suspended Prism built-in shadows and invalidated their native pipeline handles");
    }

    /** Re-enables lazy compilation only after Vanilla's complete resource-reload barrier. */
    static synchronized void endShaderResourceReload() {
        // Defensive second invalidation: no render path should run while suspended, but this also
        // covers third-party callbacks that violate Vanilla's normal reload/render ordering.
        invalidateShaderPipelines();
        terrainReplayRecordedThisFrame = false;
        shaderResourceReloadInProgress = false;
        PrismMod.LOGGER.info(
                "Minecraft shader-resource reload released Prism built-in shadows for fresh lazy pipeline compilation");
    }

    private static void invalidateShaderPipelines() {
        SHADOW_CASTER_PIPELINES.clear();
        receiverPipeline = null;
        receiverOutputFormat = null;
        debugPipeline = null;
        debugOutputFormat = null;
    }

    static synchronized void close() {
        shaderResourceReloadInProgress = false;
        replayActive = false;
        if (shadowProjectionBuffer != null) {
            shadowProjectionBuffer.close();
            shadowProjectionBuffer = null;
        }
        if (shadowNearProjectionBuffer != null) {
            shadowNearProjectionBuffer.close();
            shadowNearProjectionBuffer = null;
        }
        if (shadowReceiverBuffer != null) {
            shadowReceiverBuffer.close();
            shadowReceiverBuffer = null;
        }
        if (shadowDummyChunkSectionBuffer != null) {
            shadowDummyChunkSectionBuffer.close();
            shadowDummyChunkSectionBuffer = null;
        }
        SHADOW_CASTER_PIPELINES.clear();
        closeReceiverPipelineResources();
        closeDebugPipelineResources();
        closeSceneDepthSnapshot();
        closeTargets();
        clearVisibilityMetrics();
        api = null;
        performanceApi = null;
        shadowSectionFrame = PrismShadowSectionFrameData.EMPTY;
        shadowReceiverFrame = ShadowReceiverFrameData.EMPTY;
        shadowLightState = ShadowLightState.EMPTY;
        depthDebugEnabled = false;
        shadowLightSampleRevision = 0L;
        visibilityLightBucket = Integer.MIN_VALUE;
        visibilityLightRevision = -1L;
        visibilityLightKind = "unavailable";
        celestialLightLogged = false;
        lastLoggedLightKind = "unavailable";
        failureLogged = false;
        preparedDrawGroupsObserved = false;
        emptyDepthClearObserved = false;
        terrainReplayObserved = false;
        independentTerrainDrawObserved = false;
        terrainReplayRecordedThisFrame = false;
        debugDrawObserved = false;
        frameIndex = 0L;
        visibilityFrameIndex = 0L;
        lastDrawMetricsLogFrame = Long.MIN_VALUE;
        lastReceiverMetricsLogFrame = Long.MIN_VALUE;
        lastReceiverAppliedFrame = Long.MIN_VALUE;
        receiverDrawObserved = false;
        exactWorldMatrixCaptureLogged = false;
        sceneDepthSnapshotLogged = false;
        sceneDepthCopyUnsupportedLogged = false;
        closeShadowGpuQueries();
        closeReceiverGpuQueries();
        visibilityCaptureLogged = false;
        visibilityFailureLogged = false;
        stationaryVisibilityReuseLogged = false;
        lastVisibilityCenterX = Integer.MIN_VALUE;
        lastVisibilityCenterY = Integer.MIN_VALUE;
        lastVisibilityCenterZ = Integer.MIN_VALUE;
    }

    private static void closeReceiverPipelineResources() {
        receiverPipeline = null;
        receiverOutputFormat = null;
        if (receiverSampler != null) {
            try {
                receiverSampler.close();
            } catch (RuntimeException exception) {
                PrismMod.LOGGER.warn("Prism shadow receiver sampler cleanup failed", exception);
            }
            receiverSampler = null;
        }
    }

    private static void closeDebugPipelineResources() {
        debugPipeline = null;
        debugOutputFormat = null;
        if (debugSampler != null) {
            try {
                debugSampler.close();
            } catch (RuntimeException exception) {
                PrismMod.LOGGER.warn("Prism shadow debug sampler cleanup failed", exception);
            }
            debugSampler = null;
        }
    }

    private static void closeTargets() {
        closeView(shadowNearDepthView, "near shadow.depth view");
        shadowNearDepthView = null;
        closeTexture(shadowNearDepth, "near shadow.depth");
        shadowNearDepth = null;
        closeView(shadowDepthView, "shadow.depth view");
        shadowDepthView = null;
        closeTexture(shadowDepth, "shadow.depth");
        shadowDepth = null;
        closeView(shadowColorView, "shadow dummy color view");
        shadowColorView = null;
        closeTexture(shadowColor, "shadow dummy color");
        shadowColor = null;
    }

    private static void closeView(GpuTextureView view, String label) {
        if (view == null) return;
        try {
            view.close();
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.warn("Prism {} cleanup failed", label, exception);
        }
    }

    private static void closeTexture(GpuTexture texture, String label) {
        if (texture == null) return;
        try {
            texture.close();
        } catch (RuntimeException exception) {
            PrismMod.LOGGER.warn("Prism {} cleanup failed", label, exception);
        }
    }
}
