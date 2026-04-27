package ccc.dev.kelvin.client;

import ccc.dev.kelvin.CccKelvinMod;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class KelvinGoMineWorldRenderEvents {
    private KelvinGoMineWorldRenderEvents() {}

    @SubscribeEvent
    public static void onRenderLevelAfterTranslucent(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        BlockPos pos = KelvinGoMineHud.getHighlightChest();
        if (pos == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.hasChunkAt(pos)) {
            return;
        }
        var poseStack = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        var buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        AABB box = new AABB(pos).inflate(0.02D);
        LevelRenderer.renderLineBox(poseStack, lines, box, 1.0F, 1.0F, 0.15F, 1.0F);
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
