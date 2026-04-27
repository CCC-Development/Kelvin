package ccc.dev.kelvin.entity;

import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.context.BlockPlaceContext;

/**
 * When Kelvin is following (or protecting a patron) but pathfinding cannot reach them, mines blocking blocks
 * (using the best tool from his inventory or fist), pillars upward with solid blocks, and can bridge gaps
 * toward the player horizontally.
 */
public final class KelvinStuckEscapeController {
    private static final int INVENTORY_SLOTS = 41;
    /** Default gate — must be roughly this far before we mine random obstructions. */
    private static final double MIN_DIST_SQR = 8.0 * 8.0;
    /** How long navigation can stay idle while the player is still far before we intervene. */
    private static final int PATHLESS_STUCK_THRESHOLD = 22;
    private static final int PATHLESS_STUCK_THRESHOLD_VERTICAL = 10;
    /** Wedged against blocks while far from the player (path may still “progress” in a tight loop). */
    private static final int COLLISION_STUCK_THRESHOLD = 20;
    private static final int COLLISION_STUCK_THRESHOLD_VERTICAL = 12;
    private static final int PILLAR_COOLDOWN_TICKS = 10;
    /** Minimum horizontal gap² before we try to extend a floor toward the player. */
    private static final double BRIDGE_MIN_HORIZ_SQR = 2.5 * 2.5;
    /** If the player is farther above than this, defer horizontal bridge to vertical pillar logic. */
    private static final double BRIDGE_MAX_PLAYER_DY = 4.0D;

    private final KelvinEntity kelvin;

    private int pathlessTicks;
    private int wedgedCollisionTicks;
    private int pillarCooldown;
    /** Throttles mining-tool chat hints to the followed player (ticks remaining). */
    private int miningToolHintCooldown;
    /** When bridging fails in fluid, counts stuck ticks before a dry-land snap fallback. */
    private int waterStuckAssistTicks;
    @Nullable
    private BlockPos breakTarget;
    private float breakProgress;

    KelvinStuckEscapeController(KelvinEntity kelvin) {
        this.kelvin = kelvin;
    }

    void reset() {
        this.pathlessTicks = 0;
        this.wedgedCollisionTicks = 0;
        this.pillarCooldown = 0;
        this.breakTarget = null;
        this.breakProgress = 0.0F;
        this.miningToolHintCooldown = 0;
        this.waterStuckAssistTicks = 0;
    }

    @Nullable
    private Player resolveAnchorPlayer(Level level) {
        UUID summon = this.kelvin.getPathSummonTargetId();
        if (summon != null) {
            Player summonedToward = level.getPlayerByUUID(summon);
            if (summonedToward != null && summonedToward.isAlive() && summonedToward.level() == level) {
                return summonedToward;
            }
        }
        Optional<UUID> follow = this.kelvin.getFollowingId();
        if (follow.isPresent()) {
            return level.getPlayerByUUID(follow.get());
        }
        if (this.kelvin.isProtectOwner()) {
            return this.kelvin.getProtectPatronId().map(level::getPlayerByUUID).orElse(null);
        }
        return null;
    }

