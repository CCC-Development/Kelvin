package ccc.dev.kelvin.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/**
 * Like vanilla {@link net.minecraft.world.entity.ai.goal.MeleeAttackGoal} but does not require
 * {@link net.minecraft.world.entity.ai.navigation.PathNavigation#createPath} to succeed on activation.
 * Vanilla's path-null check prevents Kelvin from ever engaging spiders on walls, skeletons behind
 * obstacles, etc.
 */
public final class KelvinMeleeAttackGoal extends Goal {
    /** Faster than walk when chasing hostiles in protect mode, but slower than the player-follow sprint. */
    private static final double PROTECT_SPRINT_SPEED = 1.06D;

    private final KelvinEntity kelvin;
    private final double speedModifier;
    private int attackCooldown;

    public KelvinMeleeAttackGoal(KelvinEntity kelvin, double speedModifier) {
        this.kelvin = kelvin;
        this.speedModifier = speedModifier;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean viableTarget(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (target instanceof Player player) {
            if (player.getAbilities().invulnerable) {
                return false;
            }
            if (this.kelvin.isAllyPlayer(player)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean canUse() {
        return this.viableTarget(this.kelvin.getTarget());
    }

    @Override
    public boolean canContinueToUse() {
        return this.viableTarget(this.kelvin.getTarget());
    }

    @Override
    public void start() {
        this.kelvin.setAggressive(true);
    }

    @Override
    public void stop() {
        this.kelvin.setAggressive(false);
        this.kelvin.setSprinting(false);
        this.kelvin.getNavigation().stop();
        this.attackCooldown = 0;
    }

    @Override
    public void tick() {
        LivingEntity target = this.kelvin.getTarget();
        if (!this.viableTarget(target)) {
            return;
        }
        this.kelvin.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (target.level() != this.kelvin.level()) {
            return;
        }
        boolean protect = this.kelvin.isProtectOwner();
        this.kelvin.setSprinting(protect);
        double moveSpeed = protect ? PROTECT_SPRINT_SPEED : this.speedModifier;
        this.kelvin.getNavigation().moveTo(target, moveSpeed);
        this.attackCooldown = Math.max(0, this.attackCooldown - 1);
        if (this.kelvin.isWithinMeleeAttackRange(target) && this.attackCooldown <= 0) {
            this.kelvin.swing(InteractionHand.MAIN_HAND, true);
            this.kelvin.doHurtTarget(target);
            this.attackCooldown = this.adjustedTickDelay(20);
        }
    }
}
