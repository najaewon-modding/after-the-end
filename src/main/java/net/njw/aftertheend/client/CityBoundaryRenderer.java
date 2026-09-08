package net.njw.aftertheend.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(
        modid = AfterTheEnd.MODID,
        value = Dist.CLIENT
)
public final class CityBoundaryRenderer {

    /*
     * 경계에서 몇 블록 이내로 접근했을 때
     * 벽 렌더링을 시작할지.
     *
     * 이 값을 테스트하면서 조절하면 된다.
     */
    private static final double ACTIVATION_DISTANCE = 128.0;

    /*
     * 벽 자체에 가까워질수록 얼마나 진해질지 결정하는 거리.
     *
     * 일반적으로 ACTIVATION_DISTANCE와 같게 두면 자연스럽다.
     */
    private static final double FULL_OPACITY_DISTANCE = 36.0;
    private static final double WALL_PROXIMITY_DISTANCE = 128.0;

    /*
     * 플레이어를 기준으로 벽을 좌우 몇 블록까지 렌더링할지.
     */
    private static final double HORIZONTAL_RADIUS = 128.0;

    /*
     * 벽을 따라 좌우로 멀어질수록 fade되는 거리.
     *
     * HORIZONTAL_RADIUS와 비슷하거나 조금 크게 두면 자연스럽다.
     */
    private static final double ALONG_FADE_DISTANCE = 128.0;

    /*
     * 플레이어 기준 벽 높이.
     */
    private static final double WALL_BELOW = 80.0;
    private static final double WALL_ABOVE = 80.0;

    /*
     * 벽을 strip으로 나누는 단위.
     *
     * vertex gradient를 사용하므로
     * 4블록 정도여도 충분히 부드럽다.
     */
    private static final double WALL_STRIP_WIDTH = 4.0;

    /*
     * 벽 색상.
     */
    private static final int RED = 80;
    private static final int GREEN = 170;
    private static final int BLUE = 255;

    /*
     * 최대 불투명도.
     *
     * 0   = 완전 투명
     * 255 = 완전 불투명
     */
    private static final int MAX_ALPHA = 140;

    private static final ContextKey<List<WallSegment>> WALLS_KEY =
            new ContextKey<>(
                    Identifier.fromNamespaceAndPath(
                            AfterTheEnd.MODID,
                            "city_boundary_walls"
                    )
            );

    private CityBoundaryRenderer() {
    }

    @SubscribeEvent
    public static void onExtractLevelRenderState(
            ExtractLevelRenderStateEvent event
    ) {
        Vec3 camera =
                event.getCamera()
                        .position();

        Identifier dimension =
                event.getLevel()
                        .dimension()
                        .identifier();

        List<WallSegment> walls =
                new ArrayList<>();

        /*
         * 서버로부터 동기화받은
         * 모든 accessible city를 검사한다.
         */
        for (
                ClientCityManager.ClientCity city :
                ClientCityManager.getAccessibleCities()
        ) {
            CityRegion region =
                    city.getRegion(
                            dimension
                    );

            /*
             * 현재 차원에 해당 도시 영역이 없으면
             * 렌더 대상이 아니다.
             */
            if (region == null) {
                continue;
            }

            collectWallsForRegion(
                    walls,
                    region,
                    camera
            );
        }

        if (!walls.isEmpty()) {
            event.getRenderState()
                    .setRenderData(
                            WALLS_KEY,
                            List.copyOf(
                                    walls
                            )
                    );
        }
    }

