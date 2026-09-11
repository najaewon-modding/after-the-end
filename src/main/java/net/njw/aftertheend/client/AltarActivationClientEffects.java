package net.njw.aftertheend.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.network.AltarActivationPayload;

@EventBusSubscriber(modid = AfterTheEnd.MODID, value = Dist.CLIENT)
public final class AltarActivationClientEffects {
    public static final int EFFECT_DURATION_TICKS = 180;
    private static final int BEAM_START_TICK = 80;
    private static final double EGG_RISE_HEIGHT = 10.0;
    private static final Map<EffectKey, Effect> EFFECTS = new LinkedHashMap<>();

    private AltarActivationClientEffects() { }

    public static void handle(AltarActivationPayload payload) {
        EffectKey key = new EffectKey(payload.dimension(), payload.center());
        if (payload.cancelled()) {
            EFFECTS.remove(key);
            return;
        }
        EFFECTS.put(key, new Effect(payload.dimension(), payload.center().immutable(), payload.large(), payload.startGameTime()));
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        Identifier currentDimension = minecraft.level.dimension().identifier();
        long gameTime = minecraft.level.getGameTime();

        EFFECTS.entrySet().removeIf(entry -> entry.getValue().dimension().equals(currentDimension)
                && gameTime - entry.getValue().startGameTime() > EFFECT_DURATION_TICKS + 20L);

        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;

        for (Effect effect : EFFECTS.values()) {
            if (!effect.dimension().equals(currentDimension)) continue;
            float elapsed = gameTime - effect.startGameTime();
            if (elapsed < BEAM_START_TICK || elapsed > EFFECT_DURATION_TICKS) continue;
            renderEffect(effect, elapsed, poseStack, collector, camera);
        }
    }

    private static void renderEffect(Effect effect, float elapsed, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        int radius = effect.large() ? 7 : 3;
        BlockPos center = effect.center();
        Vec3 egg = new Vec3(center.getX() + 0.5, center.getY() + EGG_RISE_HEIGHT + 0.5, center.getZ() + 0.5);
        Vec3[] crystals = {
                new Vec3(center.getX() + 0.5, center.getY() + 0.5, center.getZ() - radius + 0.5),
                new Vec3(center.getX() + radius + 0.5, center.getY() + 0.5, center.getZ() + 0.5),
                new Vec3(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + radius + 0.5),
                new Vec3(center.getX() - radius + 0.5, center.getY() + 0.5, center.getZ() + 0.5)
        };
        for (Vec3 crystal : crystals) renderBeam(crystal, egg, elapsed, poseStack, collector, camera);
    }

    private static void renderBeam(Vec3 source, Vec3 target, float time, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        Vec3 delta = target.subtract(source);
        poseStack.pushPose();
        poseStack.translate(source.x - camera.pos.x, source.y - camera.pos.y, source.z - camera.pos.z);
        EnderDragonRenderer.submitCrystalBeams((float) delta.x, (float) delta.y, (float) delta.z, time, poseStack, collector, 0xF000F0);
        poseStack.popPose();
    }

    private record EffectKey(Identifier dimension, BlockPos center) { }
    private record Effect(Identifier dimension, BlockPos center, boolean large, long startGameTime) { }
}
