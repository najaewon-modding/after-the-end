package net.njw.aftertheend.client;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.CityRegion;
import net.njw.aftertheend.network.CitySyncPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(
        modid = AfterTheEnd.MODID,
        value = Dist.CLIENT
)
public final class ClientCityManager {

    private static volatile List<ClientCity>
            cities =
            List.of();

    private static volatile List<ClientCity>
            accessibleCities =
            List.of();

    private ClientCityManager() {
    }

    /*
     * =========================================================
     * Access
     * =========================================================
     */

    public static List<ClientCity> getCities() {
        return cities;
    }

    public static List<ClientCity>
    getAccessibleCities() {
        return accessibleCities;
    }

    /*
     * =========================================================
     * Snapshot Replacement
     * =========================================================
     */

    public static void replaceCities(
            List<CitySyncPayload.CityData> networkCities
    ) {
        List<ClientCity> all =
                new ArrayList<>(
                        networkCities.size()
                );

        List<ClientCity> accessible =
                new ArrayList<>();

        for (
                CitySyncPayload.CityData networkCity :
                networkCities
        ) {
            ClientCity city =
                    ClientCity.fromNetwork(
                            networkCity
                    );

            all.add(
                    city
            );

            if (city.unlocked()) {
                accessible.add(
                        city
                );
            }
        }

        cities =
                List.copyOf(
                        all
                );

        accessibleCities =
                List.copyOf(
                        accessible
                );
    }

    /*
     * =========================================================
     * Clear
     * =========================================================
     */

    public static void clear() {
        cities =
                List.of();

        accessibleCities =
                List.of();
    }

    @SubscribeEvent
    public static void onClientLogout(
            ClientPlayerNetworkEvent.LoggingOut event
    ) {
        clear();
    }

    /*
     * =========================================================
     * Client City
     * =========================================================
     */

    public record ClientCity(
            String id,
            String name,
            boolean unlocked,
            Map<Identifier, CityRegion> regions
    ) {

        public ClientCity {
            regions =
                    Map.copyOf(
                            regions
                    );
        }

        public CityRegion getRegion(
                Identifier dimension
        ) {
            return regions.get(
                    dimension
            );
        }

        private static ClientCity fromNetwork(
                CitySyncPayload.CityData city
        ) {
            Map<Identifier, CityRegion> regions =
                    new LinkedHashMap<>();

            for (
                    CitySyncPayload.RegionData region :
                    city.regions()
            ) {
                regions.put(
                        region.dimension(),
                        new CityRegion(
                                region.centerChunkX(),
                                region.centerChunkZ(),
                                region.widthChunks(),
                                region.heightChunks()
                        )
                );
            }

            return new ClientCity(
                    city.id(),
                    city.name(),
                    city.unlocked(),
                    regions
            );
        }
    }
}