    private static void collectWallsForRegion(
            List<WallSegment> walls,
            CityRegion region,
            Vec3 camera
    ) {
        double cameraX =
                camera.x;

        double cameraY =
                camera.y;

        double cameraZ =
                camera.z;

        /*
         * 실제 도시 경계 면 좌표.
         */
        double minX =
                region.minBlockX();

        double maxX =
                region.maxBlockX()
                        + 1.0;

        double minZ =
                region.minBlockZ();

        double maxZ =
                region.maxBlockZ()
                        + 1.0;

        double minY =
                cameraY
                        - WALL_BELOW;

        double maxY =
                cameraY
                        + WALL_ABOVE;

        /*
         * 서쪽 벽.
         */
        if (
                Math.abs(
                        cameraX - minX
                ) <= ACTIVATION_DISTANCE
        ) {
            double startZ =
                    Mth.clamp(
                            cameraZ
                                    - HORIZONTAL_RADIUS,
                            minZ,
                            maxZ
                    );

            double endZ =
                    Mth.clamp(
                            cameraZ
                                    + HORIZONTAL_RADIUS,
                            minZ,
                            maxZ
                    );

            if (startZ < endZ) {
                walls.add(
                        WallSegment.xWall(
                                minX,
                                minY,
                                maxY,
                                startZ,
                                endZ
                        )
                );
            }
        }

        /*
         * 동쪽 벽.
         */
        if (
                Math.abs(
                        cameraX - maxX
                ) <= ACTIVATION_DISTANCE
        ) {
            double startZ =
                    Mth.clamp(
                            cameraZ
                                    - HORIZONTAL_RADIUS,
                            minZ,
                            maxZ
                    );

            double endZ =
                    Mth.clamp(
                            cameraZ
                                    + HORIZONTAL_RADIUS,
                            minZ,
                            maxZ
                    );

            if (startZ < endZ) {
                walls.add(
                        WallSegment.xWall(
                                maxX,
                                minY,
                                maxY,
                                startZ,
                                endZ
                        )
                );
            }
        }

        /*
         * 북쪽 벽.
         */
        if (
                Math.abs(
                        cameraZ - minZ
                ) <= ACTIVATION_DISTANCE
        ) {
            double startX =
                    Mth.clamp(
                            cameraX
                                    - HORIZONTAL_RADIUS,
                            minX,
                            maxX
                    );

            double endX =
                    Mth.clamp(
                            cameraX
                                    + HORIZONTAL_RADIUS,
                            minX,
                            maxX
                    );

            if (startX < endX) {
                walls.add(
                        WallSegment.zWall(
                                minZ,
                                minY,
                                maxY,
                                startX,
                                endX
                        )
                );
            }
        }

        /*
         * 남쪽 벽.
         */
        if (
                Math.abs(
                        cameraZ - maxZ
                ) <= ACTIVATION_DISTANCE
        ) {
            double startX =
                    Mth.clamp(
                            cameraX
                                    - HORIZONTAL_RADIUS,
                            minX,
                            maxX
                    );

            double endX =
                    Mth.clamp(
                            cameraX
                                    + HORIZONTAL_RADIUS,
                            minX,
                            maxX
                    );

            if (startX < endX) {
                walls.add(
                        WallSegment.zWall(
                                maxZ,
                                minY,
                                maxY,
                                startX,
                                endX
                        )
                );
            }
        }
    }

    @SubscribeEvent
    public static void onSubmitCustomGeometry(
            SubmitCustomGeometryEvent event
    ) {
        List<WallSegment> walls =
                event.getLevelRenderState().getRenderData(WALLS_KEY);

        if (walls == null || walls.isEmpty()) {
            return;
        }

        Vec3 camera =
                event.getLevelRenderState().cameraRenderState.pos;

        PoseStack poseStack = event.getPoseStack();

        poseStack.pushPose();

        /*
         * 월드 절대좌표를 카메라 기준 렌더 좌표로 변환.
         */
        poseStack.translate(
                -camera.x,
                -camera.y,
                -camera.z
        );

        for (WallSegment wall : walls) {
            event.getSubmitNodeCollector().submitCustomGeometry(
                    poseStack,
                    RenderTypes.debugQuads(),
                    (pose, consumer) ->
                            renderWall(
                                    pose,
                                    consumer,
                                    wall,
                                    camera
                            )
            );
        }

        poseStack.popPose();
    }

