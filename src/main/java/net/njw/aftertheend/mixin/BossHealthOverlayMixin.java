package net.njw.aftertheend.mixin;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.njw.aftertheend.client.CityBossBarRenderHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
    @ModifyArg(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V"
            ),
            index = 2
    )
    private int njwAfterTheEnd$offsetCityMoveBarY(int y) {
        return CityBossBarRenderHandler.consumeBarY(y);
    }
}
