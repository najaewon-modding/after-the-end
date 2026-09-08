package net.njw.aftertheend.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 하나의 도시.
 *
 * 하나의 도시는 Overworld, Nether 등
 * 여러 차원에 걸친 영역으로 구성될 수 있다.
 *
 * 각 차원과 실제 영역의 연결은
 *
 * Map<ResourceKey<Level>, CityRegion>
 *
 * 으로 관리한다.
 */
public record City(
        String id,
        String name,
        Map<ResourceKey<Level>, CityRegion> regions
) {

    /*
     * =========================================================
     * Codec
     * =========================================================
     */

    private static final Codec<Map<ResourceKey<Level>, CityRegion>>
            REGIONS_CODEC =
            Codec.unboundedMap(
                    Level.RESOURCE_KEY_CODEC,
                    CityRegion.CODEC
            );

    public static final Codec<City> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING
                            .fieldOf("id")
                            .forGetter(
                                    City::id
                            ),

                    Codec.STRING
                            .fieldOf("name")
                            .forGetter(
                                    City::name
                            ),

                    REGIONS_CODEC
                            .fieldOf("regions")
                            .forGetter(
                                    City::regions
                            )

            ).apply(
                    instance,
                    City::new
            ));

    /*
     * =========================================================
     * Validation
     * =========================================================
     */

    public City {
        Objects.requireNonNull(
                id,
                "id"
        );

        Objects.requireNonNull(
                name,
                "name"
        );

        Objects.requireNonNull(
                regions,
                "regions"
        );

        if (id.isBlank()) {
            throw new IllegalArgumentException(
                    "City id must not be blank."
            );
        }

        if (name.isBlank()) {
            throw new IllegalArgumentException(
                    "City name must not be blank."
            );
        }

        if (regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "City must contain at least one region."
            );
        }

        /*
         * 외부에서 전달한 Map이 나중에 변경되어
         * City 내부 상태가 바뀌지 않도록 복사한다.
         *
         * Map.copyOf()는 반환된 Map 자체도 수정할 수 없게 한다.
         */
        regions =
                Map.copyOf(
                        regions
                );
    }

    /*
     * =========================================================
     * Region Access
     * =========================================================
     */

    /**
     * 해당 차원에서 이 도시가 차지하는 영역을 반환한다.
     */
    public Optional<CityRegion> getRegion(
            ResourceKey<Level> dimension
    ) {
        return Optional.ofNullable(
                regions.get(
                        dimension
                )
        );
    }

    /**
     * 특정 위치가 이 도시 안에 존재하는지 확인한다.
     */
    public boolean contains(
            ResourceKey<Level> dimension,
            int blockX,
            int blockZ
    ) {
        CityRegion region =
                regions.get(
                        dimension
                );

        if (region == null) {
            return false;
        }

        return region.containsBlock(
                blockX,
                blockZ
        );
    }

    /**
     * 이 도시가 특정 차원에 영역을 가지고 있는지 확인한다.
     */
    public boolean hasDimension(
            ResourceKey<Level> dimension
    ) {
        return regions.containsKey(
                dimension
        );
    }
}