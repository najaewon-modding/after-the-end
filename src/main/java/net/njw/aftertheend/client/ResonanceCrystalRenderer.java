package net.njw.aftertheend.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.object.crystal.EndCrystalModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.block.entity.ResonanceCrystalBlockEntity;
import net.njw.aftertheend.registry.ModContent;

@EventBusSubscriber(modid = AfterTheEnd.MODID, value = Dist.CLIENT)
public final class ResonanceCrystalRenderer implements BlockEntityRenderer<ResonanceCrystalBlockEntity, ResonanceCrystalRenderState> {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/entity/end_crystal/end_crystal.png");
    private static final int OUTER_PURPLE = 0xFF9B7BB5;
    private static final int INNER_LILAC = 0xFFC8B7D4;
    private static final int CORE_WHITE = 0xFFE2DEE5;
    private static final int BASE_PURPLE = 0xFF725780;
    private static final int FULL_BRIGHT = 0xF000F0;

    private final EndCrystalModel baseModel;
    private final EndCrystalModel outerModel;
    private final EndCrystalModel innerModel;
    private final EndCrystalModel coreModel;

    public ResonanceCrystalRenderer(BlockEntityRendererProvider.Context context) {
        baseModel = new EndCrystalModel(context.bakeLayer(ModelLayers.END_CRYSTAL));
        outerModel = new EndCrystalModel(context.bakeLayer(ModelLayers.END_CRYSTAL));
        innerModel = new EndCrystalModel(context.bakeLayer(ModelLayers.END_CRYSTAL));
        coreModel = new EndCrystalModel(context.bakeLayer(ModelLayers.END_CRYSTAL));

        baseModel.outerGlass.visible = false;

        outerModel.base.visible = false;
        outerModel.innerGlass.visible = false;

        innerModel.base.visible = false;
        innerModel.outerGlass.skipDraw = true;
        innerModel.cube.visible = false;

        coreModel.base.visible = false;
        coreModel.outerGlass.skipDraw = true;
        coreModel.innerGlass.skipDraw = true;
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModContent.RESONANCE_CRYSTAL_BLOCK_ENTITY.get(), ResonanceCrystalRenderer::new);
    }

    @Override
    public ResonanceCrystalRenderState createRenderState() {
        return new ResonanceCrystalRenderState();
    }

    @Override
    public void extractRenderState(ResonanceCrystalBlockEntity blockEntity, ResonanceCrystalRenderState state, float partialTicks,
                                   Vec3 cameraPosition, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        float gameTime = blockEntity.getLevel() == null ? partialTicks : blockEntity.getLevel().getGameTime() + partialTicks;
        state.ageInTicks = gameTime;
        state.horizontalScale = ResonanceCrystalBlockEntity.horizontalScale(gameTime);
        state.verticalScale = ResonanceCrystalBlockEntity.verticalScale(gameTime);
    }

    @Override
    public void submit(ResonanceCrystalRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        EndCrystalRenderState crystalState = new EndCrystalRenderState();
        crystalState.ageInTicks = state.ageInTicks;
        crystalState.showsBottom = true;

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.scale(state.horizontalScale, state.verticalScale, state.horizontalScale);

        submitLayer(baseModel, crystalState, poseStack, collector, state.lightCoords, BASE_PURPLE, state);
        submitLayer(outerModel, crystalState, poseStack, collector, state.lightCoords, OUTER_PURPLE, state);
        submitLayer(innerModel, crystalState, poseStack, collector, FULL_BRIGHT, INNER_LILAC, state);
        submitLayer(coreModel, crystalState, poseStack, collector, FULL_BRIGHT, CORE_WHITE, state);

        poseStack.popPose();
    }

    private static void submitLayer(EndCrystalModel model, EndCrystalRenderState crystalState, PoseStack poseStack,
                                    SubmitNodeCollector collector, int light, int tint, ResonanceCrystalRenderState state) {
        collector.submitModel(
                model,
                crystalState,
                poseStack,
                model.renderType(TEXTURE),
                light,
                OverlayTexture.NO_OVERLAY,
                tint,
                (TextureAtlasSprite) null,
                0,
                state.breakProgress
        );
    }
}
