package net.njw.aftertheend.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.njw.aftertheend.AfterTheEnd;

public record CityRecallSoundPayload(UUID playerId, boolean active) implements CustomPacketPayload {
    public static final Type<CityRecallSoundPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "city_recall_sound")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, CityRecallSoundPayload> STREAM_CODEC =
            StreamCodec.ofMember(CityRecallSoundPayload::write, CityRecallSoundPayload::decode);

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeLong(playerId.getMostSignificantBits());
        buffer.writeLong(playerId.getLeastSignificantBits());
        buffer.writeBoolean(active);
    }

    private static CityRecallSoundPayload decode(RegistryFriendlyByteBuf buffer) {
        return new CityRecallSoundPayload(new UUID(buffer.readLong(), buffer.readLong()), buffer.readBoolean());
    }

    @Override
    public Type<CityRecallSoundPayload> type() { return TYPE; }
}
