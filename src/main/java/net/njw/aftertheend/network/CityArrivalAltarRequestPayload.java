package net.njw.aftertheend.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.njw.aftertheend.AfterTheEnd;

public record CityArrivalAltarRequestPayload() implements CustomPacketPayload {
    public static final Type<CityArrivalAltarRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "city_arrival_altar_request")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, CityArrivalAltarRequestPayload> STREAM_CODEC =
            StreamCodec.ofMember(CityArrivalAltarRequestPayload::write, CityArrivalAltarRequestPayload::decode);

    private void write(RegistryFriendlyByteBuf buffer) { }
    private static CityArrivalAltarRequestPayload decode(RegistryFriendlyByteBuf buffer) { return new CityArrivalAltarRequestPayload(); }

    @Override
    public Type<CityArrivalAltarRequestPayload> type() { return TYPE; }
}
