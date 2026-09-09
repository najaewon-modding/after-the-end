package net.njw.aftertheend.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.njw.aftertheend.AfterTheEnd;

public record AltarActivationPayload(
        Identifier dimension,
        BlockPos center,
        boolean large,
        long startGameTime,
        boolean cancelled
) implements CustomPacketPayload {
    public static final Type<AltarActivationPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "altar_activation")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, AltarActivationPayload> STREAM_CODEC = StreamCodec.ofMember(
            AltarActivationPayload::write,
            AltarActivationPayload::decode
    );

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeIdentifier(dimension);
        buffer.writeBlockPos(center);
        buffer.writeBoolean(large);
        buffer.writeLong(startGameTime);
        buffer.writeBoolean(cancelled);
    }

    private static AltarActivationPayload decode(RegistryFriendlyByteBuf buffer) {
        return new AltarActivationPayload(
                buffer.readIdentifier(),
                buffer.readBlockPos(),
                buffer.readBoolean(),
                buffer.readLong(),
                buffer.readBoolean()
        );
    }

    @Override
    public Type<AltarActivationPayload> type() {
        return TYPE;
    }
}
