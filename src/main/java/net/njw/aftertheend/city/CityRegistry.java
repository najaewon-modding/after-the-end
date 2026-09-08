package net.njw.aftertheend.city;

import net.minecraft.world.level.Level;

import java.util.Map;

public final class CityRegistry {

    public static final String STARTING_CITY_ID =
            "starting_city";

    public static final City STARTING_CITY_TEMPLATE =
            new City(
                    STARTING_CITY_ID,
                    "Starting City",
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