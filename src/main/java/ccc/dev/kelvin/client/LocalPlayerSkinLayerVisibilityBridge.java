package ccc.dev.kelvin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraftforge.fml.ModList;

/**
 * Skin Layers 3D hides vanilla outer parts, then its separate render layer uses those same visibility flags as gates.
 * In this pack's player renderer stack the flags can remain false, so the 3D meshes are skipped. This bridge runs as
 * a no-op render layer before Skin Layers' own late-added layer and forcibly reopens the player overlay gates.
 */
public final class LocalPlayerSkinLayerVisibilityBridge
        extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final boolean SKIN_LAYERS_LOADED = ModList.get().isLoaded("skinlayers3d");

    public LocalPlayerSkinLayerVisibilityBridge(
            RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            AbstractClientPlayer player,
            float limbSwing,
            float limbSwingAmount,
            float partialTick,
            float ageInTicks,
            float netHeadYaw,
            float headPitch) {
        if (!SKIN_LAYERS_LOADED) {
            return;
        }
        PlayerModel<AbstractClientPlayer> model = this.getParentModel();
        model.hat.visible = true;
        model.jacket.visible = true;
        model.leftSleeve.visible = true;
        model.rightSleeve.visible = true;
        model.leftPants.visible = true;
        model.rightPants.visible = true;
    }
}
