package net.njw.aftertheend.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.njw.aftertheend.AfterTheEnd;

@EventBusSubscriber(modid = AfterTheEnd.MODID, value = Dist.CLIENT)
public final class ActivatedAltarClientEffects {
    private static final double RENDER_DISTANCE = 160.0;
    private static final double PARTICLE_DISTANCE = 64.0;
    private static final int PARTICLE_INTERVAL_TICKS = 30;
    private static final int GOLD_RED = 232;
    private static final int GOLD_GREEN = 198;
    private static final int GOLD_BLUE = 106;
    private static final int PALE_RED = 255;
    private static final int PALE_GREEN = 231;
    private static final int PALE_BLUE = 163;
    private static final int SOFT_RED = 207;
    private static final int SOFT_GREEN = 174;
    private static final int SOFT_BLUE = 88;

    private static final RenderPipeline ECHO_PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "pipeline/activated_altar_echo"))
            .withVertexShader("core/position_color")
            .withFragmentShader("core/position_color")
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .build();
    private static final RenderType ECHO_RENDER_TYPE = RenderType.create(
            "after_the_end_activated_altar_echo",
            RenderSetup.builder(ECHO_PIPELINE).sortOnUpload().createRenderSetup()
    );
    private static final DustParticleOptions GOLD_DUST = new DustParticleOptions(0xFFE7A3, 1.0F);
    private static final ContextKey<List<AltarEcho>> ECHOES_KEY = new ContextKey<>(
            Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "activated_altar_echoes"));
    private static long lastParticleTick = Long.MIN_VALUE;

    private ActivatedAltarClientEffects() { }

    @SubscribeEvent
    public static void onRegisterRenderPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(ECHO_PIPELINE);
    }

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
        double outerPhase = echo.gameTime() * 0.0090 + echo.x() * 0.003 + echo.z() * 0.002;
        double supportPhase = -echo.gameTime() * 0.0048 + echo.x() * 0.0015 - echo.z() * 0.001;
        double trianglePhase = echo.gameTime() * 0.0022 + echo.x() * 0.0011 - echo.z() * 0.0013;
        double accentPhase = -echo.gameTime() * 0.0016 + echo.x() * 0.0007 + echo.z() * 0.0009;

        renderBrokenRing(pose, consumer, echo.x(), floorY, echo.z(), radius, 0.072,
                echo.large() ? 19 : 16, 0.60, 3, outerPhase, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 176);
        renderBrokenRing(pose, consumer, echo.x(), floorY + 0.003, echo.z(), radius * 0.72, 0.034,
                echo.large() ? 15 : 12, 0.40, 2, supportPhase, SOFT_RED, SOFT_GREEN, SOFT_BLUE, 92);
        if (echo.large()) {
            renderBrokenRing(pose, consumer, echo.x(), floorY + 0.005, echo.z(), radius * 0.88, 0.026,
                    24, 0.28, 2, -outerPhase * 0.52, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 72);
        }

        renderCardinalAccents(pose, consumer, echo.x(), floorY + 0.008, echo.z(), radius, echo.large(), accentPhase);
        renderCurvedTriangularSeal(pose, consumer, echo.x(), floorY + 0.012, echo.z(), radius, echo.large(), trianglePhase);

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

    private static void renderCardinalAccents(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                              double radius, boolean large, double phase) {
        double accentRadius = radius * 0.835;
        double diamondOuter = radius * (large ? 0.074 : 0.080);
        double diamondInner = diamondOuter * 0.48;
        double radialWidth = large ? 0.040 : 0.036;
        double tangentHalf = radius * 0.046;

        for (int i = 0; i < 4; i++) {
            double angle = phase + i * Math.PI * 0.5;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double tx = -sin;
            double tz = cos;

            renderDiamond(pose, consumer,
                    x + cos * accentRadius, y, z + sin * accentRadius,
                    diamondOuter, diamondInner, angle, PALE_RED, PALE_GREEN, PALE_BLUE, 208);

            renderLineXZ(pose, consumer,
                    x + cos * radius * 0.735, y, z + sin * radius * 0.735,
                    x + cos * radius * 0.775, z + sin * radius * 0.775,
                    radialWidth, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 132);

            double markRadius = radius * 0.915;
            renderLineXZ(pose, consumer,
                    x + cos * markRadius - tx * tangentHalf, y, z + sin * markRadius - tz * tangentHalf,
                    x + cos * markRadius + tx * tangentHalf, z + sin * markRadius + tz * tangentHalf,
                    radialWidth * 0.78, SOFT_RED, SOFT_GREEN, SOFT_BLUE, 112);
        }
    }

    private static void renderCurvedTriangularSeal(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                                   double radius, boolean large, double phase) {
        double outerVertexRadius = radius * (large ? 0.635 : 0.620);
        double outerControlRadius = outerVertexRadius * 0.655;
        double outerWidth = large ? 0.122 : 0.108;
        double innerVertexRadius = outerVertexRadius * 0.52;
        double innerControlRadius = innerVertexRadius * 0.63;
        double innerWidth = large ? 0.046 : 0.040;
        double base = phase - Math.PI * 0.5;

        renderCurvedTriangle(pose, consumer, x, y, z, outerVertexRadius, outerControlRadius, base,
                outerWidth, 14, PALE_RED, PALE_GREEN, PALE_BLUE, 238);
        renderCurvedTriangle(pose, consumer, x, y + 0.002, z, innerVertexRadius, innerControlRadius, base + Math.PI,
                innerWidth, 10, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 184);

        double centerRadius = radius * (large ? 0.105 : 0.098);
        renderPolygonOutline(pose, consumer, x, y + 0.004, z, centerRadius, 3, base,
                large ? 0.034 : 0.030, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 164);
    }

    private static void renderCurvedTriangle(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                             double vertexRadius, double controlRadius, double base,
                                             double width, int segments, int red, int green, int blue, int alpha) {
        for (int i = 0; i < 3; i++) {
            double a1 = base + i * Math.PI * 2.0 / 3.0;
            double a2 = base + (i + 1) * Math.PI * 2.0 / 3.0;
            double mid = (a1 + a2) * 0.5;
            double x1 = x + Math.cos(a1) * vertexRadius;
            double z1 = z + Math.sin(a1) * vertexRadius;
            double x2 = x + Math.cos(a2) * vertexRadius;
            double z2 = z + Math.sin(a2) * vertexRadius;
            double cx = x + Math.cos(mid) * controlRadius;
            double cz = z + Math.sin(mid) * controlRadius;
            renderQuadraticBezierXZ(pose, consumer, x1, y, z1, cx, cz, x2, z2,
                    width, segments, red, green, blue, alpha);
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

        addDoubleSidedTriangle(pose, consumer, top, east, south, PALE_RED, PALE_GREEN, PALE_BLUE, 246);
        addDoubleSidedTriangle(pose, consumer, top, south, west, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 240);
        addDoubleSidedTriangle(pose, consumer, top, west, north, PALE_RED, PALE_GREEN, PALE_BLUE, 246);
        addDoubleSidedTriangle(pose, consumer, top, north, east, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 240);
        addDoubleSidedTriangle(pose, consumer, bottom, south, east, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 240);
        addDoubleSidedTriangle(pose, consumer, bottom, west, south, PALE_RED, PALE_GREEN, PALE_BLUE, 246);
        addDoubleSidedTriangle(pose, consumer, bottom, north, west, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 240);
        addDoubleSidedTriangle(pose, consumer, bottom, east, north, PALE_RED, PALE_GREEN, PALE_BLUE, 246);

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
        consumer.addVertex(pose, (float)x1, (float)y1, (float)z1).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, (float)x2, (float)y2, (float)z2).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, (float)x3, (float)y3, (float)z3).setColor(red, green, blue, alpha);
        consumer.addVertex(pose, (float)x4, (float)y4, (float)z4).setColor(red, green, blue, alpha);
    }

    private record Point(double x, double y, double z) { }
    private record AltarEcho(double x, double y, double z, boolean large, long gameTime) { }
}