    void tickServer() {
        Level level = this.kelvin.level();
        if (level.isClientSide || this.kelvin.isDowned()) {
            return;
        }
        if (this.kelvin.isCloseCombatTargetBlockingRecovery()) {
            this.reset();
            return;
        }
        Player player = this.resolveAnchorPlayer(level);
        if (player == null || !player.isAlive() || player.level() != level) {
            this.reset();
            return;
        }
        if (this.kelvin.isUsingItem()) {
            return;
        }
        if (this.kelvin.getVehicle() != null) {
            return;
        }
        if (this.pillarCooldown > 0) {
            this.pillarCooldown--;
        }
        if (this.miningToolHintCooldown > 0) {
            this.miningToolHintCooldown--;
        }

        if (this.breakTarget != null) {
            this.tickMining(level, player);
            return;
        }

        double distSqr = this.kelvin.distanceToSqr(player);
        /*
         * Do not disable escape when the anchor is even slightly above us: 3D dist² can be tiny while a ceiling
         * still blocks (summoner recall / follow from a cave below). Only skip when we are truly close on foot.
         */
        double dyToPlayer = player.getY() - this.kelvin.getY();
        boolean playerAbove = dyToPlayer >= 1.0D;
        if (distSqr < MIN_DIST_SQR && !playerAbove) {
            this.pathlessTicks = 0;
            this.wedgedCollisionTicks = 0;
            this.waterStuckAssistTicks = 0;
            return;
        }

        boolean pathing = this.kelvin.getNavigation().isInProgress();
        if (!pathing) {
            this.pathlessTicks++;
        } else {
            this.pathlessTicks = 0;
        }

        if (this.kelvin.horizontalCollision) {
            this.wedgedCollisionTicks++;
        } else {
            this.wedgedCollisionTicks = 0;
        }

        boolean summonRecall = this.kelvin.getPathSummonTargetId() != null;
        int pillarCdAfter = summonRecall ? 5 : PILLAR_COOLDOWN_TICKS;
        int pathNeed = this.wantsVerticalAssist(player) ? PATHLESS_STUCK_THRESHOLD_VERTICAL : PATHLESS_STUCK_THRESHOLD;
        int collNeed = this.wantsVerticalAssist(player) ? COLLISION_STUCK_THRESHOLD_VERTICAL : COLLISION_STUCK_THRESHOLD;
        if (summonRecall) {
            pathNeed = Math.max(4, pathNeed / 2);
            collNeed = Math.max(4, collNeed / 2);
        }
        boolean stuck = this.pathlessTicks >= pathNeed || this.wedgedCollisionTicks >= collNeed;
        if (!stuck) {
            return;
        }

        double horizSqr = this.horizontalDistSqr(player);
        double dy = player.getY() - this.kelvin.getY();

        if (this.pillarCooldown == 0 && this.tryBridgeGapTowardPlayer(level, player, horizSqr, dy)) {
            this.pathlessTicks = 0;
            this.wedgedCollisionTicks = 0;
            this.waterStuckAssistTicks = 0;
            this.pillarCooldown = pillarCdAfter;
            return;
        }

        if (this.pillarCooldown == 0 && this.tryPillar(level, player, horizSqr, dy)) {
            this.pathlessTicks = 0;
            this.wedgedCollisionTicks = 0;
            this.waterStuckAssistTicks = 0;
            this.pillarCooldown = pillarCdAfter;
            return;
        }

        BlockPos mine = this.findBlockingBlock(level, player);
        if (mine == null) {
            mine = this.findHeadOrCeilingBlock(level, player);
        }
        if (mine != null) {
            this.breakTarget = mine.immutable();
            this.breakProgress = 0.0F;
            this.pathlessTicks = 0;
            this.wedgedCollisionTicks = 0;
            this.waterStuckAssistTicks = 0;
        } else if (this.kelvin.isInWater() && !player.isInWater()) {
            this.waterStuckAssistTicks++;
            if (this.waterStuckAssistTicks >= 42) {
                this.kelvin.tryForcedWaterEscapeToward(player);
                this.waterStuckAssistTicks = 0;
                this.pathlessTicks = 0;
                this.wedgedCollisionTicks = 0;
            }
        } else {
            this.waterStuckAssistTicks = 0;
        }
    }

    /**
     * Player is mostly above Kelvin and still a sensible tower target (tight XZ, or a wide cone when they are on
     * a cliff lip / straight climb while Kelvin is below in a ditch).
     */
    private boolean wantsVerticalAssist(Player player) {
        double dy = player.getY() - this.kelvin.getY();
        return this.playerAlignedForVerticalTower(dy, this.horizontalDistSqr(player));
    }

    /**
     * {@code true} when the player is high enough and not so far sideways that stacking blocks toward them is
     * pointless (matches pillaring eligibility).
     */
    private boolean playerAlignedForVerticalTower(double dy, double horizSqr) {
        if (dy < 1.1D) {
            return false;
        }
        if (horizSqr < 8.5 * 8.5) {
            return true;
        }
        if (dy < 2.0D) {
            return false;
        }
        double horiz = Math.sqrt(horizSqr);
        return horiz <= dy * 2.85D + 8.0D;
    }