    private static void renderWall(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            WallSegment wall,
            Vec3 camera
    ) {
        if (wall.axis() == Axis.X) {
            renderXWall(
                    pose,
                    consumer,
                    wall,
                    camera
            );
        } else {
            renderZWall(
                    pose,
                    consumer,
                    wall,
                    camera
            );
        }
    }

    /**
     * X가 고정된 벽.
     */
    private static void renderXWall(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            WallSegment wall,
            Vec3 camera
    ) {
        float x = (float) wall.fixedCoordinate();

        float y1 = (float) wall.minY();
        float y2 = (float) wall.maxY();

        /*
         * 플레이어가 벽 자체에 얼마나 가까운지.
         *
         * 이 값은 같은 벽 전체에 공통이다.
         */
        double wallDistance =
                Math.abs(
                        camera.x - wall.fixedCoordinate()
                );

        double wallProximity =
                calculateWallProximity(
                        wallDistance
                );

        for (
                double z = wall.start();
                z < wall.end();
                z += WALL_STRIP_WIDTH
        ) {
            double nextZ = Math.min(
                    z + WALL_STRIP_WIDTH,
                    wall.end()
            );

            /*
             * strip의 양 끝이 플레이어 기준으로
             * 벽을 따라 얼마나 떨어져 있는지 계산.
             */
            double startAlongDistance =
                    Math.abs(camera.z - z);

            double endAlongDistance =
                    Math.abs(camera.z - nextZ);

            double startAlongFade =
                    calculateAlongFade(
                            startAlongDistance
                    );

            double endAlongFade =
                    calculateAlongFade(
                            endAlongDistance
                    );

            int startAlpha =
                    calculateFinalAlpha(
                            wallProximity,
                            startAlongFade
                    );

            int endAlpha =
                    calculateFinalAlpha(
                            wallProximity,
                            endAlongFade
                    );

            if (startAlpha <= 0 && endAlpha <= 0) {
                continue;
            }

            float z1 = (float) z;
            float z2 = (float) nextZ;

            /*
             * z1 쪽 vertex.
             */
            consumer.addVertex(
                            pose,
                            x,
                            y1,
                            z1
                    )
                    .setColor(
                            RED,
                            GREEN,
                            BLUE,
                            startAlpha
                    );

            consumer.addVertex(
                            pose,
                            x,
                            y2,
                            z1
                    )
                    .setColor(
                            RED,
                            GREEN,
                            BLUE,
                            startAlpha
                    );

            /*
             * z2 쪽 vertex.
             */
            consumer.addVertex(
                            pose,
                            x,
                            y2,
                            z2
                    )
                    .setColor(
                            RED,
                            GREEN,
                            BLUE,
                            endAlpha
                    );

            consumer.addVertex(
                            pose,
                            x,
                            y1,
                            z2
                    )
                    .setColor(
                            RED,
                            GREEN,
                            BLUE,
                            endAlpha
                    );
        }
    }

