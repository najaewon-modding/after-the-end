package net.njw.aftertheend.city.placement;

import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;
import net.njw.aftertheend.city.CityRegion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class CityPlacementService {

    /*
     * =========================================================
     * Placement Configuration
     * =========================================================
     */

    /*
     * 새 도시와 가장 가까운 기존 도시 사이의
     * 최소 / 최대 중심 거리.
     *
     * 단위: Overworld block
     */
    private static final double MIN_CITY_DISTANCE_BLOCKS =
            8_000.0;

    private static final double MAX_CITY_DISTANCE_BLOCKS =
            12_000.0;

    /*
     * 적절한 후보 위치를 찾기 위한 최대 시도 횟수.
     */
    private static final int MAX_PLACEMENT_ATTEMPTS =
            10_000;

    /*
     * Minecraft의 Overworld : Nether 좌표 비율.
     */
    private static final int OVERWORLD_NETHER_SCALE =
            8;

    /*
     * 한 chunk의 block 크기.
     */
    private static final int BLOCKS_PER_CHUNK =
            16;

    private CityPlacementService() {
    }

    /*
     * =========================================================
     * Public Placement API
     * =========================================================
     */

    /**
     * 새로운 도시를 배치하고
     * 아직 접근 불가능한 도시로 등록한다.
     */
    public static City placeLockedCity(
            MinecraftServer server,
            UUID cityId,
            String cityName
    ) {
        City city =
                createCity(
                        server,
                        cityId,
                        cityName
                );

        CityManager.addCity(
                server,
                city
        );

        return city;
    }

    /**
     * 새로운 도시를 배치하고
     * 즉시 접근 가능한 도시로 등록한다.
     *
     * 이후 실제 도시 해금 시스템에서는
     * 이 메서드를 사용할 수 있다.
     */
    public static City placeAccessibleCity(
            MinecraftServer server,
            UUID cityId,
            String cityName
    ) {
        City city =
                createCity(
                        server,
                        cityId,
                        cityName
                );

        CityManager.addAccessibleCity(
                server,
                city
        );

        return city;
    }

    /*
     * =========================================================
     * City Creation
     * =========================================================
     */

    private static City createCity(
            MinecraftServer server,
            UUID cityId,
            String cityName
    ) {
        /*
         * ID 중복을 placement 단계에서 먼저 차단한다.
         */
        if (
                CityManager.getCity(
                        server,
                        cityId
                ) != null
        ) {
            throw new IllegalArgumentException(
                    "City already exists: "
                            + cityId
            );
        }

        /*
         * 현재 존재하는 모든 도시.
         *
         * accessible 여부와 관계없이
         * 실제로 존재하는 도시 전체를 배치 기준으로 사용한다.
         */
        List<City> existingCities =
                new ArrayList<>(
                        CityManager.getCities(
                                server
                        )
                );

        if (existingCities.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot place a city because no existing city is available."
            );
        }

        /*
         * 현재는 새 도시의 크기를
         * Starting City와 동일하게 사용한다.
         *
         * 나중에 도시별 크기를 다르게 만들고 싶다면
         * 이 부분을 template/config 기반으로 일반화하면 된다.
         */
        City startingCity =
                CityManager.getStartingCity(
                        server
                );

        CityRegion overworldTemplate =
                startingCity.getRegion(
                        Level.OVERWORLD
                ).orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Starting city has no Overworld region."
                                )
                );

        CityRegion netherTemplate =
                startingCity.getRegion(
                        Level.NETHER
                ).orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Starting city has no Nether region."
                                )
                );

        PlacementCandidate candidate =
                findPlacementCandidate(
                        existingCities,
                        overworldTemplate,
                        netherTemplate
                );

        /*
         * 후보 위치를 실제 CityRegion으로 변환.
         */
        CityRegion overworldRegion =
                new CityRegion(
                        candidate.overworldCenterChunkX(),
                        candidate.overworldCenterChunkZ(),
                        overworldTemplate.widthChunks(),
                        overworldTemplate.heightChunks()
                );

        /*
         * Overworld center chunk를 8의 배수로 정렬했기 때문에
         *
         * OW chunk / 8 = Nether chunk
         *
         * 를 정확히 적용할 수 있다.
         */
        CityRegion netherRegion =
                new CityRegion(
                        candidate.overworldCenterChunkX()
                                / OVERWORLD_NETHER_SCALE,
                        candidate.overworldCenterChunkZ()
                                / OVERWORLD_NETHER_SCALE,
                        netherTemplate.widthChunks(),
                        netherTemplate.heightChunks()
                );

        Map<ResourceKey<Level>, CityRegion> regions =
                new LinkedHashMap<>();

        regions.put(
                Level.OVERWORLD,
                overworldRegion
        );

        regions.put(
                Level.NETHER,
                netherRegion
        );

        return new City(
                cityId,
                cityName,
                regions
        );
    }

    /*
     * =========================================================
     * Candidate Search
     * =========================================================
     */

    private static PlacementCandidate findPlacementCandidate(
            List<City> existingCities,
            CityRegion overworldTemplate,
            CityRegion netherTemplate
    ) {
        /*
         * Overworld region을 실제로 가지고 있는 도시만
         * 기준 도시 후보가 될 수 있다.
         */
        List<City> overworldCities =
                existingCities.stream()
                        .filter(
                                city ->
                                        city.hasDimension(
                                                Level.OVERWORLD
                                        )
                        )
                        .toList();

        if (overworldCities.isEmpty()) {
            throw new IllegalStateException(
                    "No existing city has an Overworld region."
            );
        }

        ThreadLocalRandom random =
                ThreadLocalRandom.current();

        for (
                int attempt = 0;
                attempt < MAX_PLACEMENT_ATTEMPTS;
                attempt++
        ) {
            /*
             * 기존 도시 중 하나를 임시 기준점으로 선택.
             *
             * 최종적으로는 모든 도시와 다시 거리를 비교하기 때문에
             * 이 도시가 반드시 최종 nearest city일 필요는 없다.
             */
            City referenceCity =
                    overworldCities.get(
                            random.nextInt(
                                    overworldCities.size()
                            )
                    );

            CityRegion referenceRegion =
                    referenceCity.getRegion(
                            Level.OVERWORLD
                    ).orElseThrow();

            double referenceX =
                    centerBlockX(
                            referenceRegion
                    );

            double referenceZ =
                    centerBlockZ(
                            referenceRegion
                    );

            double angle =
                    random.nextDouble(
                            0.0,
                            Math.PI * 2.0
                    );

            double distance =
                    random.nextDouble(
                            MIN_CITY_DISTANCE_BLOCKS,
                            MAX_CITY_DISTANCE_BLOCKS
                    );

            double rawBlockX =
                    referenceX
                            + Math.cos(angle)
                            * distance;

            double rawBlockZ =
                    referenceZ
                            + Math.sin(angle)
                            * distance;

            /*
             * 먼저 가장 가까운 Overworld chunk로 변환.
             */
            int rawChunkX =
                    blockToNearestChunk(
                            rawBlockX
                    );

            int rawChunkZ =
                    blockToNearestChunk(
                            rawBlockZ
                    );

            /*
             * Nether 8:1 관계를 정확하게 유지하기 위해
             * Overworld center chunk를 8의 배수로 정렬한다.
             */
            int candidateChunkX =
                    snapToNetherAlignedChunk(
                            rawChunkX
                    );

            int candidateChunkZ =
                    snapToNetherAlignedChunk(
                            rawChunkZ
                    );

            CityRegion candidateOverworld =
                    new CityRegion(
                            candidateChunkX,
                            candidateChunkZ,
                            overworldTemplate.widthChunks(),
                            overworldTemplate.heightChunks()
                    );

            CityRegion candidateNether =
                    new CityRegion(
                            candidateChunkX
                                    / OVERWORLD_NETHER_SCALE,
                            candidateChunkZ
                                    / OVERWORLD_NETHER_SCALE,
                            netherTemplate.widthChunks(),
                            netherTemplate.heightChunks()
                    );

            /*
             * 모든 기존 도시를 기준으로
             * nearest-distance 규칙을 다시 검증한다.
             */
            if (
                    !hasValidNearestCityDistance(
                            existingCities,
                            candidateOverworld
                    )
            ) {
                continue;
            }

            /*
             * 실제 도시 영역끼리 겹치지 않는지 검증.
             */
            if (
                    overlapsExistingCity(
                            existingCities,
                            Level.OVERWORLD,
                            candidateOverworld
                    )
            ) {
                continue;
            }

            if (
                    overlapsExistingCity(
                            existingCities,
                            Level.NETHER,
                            candidateNether
                    )
            ) {
                continue;
            }

            return new PlacementCandidate(
                    candidateChunkX,
                    candidateChunkZ
            );
        }

        throw new IllegalStateException(
                "Could not find a valid city placement within "
                        + MAX_PLACEMENT_ATTEMPTS
                        + " attempts."
        );
    }

    /*
     * =========================================================
     * Distance Validation
     * =========================================================
     */

    /**
     * 후보 도시에서 가장 가까운 기존 도시까지의 거리가
     *
     * 8000 <= distance <= 12000
     *
     * 인지 확인한다.
     */
    private static boolean hasValidNearestCityDistance(
            List<City> existingCities,
            CityRegion candidate
    ) {
        double candidateX =
                centerBlockX(
                        candidate
                );

        double candidateZ =
                centerBlockZ(
                        candidate
                );

        double nearestDistanceSquared =
                Double.POSITIVE_INFINITY;

        for (City city :
                existingCities) {

            CityRegion region =
                    city.getRegion(
                            Level.OVERWORLD
                    ).orElse(null);

            if (region == null) {
                continue;
            }

            double dx =
                    candidateX
                            - centerBlockX(
                            region
                    );

            double dz =
                    candidateZ
                            - centerBlockZ(
                            region
                    );

            double distanceSquared =
                    dx * dx
                            + dz * dz;

            nearestDistanceSquared =
                    Math.min(
                            nearestDistanceSquared,
                            distanceSquared
                    );
        }

        double minDistanceSquared =
                MIN_CITY_DISTANCE_BLOCKS
                        * MIN_CITY_DISTANCE_BLOCKS;

        double maxDistanceSquared =
                MAX_CITY_DISTANCE_BLOCKS
                        * MAX_CITY_DISTANCE_BLOCKS;

        return nearestDistanceSquared
                >= minDistanceSquared
                && nearestDistanceSquared
                <= maxDistanceSquared;
    }

    /*
     * =========================================================
     * Overlap Validation
     * =========================================================
     */

    private static boolean overlapsExistingCity(
            List<City> existingCities,
            ResourceKey<Level> dimension,
            CityRegion candidate
    ) {
        for (City city :
                existingCities) {

            CityRegion existing =
                    city.getRegion(
                            dimension
                    ).orElse(null);

            if (existing == null) {
                continue;
            }

            if (
                    regionsOverlap(
                            candidate,
                            existing
                    )
            ) {
                return true;
            }
        }

        return false;
    }

    /**
     * 두 직사각형 CityRegion이
     * chunk 단위에서 실제로 겹치는지 검사한다.
     */
    private static boolean regionsOverlap(
            CityRegion first,
            CityRegion second
    ) {
        boolean separatedOnX =
                first.maxChunkX()
                        < second.minChunkX()
                        || first.minChunkX()
                        > second.maxChunkX();

        boolean separatedOnZ =
                first.maxChunkZ()
                        < second.minChunkZ()
                        || first.minChunkZ()
                        > second.maxChunkZ();

        return !separatedOnX
                && !separatedOnZ;
    }

    /*
     * =========================================================
     * Coordinate Conversion
     * =========================================================
     */

    /**
     * CityRegion의 논리적 중심 block X.
     *
     * centerChunkX = 0인 Starting City의 중심은
     * block X = 0으로 취급한다.
     */
    private static double centerBlockX(
            CityRegion region
    ) {
        return (double) region.centerChunkX()
                * BLOCKS_PER_CHUNK;
    }

    /**
     * CityRegion의 논리적 중심 block Z.
     */
    private static double centerBlockZ(
            CityRegion region
    ) {
        return (double) region.centerChunkZ()
                * BLOCKS_PER_CHUNK;
    }

    /**
     * block 좌표를 가장 가까운 chunk 좌표로 변환.
     */
    private static int blockToNearestChunk(
            double blockCoordinate
    ) {
        long chunk =
                Math.round(
                        blockCoordinate
                                / BLOCKS_PER_CHUNK
                );

        if (
                chunk < Integer.MIN_VALUE
                        || chunk > Integer.MAX_VALUE
        ) {
            throw new IllegalArgumentException(
                    "Chunk coordinate is outside the integer range."
            );
        }

        return (int) chunk;
    }

    /**
     * Overworld center chunk를
     * 가장 가까운 8의 배수로 정렬한다.
     *
     * 이렇게 하면 Nether center chunk를
     *
     * overworldChunk / 8
     *
     * 로 정확하게 계산할 수 있다.
     */
    private static int snapToNetherAlignedChunk(
            int chunkCoordinate
    ) {
        long aligned =
                Math.round(
                        chunkCoordinate
                                / (double) OVERWORLD_NETHER_SCALE
                )
                        * OVERWORLD_NETHER_SCALE;

        if (
                aligned < Integer.MIN_VALUE
                        || aligned > Integer.MAX_VALUE
        ) {
            throw new IllegalArgumentException(
                    "Aligned chunk coordinate is outside the integer range."
            );
        }

        return (int) aligned;
    }

    /*
     * =========================================================
     * Placement Candidate
     * =========================================================
     */

    private record PlacementCandidate(
            int overworldCenterChunkX,
            int overworldCenterChunkZ
    ) {
    }
}