    private double horizontalDistSqr(Player player) {
        double dx = player.getX() - this.kelvin.getX();
        double dz = player.getZ() - this.kelvin.getZ();
        return dx * dx + dz * dz;
    }

    private void tickMining(Level level, Player player) {
        if (this.breakTarget == null) {
            return;
        }
        BlockPos pos = this.breakTarget;
        if (!level.isLoaded(pos) || this.kelvin.blockPosition().distSqr(pos) > 6.5 * 6.5) {
            this.breakTarget = null;
            this.breakProgress = 0.0F;
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            this.breakTarget = null;
            this.breakProgress = 0.0F;
            return;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            this.breakTarget = null;
            this.breakProgress = 0.0F;
            return;
        }

        ItemStack tool = this.bestDiggingTool(state);
        float digSpeed = Math.max(1.0F, tool.getDestroySpeed(state));
        if (this.miningToolHintCooldown == 0
                && (this.kelvin.getFollowingId().isPresent() || this.kelvin.getPathSummonTargetId() != null)) {
            String hint = this.miningUtensilNeeded(state, tool, digSpeed);
            if (hint != null) {
                this.kelvin.sendMiningToolHintToFollowed(hint);
                this.miningToolHintCooldown = 900;
            }
        }
        this.breakProgress += digSpeed / (hardness * 30.0F);
        SoundType st = state.getSoundType();
        if (this.kelvin.tickCount % 4 == 0) {
            level.playSound(null, pos, st.getHitSound(), SoundSource.BLOCKS, (st.getVolume() + 1.0F) / 8.0F, st.getPitch() * 0.5F);
        }

        if (this.breakProgress < 1.0F) {
            return;
        }

        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            this.breakTarget = null;
            this.breakProgress = 0.0F;
            return;
        }

