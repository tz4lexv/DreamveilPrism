package dev.dreamveil.prism.api.visibility;

import dev.dreamveil.prism.api.PrismLimits;

/** Generic spatial query. No shadow/mirror/probe semantics or imposed shader projection. */
public record PrismVisibilityVolume(Shape shape, Space space, double x, double y, double z,
        double halfX, double halfY, double halfZ) {
    public enum Shape { SPHERE, AABB }
    public enum Space { CAMERA_RELATIVE, WORLD }
    public PrismVisibilityVolume {
        if (shape == null || space == null) throw new IllegalArgumentException("Visibility shape/space required");
        double maxCenter = space == Space.CAMERA_RELATIVE ? PrismLimits.LOADER.maxRelativeVisibilityOffset() : 30_000_000;
        if (!finite(x,y,z,halfX,halfY,halfZ) || Math.abs(x) > maxCenter || Math.abs(y) > maxCenter
                || Math.abs(z) > maxCenter || halfX <= 0 || halfY <= 0 || halfZ <= 0
                || Math.max(halfX,Math.max(halfY,halfZ)) > PrismLimits.LOADER.maxVisibilityRadius()
                || (shape == Shape.SPHERE && (halfX != halfY || halfX != halfZ))) {
            throw new IllegalArgumentException("Invalid visibility center/extent (relative center +/-512; world +/-30000000; extent >0..64)");
        }
    }
    public Resolved resolve(double cameraX, double cameraY, double cameraZ) {
        if (!finite(cameraX,cameraY,cameraZ)) throw new IllegalArgumentException("Non-finite camera origin");
        return new Resolved(shape, x + (space == Space.CAMERA_RELATIVE ? cameraX : 0),
                y + (space == Space.CAMERA_RELATIVE ? cameraY : 0), z + (space == Space.CAMERA_RELATIVE ? cameraZ : 0), halfX,halfY,halfZ);
    }
    public record Resolved(Shape shape, double x, double y, double z, double halfX, double halfY, double halfZ) {
        public Resolved {
            if (shape == null || !finite(x,y,z,halfX,halfY,halfZ) || halfX <= 0 || halfY <= 0 || halfZ <= 0
                    || (shape == Shape.SPHERE && (halfX != halfY || halfX != halfZ)))
                throw new IllegalArgumentException("Invalid resolved volume");
        }
        public boolean intersects(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
            if (!finite(minX,minY,minZ,maxX,maxY,maxZ) || minX > maxX || minY > maxY || minZ > maxZ) return false;
            if (shape == Shape.AABB) return maxX >= x-halfX && minX <= x+halfX && maxY >= y-halfY
                    && minY <= y+halfY && maxZ >= z-halfZ && minZ <= z+halfZ;
            double dx = Math.max(minX-x, Math.max(0,x-maxX));
            double dy = Math.max(minY-y, Math.max(0,y-maxY));
            double dz = Math.max(minZ-z, Math.max(0,z-maxZ));
            return dx*dx + dy*dy + dz*dz <= halfX*halfX;
        }
        public double distanceSquared(double px,double py,double pz) {
            double dx=px-x,dy=py-y,dz=pz-z; return dx*dx+dy*dy+dz*dz;
        }
    }
    private static boolean finite(double x,double y,double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
    private static boolean finite(double x,double y,double z,double a,double b,double c) {
        return finite(x,y,z) && finite(a,b,c);
    }
}
