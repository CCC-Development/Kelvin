package ccc.dev.kelvin.client;

import ccc.dev.kelvin.entity.KelvinEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Vanilla {@link PlayerModel} only drives the main-hand attack pose for {@link net.minecraft.world.entity.player.Player}
 * in {@code setupAnim}. Kelvin also does not reliably get client {@link net.minecraft.world.entity.LivingEntity#getAttackAnim}
 * updates for mob swings, so {@link KelvinEntity#getMainhandSwingAnim} uses a synced swing countdown from
 * {@link KelvinEntity#swing}. Falls back to {@code getAttackAnim} when that is inactive.
 */
@OnlyIn(Dist.CLIENT)
public final class KelvinPlayerModel extends PlayerModel<KelvinEntity> {
    public KelvinPlayerModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(KelvinEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        if (entity.isRemoved() || !entity.isAlive()) {
            return;
        }
        float partialTick = Minecraft.getInstance().getFrameTime();
        float attackAnim = entity.getMainhandSwingAnim(partialTick);
        if (attackAnim <= 0.0F) {
            attackAnim = entity.getAttackAnim(partialTick);
        }
        if (attackAnim <= 0.0F || entity.isUsingItem()) {
            return;
        }
        HumanoidModel.ArmPose rightPose = this.rightArmPose;
        if (rightPose == HumanoidModel.ArmPose.BLOCK
                || rightPose == HumanoidModel.ArmPose.BOW_AND_ARROW
                || rightPose == HumanoidModel.ArmPose.CROSSBOW_HOLD
                || rightPose == HumanoidModel.ArmPose.CROSSBOW_CHARGE
                || rightPose == HumanoidModel.ArmPose.SPYGLASS) {
            return;
        }
        float f = Mth.sin(attackAnim * (float) Math.PI);
        float g = Mth.sin(-(1.0F - attackAnim) * (1.0F - attackAnim) * (float) Math.PI);
        this.rightArm.xRot = -Mth.HALF_PI - 0.1F - f * 1.2F + g * 0.4F;
        this.rightArm.yRot = -0.4F * f + g * 0.3F;
        this.rightArm.zRot = -0.3F * f - g * 0.2F;
        float h = Mth.sin(-(1.0F - attackAnim) * (1.0F - attackAnim) * (float) Math.PI);
        this.leftArm.yRot = 0.5F - f * 0.8F + h * -0.3F;
        this.rightSleeve.xRot = this.rightArm.xRot;
        this.rightSleeve.yRot = this.rightArm.yRot;
        this.rightSleeve.zRot = this.rightArm.zRot;
        this.leftSleeve.yRot = this.leftArm.yRot;
    }
}
