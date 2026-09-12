package net.njw.aftertheend.city.altar;

public final class AltarTravelAccess {
    private static final double SMALL_HORIZONTAL_RANGE = 12.0D;
    private static final double LARGE_HORIZONTAL_RANGE = 20.0D;
    private static final double VERTICAL_RANGE = 10.0D;

    private AltarTravelAccess() { }

    public static boolean isNear(double playerX, double playerY, double playerZ,
                                 int blockX, int y, int blockZ, boolean large) {
        int centerOffset = large ? 13 : 5;
        double centerX = blockX + centerOffset + 0.5D;
        double centerY = y + 3.0D;
        double centerZ = blockZ + centerOffset + 0.5D;
        if (Math.abs(playerY - centerY) > VERTICAL_RANGE) return false;
        double dx = playerX - centerX;
        double dz = playerZ - centerZ;
        double range = large ? LARGE_HORIZONTAL_RANGE : SMALL_HORIZONTAL_RANGE;
        return dx * dx + dz * dz <= range * range;
    }
}
