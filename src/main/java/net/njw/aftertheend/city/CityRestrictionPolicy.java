package net.njw.aftertheend.city;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Defines the dimensions in which city boundaries restrict normal gameplay. */
public final class CityRestrictionPolicy {
    private CityRestrictionPolicy() {
    }

    public static boolean isManagedDimension(ResourceKey<Level> dimension) {
        return Level.OVERWORLD.equals(dimension) || Level.NETHER.equals(dimension);
    }
}
