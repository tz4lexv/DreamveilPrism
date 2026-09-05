package dev.dreamveil.prism.pack;

import java.util.*;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.dreamveil.prism.PrismMod;
import dev.dreamveil.prism.api.PrismLimits;
import dev.dreamveil.prism.api.visibility.PrismVisibilityVolume;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Bounded resident-model extraction; no chunk requests, recursive world render or retained entities. */
public final class PrismResidentModelViews {
    public static final int MAX_PER_DOMAIN = PrismLimits.LOADER.maxResidentModelsPerDomain();
    public static final int MAX_SCANNED = PrismLimits.LOADER.maxScannedCandidatesPerDomain();
    private record Scope(PrismVisibilityVolume volume, List<String> domains, boolean legacy) {}
    private record Query(PrismVisibilityVolume.Resolved volume, boolean legacy) {}
    private record Config(long generation, List<Scope> scopes) {
        boolean enabled() { return !scopes.isEmpty(); }
        List<Query> queries(String domain,double x,double y,double z) {
            return scopes.stream().filter(s -> s.domains.contains(domain))
                    .map(s -> new Query(s.volume.resolve(x,y,z),s.legacy)).distinct().toList();
        }
    }
    private static volatile Config config = new Config(0,List.of());
    private static volatile long failedGeneration = -1;
    private static long loggedGeneration = -1;
    private PrismResidentModelViews() {}
    static void configure(List<PrismPipelineDefinition> pipelines) {
        var scopes = new ArrayList<Scope>();
        for (var pipeline : pipelines) {
            if (!pipeline.isSceneView() || !pipeline.sceneView().capturedModels()) continue;
            var view = pipeline.sceneView();
            if (view.visibility() != null) scopes.add(new Scope(view.visibility(),view.modelDomains(),false));
            else if (view.modelRadius() > 0) scopes.add(new Scope(new PrismVisibilityVolume(
                    PrismVisibilityVolume.Shape.SPHERE,PrismVisibilityVolume.Space.CAMERA_RELATIVE,
                    0,0,0,view.modelRadius(),view.modelRadius(),view.modelRadius()),view.modelDomains(),true));
        }
        config = new Config(config.generation + 1,List.copyOf(scopes));
    }
    public static void extract(ClientLevel level, LevelRenderer renderer, LevelRenderState frame,
            Camera camera, DeltaTracker delta, float partialTick) {
        var target = (PrismResidentModelState)frame;
        target.prism$residentModels(PrismResidentModelState.Snapshot.EMPTY);
        Config request = config;
        if (level == null || !request.enabled() || failedGeneration == request.generation) return;
        try {
            var origin = camera.position();
            var entityQueries = request.queries("entity",origin.x,origin.y,origin.z);
            var blockQueries = request.queries("block_entity",origin.x,origin.y,origin.z);
            var entities = new ArrayList<EntityRenderState>();
            var blocks = new ArrayList<BlockEntityRenderState>();
            // Exclude actual main extraction, not an approximation of Minecraft's culling.
            if (!entityQueries.isEmpty()) {
                var mainIds = new HashSet<UUID>();
                for (var state : frame.entityRenderStates) mainIds.add(((PrismMotionIdentity)state).prism$motionId());
                var candidates = new PrismNearestCandidates<Entity>(MAX_PER_DOMAIN);
                int scanned = 0;
                for (var entity : level.entitiesForRendering()) {
                    if (++scanned > MAX_SCANNED) break;
                    if (entity.isRemoved() || entity.tickCount == 0 || entity.isInvisible()
                            || mainIds.contains(entity.getUUID())) continue;
                    var box = entity.getBoundingBox();
                    double distance = distance(entityQueries,entity.getX(),entity.getY(),entity.getZ(),
                            box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ);
                    candidates.offer(entity,distance,entity.getId());
                }
                for (var entity : candidates.values()) {
                    float tick = delta.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
                    entities.add(renderer.entityRenderDispatcher().extractEntity(entity,tick));
                }
            }
            if (!blockQueries.isEmpty()) {
                var mainPositions = new HashSet<Long>();
                for (var state : frame.blockEntityRenderStates) mainPositions.add(state.blockPos.asLong());
                var candidates = new PrismNearestCandidates<BlockEntity>(MAX_PER_DOMAIN);
                var visitedChunks = new HashSet<Long>();
                int scanned = 0;
                scan: for (var query : blockQueries) {
                    var volume = query.volume;
                    int minX = Math.floorDiv((int)Math.floor(volume.x()-volume.halfX()),16);
                    int maxX = Math.floorDiv((int)Math.floor(volume.x()+volume.halfX()),16);
                    int minZ = Math.floorDiv((int)Math.floor(volume.z()-volume.halfZ()),16);
                    int maxZ = Math.floorDiv((int)Math.floor(volume.z()+volume.halfZ()),16);
                    for (int x=minX; x<=maxX; x++) for (int z=minZ; z<=maxZ; z++) {
                        long key = ((long)x << 32) | (z & 0xffffffffL);
                        if (!visitedChunks.add(key)) continue;
                        // false is essential: this never creates/requests a missing chunk.
                        var chunk = level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
                        if (chunk == null) continue;
                        for (var block : chunk.getBlockEntities().values()) {
                            if (++scanned > MAX_SCANNED) break scan;
                            var pos = block.getBlockPos();
                            if (block.isRemoved() || mainPositions.contains(pos.asLong())) continue;
                            double distance = distance(blockQueries,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,
                                    pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+1,pos.getZ()+1);
                            candidates.offer(block,distance,pos.asLong());
                        }
                    }
                }
                for (var block : candidates.values()) {
                    var state = renderer.blockEntityRenderDispatcher().tryExtractRenderState(block,partialTick,null,false);
                    if (state != null) blocks.add(state);
                }
            }
            target.prism$residentModels(new PrismResidentModelState.Snapshot(request.generation,entities,blocks));
        } catch (RuntimeException failure) { fail(request,failure); }
    }
    private static double distance(List<Query> queries,double x,double y,double z,
            double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        double result = Double.POSITIVE_INFINITY;
        for (var query : queries) {
            var v = query.volume;
            // Preserve API 1.23's point/radius admission; generic volumes use object bounds.
            boolean matches = query.legacy ? v.distanceSquared(x,y,z) <= v.halfX()*v.halfX()
                    : v.intersects(minX,minY,minZ,maxX,maxY,maxZ);
            if (matches) result = Math.min(result,v.distanceSquared(x,y,z));
        }
        return result;
    }
    static boolean inside(double distanceSquared, int radius) {
        return radius > 0 && Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= (double)radius * radius;
    }
    public static void capture(LevelRenderer renderer, LevelRenderState frame) {
        Config request = config;
        var snapshot = ((PrismResidentModelState)frame).prism$residentModels();
        if (!request.enabled() || snapshot.generation() != request.generation || failedGeneration == request.generation
                || !PrismModelViewCapture.enabled()) return;
        try {
            var camera = frame.cameraRenderState;
            var origin = camera.pos;
            for (var state : snapshot.entities()) {
                double x=state.x-origin.x,y=state.y-origin.y,z=state.z-origin.z;
                renderer.entityRenderDispatcher().submit(state,camera,x,y,z,new PoseStack(),
                        new PrismResidentModelCollector("entity",x*x+y*y+z*z));
            }
            for (var state : snapshot.blocks()) {
                var pos = state.blockPos;
                var poses = new PoseStack();
                poses.translate(pos.getX()-origin.x,pos.getY()-origin.y,pos.getZ()-origin.z);
                renderer.blockEntityRenderDispatcher().submit(state,poses,
                        new PrismResidentModelCollector("block_entity",pos.distToCenterSqr(origin)),camera);
            }
            if (loggedGeneration != request.generation && (!snapshot.entities().isEmpty() || !snapshot.blocks().isEmpty())) {
                loggedGeneration = request.generation;
                PrismMod.LOGGER.info("Prism resident model capture: additionalEntities={}, additionalBlockEntities={}, queries={}",
                        snapshot.entities().size(),snapshot.blocks().size(),request.scopes.size());
            }
        } catch (RuntimeException failure) { fail(request,failure); }
        finally { ((PrismResidentModelState)frame).prism$residentModels(PrismResidentModelState.Snapshot.EMPTY); }
    }
    private static void fail(Config request, RuntimeException failure) {
        if (failedGeneration != request.generation) {
            failedGeneration = request.generation;
            PrismMod.LOGGER.warn("Prism resident model extraction disabled for this pack generation; main rendering preserved",failure);
        }
    }
}
