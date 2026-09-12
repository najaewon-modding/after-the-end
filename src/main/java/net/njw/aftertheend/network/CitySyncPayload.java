package net.njw.aftertheend.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;

public record CitySyncPayload(List<CityData> cities) implements CustomPacketPayload {
    public static final Type<CitySyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "city_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CitySyncPayload> STREAM_CODEC = StreamCodec.ofMember(CitySyncPayload::write, CitySyncPayload::decode);

    public CitySyncPayload {
        cities = List.copyOf(cities);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(cities.size());
        for (CityData city : cities) {
            buffer.writeLong(city.id().getMostSignificantBits());
            buffer.writeLong(city.id().getLeastSignificantBits());
            buffer.writeUtf(city.name());
            buffer.writeBoolean(city.unlocked());
            buffer.writeVarInt(city.regions().size());
            for (RegionData region : city.regions()) {
                buffer.writeIdentifier(region.dimension());
                buffer.writeInt(region.centerChunkX());
                buffer.writeInt(region.centerChunkZ());
                buffer.writeVarInt(region.widthChunks());
                buffer.writeVarInt(region.heightChunks());
            }
            buffer.writeVarInt(city.activatedAltars().size());
            for (AltarData altar : city.activatedAltars()) {
                buffer.writeInt(altar.blockX());
                buffer.writeInt(altar.y());
                buffer.writeInt(altar.blockZ());
                buffer.writeBoolean(altar.large());
            }
        }
    }

    private static CitySyncPayload decode(RegistryFriendlyByteBuf buffer) {
        int cityCount = buffer.readVarInt();
        List<CityData> cities = new ArrayList<>(cityCount);
        for (int cityIndex = 0; cityIndex < cityCount; cityIndex++) {
            UUID cityId = new UUID(buffer.readLong(), buffer.readLong());
            String cityName = buffer.readUtf();
            boolean unlocked = buffer.readBoolean();
            int regionCount = buffer.readVarInt();
            List<RegionData> regions = new ArrayList<>(regionCount);
            for (int regionIndex = 0; regionIndex < regionCount; regionIndex++) {
                regions.add(new RegionData(
                        buffer.readIdentifier(),
                        buffer.readInt(),
                        buffer.readInt(),
                        buffer.readVarInt(),
                        buffer.readVarInt()
                ));
            }
            int altarCount = buffer.readVarInt();
            List<AltarData> activatedAltars = new ArrayList<>(altarCount);
            for (int altarIndex = 0; altarIndex < altarCount; altarIndex++) {
                activatedAltars.add(new AltarData(
                        buffer.readInt(),
                        buffer.readInt(),
                        buffer.readInt(),
                        buffer.readBoolean()
                ));
            }
            cities.add(new CityData(cityId, cityName, unlocked, regions, activatedAltars));
        }
        return new CitySyncPayload(cities);
    }

    @Override
    public Type<CitySyncPayload> type() {
        return TYPE;
    }

    public record CityData(UUID id, String name, boolean unlocked, List<RegionData> regions, List<AltarData> activatedAltars) {
        public CityData {
            regions = List.copyOf(regions);
            activatedAltars = List.copyOf(activatedAltars);
        }

        public static CityData fromCity(City city, boolean unlocked, List<AltarData> activatedAltars) {
            List<RegionData> regions = new ArrayList<>();
            city.regions().forEach((dimension, region) -> regions.add(new RegionData(
                    dimension.identifier(),
                    region.centerChunkX(),
                    region.centerChunkZ(),
                    region.widthChunks(),
                    region.heightChunks()
            )));
            return new CityData(city.id(), city.name(), unlocked, regions, activatedAltars);
        }
    }

    public record RegionData(Identifier dimension, int centerChunkX, int centerChunkZ, int widthChunks, int heightChunks) { }
    public record AltarData(int blockX, int y, int blockZ, boolean large) { }
}
