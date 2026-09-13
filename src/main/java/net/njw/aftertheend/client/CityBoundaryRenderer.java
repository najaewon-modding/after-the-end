package net.njw.aftertheend.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.CityRegion;

@EventBusSubscriber(modid = AfterTheEnd.MODID, value = Dist.CLIENT)
public final class CityBoundaryRenderer {
    private static final double ACTIVATION_DISTANCE = 128.0;
    private static final double FULL_OPACITY_DISTANCE = 36.0;
    private static final double WALL_PROXIMITY_DISTANCE = 128.0;
    private static final double HORIZONTAL_RADIUS = 128.0;
    private static final double ALONG_FADE_DISTANCE = 128.0;
    private static final double WALL_BELOW = 80.0;
    private static final double WALL_ABOVE = 80.0;
    private static final double WALL_STRIP_WIDTH = 4.0;
    private static final int RED = 80;
    private static final int GREEN = 170;
    private static final int BLUE = 255;
    private static final int MAX_ALPHA = 140;
    private static final ContextKey<List<WallSegment>> WALLS_KEY = new ContextKey<>(
            Identifier.fromNamespaceAndPath(AfterTheEnd.MODID, "city_boundary_walls")
    );

    private CityBoundaryRenderer() { }

    @SubscribeEvent
    public static void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
        Vec3 camera = event.getCamera().position();
        Identifier dimension = event.getLevel().dimension().identifier();
        List<WallSegment> walls = new ArrayList<>();
        for (ClientCityManager.ClientCity city : ClientCityManager.getAccessibleCities()) {
            CityRegion region = city.getRegion(dimension);
            if (region != null) collectWallsForRegion(walls, region, camera);
        }
        if (!walls.isEmpty()) event.getRenderState().setRenderData(WALLS_KEY, List.copyOf(walls));
    }

    private static void collectWallsForRegion(List<WallSegment> walls, CityRegion region, Vec3 camera) {
        double minX = region.minBlockX();
        double maxX = region.maxBlockX() + 1.0;
        double minZ = region.minBlockZ();
        double maxZ = region.maxBlockZ() + 1.0;
        double minY = camera.y - WALL_BELOW;
        double maxY = camera.y + WALL_ABOVE;

        if (Math.abs(camera.x - minX) <= ACTIVATION_DISTANCE) {
            double start = Mth.clamp(camera.z - HORIZONTAL_RADIUS, minZ, maxZ);
            double end = Mth.clamp(camera.z + HORIZONTAL_RADIUS, minZ, maxZ);
            if (start < end) walls.add(WallSegment.xWall(minX, minY, maxY, start, end));
        }
        if (Math.abs(camera.x - maxX) <= ACTIVATION_DISTANCE) {
            double start = Mth.clamp(camera.z - HORIZONTAL_RADIUS, minZ, maxZ);
            double end = Mth.clamp(camera.z + HORIZONTAL_RADIUS, minZ, maxZ);
            if (start < end) walls.add(WallSegment.xWall(maxX, minY, maxY, start, end));
        }
        if (Math.abs(camera.z - minZ) <= ACTIVATION_DISTANCE) {
            double start = Mth.clamp(camera.x - HORIZONTAL_RADIUS, minX, maxX);
            double end = Mth.clamp(camera.x + HORIZONTAL_RADIUS, minX, maxX);
            if (start < end) walls.add(WallSegment.zWall(minZ, minY, maxY, start, end));
        }
        if (Math.abs(camera.z - maxZ) <= ACTIVATION_DISTANCE) {
            double start = Mth.clamp(camera.x - HORIZONTAL_RADIUS, minX, maxX);
            double end = Mth.clamp(camera.x + HORIZONTAL_RADIUS, minX, maxX);
            if (start < end) walls.add(WallSegment.zWall(maxZ, minY, maxY, start, end));
        }
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(SubmitCustomGeometryEvent event) {
        List<WallSegment> walls = event.getLevelRenderState().getRenderData(WALLS_KEY);
        if (walls == null || walls.isEmpty()) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (WallSegment wall : walls) {
            event.getSubmitNodeCollector().submitCustomGeometry(
                    poseStack,
                    RenderTypes.dragonRays(),
                    (pose, consumer) -> renderWall(pose, consumer, wall, camera)
            );
        }
        poseStack.popPose();
    }

    private static void renderWall(PoseStack.Pose pose, VertexConsumer consumer, WallSegment wall, Vec3 camera) {
        if (wall.axis() == Axis.X) renderXWall(pose, consumer, wall, camera);
        else renderZWall(pose, consumer, wall, camera);
    }

    private static void renderXWall(PoseStack.Pose pose, VertexConsumer consumer, WallSegment wall, Vec3 camera) {
        float x = (float) wall.fixedCoordinate();
        float y1 = (float) wall.minY();
        float y2 = (float) wall.maxY();
        double wallProximity = calculateWallProximity(Math.abs(camera.x - wall.fixedCoordinate()));
        for (double z = wall.start(); z < wall.end(); z += WALL_STRIP_WIDTH) {
            double nextZ = Math.min(z + WALL_STRIP_WIDTH, wall.end());
            int alpha1 = calculateFinalAlpha(wallProximity, calculateAlongFade(Math.abs(camera.z - z)));
            int alpha2 = calculateFinalAlpha(wallProximity, calculateAlongFade(Math.abs(camera.z - nextZ)));
            if (alpha1 <= 0 && alpha2 <= 0) continue;
            addDoubleSidedQuad(
                    pose, consumer,
                    x, y1, (float) z, alpha1,
                    x, y2, (float) z, alpha1,
                    x, y2, (float) nextZ, alpha2,
                    x, y1, (float) nextZ, alpha2
            );
        }
    }

    private static void renderZWall(PoseStack.Pose pose, VertexConsumer consumer, WallSegment wall, Vec3 camera) {
        float z = (float) wall.fixedCoordinate();
        float y1 = (float) wall.minY();
        float y2 = (float) wall.maxY();
        double wallProximity = calculateWallProximity(Math.abs(camera.z - wall.fixedCoordinate()));
        for (double x = wall.start(); x < wall.end(); x += WALL_STRIP_WIDTH) {
            double nextX = Math.min(x + WALL_STRIP_WIDTH, wall.end());
            int alpha1 = calculateFinalAlpha(wallProximity, calculateAlongFade(Math.abs(camera.x - x)));
            int alpha2 = calculateFinalAlpha(wallProximity, calculateAlongFade(Math.abs(camera.x - nextX)));
            if (alpha1 <= 0 && alpha2 <= 0) continue;
            addDoubleSidedQuad(
                    pose, consumer,
                    (float) x, y1, z, alpha1,
                    (float) x, y2, z, alpha1,
                    (float) nextX, y2, z, alpha2,
                    (float) nextX, y1, z, alpha2
            );
        }
    }

    private static void addDoubleSidedQuad(
            PoseStack.Pose pose, VertexConsumer consumer,
            float ax, float ay, float az, int aa,
            float bx, float by, float bz, int ba,
            float cx, float cy, float cz, int ca,
            float dx, float dy, float dz, int da
    ) {
        vertex(pose, consumer, ax, ay, az, aa);
        vertex(pose, consumer, bx, by, bz, ba);
        vertex(pose, consumer, cx, cy, cz, ca);
        vertex(pose, consumer, ax, ay, az, aa);
        vertex(pose, consumer, cx, cy, cz, ca);
        vertex(pose, consumer, dx, dy, dz, da);
        vertex(pose, consumer, cx, cy, cz, ca);
        vertex(pose, consumer, bx, by, bz, ba);
        vertex(pose, consumer, ax, ay, az, aa);
        vertex(pose, consumer, dx, dy, dz, da);
        vertex(pose, consumer, cx, cy, cz, ca);
        vertex(pose, consumer, ax, ay, az, aa);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer consumer, float x, float y, float z, int alpha) {
        consumer.addVertex(pose, x, y, z).setColor(RED, GREEN, BLUE, alpha);
    }

    private static double calculateWallProximity(double distance) {
        if (distance <= FULL_OPACITY_DISTANCE) return 1.0;
        double value = 1.0 - Mth.clamp(
                (distance - FULL_OPACITY_DISTANCE) / (WALL_PROXIMITY_DISTANCE - FULL_OPACITY_DISTANCE), 0.0, 1.0
        );
        return smoothstep(value);
    }

    private static double calculateAlongFade(double distance) {
        return smoothstep(1.0 - Mth.clamp(distance / ALONG_FADE_DISTANCE, 0.0, 1.0));
    }

    private static int calculateFinalAlpha(double wallProximity, double alongFade) {
        return (int) Mth.clamp(MAX_ALPHA * wallProximity * alongFade, 0.0, 255.0);
    }

    private static double smoothstep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private enum Axis { X, Z }

    private record WallSegment(Axis axis, double fixedCoordinate, double minY, double maxY, double start, double end) {
        private static WallSegment xWall(double x, double minY, double maxY, double startZ, double endZ) {
            return new WallSegment(Axis.X, x, minY, maxY, startZ, endZ);
        }

        private static WallSegment zWall(double z, double minY, double maxY, double startX, double endX) {
            return new WallSegment(Axis.Z, z, minY, maxY, startX, endX);
        }
    }
}
