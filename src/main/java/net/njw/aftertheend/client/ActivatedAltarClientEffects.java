package net.njw.aftertheend.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.njw.aftertheend.AfterTheEnd;

@EventBusSubscriber(modid = AfterTheEnd.MODID, value = Dist.CLIENT)
public final class ActivatedAltarClientEffects {
    private static final double RENDER_DISTANCE = 160.0;
    private static final double PARTICLE_DISTANCE = 64.0;
    private static final int PARTICLE_INTERVAL_TICKS = 30;
    private static final int RUNE_RED = 164;
    private static final int RUNE_GREEN = 112;
    private static final int RUNE_BLUE = 255;
    private static final ContextKey<List<AltarEcho>> ECHOES_KEY = new ContextKey<>(
            Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "activated_altar_echoes"));
    private static long lastParticleTick = Long.MIN_VALUE;

    private ActivatedAltarClientEffects() { }

    @SubscribeEvent
    public static void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
        if (!event.getLevel().dimension().equals(Level.OVERWORLD)) return;

        Vec3 camera = event.getCamera().position();
        long gameTime = event.getLevel().getGameTime();
        double renderDistanceSqr = RENDER_DISTANCE * RENDER_DISTANCE;
        List<AltarEcho> echoes = new ArrayList<>();

        for (ClientCityManager.ClientCity city : ClientCityManager.getAccessibleCities()) {
            for (ClientCityManager.ClientAltar altar : city.activatedAltars()) {
                int centerOffset = altar.large() ? 13 : 5;
                double x = altar.blockX() + centerOffset + 0.5;
                double y = altar.y() + 3.0;
                double z = altar.blockZ() + centerOffset + 0.5;
                if (camera.distanceToSqr(x, y, z) <= renderDistanceSqr) {
                    echoes.add(new AltarEcho(x, y, z, altar.large(), gameTime));
                }
            }
        }

        if (!echoes.isEmpty()) {
            event.getRenderState().setRenderData(ECHOES_KEY, List.copyOf(echoes));
        }

        if (gameTime != lastParticleTick && Math.floorMod(gameTime, PARTICLE_INTERVAL_TICKS) == 0L) {
            lastParticleTick = gameTime;
            spawnParticles(event, echoes, camera, gameTime);
        }
    }

    private static void spawnParticles(ExtractLevelRenderStateEvent event, List<AltarEcho> echoes, Vec3 camera, long gameTime) {
        double particleDistanceSqr = PARTICLE_DISTANCE * PARTICLE_DISTANCE;
        for (AltarEcho echo : echoes) {
            if (camera.distanceToSqr(echo.x(), echo.y(), echo.z()) > particleDistanceSqr) continue;
            double radius = echo.large() ? 2.2 : 1.55;
            double angle = gameTime * 0.17 + echo.x() * 0.019 + echo.z() * 0.013;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            event.getLevel().addParticle(
                    ParticleTypes.PORTAL,
                    echo.x() + cos * radius,
                    echo.y() + 0.22,
                    echo.z() + sin * radius,
                    -cos * 0.045,
                    0.035,
                    -sin * 0.045
            );
        }
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        List<AltarEcho> echoes = event.getLevelRenderState().getRenderData(ECHOES_KEY);
        if (echoes == null || echoes.isEmpty()) return;

        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (AltarEcho echo : echoes) {
            event.getSubmitNodeCollector().submitCustomGeometry(
                    poseStack,
                    RenderTypes.debugQuads(),
                    (pose, consumer) -> renderEcho(pose, consumer, echo)
            );
        }
        poseStack.popPose();
    }

    private static void renderEcho(PoseStack.Pose pose, VertexConsumer consumer, AltarEcho echo) {
        double radius = echo.large() ? 3.25 : 2.35;
        double floorY = echo.y() + 0.035;
        double phase = echo.gameTime() * 0.018 + echo.x() * 0.003 + echo.z() * 0.002;

        renderRing(pose, consumer, echo.x(), floorY, echo.z(), radius, radius - 0.10, 36, phase, 178);
        renderRing(pose, consumer, echo.x(), floorY + 0.006, echo.z(), radius * 0.57, radius * 0.57 - 0.065, 28, -phase * 0.7, 118);
        renderRunes(pose, consumer, echo.x(), floorY + 0.010, echo.z(), radius, phase);

        double bob = Math.sin(echo.gameTime() * 0.12 + echo.x() * 0.05 + echo.z() * 0.04) * 0.08;
        renderCore(pose, consumer, echo.x(), echo.y() + 1.05 + bob, echo.z(), echo.gameTime());
    }

    private static void renderRing(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                   double outerRadius, double innerRadius, int segments, double angleOffset, int alpha) {
        for (int i = 0; i < segments; i++) {
            double a1 = angleOffset + Math.PI * 2.0 * i / segments;
            double a2 = angleOffset + Math.PI * 2.0 * (i + 1) / segments;
            addQuad(pose, consumer,
                    x + Math.cos(a1) * outerRadius, y, z + Math.sin(a1) * outerRadius,
                    x + Math.cos(a1) * innerRadius, y, z + Math.sin(a1) * innerRadius,
                    x + Math.cos(a2) * innerRadius, y, z + Math.sin(a2) * innerRadius,
                    x + Math.cos(a2) * outerRadius, y, z + Math.sin(a2) * outerRadius,
                    RUNE_RED, RUNE_GREEN, RUNE_BLUE, alpha);
        }
    }

    private static void renderRunes(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                    double radius, double phase) {
        for (int i = 0; i < 8; i++) {
            double angle = phase * 0.45 + i * Math.PI / 4.0;
            double radialCenter = radius * 0.79;
            double radialHalf = radius * 0.075;
            double tangentHalf = radius * 0.045;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double tx = -sin;
            double tz = cos;
            double inner = radialCenter - radialHalf;
            double outer = radialCenter + radialHalf;
            addQuad(pose, consumer,
                    x + cos * outer + tx * tangentHalf, y, z + sin * outer + tz * tangentHalf,
                    x + cos * inner + tx * tangentHalf, y, z + sin * inner + tz * tangentHalf,
                    x + cos * inner - tx * tangentHalf, y, z + sin * inner - tz * tangentHalf,
                    x + cos * outer - tx * tangentHalf, y, z + sin * outer - tz * tangentHalf,
                    198, 155, 255, 205);
        }
    }

    private static void renderCore(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z, long gameTime) {
        double horizontal = 0.34 + Math.sin(gameTime * 0.10) * 0.025;
        double vertical = 0.58;
        Point top = new Point(x, y + vertical, z);
        Point bottom = new Point(x, y - vertical, z);
        Point east = new Point(x + horizontal, y, z);
        Point south = new Point(x, y, z + horizontal);
        Point west = new Point(x - horizontal, y, z);
        Point north = new Point(x, y, z - horizontal);

        addDoubleSidedTriangle(pose, consumer, top, east, south, 226, 204, 255, 235);
        addDoubleSidedTriangle(pose, consumer, top, south, west, 196, 151, 255, 230);
        addDoubleSidedTriangle(pose, consumer, top, west, north, 226, 204, 255, 235);
        addDoubleSidedTriangle(pose, consumer, top, north, east, 196, 151, 255, 230);
        addDoubleSidedTriangle(pose, consumer, bottom, south, east, 147, 87, 235, 220);
        addDoubleSidedTriangle(pose, consumer, bottom, west, south, 174, 112, 255, 225);
        addDoubleSidedTriangle(pose, consumer, bottom, north, west, 147, 87, 235, 220);
        addDoubleSidedTriangle(pose, consumer, bottom, east, north, 174, 112, 255, 225);

        renderRing(pose, consumer, x, y, z, horizontal * 1.85, horizontal * 1.67, 24, -gameTime * 0.035, 105);
    }

    private static void addDoubleSidedTriangle(PoseStack.Pose pose, VertexConsumer consumer, Point a, Point b, Point c,
                                                int red, int green, int blue, int alpha) {
        addTriangle(pose, consumer, a, b, c, red, green, blue, alpha);
        addTriangle(pose, consumer, c, b, a, red, green, blue, alpha);
    }

    private static void addTriangle(PoseStack.Pose pose, VertexConsumer consumer, Point a, Point b, Point c,
                                    int red, int green, int blue, int alpha) {
        addQuad(pose, consumer,
                a.x(), a.y(), a.z(),
                b.x(), b.y(), b.z(),
                c.x(), c.y(), c.z(),
                c.x(), c.y(), c.z(),
                red, green, blue, alpha);
    }

    private static void addQuad(PoseStack.Pose pose, VertexConsumer consumer,
                                double x1, double y1, double z1, double x2, double y2, double z2,
                                double x3, double y3, double z3, double x4, double y4, double z4,
                                int red, int green, int blue, int alpha) {
        consumer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, (float) x3, (float) y3, (float) z3).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, (float) x4, (float) y4, (float) z4).setColor(red, green, blue, alpha);
    }

    private record Point(double x, double y, double z) { }
    private record AltarEcho(double x, double y, double z, boolean large, long gameTime) { }
}
