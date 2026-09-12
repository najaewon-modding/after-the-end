package net.njw.aftertheend.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.DustParticleOptions;
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
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final int GOLD_RED = 232;
    private static final int GOLD_GREEN = 198;
    private static final int GOLD_BLUE = 106;
    private static final int PALE_RED = 255;
    private static final int PALE_GREEN = 231;
    private static final int PALE_BLUE = 163;
    private static final int MUTED_RED = 185;
    private static final int MUTED_GREEN = 154;
    private static final int MUTED_BLUE = 82;
    private static final Identifier WHITE_TEXTURE = Identifier.withDefaultNamespace("textures/block/white_concrete.png");
    private static final RenderType ECHO_RENDER_TYPE = RenderTypes.entityTranslucent(WHITE_TEXTURE, false);
    private static final DustParticleOptions GOLD_DUST = new DustParticleOptions(0xFFE7A3, 1.0F);
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

        if (!echoes.isEmpty()) event.getRenderState().setRenderData(ECHOES_KEY, List.copyOf(echoes));

        if (gameTime != lastParticleTick && Math.floorMod(gameTime, PARTICLE_INTERVAL_TICKS) == 0L) {
            lastParticleTick = gameTime;
            spawnParticles(event, echoes, camera, gameTime);
        }
    }

    private static void spawnParticles(ExtractLevelRenderStateEvent event, List<AltarEcho> echoes, Vec3 camera, long gameTime) {
        double particleDistanceSqr = PARTICLE_DISTANCE * PARTICLE_DISTANCE;
        for (AltarEcho echo : echoes) {
            if (camera.distanceToSqr(echo.x(), echo.y(), echo.z()) > particleDistanceSqr) continue;
            double radius = echo.large() ? 2.6 : 1.8;
            double angle = gameTime * 0.13 + echo.x() * 0.019 + echo.z() * 0.013;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            event.getLevel().addParticle(
                    GOLD_DUST,
                    echo.x() + cos * radius,
                    echo.y() + 0.18,
                    echo.z() + sin * radius,
                    -cos * 0.025,
                    0.018,
                    -sin * 0.025
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
                    ECHO_RENDER_TYPE,
                    (pose, consumer) -> renderEcho(pose, consumer, echo)
            );
        }
        poseStack.popPose();
    }

    private static void renderEcho(PoseStack.Pose pose, VertexConsumer consumer, AltarEcho echo) {
        double radius = echo.large() ? 3.55 : 2.50;
        double floorY = echo.y() + 0.035;
        double outerPhase = echo.gameTime() * 0.0105 + echo.x() * 0.003 + echo.z() * 0.002;
        double innerPhase = -echo.gameTime() * 0.0065 + echo.x() * 0.0015 - echo.z() * 0.001;
        double sealPhase = echo.gameTime() * 0.0024 + echo.x() * 0.0011 - echo.z() * 0.0013;

        renderBrokenRing(pose, consumer, echo.x(), floorY, echo.z(), radius, 0.080,
                echo.large() ? 18 : 15, 0.62, 3, outerPhase, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 176);
        renderBrokenRing(pose, consumer, echo.x(), floorY + 0.004, echo.z(), radius * 0.76, 0.045,
                echo.large() ? 12 : 9, 0.50, 2, innerPhase, MUTED_RED, MUTED_GREEN, MUTED_BLUE, 102);
        if (echo.large()) {
            renderBrokenRing(pose, consumer, echo.x(), floorY + 0.007, echo.z(), radius * 0.59, 0.038,
                    15, 0.42, 2, -outerPhase * 0.48, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 86);
        }

        renderCardinalSigils(pose, consumer, echo.x(), floorY + 0.010, echo.z(), radius, echo.large(), sealPhase * 0.72);
        renderTriangularSeal(pose, consumer, echo.x(), floorY + 0.016, echo.z(), radius, echo.large(), sealPhase);

        double bob = Math.sin(echo.gameTime() * 0.12 + echo.x() * 0.05 + echo.z() * 0.04) * 0.08;
        renderCore(pose, consumer, echo.x(), echo.y() + 1.05 + bob, echo.z(), echo.gameTime());
    }

    private static void renderBrokenRing(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                         double radius, double thickness, int pieces, double fillFraction,
                                         int subdivisions, double angleOffset, int red, int green, int blue, int alpha) {
        double sector = Math.PI * 2.0 / pieces;
        double arc = sector * fillFraction;
        for (int piece = 0; piece < pieces; piece++) {
            double center = angleOffset + piece * sector;
            double start = center - arc * 0.5;
            for (int step = 0; step < subdivisions; step++) {
                double a1 = start + arc * step / subdivisions;
                double a2 = start + arc * (step + 1) / subdivisions;
                addRingSegment(pose, consumer, x, y, z, radius, radius - thickness, a1, a2,
                        red, green, blue, alpha);
            }
        }
    }

    private static void renderCardinalSigils(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                              double radius, boolean large, double phase) {
        double socketRadius = radius * 0.80;
        double lineStart = radius * 0.31;
        double lineEnd = socketRadius - radius * 0.14;
        double lineWidth = large ? 0.054 : 0.048;
        double diamondOuter = large ? radius * 0.102 : radius * 0.112;
        double diamondInner = diamondOuter * 0.50;

        for (int i = 0; i < 4; i++) {
            double angle = phase + i * Math.PI * 0.5;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            renderLineXZ(pose, consumer,
                    x + cos * lineStart, y, z + sin * lineStart,
                    x + cos * lineEnd, z + sin * lineEnd,
                    lineWidth, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 176);
            renderDiamond(pose, consumer,
                    x + cos * socketRadius, y + 0.002, z + sin * socketRadius,
                    diamondOuter, diamondInner, angle, PALE_RED, PALE_GREEN, PALE_BLUE, 228);

            double markRadius = socketRadius + radius * 0.13;
            double tangent = radius * 0.065;
            double tx = -sin;
            double tz = cos;
            renderLineXZ(pose, consumer,
                    x + cos * markRadius - tx * tangent, y + 0.001, z + sin * markRadius - tz * tangent,
                    x + cos * markRadius + tx * tangent, z + sin * markRadius + tz * tangent,
                    lineWidth * 0.82, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 146);
        }
    }

    private static void renderTriangularSeal(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                             double radius, boolean large, double phase) {
        double frameRadius = radius * (large ? 0.50 : 0.48);
        double armRadius = radius * 0.405;
        double controlRadius = radius * 0.095;
        double innerRadius = radius * 0.155;
        double base = phase - Math.PI * 0.5;

        renderBrokenRing(pose, consumer, x, y, z, frameRadius, 0.030,
                large ? 12 : 9, 0.56, 2, -phase * 0.78, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 96);
        renderPolygonOutline(pose, consumer, x, y + 0.002, z, radius * 0.445, 3, base,
                large ? 0.066 : 0.060, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 214);

        for (int i = 0; i < 3; i++) {
            double a1 = base + i * Math.PI * 2.0 / 3.0;
            double a2 = base + (i + 1) * Math.PI * 2.0 / 3.0;
            double mid = (a1 + a2) * 0.5;
            double x1 = x + Math.cos(a1) * armRadius;
            double z1 = z + Math.sin(a1) * armRadius;
            double x2 = x + Math.cos(a2) * armRadius;
            double z2 = z + Math.sin(a2) * armRadius;
            double cx = x + Math.cos(mid) * controlRadius;
            double cz = z + Math.sin(mid) * controlRadius;
            renderQuadraticBezierXZ(pose, consumer, x1, y + 0.005, z1, cx, cz, x2, z2,
                    large ? 0.105 : 0.092, 10, PALE_RED, PALE_GREEN, PALE_BLUE, 232);

            double innerStartRadius = armRadius * 0.76;
            double innerControlRadius = controlRadius * 0.42;
            double ix1 = x + Math.cos(a1 + 0.08) * innerStartRadius;
            double iz1 = z + Math.sin(a1 + 0.08) * innerStartRadius;
            double ix2 = x + Math.cos(a2 - 0.08) * innerStartRadius;
            double iz2 = z + Math.sin(a2 - 0.08) * innerStartRadius;
            double icx = x + Math.cos(mid) * innerControlRadius;
            double icz = z + Math.sin(mid) * innerControlRadius;
            renderQuadraticBezierXZ(pose, consumer, ix1, y + 0.008, iz1, icx, icz, ix2, iz2,
                    large ? 0.044 : 0.038, 8, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 194);
        }

        renderPolygonOutline(pose, consumer, x, y + 0.011, z, innerRadius, 3, base + Math.PI,
                large ? 0.052 : 0.046, PALE_RED, PALE_GREEN, PALE_BLUE, 242);
        if (large) {
            renderPolygonOutline(pose, consumer, x, y + 0.013, z, radius * 0.355, 3, base + Math.PI / 3.0,
                    0.028, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 112);
        }
    }

    private static void renderQuadraticBezierXZ(PoseStack.Pose pose, VertexConsumer consumer,
                                                 double x1, double y, double z1, double cx, double cz,
                                                 double x2, double z2, double width, int segments,
                                                 int red, int green, int blue, int alpha) {
        double previousX = x1;
        double previousZ = z1;
        for (int i = 1; i <= segments; i++) {
            double t = (double)i / segments;
            double u = 1.0 - t;
            double nextX = u * u * x1 + 2.0 * u * t * cx + t * t * x2;
            double nextZ = u * u * z1 + 2.0 * u * t * cz + t * t * z2;
            renderLineXZ(pose, consumer, previousX, y, previousZ, nextX, nextZ, width,
                    red, green, blue, alpha);
            previousX = nextX;
            previousZ = nextZ;
        }
    }

    private static void renderDiamond(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                      double outer, double inner, double angle,
                                      int red, int green, int blue, int alpha) {
        for (int i = 0; i < 4; i++) {
            double a1 = angle + i * Math.PI * 0.5;
            double a2 = angle + (i + 1) * Math.PI * 0.5;
            double mid = (a1 + a2) * 0.5;
            addQuad(pose, consumer,
                    x + Math.cos(a1) * outer, y, z + Math.sin(a1) * outer,
                    x + Math.cos(mid) * inner, y, z + Math.sin(mid) * inner,
                    x + Math.cos(a2) * outer, y, z + Math.sin(a2) * outer,
                    x + Math.cos(mid) * outer * 0.93, y, z + Math.sin(mid) * outer * 0.93,
                    red, green, blue, alpha);
        }
    }

    private static void renderPolygonOutline(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                             double radius, int sides, double angleOffset, double width,
                                             int red, int green, int blue, int alpha) {
        for (int i = 0; i < sides; i++) {
            double a1 = angleOffset + Math.PI * 2.0 * i / sides;
            double a2 = angleOffset + Math.PI * 2.0 * (i + 1) / sides;
            renderLineXZ(pose, consumer,
                    x + Math.cos(a1) * radius, y, z + Math.sin(a1) * radius,
                    x + Math.cos(a2) * radius, z + Math.sin(a2) * radius,
                    width, red, green, blue, alpha);
        }
    }

    private static void renderLineXZ(PoseStack.Pose pose, VertexConsumer consumer,
                                     double x1, double y, double z1, double x2, double z2, double width,
                                     int red, int green, int blue, int alpha) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 1.0E-6) return;
        double px = -dz / length * width * 0.5;
        double pz = dx / length * width * 0.5;
        addQuad(pose, consumer,
                x1 + px, y, z1 + pz,
                x1 - px, y, z1 - pz,
                x2 - px, y, z2 - pz,
                x2 + px, y, z2 + pz,
                red, green, blue, alpha);
    }

    private static void addRingSegment(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                       double outerRadius, double innerRadius, double a1, double a2,
                                       int red, int green, int blue, int alpha) {
        addQuad(pose, consumer,
                x + Math.cos(a1) * outerRadius, y, z + Math.sin(a1) * outerRadius,
                x + Math.cos(a1) * innerRadius, y, z + Math.sin(a1) * innerRadius,
                x + Math.cos(a2) * innerRadius, y, z + Math.sin(a2) * innerRadius,
                x + Math.cos(a2) * outerRadius, y, z + Math.sin(a2) * outerRadius,
                red, green, blue, alpha);
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

        addDoubleSidedTriangle(pose, consumer, top, east, south, PALE_RED, PALE_GREEN, PALE_BLUE, 245);
        addDoubleSidedTriangle(pose, consumer, top, south, west, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 238);
        addDoubleSidedTriangle(pose, consumer, top, west, north, PALE_RED, PALE_GREEN, PALE_BLUE, 245);
        addDoubleSidedTriangle(pose, consumer, top, north, east, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 238);
        addDoubleSidedTriangle(pose, consumer, bottom, south, east, MUTED_RED, MUTED_GREEN, MUTED_BLUE, 225);
        addDoubleSidedTriangle(pose, consumer, bottom, west, south, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 232);
        addDoubleSidedTriangle(pose, consumer, bottom, north, west, MUTED_RED, MUTED_GREEN, MUTED_BLUE, 225);
        addDoubleSidedTriangle(pose, consumer, bottom, east, north, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 232);

        renderBrokenRing(pose, consumer, x, y, z, horizontal * 1.95, horizontal * 0.14,
                10, 0.60, 2, -gameTime * 0.012, PALE_RED, PALE_GREEN, PALE_BLUE, 126);
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
        addVertex(pose, consumer, x1, y1, z1, red, green, blue, alpha);
        addVertex(pose, consumer, x2, y2, z2, red, green, blue, alpha);
        addVertex(pose, consumer, x3, y3, z3, red, green, blue, alpha);
        addVertex(pose, consumer, x4, y4, z4, red, green, blue, alpha);
    }

    private static void addVertex(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                  int red, int green, int blue, int alpha) {
        consumer.addVertex(pose, (float)x, (float)y, (float)z)
                .setColor(red, green, blue, alpha)
                .setUv(0.5F, 0.5F)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    private record Point(double x, double y, double z) { }
    private record AltarEcho(double x, double y, double z, boolean large, long gameTime) { }
}
