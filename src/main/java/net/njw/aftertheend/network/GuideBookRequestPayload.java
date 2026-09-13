package net.njw.aftertheend.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.guide.GuideBookService;

public record GuideBookRequestPayload() implements CustomPacketPayload {
    public static final Type<GuideBookRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "guide_book_request")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, GuideBookRequestPayload> STREAM_CODEC =
            StreamCodec.ofMember(GuideBookRequestPayload::write, GuideBookRequestPayload::decode);

    private void write(RegistryFriendlyByteBuf buffer) { }
    private static GuideBookRequestPayload decode(RegistryFriendlyByteBuf buffer) { return new GuideBookRequestPayload(); }

    public static void handle(GuideBookRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) GuideBookService.giveIfMissing(player);
    }

    @Override
    public Type<GuideBookRequestPayload> type() { return TYPE; }
}
