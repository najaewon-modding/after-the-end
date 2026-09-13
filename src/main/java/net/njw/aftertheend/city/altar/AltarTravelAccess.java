package net.njw.aftertheend.city.altar;

public final class AltarTravelAccess {
    private static final int SMALL_WIDTH = 11;
    private static final int SMALL_HEIGHT = 7;
    private static final int LARGE_WIDTH = 27;
    private static final int LARGE_HEIGHT = 10;
    private static final double HORIZONTAL_INSET = 1.0D;
    private static final double VERTICAL_BELOW_MARGIN = 1.0D;
    private static final double VERTICAL_ABOVE_MARGIN = 1.0D;

    private AltarTravelAccess() { }

    public static boolean isNear(double playerX, double playerY, double playerZ,
                                 int blockX, int y, int blockZ, boolean large) {
        return isInsideInteractionArea(playerX, playerY, playerZ, blockX, y, blockZ, large);
    }

    public static boolean isInsideInteractionArea(double playerX, double playerY, double playerZ,
                                                  int blockX, int y, int blockZ, boolean large) {
        int width = footprintWidth(large);
        int height = structureHeight(large);
        double minX = blockX + HORIZONTAL_INSET;
        double maxX = blockX + width - HORIZONTAL_INSET;
        double minY = y - VERTICAL_BELOW_MARGIN;
        double maxY = y + height + VERTICAL_ABOVE_MARGIN;
        double minZ = blockZ + HORIZONTAL_INSET;
        double maxZ = blockZ + width - HORIZONTAL_INSET;
        return playerX >= minX && playerX < maxX
                && playerY >= minY && playerY < maxY
                && playerZ >= minZ && playerZ < maxZ;
    }

    public static int footprintWidth(boolean large) { return large ? LARGE_WIDTH : SMALL_WIDTH; }
    public static int structureHeight(boolean large) { return large ? LARGE_HEIGHT : SMALL_HEIGHT; }
    public static int centerOffset(boolean large) { return footprintWidth(large) / 2; }
}
