package net.njw.aftertheend.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.njw.aftertheend.AfterTheEnd;

/**
 * 클라이언트가 특정 도시로 이동하고 싶다는 요청.
 *
 * 클라이언트에서는 cityId만 전송한다.
 *
 * 실제 도시 존재 여부, 접근 가능 여부,
 * 목적지 좌표, 안전한 Y 좌표는 서버가 결정한다.
 */
public record CityTeleportRequestPayload(
        String cityId
) implements CustomPacketPayload {

    public static final Type<CityTeleportRequestPayload> TYPE =
            new Type<>(
                    Identifier.fromNamespaceAndPath(
                            AfterTheEnd.MODID,
                            "city_teleport_request"
                    )
            );

    public static final StreamCodec<
            RegistryFriendlyByteBuf,
            CityTeleportRequestPayload
            > STREAM_CODEC =
            StreamCodec.ofMember(
                    CityTeleportRequestPayload::write,
                    CityTeleportRequestPayload::decode
            );

    /*
     * =========================================================
     * Encoding
     * =========================================================
     */

    private void write(
            RegistryFriendlyByteBuf buffer
    ) {
        buffer.writeUtf(
                cityId
        );
    }

    /*
     * =========================================================
     * Decoding
     * =========================================================
     */

    private static CityTeleportRequestPayload decode(
            RegistryFriendlyByteBuf buffer
    ) {
        return new CityTeleportRequestPayload(
                buffer.readUtf()
        );
    }

    /*
     * =========================================================
     * Type
     * =========================================================
     */

    @Override
    public Type<CityTeleportRequestPayload> type() {
        return TYPE;
    }
}