    /**
     * Z가 고정된 벽.
     */
    private static void renderZWall(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            WallSegment wall,
            Vec3 camera
    ) {
        float z = (float) wall.fixedCoordinate();

        float y1 = (float) wall.minY();
        float y2 = (float) wall.maxY();

        /*
         * 플레이어가 벽 자체에 얼마나 가까운지.
         */
        double wallDistance =
                Math.abs(
                        camera.z - wall.fixedCoordinate()
                );

        double wallProximity =
                calculateWallProximity(
                        wallDistance
                );

        for (
                double x = wall.start();
                x < wall.end();
                x += WALL_STRIP_WIDTH
        ) {
            double nextX = Math.min(
                    x + WALL_STRIP_WIDTH,
                    wall.end()
            );

            double startAlongDistance =
                    Math.abs(camera.x - x);

            double endAlongDistance =
                    Math.abs(camera.x - nextX);

            double startAlongFade =
                    calculateAlongFade(
                            startAlongDistance
                    );

            double endAlongFade =
                    calculateAlongFade(
                            endAlongDistance
                    );

            int startAlpha =
                    calculateFinalAlpha(
                            wallProximity,
                            startAlongFade
                    );

            int endAlpha =
                    calculateFinalAlpha(
                            wallProximity,
                            endAlongFade
                    );

            if (startAlpha <= 0 && endAlpha <= 0) {
                continue;
            }

            float x1 = (float) x;
            float x2 = (float) nextX;

            consumer.addVertex(
                            pose,
                            x1,
                            y1,
                            z
                    )
                    .setColor(
                            RED,
                            GREEN,
                            BLUE,
                            startAlpha
                    );

            consumer.addVertex(
                            pose,
                            x1,
                            y2,
                            z
                    )
                    .setColor(
                            RED,
                            GREEN,
                            BLUE,
                            startAlpha
                    );

            consumer.addVertex(
                            pose,
                            x2,
                            y2,
                            z
                    )
                    .setColor(
                            RED,
                            GREEN,
                            BLUE,
                            endAlpha
                    );

            consumer.addVertex(
                            pose,
                            x2,
                            y1,
                            z
                    )
                    .setColor(
                            RED,
                            GREEN,
                            BLUE,
                            endAlpha
                    );
        }
    }

    /**
     * 플레이어가 경계 자체에 얼마나 가까운지 계산.
     *
     * 1.0 = 경계 바로 앞
     * 0.0 = 충분히 멀리 떨어짐
     */
    private static double calculateWallProximity(
            double distance
    ) {
        // 8블록 이내에서는 최대 진하기 유지
        if (distance <= FULL_OPACITY_DISTANCE) {
            return 1.0;
        }

        // 8 ~ 32블록 사이에서 1 → 0으로 감소
        double value =
                1.0 - Mth.clamp(
                        (distance - FULL_OPACITY_DISTANCE)
                                / (WALL_PROXIMITY_DISTANCE - FULL_OPACITY_DISTANCE),
                        0.0,
                        1.0
                );

        return smoothstep(value);
    }

    /**
     * 같은 벽 안에서 플레이어 기준 좌우로
     * 얼마나 떨어져 있는지에 따른 fade.
     *
     * 1.0 = 플레이어 앞
     * 0.0 = 벽을 따라 충분히 멀리 떨어짐
     */
    private static double calculateAlongFade(
            double distance
    ) {
        double value =
                1.0 - Mth.clamp(
                        distance / ALONG_FADE_DISTANCE,
                        0.0,
                        1.0
                );

        return smoothstep(value);
    }

    /**
     * 두 fade 값을 합쳐 실제 alpha 생성.
     */
    private static int calculateFinalAlpha(
            double wallProximity,
            double alongFade
    ) {
        double opacity =
                wallProximity * alongFade;

        return (int) Mth.clamp(
                MAX_ALPHA * opacity,
                0.0,
                255.0
        );
    }

    /**
     * 0~1 값을 양 끝에서 부드럽게 이어주는 함수.
     */
    private static double smoothstep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private enum Axis {
        X,
        Z
    }

    private record WallSegment(
            Axis axis,
            double fixedCoordinate,
            double minY,
            double maxY,
            double start,
            double end
    ) {

        private static WallSegment xWall(
                double x,
                double minY,
                double maxY,
                double startZ,
                double endZ
        ) {
            return new WallSegment(
                    Axis.X,
                    x,
                    minY,
                    maxY,
                    startZ,
                    endZ
            );
        }

        private static WallSegment zWall(
                double z,
                double minY,
                double maxY,
                double startX,
                double endX
        ) {
            return new WallSegment(
                    Axis.Z,
                    z,
                    minY,
                    maxY,
                    startX,
                    endX
            );
        }
    }
}