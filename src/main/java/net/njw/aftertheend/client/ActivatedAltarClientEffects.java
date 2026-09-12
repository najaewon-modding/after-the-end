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
        double outerPhase = echo.gameTime() * 0.0065 + echo.x() * 0.003 + echo.z() * 0.002;
        double orbitPhase = -echo.gameTime() * 0.0034 + echo.x() * 0.0015 - echo.z() * 0.001;
        double bandPhase = echo.gameTime() * 0.0019 + echo.x() * 0.0011 - echo.z() * 0.0013;
        double weavePhase = -echo.gameTime() * 0.00125 + echo.x() * 0.0007 + echo.z() * 0.0009;

        renderBrokenRing(pose, consumer, echo.x(), floorY, echo.z(), radius, 0.056,
                echo.large() ? 22 : 18, 0.64, 3, outerPhase, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 170);
        renderSolidRing(pose, consumer, echo.x(), floorY + 0.002, echo.z(), radius * 0.925, 0.018,
                72, SOFT_RED, SOFT_GREEN, SOFT_BLUE, 108);
        renderSolidRing(pose, consumer, echo.x(), floorY + 0.004, echo.z(), radius * 0.835, 0.024,
                72, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 142);
        renderOrbitNodes(pose, consumer, echo.x(), floorY + 0.006, echo.z(), radius * 0.885,
                echo.large() ? 7 : 6, orbitPhase, radius * 0.026, true);
        renderOrbitNodes(pose, consumer, echo.x(), floorY + 0.007, echo.z(), radius * 0.790,
                echo.large() ? 5 : 4, -orbitPhase * 0.73 + 0.41, radius * 0.019, false);

        renderSolidRing(pose, consumer, echo.x(), floorY + 0.008, echo.z(), radius * 0.745, 0.018,
                64, SOFT_RED, SOFT_GREEN, SOFT_BLUE, 86);
        renderLatticeBand(pose, consumer, echo.x(), floorY + 0.010, echo.z(), radius * 0.675, radius * 0.735,
                echo.large() ? 30 : 24, bandPhase);
        renderSolidRing(pose, consumer, echo.x(), floorY + 0.012, echo.z(), radius * 0.665, 0.021,
                64, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 126);

        renderAstralSeal(pose, consumer, echo.x(), floorY + 0.014, echo.z(), radius, echo.large(), weavePhase);

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

    private static void renderSolidRing(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                        double radius, double thickness, int segments,
                                        int red, int green, int blue, int alpha) {
        for (int i = 0; i < segments; i++) {
            double a1 = Math.PI * 2.0 * i / segments;
            double a2 = Math.PI * 2.0 * (i + 1) / segments;
            addRingSegment(pose, consumer, x, y, z, radius, radius - thickness, a1, a2,
                    red, green, blue, alpha);
        }
    }

    private static void renderOrbitNodes(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                         double orbitRadius, int count, double phase, double nodeRadius, boolean emphasize) {
        for (int i = 0; i < count; i++) {
            double angle = phase + i * Math.PI * 2.0 / count + Math.sin(i * 2.37) * 0.11;
            double nx = x + Math.cos(angle) * orbitRadius;
            double nz = z + Math.sin(angle) * orbitRadius;
            double scale = 0.72 + 0.34 * (0.5 + 0.5 * Math.sin(i * 1.91 + phase * 0.7));
            double r = nodeRadius * scale;
            renderFilledDisc(pose, consumer, nx, y, nz, r, 12,
                    emphasize ? PALE_RED : GOLD_RED,
                    emphasize ? PALE_GREEN : GOLD_GREEN,
                    emphasize ? PALE_BLUE : GOLD_BLUE,
                    emphasize ? 216 : 164);
            renderSolidRing(pose, consumer, nx, y + 0.001, nz, r * 1.65, Math.max(0.010, r * 0.28), 16,
                    GOLD_RED, GOLD_GREEN, GOLD_BLUE, emphasize ? 142 : 96);
        }
    }

    private static void renderLatticeBand(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                          double innerRadius, double outerRadius, int segments, double phase) {
        double width = (outerRadius - innerRadius) * 0.11;
        for (int i = 0; i < segments; i++) {
            double a1 = phase + Math.PI * 2.0 * i / segments;
            double a2 = phase + Math.PI * 2.0 * (i + 1) / segments;
            renderLineXZ(pose, consumer,
                    x + Math.cos(a1) * innerRadius, y, z + Math.sin(a1) * innerRadius,
                    x + Math.cos(a2) * outerRadius, z + Math.sin(a2) * outerRadius,
                    width, SOFT_RED, SOFT_GREEN, SOFT_BLUE, 82);
            renderLineXZ(pose, consumer,
                    x + Math.cos(a1) * outerRadius, y, z + Math.sin(a1) * outerRadius,
                    x + Math.cos(a2) * innerRadius, z + Math.sin(a2) * innerRadius,
                    width, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 66);
        }
    }

    private static void renderAstralSeal(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                         double radius, boolean large, double phase) {
        double outer = radius * 0.585;
        double middle = radius * 0.525;
        double weaveLong = radius * 0.455;
        double weaveShort = radius * 0.265;
        double inner = radius * 0.245;
        double core = radius * 0.155;

        renderSolidRing(pose, consumer, x, y, z, outer, large ? 0.040 : 0.035, 72,
                PALE_RED, PALE_GREEN, PALE_BLUE, 210);
        renderSolidRing(pose, consumer, x, y + 0.002, z, middle, large ? 0.020 : 0.017, 72,
                GOLD_RED, GOLD_GREEN, GOLD_BLUE, 132);
        renderBrokenRing(pose, consumer, x, y + 0.003, z, (outer + middle) * 0.5, large ? 0.018 : 0.015,
                large ? 28 : 24, 0.42, 2, phase * 1.7, SOFT_RED, SOFT_GREEN, SOFT_BLUE, 82);

        for (int i = 0; i < 5; i++) {
            double rotation = phase + i * Math.PI / 5.0;
            renderEllipseOutline(pose, consumer, x, y + 0.004 + i * 0.0005, z,
                    weaveLong, weaveShort, rotation, 40,
                    large ? 0.024 : 0.020, GOLD_RED, GOLD_GREEN, GOLD_BLUE, 112);
        }
        for (int i = 0; i < 3; i++) {
            double rotation = -phase * 0.81 + Math.PI / 10.0 + i * Math.PI / 3.0;
            renderEllipseOutline(pose, consumer, x, y + 0.007 + i * 0.0005, z,
                    weaveLong * 0.86, weaveShort * 0.78, rotation, 36,
                    large ? 0.017 : 0.015, PALE_RED, PALE_GREEN, PALE_BLUE, 86);
        }

        renderOrbitNode(pose, consumer, x, y + 0.009, z, radius * 0.395, phase + 0.58,
                radius * (large ? 0.060 : 0.056), true);
        renderOrbitNode(pose, consumer, x, y + 0.010, z, radius * 0.335, phase + 3.62,
                radius * (large ? 0.047 : 0.043), false);

        renderSolidRing(pose, consumer, x, y + 0.011, z, inner, large ? 0.034 : 0.030, 56,
                PALE_RED, PALE_GREEN, PALE_BLUE, 222);
        renderSolidRing(pose, consumer, x, y + 0.013, z, core, large ? 0.020 : 0.018, 48,
                GOLD_RED, GOLD_GREEN, GOLD_BLUE, 172);
        renderRoseCurve(pose, consumer, x, y + 0.015, z, core * 0.84, core * 0.30,
                large ? 7 : 6, phase * 1.35, 72, large ? 0.018 : 0.016,
                PALE_RED, PALE_GREEN, PALE_BLUE, 176);
        renderFilledDisc(pose, consumer, x, y + 0.017, z, core * 0.22, 16,
                PALE_RED, PALE_GREEN, PALE_BLUE, 220);
    }

    private static void renderOrbitNode(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                        double orbitRadius, double angle, double nodeRadius, boolean pale) {
        double nx = x + Math.cos(angle) * orbitRadius;
        double nz = z + Math.sin(angle) * orbitRadius;
        renderFilledDisc(pose, consumer, nx, y, nz, nodeRadius * 0.58, 16,
                pale ? PALE_RED : GOLD_RED,
                pale ? PALE_GREEN : GOLD_GREEN,
                pale ? PALE_BLUE : GOLD_BLUE,
                pale ? 224 : 188);
        renderSolidRing(pose, consumer, nx, y + 0.001, nz, nodeRadius, Math.max(0.012, nodeRadius * 0.16), 24,
                GOLD_RED, GOLD_GREEN, GOLD_BLUE, pale ? 188 : 148);
        renderSolidRing(pose, consumer, nx, y + 0.002, nz, nodeRadius * 1.24, Math.max(0.009, nodeRadius * 0.08), 24,
                SOFT_RED, SOFT_GREEN, SOFT_BLUE, 88);
    }

    private static void renderEllipseOutline(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                             double radiusX, double radiusZ, double rotation, int segments, double width,
                                             int red, int green, int blue, int alpha) {
        double cosR = Math.cos(rotation);
        double sinR = Math.sin(rotation);
        double previousX = x + radiusX * cosR;
        double previousZ = z + radiusX * sinR;
        for (int i = 1; i <= segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            double lx = Math.cos(angle) * radiusX;
            double lz = Math.sin(angle) * radiusZ;
            double nextX = x + lx * cosR - lz * sinR;
            double nextZ = z + lx * sinR + lz * cosR;
            renderLineXZ(pose, consumer, previousX, y, previousZ, nextX, nextZ, width,
                    red, green, blue, alpha);
            previousX = nextX;
            previousZ = nextZ;
        }
    }

    private static void renderRoseCurve(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                        double baseRadius, double amplitude, int petals, double phase, int segments,
                                        double width, int red, int green, int blue, int alpha) {
        double previousAngle = 0.0;
        double previousRadius = baseRadius + amplitude * Math.cos(petals * previousAngle + phase);
        double previousX = x + Math.cos(previousAngle + phase * 0.31) * previousRadius;
        double previousZ = z + Math.sin(previousAngle + phase * 0.31) * previousRadius;
        for (int i = 1; i <= segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            double r = baseRadius + amplitude * Math.cos(petals * angle + phase);
            double nextX = x + Math.cos(angle + phase * 0.31) * r;
            double nextZ = z + Math.sin(angle + phase * 0.31) * r;
            renderLineXZ(pose, consumer, previousX, y, previousZ, nextX, nextZ, width,
                    red, green, blue, alpha);
            previousX = nextX;
            previousZ = nextZ;
        }
    }

    private static void renderFilledDisc(PoseStack.Pose pose, VertexConsumer consumer, double x, double y, double z,
                                         double radius, int segments, int red, int green, int blue, int alpha) {
        Point center = new Point(x, y, z);
        for (int i = 0; i < segments; i++) {
            double a1 = Math.PI * 2.0 * i / segments;
            double a2 = Math.PI * 2.0 * (i + 1) / segments;
            addTriangle(pose, consumer, center,
                    new Point(x + Math.cos(a1) * radius, y, z + Math.sin(a1) * radius),
                    new Point(x + Math.cos(a2) * radius, y, z + Math.sin(a2) * radius),
                    red, green, blue, alpha);
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
