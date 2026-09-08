package net.njw.aftertheend.city.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;
import net.njw.aftertheend.city.CityRegion;
import net.njw.aftertheend.city.CityRegistry;

public final class EnderEyeHandler {

    private EnderEyeHandler() {
    }

    @SubscribeEvent
    public static void onRightClickItem(
            PlayerInteractEvent.RightClickItem event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        ItemStack stack =
                player.getItemInHand(event.getHand());

        if (!stack.is(Items.ENDER_EYE)) {
            return;
        }

        ServerLevel level = player.level();

        /*
         * Ender Eye의 Stronghold 탐색은 Overworld에서만 처리.
         */
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }

        City city =
                CityManager.getStartingCity(
                        level.getServer()
                );

        CityRegion region =
                city.getRegion(Level.OVERWORLD).orElse(null);

        if (region == null) {
            return;
        }

        BlockPos target =
                StructureRequirementService
                        .findNearestStrongholdInsideRegion(
                                level,
                                player.blockPosition(),
                                region
                        );

        if (target == null) {
            event.setCanceled(true);
            event.setCancellationResult(
                    InteractionResult.FAIL
            );

            player.sendOverlayMessage(
                    Component.translatable(
                            "message.njw_after_the_end.ender_eye.no_stronghold"
                    )
            );

            return;
        }

        AfterTheEnd.LOGGER.info(
                "Ender Eye target: x={}, y={}, z={}",
                target.getX(),
                target.getY(),
                target.getZ()
        );

        /*
         * City 안에 Stronghold가 없다면
         * vanilla 탐색으로 넘어가지 않도록 막는다.
         */
        if (target == null) {
            event.setCanceled(true);
            event.setCancellationResult(
                    InteractionResult.FAIL
            );

            player.sendOverlayMessage(
                    Component.literal(
                            "No accessible stronghold was found in this city."
                    )
            );

            return;
        }

        /*
         * Vanilla Ender Eye 사용을 막고
         * 우리가 직접 EyeOfEnder를 생성한다.
         */
        event.setCanceled(true);

        event.setCancellationResult(
                InteractionResult.SUCCESS
        );

        EyeOfEnder eye =
                new EyeOfEnder(
                        level,
                        player.getX(),
                        player.getY(0.5),
                        player.getZ()
                );

        eye.setItem(stack);

        eye.signalTo(
                new Vec3(
                        target.getX() + 0.5,
                        target.getY(),
                        target.getZ() + 0.5
                )
        );

        level.addFreshEntity(eye);

        /*
         * 크리에이티브가 아니면 Ender Eye 하나 소비.
         */
        if (!player.isCreative()) {
            stack.shrink(1);
        }

        player.swing(
                event.getHand(),
                true
        );
    }
}