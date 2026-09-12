package net.njw.aftertheend.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.CityManager;

public record CityRenameRequestPayload(UUID cityId, String name) implements CustomPacketPayload {
    public static final int MAX_NAME_LENGTH = 64;
    public static final Type<CityRenameRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "city_rename_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CityRenameRequestPayload> STREAM_CODEC =
            StreamCodec.ofMember(CityRenameRequestPayload::write, CityRenameRequestPayload::decode);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(cityId.getMostSignificantBits());
        buffer.writeLong(cityId.getLeastSignificantBits());
        buffer.writeUtf(name, MAX_NAME_LENGTH);
    }

    private static CityRenameRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        return new CityRenameRequestPayload(
                new UUID(buffer.readLong(), buffer.readLong()),
                buffer.readUtf(MAX_NAME_LENGTH)
        );
    }

    public static void handle(CityRenameRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        String normalized = payload.name().strip();
        if (normalized.isBlank() || normalized.length() > MAX_NAME_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl)) return;
        MinecraftServer server = player.level().getServer();
        if (CityManager.getCity(server, payload.cityId()) == null) return;
        CityManager.renameCity(server, payload.cityId(), normalized);
        CitySyncService.syncToAll(server);
    }

    @Override
    public Type<CityRenameRequestPayload> type() {
        return TYPE;
    }
}
