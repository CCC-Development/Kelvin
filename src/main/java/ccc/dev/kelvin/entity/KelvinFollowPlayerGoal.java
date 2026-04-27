package ccc.dev.kelvin.entity;

import com.mrcrayfish.vehicle.entity.vehicle.GolfCartEntity;
import java.util.UUID;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;

public final class KelvinFollowPlayerGoal extends Goal {
    /** Beyond this distance (blocks²), Kelvin sprints to catch up — matches roughly “a few seconds” of player walking. */
    private static final double SPRINT_DISTANCE_SQR = 7.0 * 7.0;
    /** Stop pathing when this close so he does not jitter on the player’s feet. */
    private static final double STOP_DISTANCE_SQR = 4.0;
    private static final double WALK_SPEED = 1.2D;
    private static final double SPRINT_SPEED = 1.55D;
    /** Close enough to snap into the followed boat as second passenger. */
    private static final double BOARD_BOAT_DIST_SQR = 3.5 * 3.5;
    /** Golf cart footprint is larger — allow boarding from slightly farther away. */
    private static final double BOARD_GOLF_CART_DIST_SQR = 6.0 * 6.0;

    private final KelvinEntity kelvin;

    public KelvinFollowPlayerGoal(KelvinEntity kelvin) {
        this.kelvin = kelvin;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (kelvin.isDowned() || kelvin.getGoMineController().isActive()) {
            return false;
        }
        boolean hasFollow = kelvin.getFollowingId().isPresent();
        boolean summonerPath = kelvin.getPathSummonTargetId() != null;
        if (!hasFollow && !summonerPath) {
            return false;
        }
        // While Kelvin has a combat target, follow yields to melee attack (higher-priority goal).
        return kelvin.getTarget() == null || !kelvin.getTarget().isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public void stop() {
        this.kelvin.setSprinting(false);
        this.kelvin.setSwimming(false);
        if (!this.kelvin.isDowned() && this.kelvin.getPose() == Pose.SWIMMING && !this.kelvin.isInWater()) {
            this.kelvin.setPose(Pose.STANDING);
        }
    }

    @Override
    public void tick() {
        UUID followId = kelvin.getFollowingId().orElse(null);
        UUID summonId = kelvin.getPathSummonTargetId();
        UUID id = followId != null ? followId : summonId;
        if (id == null) {
            return;
        }
        Player player = kelvin.level().getPlayerByUUID(id);
        if (player == null
                || !player.isAlive()
                || !player.level().dimension().equals(kelvin.level().dimension())) {
            this.kelvin.setSprinting(false);
            if (followId != null) {
                kelvin.clearFollowing();
            } else {
                kelvin.clearPathSummonTarget();
            }
            return;
        }

        this.syncBoatPassenger(player);
        this.syncGolfCartPassenger(player, followId);

        if (this.kelvin.isFollowNavigationDeferredForPillar()) {
            this.kelvin.setSprinting(false);
            this.kelvin.setSwimming(false);
            this.kelvin.getNavigation().stop();
            return;
        }

        if (this.kelvin.getVehicle() instanceof Boat shared && player.getVehicle() == shared) {
            this.kelvin.setSprinting(false);
            this.kelvin.setSwimming(false);
            if (!this.kelvin.isDowned() && this.kelvin.getPose() == Pose.SWIMMING) {
                this.kelvin.setPose(Pose.STANDING);
            }
            this.kelvin.getNavigation().stop();
            return;
        }

        if (this.kelvin.getVehicle() instanceof GolfCartEntity sharedCart && player.getVehicle() == sharedCart) {
            this.kelvin.setSprinting(false);
            this.kelvin.setSwimming(false);
            if (!this.kelvin.isDowned() && this.kelvin.getPose() == Pose.SWIMMING) {
                this.kelvin.setPose(Pose.STANDING);
            }
            this.kelvin.getNavigation().stop();
            return;
        }

        double distSqr = kelvin.distanceToSqr(player);

        if (this.kelvin.isInWater() && !player.isInWater()) {
            this.kelvin.tryTeleportOutOfWaterTowardFollowed(player);
            this.kelvin.setSprinting(false);
            this.kelvin.setSwimming(false);
            if (!this.kelvin.isDowned()) {
                this.kelvin.setPose(Pose.STANDING);
            }
            if (distSqr > STOP_DISTANCE_SQR) {
                this.kelvin.getNavigation().moveTo(player, WALK_SPEED);
            } else {
                this.kelvin.getNavigation().stop();
            }
            return;
        }

        if (!this.kelvin.isDowned() && this.kelvin.getPose() == Pose.SWIMMING && !this.kelvin.isInWater()) {
            this.kelvin.setPose(Pose.STANDING);
        }
        this.kelvin.setSwimming(false);

        if (distSqr > STOP_DISTANCE_SQR) {
            boolean sprint = distSqr > SPRINT_DISTANCE_SQR;
            this.kelvin.setSprinting(sprint);
            this.kelvin.getNavigation().moveTo(player, sprint ? SPRINT_SPEED : WALK_SPEED);
        } else {
            this.kelvin.setSprinting(false);
            this.kelvin.getNavigation().stop();
        }
    }

    private void syncBoatPassenger(Player player) {
        if (this.kelvin.getVehicle() instanceof Boat kelvinBoat) {
            if (player.getVehicle() != kelvinBoat) {
                this.kelvin.stopRiding();
            }
            return;
        }
        if (player.getVehicle() instanceof Boat boat
                && boat.isAlive()
                && !boat.isRemoved()
                && boat.getPassengers().size() < 2
                && this.kelvin.distanceToSqr(boat) < BOARD_BOAT_DIST_SQR) {
            this.kelvin.startRiding(boat, true);
            this.kelvin.getNavigation().stop();
        }
    }

    /**
     * While Kelvin is in <em>follow me</em> mode (not summoner-only pathing), snap him into a free seat on the player's
     * golf cart when the player is driving.
     */
    private void syncGolfCartPassenger(Player player, UUID followId) {
        if (followId == null) {
            return;
        }
        if (this.kelvin.getVehicle() instanceof GolfCartEntity kelvinCart) {
            if (player.getVehicle() != kelvinCart || kelvinCart.getControllingPassenger() != player) {
                this.kelvin.stopRiding();
            }
            return;
        }
        if (!(player.getVehicle() instanceof GolfCartEntity cart)
                || !cart.isAlive()
                || cart.isRemoved()
                || cart.getControllingPassenger() != player) {
            return;
        }
        int seat = cart.getSeatTracker().getNextAvailableSeat();
        if (seat == -1) {
            return;
        }
        if (this.kelvin.distanceToSqr(cart) >= BOARD_GOLF_CART_DIST_SQR) {
            return;
        }
        if (this.kelvin.startRiding(cart, true)) {
            cart.getSeatTracker().setSeatIndex(seat, this.kelvin.getUUID());
            this.kelvin.getNavigation().stop();
        }
    }
}
