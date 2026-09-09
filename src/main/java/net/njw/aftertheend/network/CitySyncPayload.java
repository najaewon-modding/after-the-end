package net.njw.aftertheend.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;

import java.util.ArrayList;
import java.util.List;

/**
 * 서버에 존재하는 도시들의 snapshot을
 * 클라이언트로 전송한다.
 *
 * UI에서는 모든 도시를 표시하고,
 * BoundaryRenderer에서는 unlocked city만 사용한다.
 */
public record CitySyncPayload(
        List<CityData> cities
) implements CustomPacketPayload {

    public static final Type<CitySyncPayload> TYPE =
            new Type<>(
                    Identifier.fromNamespaceAndPath(
                            AfterTheEnd.MODID,
                            "city_sync"
                    )
            );

    public static final StreamCodec<
            RegistryFriendlyByteBuf,
            CitySyncPayload
            > STREAM_CODEC =
            StreamCodec.ofMember(
                    CitySyncPayload::write,
                    CitySyncPayload::decode
            );

    public CitySyncPayload {
        cities =
                List.copyOf(
                        cities
                );
    }

    /*
     * =========================================================
     * Encoding
     * =========================================================
     */

    private void write(
            RegistryFriendlyByteBuf buffer
    ) {
        buffer.writeVarInt(
                cities.size()
        );

        for (CityData city : cities) {
            buffer.writeLong(city.id().getMostSignificantBits());
            buffer.writeLong(city.id().getLeastSignificantBits());

            buffer.writeUtf(
                    city.name()
            );

            buffer.writeBoolean(
                    city.unlocked()
            );

            buffer.writeVarInt(
                    city.regions().size()
            );

            for (RegionData region :
                    city.regions()) {

                buffer.writeIdentifier(
                        region.dimension()
                );

                buffer.writeInt(
                        region.centerChunkX()
                );

                buffer.writeInt(
                        region.centerChunkZ()
                );

                buffer.writeVarInt(
                        region.widthChunks()
                );

                buffer.writeVarInt(
                        region.heightChunks()
                );
            }
        }
    }

    /*
     * =========================================================
     * Decoding
     * =========================================================
     */

    private static CitySyncPayload decode(
            RegistryFriendlyByteBuf buffer
    ) {
        int cityCount =
                buffer.readVarInt();

        List<CityData> cities =
                new ArrayList<>(
                        cityCount
                );

        for (
                int cityIndex = 0;
                cityIndex < cityCount;
                cityIndex++
        ) {
            UUID cityId =
                    new UUID(buffer.readLong(), buffer.readLong());

            String cityName =
                    buffer.readUtf();

            boolean unlocked =
                    buffer.readBoolean();

            int regionCount =
                    buffer.readVarInt();

            List<RegionData> regions =
                    new ArrayList<>(
                            regionCount
                    );

            for (
                    int regionIndex = 0;
                    regionIndex < regionCount;
                    regionIndex++
            ) {
                Identifier dimension =
                        buffer.readIdentifier();

                int centerChunkX =
                        buffer.readInt();

                int centerChunkZ =
                        buffer.readInt();

                int widthChunks =
                        buffer.readVarInt();

                int heightChunks =
                        buffer.readVarInt();

                regions.add(
                        new RegionData(
                                dimension,
                                centerChunkX,
                                centerChunkZ,
                                widthChunks,
                                heightChunks
                        )
                );
            }

            cities.add(
                    new CityData(
                            cityId,
                            cityName,
                            unlocked,
                            regions
                    )
            );
        }

        return new CitySyncPayload(
                cities
        );
    }

    @Override
    public Type<CitySyncPayload> type() {
        return TYPE;
    }

    /*
     * =========================================================
     * City DTO
     * =========================================================
     */

    public record CityData(
            UUID id,
            String name,
            boolean unlocked,
            List<RegionData> regions
    ) {

        public CityData {
            regions =
                    List.copyOf(
                            regions
                    );
        }

        public static CityData fromCity(
                City city,
                boolean unlocked
        ) {
            List<RegionData> regions =
                    new ArrayList<>();

            city.regions().forEach(
                    (dimension, region) ->
                            regions.add(
                                    new RegionData(
                                            dimension.identifier(),
                                            region.centerChunkX(),
                                            region.centerChunkZ(),
                                            region.widthChunks(),
                                            region.heightChunks()
                                    )
                            )
            );

            return new CityData(
                    city.id(),
                    city.name(),
                    unlocked,
                    regions
            );
        }
    }

    /*
     * =========================================================
     * Region DTO
     * =========================================================
     */

    public record RegionData(
            Identifier dimension,
            int centerChunkX,
            int centerChunkZ,
            int widthChunks,
            int heightChunks
    ) {
    }
}