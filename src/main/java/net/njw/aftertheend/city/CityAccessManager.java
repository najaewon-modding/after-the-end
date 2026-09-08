package net.njw.aftertheend.city;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public final class CityAccessManager {
    private CityAccessManager() {
    }

    public static boolean isInsideAccessibleArea(ServerPlayer player) {
        return isBlockInsideAccessibleArea(player, player.level().dimension(), player.getBlockX(), player.getBlockZ());
    }

    public static boolean isBlockInsideAccessibleArea(ServerPlayer player, int blockX, int blockZ) {
        return isBlockInsideAccessibleArea(player, player.level().dimension(), blockX, blockZ);
    }

    public static boolean isEntityInsideAccessibleArea(ServerPlayer player, Entity entity) {
        return isBlockInsideAccessibleArea(player, entity.level().dimension(), entity.getBlockX(), entity.getBlockZ());
    }

    private static boolean isBlockInsideAccessibleArea(ServerPlayer player, ResourceKey<Level> dimension, int blockX, int blockZ) {
        if (!CityRestrictionPolicy.isManagedDimension(dimension)) return true;
        MinecraftServer server = player.level().getServer();
        return server != null && CityManager.isInsideAccessibleCity(server, dimension, blockX, blockZ);
    }
}
