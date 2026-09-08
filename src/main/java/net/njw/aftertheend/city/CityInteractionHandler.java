package net.njw.aftertheend.city;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.util.TriState;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;

public final class CityInteractionHandler {

    private static final Component BLOCKED_MESSAGE =
            Component.translatable(
                    "message.njw_after_the_end.interaction.blocked"
            );

    private CityInteractionHandler() {
    }

    /**
     * 잠긴 영역의 블록 파괴 차단.
     */
    @SubscribeEvent
    public static void onLeftClickBlock(
            PlayerInteractEvent.LeftClickBlock event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (CityAccessManager.isBlockInsideAccessibleArea(
                player,
                event.getPos().getX(),
                event.getPos().getZ()
        )) {
            return;
        }

        event.setCanceled(true);
        showBlockedMessage(player);
    }

    /**
     * 잠긴 영역의 블록 설치 및 블록 상호작용 차단.
     *
     * 상자, 문, 버튼, 레버 등의 사용도 포함된다.
     */
    @SubscribeEvent
    public static void onRightClickBlock(
            PlayerInteractEvent.RightClickBlock event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (CityAccessManager.isBlockInsideAccessibleArea(
                player,
                event.getPos().getX(),
                event.getPos().getZ()
        )) {
            return;
        }

        event.setCanceled(true);
        showBlockedMessage(player);
    }

    /**
     * 잠긴 영역에 있는 엔티티 공격 차단.
     *
     * 몹을 공격해서 경험치나 드롭을 얻는 것을 방지한다.
     */
    @SubscribeEvent
    public static void onAttackEntity(
            AttackEntityEvent event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (CityAccessManager.isEntityInsideAccessibleArea(
                player,
                event.getTarget()
        )) {
            return;
        }

        event.setCanceled(true);
        showBlockedMessage(player);
    }

    /**
     * 잠긴 영역에 있는 엔티티 우클릭 상호작용 차단.
     *
     * 주민 거래, 동물 먹이주기, 보트 탑승 등의
     * 일반적인 엔티티 상호작용을 막는다.
     */
    @SubscribeEvent
    public static void onEntityInteract(
            PlayerInteractEvent.EntityInteract event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (CityAccessManager.isEntityInsideAccessibleArea(
                player,
                event.getTarget()
        )) {
            return;
        }

        event.setCanceled(true);
        showBlockedMessage(player);
    }

    /**
     * 잠긴 영역에 떨어져 있는 아이템 획득 차단.
     */
    @SubscribeEvent
    public static void onItemPickup(
            ItemEntityPickupEvent.Pre event
    ) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        if (CityAccessManager.isEntityInsideAccessibleArea(
                player,
                event.getItemEntity()
        )) {
            return;
        }

        event.setCanPickup(TriState.FALSE);
        showBlockedMessage(player);
    }

    /**
     * 잠긴 영역에 있는 경험치 구슬 획득 차단.
     */
    @SubscribeEvent
    public static void onExperiencePickup(
            PlayerXpEvent.PickupXp event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (CityAccessManager.isEntityInsideAccessibleArea(
                player,
                event.getOrb()
        )) {
            return;
        }

        event.setCanceled(true);
        showBlockedMessage(player);
    }

    private static void showBlockedMessage(
            ServerPlayer player
    ) {
        player.sendOverlayMessage(
                BLOCKED_MESSAGE
        );
    }
}