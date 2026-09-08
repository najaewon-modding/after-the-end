package net.njw.aftertheend.city;

public record ChunkRectangle(int centerX, int centerZ, int width, int height) {
    public ChunkRectangle {
        if (width <= 0) throw new IllegalArgumentException("width must be greater than 0.");
        if (height <= 0) throw new IllegalArgumentException("height must be greater than 0.");
    }

    public int minX() { return centerX - width / 2; }
    public int maxX() { return minX() + width - 1; }
    public int minZ() { return centerZ - height / 2; }
    public int maxZ() { return minZ() + height - 1; }
    public boolean contains(int x, int z) { return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ(); }
}
