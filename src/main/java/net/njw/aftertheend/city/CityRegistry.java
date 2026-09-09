package net.njw.aftertheend.city;

import java.util.UUID;
import net.minecraft.world.level.Level;

import java.util.Map;

public final class CityRegistry {

    public static final UUID STARTING_CITY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static final City STARTING_CITY_TEMPLATE =
            new City(
                    STARTING_CITY_ID,
                    STARTING_CITY_ID.toString(),
                    Map.of(
                            Level.OVERWORLD,
                            new CityRegion(
                                    0,
                                    0,
                                    256,
                                    256
                            ),

                            Level.NETHER,
                            new CityRegion(
                                    0,
                                    0,
                                    32,
                                    32
                            )
                    )
            );

    private CityRegistry() {
    }
}