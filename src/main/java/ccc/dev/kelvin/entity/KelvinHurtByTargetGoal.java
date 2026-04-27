package ccc.dev.kelvin.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.player.Player;

/**
 * Vanilla {@link HurtByTargetGoal} would make Kelvin fight the patron or followed player if they
 * accidentally punch him; this goal ignores retaliation against those players.
 */
public final class KelvinHurtByTargetGoal extends HurtByTargetGoal {
    private final KelvinEntity kelvin;

    public KelvinHurtByTargetGoal(KelvinEntity kelvin) {
        super(kelvin);
        this.kelvin = kelvin;
    }

    @Override
    public boolean canUse() {
        if (this.kelvin.getPathSummonTargetId() != null || this.kelvin.isSummonerRecallGraceActive()) {
            return false;
        }
        if (!super.canUse()) {
            return false;
        }
        return !isAllyAttacker(this.kelvin.getLastHurtByMob());
    }

    @Override
    public boolean canContinueToUse() {
        if (this.kelvin.getPathSummonTargetId() != null || this.kelvin.isSummonerRecallGraceActive()) {
            return false;
        }
        if (!super.canContinueToUse()) {
            return false;
        }
        LivingEntity target = this.kelvin.getTarget();
        return !isAllyAttacker(target);
    }

    private boolean isAllyAttacker(LivingEntity entity) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        return this.kelvin.isAllyPlayer(player);
    }
}