        BlockEntity be = level.getBlockEntity(pos);
        Block.dropResources(state, level, pos, be, this.kelvin, tool);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
        level.levelEvent(2001, pos, Block.getId(state));
        this.kelvin.playSound(st.getBreakSound(), 1.0F, 0.9F + level.random.nextFloat() * 0.2F);
        this.breakTarget = null;
        this.breakProgress = 0.0F;
        this.kelvin.getNavigation().stop();
        this.kelvin.getNavigation().moveTo(player, 1.25D);
        this.wedgedCollisionTicks = 0;
    }

    private ItemStack bestDiggingTool(BlockState state) {
        ItemStack best = ItemStack.EMPTY;
        float bestSpeed = 1.0F;
        for (int i = 0; i < INVENTORY_SLOTS; i++) {
            ItemStack stack = this.kelvin.getKelvinInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float spd = stack.getDestroySpeed(state);
            if (spd > bestSpeed) {
                bestSpeed = spd;
                best = stack;
            }
        }
        return best;
    }

    /**
     * If Kelvin is mining with a poor tool for this block, return the noun for a chat hint (pickaxe, shovel, axe,
     * hoe). Otherwise {@code null}.
     */
    @Nullable
    private String miningUtensilNeeded(BlockState state, ItemStack tool, float digSpeed) {
        if (tool.isCorrectToolForDrops(state)) {
            return null;
        }
        if (digSpeed > 3.5F) {
            return null;
        }
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return "pickaxe";
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return "shovel";
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return "axe";
        }
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            return "hoe";
        }
        return null;
    }

    @Nullable
    private BlockPos findBlockingBlock(Level level, Player player) {
        Vec3 eye = this.kelvin.getEyePosition();
        Vec3 toward = player.getEyePosition().subtract(eye);
        if (toward.lengthSqr() < 1.0E-4D) {
            return null;
        }
        Vec3 dir = toward.normalize();
        Vec3 end = eye.add(dir.scale(4.75D));
        BlockHitResult hit =
                level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this.kelvin));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        if (pos.distSqr(this.kelvin.blockPosition()) > 5.5 * 5.5) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F) {
            return null;
        }
        return pos;
    }

    /**
     * Low ceiling / blocks above feet that block jumping toward a player above (ray toward eyes can miss
     * when the obstacle is straight overhead).
     */
    @Nullable
    private BlockPos findHeadOrCeilingBlock(Level level, Player player) {
        if (player.getY() <= this.kelvin.getY() + 0.35D) {
            return null;
        }
        BlockPos feet = this.kelvin.blockPosition();
        for (int dy = 1; dy <= 12; dy++) {
            BlockPos p = feet.above(dy);
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.getDestroySpeed(level, p) < 0.0F) {
                continue;
            }
            return p.immutable();
        }
        return null;
    }

    private boolean tryPillar(Level level, Player player, double horizSqr, double dy) {
        if (!this.playerAlignedForVerticalTower(dy, horizSqr)) {
            return false;
        }
        if (!this.hasSupportUnderFeetForScaffold(level)) {
            return false;
        }
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return false;
        }

        BlockPos feet = this.kelvin.blockPosition();
        BlockState feetState = level.getBlockState(feet);
        BlockPos belowFeet = feet.below();
        BlockState belowState = level.getBlockState(belowFeet);

        if (!belowState.isAir() && !belowState.canBeReplaced()) {
            if (this.trySideStepTowardAnchor(level, player, feet)) {
                return true;
            }
            return this.tryStackStandingOnSolid(level, player, feet, belowFeet, feetState);
        }
        return this.tryStackUnderFeetAir(level, player, belowFeet);
    }

    /**
     * True when there is something load-bearing under Kelvin's feet column (ground, or solid through shallow
     * fluid), so scaffolding is not placed blindly in mid-air.
     */
    private boolean hasSupportUnderFeetForScaffold(Level level) {
        BlockPos p = this.kelvin.blockPosition();
        for (int i = 0; i < 12; i++) {
            BlockState s = level.getBlockState(p);
            if (s.isFaceSturdy(level, p, Direction.UP)) {
                return true;
            }
            if (s.isAir()) {
                return false;
            }
            if (!s.getFluidState().isEmpty()) {
                p = p.below();
                continue;
            }
            if (!s.canBeReplaced()) {
                return true;
            }
            p = p.below();
        }
        return false;
    }

    /**
     * When Kelvin's feet cell is air but he stands on solid below, prefer a horizontal step toward the anchor
     * instead of filling the block he occupies (avoids suffocating / blocks inside the mob).
     */
    private boolean trySideStepTowardAnchor(Level level, Player player, BlockPos feet) {
        double dx = player.getX() - this.kelvin.getX();
        double dz = player.getZ() - this.kelvin.getZ();
        Direction primary =
                Math.abs(dx) >= Math.abs(dz)
                        ? (dx > 0.0D ? Direction.EAST : Direction.WEST)
                        : (dz > 0.0D ? Direction.SOUTH : Direction.NORTH);
        Direction[] tryOrder = {
            primary,
            primary.getClockWise(Direction.Axis.Y),
            primary.getCounterClockWise(Direction.Axis.Y),
            primary.getOpposite()
        };
        for (Direction d : tryOrder) {
            BlockPos at = feet.relative(d);
            if (!level.getBlockState(at).canBeReplaced()) {
                continue;
            }
            BlockPos under = at.below();
            if (!level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) {
                continue;
            }
            if (this.placementWouldCollideWithKelvin(at)) {
                continue;
            }
            int slot = this.findSolidBuildingBlockSlot(under);
            if (slot < 0) {
                continue;
            }
            BlockHitResult hit =
                    new BlockHitResult(Vec3.atCenterOf(under).relative(Direction.UP, 0.35D), Direction.UP, under, false);
            return this.placeSolidBlockFromSlot(level, player, slot, at, hit, true);
        }
        return false;
    }

    /**
     * Standing on solid ground with air in the feet column: place a block in that column (speed-tower style) to
     * rise toward the player.
     */
    private boolean tryStackStandingOnSolid(
            Level level, Player player, BlockPos feet, BlockPos belowFeet, BlockState feetState) {
        if (!feetState.isAir() && !feetState.canBeReplaced()) {
            return false;
        }
        int slot = this.findSolidBuildingBlockSlot(feet);
        if (slot < 0) {
            return false;
        }
        BlockHitResult hit =
                new BlockHitResult(Vec3.atCenterOf(belowFeet).relative(Direction.UP, 0.35D), Direction.UP, belowFeet, false);
        return this.placeSolidBlockFromSlot(level, player, slot, feet, hit, false);
    }

    /** Feet over air/replaceable space: place the block under the feet block (classic downward pillar). */
    private boolean tryStackUnderFeetAir(Level level, Player player, BlockPos belowFeet) {
        BlockState belowState = level.getBlockState(belowFeet);
        if (!belowState.isAir() && !belowState.canBeReplaced()) {
            return false;
        }
        int slot = this.findSolidBuildingBlockSlot(belowFeet);
        if (slot < 0) {
            return false;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(belowFeet), Direction.UP, belowFeet, false);
        return this.placeSolidBlockFromSlot(level, player, slot, belowFeet, hit, false);
    }

    private boolean tryBridgeGapTowardPlayer(Level level, Player player, double horizSqr, double dy) {
        if (horizSqr < BRIDGE_MIN_HORIZ_SQR) {
            return false;
        }
        if (dy > BRIDGE_MAX_PLAYER_DY || dy < -6.0D) {
            return false;
        }
        if (!this.hasSupportUnderFeetForScaffold(level)) {
            return false;
        }
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return false;
        }
        if (this.wantsVerticalAssist(player) && dy > 1.5D) {
            return false;
        }

        BlockPos feet = this.kelvin.blockPosition();
        double dx = player.getX() - this.kelvin.getX();
        double dz = player.getZ() - this.kelvin.getZ();
        Direction stepDir =
                Math.abs(dx) >= Math.abs(dz)
                        ? (dx > 0.0D ? Direction.EAST : Direction.WEST)
                        : (dz > 0.0D ? Direction.SOUTH : Direction.NORTH);
        BlockPos step = feet.relative(stepDir);
        BlockPos walkOn = step.below();
        BlockState walkSt = level.getBlockState(walkOn);
        if (this.isStandableBridgeSupport(level, walkOn, walkSt)) {
            return false;
        }

        // Shallow water / shoreline: extend one block forward by replacing the fluid under the step, not the
        // entire column down to the seabed (that produced deep underwater placements that never read as a bridge).
        if (!walkSt.getFluidState().isEmpty() && walkSt.getFluidState().is(FluidTags.WATER)) {
            BlockPos placePos = walkOn;
            if (!this.canScaffoldReplace(level, placePos)) {
                return false;
            }
            if (this.placementWouldCollideWithKelvin(placePos)) {
                return false;
            }
            if (placePos.distSqr(feet) > 10.0 * 10.0) {
                return false;
            }
            int slot = this.findSolidBuildingBlockSlot(placePos);
            if (slot < 0) {
                return false;
            }
            BlockPos click = walkOn.below();
            BlockHitResult hit =
                    new BlockHitResult(Vec3.atCenterOf(click).relative(Direction.UP, 0.35D), Direction.UP, click, false);
            return this.placeSolidBlockFromSlot(level, player, slot, placePos, hit, true);
        }

        BlockPos scan = walkOn;
        int fall = 0;
        while (fall < 22 && !this.isStandableBridgeSupport(level, scan, level.getBlockState(scan))) {
            scan = scan.below();
            fall++;
        }
        BlockState anchorSt = level.getBlockState(scan);
        if (!this.isStandableBridgeSupport(level, scan, anchorSt)) {
            return false;
        }
        BlockPos placePos = scan.above();
        if (!this.canScaffoldReplace(level, placePos)) {
            return false;
        }
        if (this.placementWouldCollideWithKelvin(placePos)) {
            return false;
        }
        if (placePos.distSqr(feet) > 10.0 * 10.0) {
            return false;
        }
        int slot = this.findSolidBuildingBlockSlot(scan);
        if (slot < 0) {
            return false;
        }
        BlockHitResult hit =
                new BlockHitResult(Vec3.atCenterOf(scan).relative(Direction.UP, 0.35D), Direction.UP, scan, false);
        return this.placeSolidBlockFromSlot(level, player, slot, placePos, hit, true);
    }

    private boolean isStandableBridgeSupport(Level level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.canBeReplaced()) {
            return false;
        }
        return state.isFaceSturdy(level, pos, Direction.UP);
    }

    /** Air, replaceable blocks, or water that we can overwrite with a solid bridge slab. */
    private boolean canScaffoldReplace(Level level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.isAir() || st.canBeReplaced()) {
            return true;
        }
        return st.getFluidState().is(FluidTags.WATER);
    }

    private boolean placementWouldCollideWithKelvin(BlockPos placePos) {
        AABB blockBox = new AABB(placePos);
        return blockBox.inflate(0.002D).intersects(this.kelvin.getBoundingBox().deflate(0.04D, 0.08D, 0.04D));
    }

    /**
     * @param resumePathTowardPlayer when {@code false} (vertical pillar), follow pathing is deferred and Kelvin
     *     does not jump or walk toward the player — avoids launching off a thin tower or taking fall damage.
     */
    private boolean placeSolidBlockFromSlot(
            Level level,
            Player player,
            int slot,
            BlockPos placePos,
            BlockHitResult hitForPlacement,
            boolean resumePathTowardPlayer) {
        ItemStack stack = this.kelvin.getKelvinInventory().getItem(slot);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        Block block = blockItem.getBlock();
        BlockPlaceContext ctx = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, hitForPlacement);
        BlockState placeState = block.getStateForPlacement(ctx);
        if (placeState == null || !placeState.canSurvive(level, placePos)) {
            return false;
        }
        if (resumePathTowardPlayer && this.placementWouldCollideWithKelvin(placePos)) {
            return false;
        }
        if (!level.setBlock(placePos, placeState, Block.UPDATE_ALL_IMMEDIATE)) {
            return false;
        }

        stack.shrink(1);
        if (stack.isEmpty()) {
            this.kelvin.getKelvinInventory().setItem(slot, ItemStack.EMPTY);
        } else {
            this.kelvin.getKelvinInventory().setItem(slot, stack);
        }

        SoundType sound = placeState.getSoundType();
        this.kelvin.playSound(sound.getPlaceSound(), (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        this.kelvin.getNavigation().stop();
        if (!resumePathTowardPlayer && placePos.getY() == this.kelvin.blockPosition().getY()) {
            this.kelvin.setPos(
                    placePos.getX() + 0.5D,
                    placePos.getY() + 1.0D + 1.0E-3D,
                    placePos.getZ() + 0.5D);
            this.kelvin.setDeltaMovement(0.0D, 0.0D, 0.0D);
        }
        if (resumePathTowardPlayer) {
            this.kelvin.getJumpControl().jump();
            this.kelvin.getNavigation().moveTo(player, 1.2D);
        } else {
            this.kelvin.deferFollowNavigationForPillarAscent();
            this.kelvin.setDeltaMovement(0.0D, 0.0D, 0.0D);
        }
        return true;
    }

    /** First hotbar + storage slot with a simple solid block item (skips armour, offhand index 40 included). */
    private int findSolidBuildingBlockSlot(BlockPos refBelow) {
        Level level = this.kelvin.level();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = this.kelvin.getKelvinInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            Block block = blockItem.getBlock();
            if (block == Blocks.TNT || block == Blocks.SAND || block == Blocks.GRAVEL) {
                continue;
            }
            BlockState def = block.defaultBlockState();
            if (!def.isCollisionShapeFullBlock(level, refBelow)) {
                continue;
            }
            return i;
        }
        return -1;
    }
}
