package ccc.dev.kelvin.client;

import ccc.dev.kelvin.CccKelvinMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * MCglTF binds textures outside Blaze3D; {@link #afterEntities} reconciles the sampler table after the batch. Re-bind
 * player skin/lightmap samplers and restore outer-layer gates before player body/arm passes.
 */
@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class KelvinRenderPipelineForgeClientEvents {
    private KelvinRenderPipelineForgeClientEvents() {}

    private static final ResourceLocation LIGHTMAP_LOCATION =
            ResourceLocation.withDefaultNamespace("dynamic/light_map_1");

    @SubscribeEvent
    public static void afterEntities(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        KelvinMcGltfPipelineSync.syncShaderTextureIdsFromGl(0, 15);
    }

    /**
     * Runs after other Pre listeners. Restore player outer-part visibility after animation/skin-layer hooks and fix
     * sampler state before vanilla or Skin Layers 3D asks for the skin/lightmap samplers.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void beforePlayerBody(RenderPlayerEvent.Pre event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        applyPlayerSkinSamplers(player);
        var model = event.getRenderer().getModel();
        model.hat.visible = true;
        model.jacket.visible = true;
        model.leftSleeve.visible = true;
        model.rightSleeve.visible = true;
        model.leftPants.visible = true;
        model.rightPants.visible = true;
    }

    /** First-person arm + sleeve overlay uses the same samplers; vanilla runs after all Pre handlers. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void beforeLocalPlayerArm(RenderArmEvent event) {
        if (HeliIntroCutsceneClient.isActive()) {
            return;
        }
        AbstractClientPlayer player = event.getPlayer();
        if (!player.isLocalPlayer()) {
            return;
        }
        applyPlayerSkinSamplers(player);
    }

    private static void applyPlayerSkinSamplers(AbstractClientPlayer player) {
        RenderSystem.setShaderTexture(0, player.getSkinTextureLocation());
        RenderSystem.setShaderTexture(2, LIGHTMAP_LOCATION);
    }
}
