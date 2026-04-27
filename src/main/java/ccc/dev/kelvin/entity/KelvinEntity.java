package ccc.dev.kelvin.entity;

import ccc.dev.kelvin.KelvinSummonerWorldData;
import ccc.dev.kelvin.network.ModNetwork;
import ccc.dev.kelvin.advancement.KelvinRevivalRewards;
import ccc.dev.kelvin.ModMenus;
import ccc.dev.kelvin.world.inventory.KelvinInventoryMenu;
import com.google.common.collect.Multimap;
import com.mrcrayfish.vehicle.entity.vehicle.GolfCartEntity;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraftforge.network.NetworkHooks;

public final class KelvinEntity extends PathfinderMob {

    /** Squared distance for opening Kelvin's UI, menu validity, and follow/protect action packets (server-checked). */
    public static final double PLAYER_INTERACTION_MAX_DIST_SQR = 8.0 * 8.0;

    private static final String TAG_FOLLOWING = "KelvinFollowing";
    private static final String TAG_ITEMS = "KelvinItems";
    private static final String TAG_DOWNED = "KelvinDowned";
    private static final String TAG_PROTECT_OWNER = "KelvinProtectOwner";
    private static final String TAG_PROTECT_PATRON = "KelvinProtectPatron";
    private static final String TAG_BONDED_OWNER = "KelvinBondedOwner";

    private static final EntityDataAccessor<Boolean> DATA_DOWNED =
            SynchedEntityData.defineId(KelvinEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_PROTECT_OWNER =
            SynchedEntityData.defineId(KelvinEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<UUID>> DATA_PROTECT_PATRON =
            SynchedEntityData.defineId(KelvinEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    /** Follow target (player UUID); synced so client UI (e.g. inventory toggle) matches server without reopening. */
    private static final EntityDataAccessor<Optional<UUID>> DATA_FOLLOWING =
            SynchedEntityData.defineId(KelvinEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    /**
     * Main-hand swing visual ticks remaining (server decrements once per tick; synced for client arm pose).
     */
    private static final EntityDataAccessor<Byte> DATA_MAINHAND_SWING_TICKS_REMAINING =
            SynchedEntityData.defineId(KelvinEntity.class, EntityDataSerializers.BYTE);
    /** After heli intro spawn: client draws a red through-walls highlight while this counts down (server ticks). */
    private static final EntityDataAccessor<Integer> DATA_HELI_INTRO_REVEAL_HIGHLIGHT_TICKS =
            SynchedEntityData.defineId(KelvinEntity.class, EntityDataSerializers.INT);
    private static final int HELI_INTRO_REVEAL_HIGHLIGHT_DURATION_TICKS = 20 * 30;
    /** Scoreboard team id (max 16 chars): red {@link MobEffects#GLOWING} outline like a spectral hit. */
    private static final String HELI_INTRO_REVEAL_TEAM_ID = "ccc_kv_sglow";
    private static final int INVENTORY_SIZE = 41;
    private static final byte CLIENT_SWING_VISUAL_TICKS = 10;

    private static final double ITEM_PICKUP_BOX = 1.35;
    private static final int FOOD_EAT_INTERVAL_TICKS = 12;
    /** 20 tps × 30 s — time between "my health is low" messages when a player receives one. */
    private static final int NO_FOOD_MESSAGE_COOLDOWN_TICKS = 600;
    private static final int NO_FOOD_RETRY_NO_PLAYER_TICKS = 40;

    /**
     * Player UUID kept for summoner / ally checks while downed (follow UUID is cleared in that state).
     * Also saved so a Kelvin unloaded while downed still remembers his owner.
     */
    @Nullable
    private UUID bondedOwnerUuid;
    /**
     * While set, stuck-escape (mining / pillar / bridge) keys off this player — used when the Kelvin Summoner paths
     * Kelvin toward someone who may not be his follow/protect target.
     */
    @Nullable
    private UUID pathSummonTargetUuid;
    /**
     * After {@link #interruptForSummonerCommand()}, brief window where {@link KelvinHurtByTargetGoal} must not
     * re-target from {@link #getLastHurtByMob()} so Kelvin paths to the player instead of resuming a nearby fight.
     */
    private int summonerRecallGraceTicks;

    private int foodEatCooldown;
    private int noFoodMessageCooldown;
    /**
     * While {@code > 0}, follow AI does not path toward the player so Kelvin does not walk off a narrow pillar
     * while stacking blocks upward.
     */
    private int followNavigationSuppressedTicks;
    /** After a water→dry teleport, avoid spamming while he settles on land. */
    private int waterEscapeTeleportCooldown;
    /**
     * Villager-like social attention toward very close players: idle → face player for a few seconds (body + eyes)
     * → look away → idle.
     */
    private int socialGlanceIdleTicks;
    private int socialGlanceActiveTicks;
    private int socialGlanceLookAwayTicks;
    @Nullable
    private UUID socialGlancePlayerUuid;

    private final SimpleContainer kelvinInventory = new SimpleContainer(INVENTORY_SIZE);
    private final KelvinStuckEscapeController stuckEscape = new KelvinStuckEscapeController(this);
    private final KelvinGoMineController goMineController = new KelvinGoMineController(this);

    /** When true, {@link #completeUsingItem()} applies {@link #pendingHealAfterEat} and restores main-hand inventory. */
    private boolean eatingAnimHealPending;
    private float pendingHealAfterEat;
    @Nullable
    private ItemStack pendingMainHandRestoreAfterEat;

    /**
     * When {@code >= 0}, Kelvin's main hand (slot 0) was swapped with this inventory slot for protect-mode combat.
     * Restored when the fight ends or protect mode turns off.
     */
    private int protectCombatSwapSourceSlot = -1;

    public KelvinEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        /*
         * Vanilla mob pickup equips armour on the entity's equipment slots without writing to
         * {@link #kelvinInventory}, while the Kelvin menu and {@link #syncEquipmentFromInventory()} only
         * use that container — armour looked equipped briefly then vanished. Pickup is handled in
         * {@link #tryPickupNearbyItems()} / {@link #pickUpItem} via the inventory instead.
         */
        this.setCanPickUpLoot(false);
        this.socialGlanceIdleTicks = 25 + this.random.nextInt(55);
        this.kelvinInventory.addListener(container -> {
            if (this.level() != null && !this.level().isClientSide) {
                this.syncEquipmentFromInventory();
                this.broadcastKelvinInventoryMenuToViewers();
            }
        });
    }

    /**
     * When Kelvin has a combat target this close, pillar/mine escape and healing pause so he does not ignore a
     * nearby fight. Distant targets (e.g. on the surface while he is in a pit) no longer block escape.
     */
    public boolean isCloseCombatTargetBlockingRecovery() {
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive() || target.level() != this.level()) {
            return false;
        }
        return this.distanceToSqr(target) < 20.0 * 20.0;
    }

    /** Keeps any open Kelvin inventory UIs in sync on dedicated servers when his container changes from AI/pickup. */
    private void broadcastKelvinInventoryMenuToViewers() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        for (ServerPlayer player : serverLevel.players()) {
            if (player.containerMenu instanceof KelvinInventoryMenu menu && menu.getKelvin() == this) {
                menu.broadcastChanges();
            }
        }
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_DOWNED, false);
        this.entityData.define(DATA_PROTECT_OWNER, false);
        this.entityData.define(DATA_PROTECT_PATRON, Optional.empty());
        this.entityData.define(DATA_FOLLOWING, Optional.empty());
        this.entityData.define(DATA_MAINHAND_SWING_TICKS_REMAINING, (byte) 0);
        this.entityData.define(DATA_HELI_INTRO_REVEAL_HIGHLIGHT_TICKS, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key == DATA_DOWNED) {
            this.refreshDimensions();
        }
    }

    public boolean isDowned() {
        return this.entityData.get(DATA_DOWNED);
    }

    private void setDowned(boolean downed) {
        this.entityData.set(DATA_DOWNED, downed);
    }

    /** Copies armour / hands from the menu container into vanilla equipment for client sync and armour layers. */
    private void syncEquipmentFromInventory() {
        super.setItemSlot(EquipmentSlot.MAINHAND, this.kelvinInventory.getItem(0).copy());
        super.setItemSlot(EquipmentSlot.OFFHAND, this.kelvinInventory.getItem(40).copy());
        super.setItemSlot(EquipmentSlot.FEET, this.kelvinInventory.getItem(36).copy());
        super.setItemSlot(EquipmentSlot.LEGS, this.kelvinInventory.getItem(37).copy());
        super.setItemSlot(EquipmentSlot.CHEST, this.kelvinInventory.getItem(38).copy());
        super.setItemSlot(EquipmentSlot.HEAD, this.kelvinInventory.getItem(39).copy());
    }

    private static int armorInventoryIndex(EquipmentSlot slot) {
        return switch (slot) {
            case FEET -> 36;
            case LEGS -> 37;
            case CHEST -> 38;
            case HEAD -> 39;
            default -> -1;
        };
    }

    private static double sumAddValueModifiers(ItemStack stack, EquipmentSlot slot, Attribute attribute) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        Multimap<Attribute, AttributeModifier> multimap = stack.getAttributeModifiers(slot);
        Collection<AttributeModifier> mods = multimap.get(attribute);
        double sum = 0.0D;
        for (AttributeModifier mod : mods) {
            if (mod.getOperation() == AttributeModifier.Operation.ADDITION) {
                sum += mod.getAmount();
            }
        }
        return sum;
    }

    /**
     * Armour value, toughness, and a chest-only bonus for elytra so random chestplates do not
     * replace elytra from world pickup.
     */
    private static double armourComparableScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return -1.0D;
        }
        double armor = sumAddValueModifiers(stack, slot, Attributes.ARMOR);
        double tough = sumAddValueModifiers(stack, slot, Attributes.ARMOR_TOUGHNESS);
        double score = armor * 1000.0D + tough * 100.0D;
        if (slot == EquipmentSlot.CHEST && stack.is(Items.ELYTRA)) {
            score += 50_000.0D;
        }
        return score;
    }

    /**
     * Puts humanoid armour into inventory slots 36–39 when the slot is empty or the piece is strictly better
     * than the current one; the old stack is dropped at Kelvin's feet.
     */
    /** Drops swapped-out armour with outward velocity and pickup delay so {@link #tryPickupNearbyItems} does not reclaim it. */
    private void dropReplacedArmorPiece(ItemStack stack) {
        if (stack.isEmpty() || this.level().isClientSide) {
            return;
        }
        double angle = this.random.nextDouble() * Math.PI * 2;
        double speed = 0.52D + this.random.nextDouble() * 0.26D;
        double vx = Math.cos(angle) * speed;
        double vz = Math.sin(angle) * speed;
        double vy = 0.11D + this.random.nextDouble() * 0.09D;
        ItemEntity item =
                new ItemEntity(this.level(), this.getX(), this.getEyeY() - 0.12D, this.getZ(), stack);
        item.setDeltaMovement(vx, vy, vz);
        item.setPickUpDelay(45);
        item.setThrower(this.getUUID());
        this.level().addFreshEntity(item);
    }

    private ItemStack tryAutoEquipArmour(ItemStack stack) {
        ItemStack remaining = stack.copy();
        while (!remaining.isEmpty()) {
            EquipmentSlot equipSlot = LivingEntity.getEquipmentSlotForItem(remaining);
            if (equipSlot.getType() != EquipmentSlot.Type.ARMOR) {
                break;
            }
            if (!remaining.canEquip(equipSlot, this)) {
                break;
            }
            int invSlot = armorInventoryIndex(equipSlot);
            if (invSlot < 0) {
                break;
            }
            ItemStack current = this.kelvinInventory.getItem(invSlot);
            if (equipSlot == EquipmentSlot.CHEST && current.is(Items.ELYTRA) && !remaining.is(Items.ELYTRA)) {
                break;
            }
            double newScore = armourComparableScore(remaining, equipSlot);
            double curScore = armourComparableScore(current, equipSlot);
            if (!current.isEmpty()) {
                if (newScore < curScore) {
                    break;
                }
                if (newScore == curScore && remaining.getDamageValue() >= current.getDamageValue()) {
                    break;
                }
            }
            ItemStack incomingPiece = remaining.split(1);
            if (!current.isEmpty()) {
                this.dropReplacedArmorPiece(current.copy());
            }
            this.kelvinInventory.setItem(invSlot, incomingPiece);
        }
        return remaining;
    }

    public SimpleContainer getKelvinInventory() {
        return this.kelvinInventory;
    }

    public KelvinGoMineController getGoMineController() {
        return this.goMineController;
    }

    public void startOpen(Player player) {
        this.kelvinInventory.startOpen(player);
    }

    public void stopOpen(Player player) {
        this.kelvinInventory.stopOpen(player);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D);
    }

    @Override
    public SpawnGroupData finalizeSpawn(
            ServerLevelAccessor level,
            DifficultyInstance difficulty,
            MobSpawnType reason,
            @Nullable SpawnGroupData spawnData,
            CompoundTag spawnDataTag) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, reason, spawnData, spawnDataTag);
        if (!level.getLevel().isClientSide()) {
            this.setPersistenceRequired();
            if (!this.hasCustomName()) {
                this.setCustomName(Component.literal("Kelvin"));
                this.setCustomNameVisible(true);
            }
        }
        return out;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    public Optional<UUID> getFollowingId() {
        return this.entityData.get(DATA_FOLLOWING);
    }

    public boolean isProtectOwner() {
        return this.entityData.get(DATA_PROTECT_OWNER);
    }

    public void setProtectOwner(boolean value) {
        if (this.level().isClientSide) {
            return;
        }
        if (!value) {
            this.setTarget(null);
            this.restoreCombatMainHandSwap();
            this.entityData.set(DATA_PROTECT_PATRON, Optional.empty());
        }
        this.entityData.set(DATA_PROTECT_OWNER, value);
        if (!value && !this.level().isClientSide) {
            this.bondedOwnerUuid = this.getFollowingId().orElse(null);
        }
    }

    /** Server: player Kelvin will retaliate for when protect mode is on (synced for client HUD). */
    public void assignProtectPatron(UUID playerId) {
        if (this.level().isClientSide) {
            return;
        }
        this.entityData.set(DATA_PROTECT_PATRON, Optional.of(playerId));
        this.bondedOwnerUuid = playerId;
    }

    public Optional<UUID> getProtectPatronId() {
        return this.entityData.get(DATA_PROTECT_PATRON);
    }

    /** True if this Kelvin should respond to the given player being hurt (server logic). */
    public boolean protectsPlayer(UUID playerUuid) {
        if (!this.isProtectOwner()) {
            return false;
        }
        if (this.getProtectPatronId().filter(playerUuid::equals).isPresent()) {
            return true;
        }
        return this.getFollowingId().filter(playerUuid::equals).isPresent();
    }

    /** Players Kelvin must never melee or retaliate against (patron or current follow target). */
    public boolean isAllyPlayer(Player player) {
        UUID id = player.getUUID();
        if (this.bondedOwnerUuid != null && this.bondedOwnerUuid.equals(id)) {
            return true;
        }
        return this.getProtectPatronId().filter(id::equals).isPresent()
                || this.getFollowingId().filter(id::equals).isPresent()
                || (this.pathSummonTargetUuid != null && this.pathSummonTargetUuid.equals(id));
    }

    /**
     * Tells the followed player Kelvin needs a better tool while mining out of a ditch (throttled by the caller).
     * Only when {@link #getFollowingId()} is set.
     */
    public void sendMiningToolHintToFollowed(String toolNoun) {
        if (this.level().isClientSide || this.level().getServer() == null) {
            return;
        }
        UUID hintTo = this.getFollowingId()
                .or(() -> Optional.ofNullable(this.pathSummonTargetUuid))
                .or(() -> Optional.ofNullable(this.goMineController.getOrdererUuid()))
                .orElse(null);
        if (hintTo == null) {
            return;
        }
        ServerPlayer followed = this.level().getServer().getPlayerList().getPlayer(hintTo);
        if (followed == null || followed.level() != this.level()) {
            return;
        }
        Component msg = Component.literal("<")
                .withStyle(ChatFormatting.WHITE)
                .append(Component.literal("Kelvin").withStyle(ChatFormatting.BLUE))
                .append(Component.literal("> It would help if I had a " + toolNoun + "...")
                        .withStyle(ChatFormatting.WHITE));
        followed.sendSystemMessage(msg);
    }

    /**
     * Client-side attack swing progress in {@code [0, 1]} for rendering (synced from server on each main-hand
     * {@link #swing}). Falls back to vanilla {@link #getAttackAnim} when no synced swing is active.
     */
    public float getMainhandSwingAnim(float partialTick) {
        byte remaining = this.entityData.get(DATA_MAINHAND_SWING_TICKS_REMAINING);
        if (remaining <= 0) {
            return this.getAttackAnim(partialTick);
        }
        float t = (remaining - partialTick) / (float) CLIENT_SWING_VISUAL_TICKS;
        return Mth.clamp(t, 0.0F, 1.0F);
    }

    /** Synced remaining ticks for the post–heli-intro red highlight (0 = off). */
    public int getHeliIntroRevealHighlightTicks() {
        return this.entityData.get(DATA_HELI_INTRO_REVEAL_HIGHLIGHT_TICKS);
    }

    /** Server: 30s spectral-style red outline ({@link MobEffects#GLOWING} + scoreboard team color) after the intro. */
    public void beginHeliIntroRevealHighlight() {
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel sl)) {
            return;
        }
        this.clearHeliIntroRevealScoreboard(sl);
        this.entityData.set(DATA_HELI_INTRO_REVEAL_HIGHLIGHT_TICKS, HELI_INTRO_REVEAL_HIGHLIGHT_DURATION_TICKS);
        this.addEffect(
                new MobEffectInstance(
                        MobEffects.GLOWING,
                        HELI_INTRO_REVEAL_HIGHLIGHT_DURATION_TICKS,
                        0,
                        false,
                        false,
                        false),
                this);
        this.applyHeliIntroRevealScoreboard(sl);
    }

    private void applyHeliIntroRevealScoreboard(ServerLevel sl) {
        Scoreboard board = sl.getScoreboard();
        board.removePlayerFromTeam(this.getStringUUID());
        PlayerTeam team = board.getPlayerTeam(HELI_INTRO_REVEAL_TEAM_ID);
        if (team == null) {
            team = board.addPlayerTeam(HELI_INTRO_REVEAL_TEAM_ID);
            team.setColor(ChatFormatting.RED);
            team.setCollisionRule(Team.CollisionRule.NEVER);
        }
        board.addPlayerToTeam(this.getStringUUID(), team);
    }

    private void clearHeliIntroRevealScoreboard(ServerLevel sl) {
        sl.getScoreboard().removePlayerFromTeam(this.getStringUUID());
    }

    @Override
    public void swing(InteractionHand hand, boolean updateSelf) {
        super.swing(hand, updateSelf);
        if (!this.level().isClientSide && hand == InteractionHand.MAIN_HAND) {
            this.entityData.set(DATA_MAINHAND_SWING_TICKS_REMAINING, CLIENT_SWING_VISUAL_TICKS);
        }
    }

    /**
     * {@link com.mrcrayfish.vehicle.entity.VehicleEntity} adds this to seat Y; Kelvin’s model reads a bit high on the
     * golf cart compared to players.
     */
    private static final double GOLF_CART_SEAT_Y_ADJUST = -0.3125;

    @Override
    public double getMyRidingOffset() {
        double base = super.getMyRidingOffset();
        if (this.getVehicle() instanceof GolfCartEntity) {
            return base + GOLF_CART_SEAT_Y_ADJUST;
        }
        return base;
    }

    public void setFollowing(Player player) {
        if (this.level().isClientSide) {
            return;
        }
        UUID id = player.getUUID();
        this.entityData.set(DATA_FOLLOWING, Optional.of(id));
        this.bondedOwnerUuid = id;
        this.pathSummonTargetUuid = null;
    }

    /** Summoner pathing: stuck-escape mines toward this player until they are close or this is cleared. */
    public void setPathSummonTarget(@Nullable Player player) {
        if (this.level().isClientSide) {
            return;
        }
        this.pathSummonTargetUuid = player != null ? player.getUUID() : null;
    }

    @Nullable
    UUID getPathSummonTargetId() {
        return this.pathSummonTargetUuid;
    }

    /** True while a summoner recall is in progress or settling (no hurt-by retaliation). */
    public boolean isSummonerRecallGraceActive() {
        return this.summonerRecallGraceTicks > 0;
    }

    void clearPathSummonTarget() {
        this.pathSummonTargetUuid = null;
    }

    public void clearFollowing() {
        clearFollowing(false);
    }

    public void clearFollowing(boolean preserveBondedOwner) {
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_FOLLOWING, Optional.empty());
        }
        if (!preserveBondedOwner) {
            this.bondedOwnerUuid = null;
        }
        this.setTarget(null);
        this.restoreCombatMainHandSwap();
        this.getNavigation().stop();
        this.stuckEscape.reset();
        this.waterEscapeTeleportCooldown = 0;
        this.followNavigationSuppressedTicks = 0;
        this.pathSummonTargetUuid = null;
    }

    /** Called when placing a vertical escape block — briefly disables follow pathing and fall buildup. */
    void deferFollowNavigationForPillarAscent() {
        this.followNavigationSuppressedTicks = 55;
        this.fallDistance = 0.0F;
    }

    boolean isFollowNavigationDeferredForPillar() {
        return this.followNavigationSuppressedTicks > 0;
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level);
        nav.setCanOpenDoors(true);
        return nav;
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new KelvinMeleeAttackGoal(this, 1.25D));
        this.goalSelector.addGoal(2, new OpenDoorGoal(this, false));
        this.goalSelector.addGoal(3, new KelvinFollowPlayerGoal(this));
        this.targetSelector.addGoal(1, new KelvinHurtByTargetGoal(this));
    }

    /**
     * When Kelvin is stuck in water but the followed player is on land, snap to dry ground near them instead of
     * swimming AI (which was unreliable for a player-shaped mob).
     */
    void tryTeleportOutOfWaterTowardFollowed(Player player) {
        if (this.level().isClientSide || !this.isInWater() || player.isInWater()) {
            return;
        }
        this.snapTowardDryGroundNear(player);
    }

    /**
     * Stuck-escape fallback: if Kelvin is still in fluid while the anchor is dry, snap to land near them even when
     * bridging cannot finish (same geometry as {@link #tryTeleportOutOfWaterTowardFollowed}).
     */
    public void tryForcedWaterEscapeToward(Player anchor) {
        if (this.level().isClientSide || !this.isInWater()) {
            return;
        }
        if (anchor.isInWater()) {
            return;
        }
        this.snapTowardDryGroundNear(anchor);
    }

    private void snapTowardDryGroundNear(Player player) {
        if (this.waterEscapeTeleportCooldown > 0) {
            return;
        }
        Vec3 target = player.position();
        Vec3 flatLook = new Vec3(player.getLookAngle().x, 0.0D, player.getLookAngle().z);
        if (flatLook.lengthSqr() < 1.0E-4D) {
            flatLook = new Vec3(0.0D, 0.0D, 1.0D);
        } else {
            flatLook = flatLook.normalize();
        }
        Vec3 dest = target.subtract(flatLook.scale(1.75D));
        dest = new Vec3(dest.x, target.y, dest.z);
        this.stopRiding();
        this.setPos(dest.x, dest.y, dest.z);
        this.setDeltaMovement(Vec3.ZERO);
        this.fallDistance = 0.0F;
        this.setPose(Pose.STANDING);
        this.setSwimming(false);
        this.getNavigation().stop();
        float yRot = player.getYRot();
        this.setYRot(yRot);
        this.yBodyRotO = yRot;
        this.setYBodyRot(yRot);
        this.yHeadRotO = yRot;
        this.setYHeadRot(yRot);
        this.waterEscapeTeleportCooldown = 35;
    }

    /**
     * Kelvin Summoner: snap to dry ground just in front of the player (works whether Kelvin was in water or not).
     */
    public void summonTeleportNear(Player player) {
        if (this.level().isClientSide) {
            return;
        }
        Vec3 target = player.position();
        Vec3 flatLook = new Vec3(player.getLookAngle().x, 0.0D, player.getLookAngle().z);
        if (flatLook.lengthSqr() < 1.0E-4D) {
            flatLook = new Vec3(0.0D, 0.0D, 1.0D);
        } else {
            flatLook = flatLook.normalize();
        }
        Vec3 dest = target.subtract(flatLook.scale(1.75D));
        dest = new Vec3(dest.x, target.y, dest.z);
        this.stopRiding();
        this.setPos(dest.x, dest.y, dest.z);
        this.setDeltaMovement(Vec3.ZERO);
        this.fallDistance = 0.0F;
        this.setPose(Pose.STANDING);
        this.setSwimming(false);
        this.getNavigation().stop();
        float yRot = player.getYRot();
        this.setYRot(yRot);
        this.yBodyRotO = yRot;
        this.setYBodyRot(yRot);
        this.yHeadRotO = yRot;
        this.setYHeadRot(yRot);
        this.waterEscapeTeleportCooldown = 35;
    }

    /**
     * Kelvin Summoner: moves Kelvin to the player's dimension (if needed) then snaps in front of them.
     *
     * @return {@code false} if a cross-dimension teleport was required and failed
     */
    public boolean summonBringToPlayer(ServerPlayer player) {
        if (this.level().isClientSide) {
            return true;
        }
        if (this.level() == player.serverLevel()) {
            this.summonTeleportNear(player);
            return true;
        }
        boolean moved = this.teleportTo(
                player.serverLevel(),
                player.getX(),
                player.getY(),
                player.getZ(),
                Collections.<RelativeMovement>emptySet(),
                player.getYRot(),
                player.getXRot());
        if (!moved) {
            return false;
        }
        this.summonTeleportNear(player);
        return true;
    }

    /**
     * Kelvin Summoner: drop combat, eating, stuck-escape work, riding, and navigation so he can path or teleport in
     * a clean default state. Protect mode is turned off so he stops fighting and comes to the summoner; follow bond
     * is unchanged.
     */
    public void interruptForSummonerCommand() {
        if (this.level().isClientSide) {
            return;
        }
        this.goMineController.stop(null);
        this.setProtectOwner(false);
        this.setLastHurtByMob(null);
        this.summonerRecallGraceTicks = 45;
        this.stopRiding();
        this.cancelEatAnimationIfAny();
        this.setTarget(null);
        this.setAggressive(false);
        this.setSprinting(false);
        this.restoreCombatMainHandSwap();
        this.getNavigation().stop();
        this.stuckEscape.reset();
        this.followNavigationSuppressedTicks = 0;
        this.waterEscapeTeleportCooldown = 0;
        this.pathSummonTargetUuid = null;
        if (!this.isInWater()) {
            this.setSwimming(false);
            if (this.onGround()) {
                this.setPose(Pose.STANDING);
            }
        }
    }

    private void cancelEatAnimationIfAny() {
        if (!this.isUsingItem()) {
            this.eatingAnimHealPending = false;
            this.pendingHealAfterEat = 0.0F;
            return;
        }
        if (this.eatingAnimHealPending) {
            this.eatingAnimHealPending = false;
            this.pendingHealAfterEat = 0.0F;
            if (this.pendingMainHandRestoreAfterEat != null) {
                this.kelvinInventory.setItem(0, this.pendingMainHandRestoreAfterEat);
                this.pendingMainHandRestoreAfterEat = null;
            }
            this.syncEquipmentFromInventory();
        }
        this.stopUsingItem();
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide && this.isAlive()) {
            byte swingRem = this.entityData.get(DATA_MAINHAND_SWING_TICKS_REMAINING);
            if (swingRem > 0) {
                this.entityData.set(DATA_MAINHAND_SWING_TICKS_REMAINING, (byte) (swingRem - 1));
            }
            int hi = this.entityData.get(DATA_HELI_INTRO_REVEAL_HIGHLIGHT_TICKS);
            if (hi > 0) {
                int next = hi - 1;
                this.entityData.set(DATA_HELI_INTRO_REVEAL_HIGHLIGHT_TICKS, next);
                if (next == 0 && this.level() instanceof ServerLevel sl) {
                    this.clearHeliIntroRevealScoreboard(sl);
                    this.removeEffect(MobEffects.GLOWING);
                }
            }
        }
        super.tick();
        if (!this.level().isClientSide && this.isAlive()) {
            if (this.summonerRecallGraceTicks > 0) {
                this.summonerRecallGraceTicks--;
            }
            if (this.followNavigationSuppressedTicks > 0) {
                this.followNavigationSuppressedTicks--;
            }
            if (this.getTarget() != null && this.getTarget().isAlive()) {
                this.followNavigationSuppressedTicks = 0;
            }
            if (this.isDowned()) {
                this.setDeltaMovement(Vec3.ZERO);
                this.setPose(Pose.SWIMMING);
                return;
            }
            if (this.noFoodMessageCooldown > 0) {
                this.noFoodMessageCooldown--;
            }
            if (this.waterEscapeTeleportCooldown > 0) {
                this.waterEscapeTeleportCooldown--;
            }
            if (this.goMineController.isActive()) {
                this.goMineController.tickServer();
                this.tryPickupNearbyItemsGoMine();
            } else {
                this.tryPickupNearbyItems();
            }
            if (++this.foodEatCooldown >= FOOD_EAT_INTERVAL_TICKS) {
                this.foodEatCooldown = 0;
                this.tryEatFoodFromInventory();
            }
            this.tryNotifyNoFoodRequest();
            if (this.isProtectOwner() && this.getProtectPatronId().isEmpty()) {
                this.getFollowingId().ifPresent(this::assignProtectPatron);
            }
            this.tickPatronThreatFromLastHurt();
            this.tickProtectHostilesNearPatron();
            this.tickCombatMainHandAndAllyTargets();
            if (!this.goMineController.isActive()) {
                this.stuckEscape.tickServer();
            }
            this.tickPathSummonReleaseIfClose();
            this.tickNearbyPlayerGlance();
        }
    }

    private void tickPathSummonReleaseIfClose() {
        if (this.pathSummonTargetUuid == null) {
            return;
        }
        Player p = this.level().getPlayerByUUID(this.pathSummonTargetUuid);
        if (p == null || !p.isAlive() || p.level() != this.level()) {
            this.pathSummonTargetUuid = null;
            return;
        }
        if (this.distanceToSqr(p) < 4.5 * 4.5) {
            this.pathSummonTargetUuid = null;
        }
    }

    private static final double NEAR_PLAYER_GLANCE_DIST_SQR = 3.25 * 3.25;
    /** Slightly farther than pick range — cancel the social glance if they back off mid-gesture. */
    private static final double NEAR_PLAYER_GLANCE_ABORT_DIST_SQR = 4.5 * 4.5;

    /** Villager-style: several seconds facing a nearby player (body + head), then a short look-away, then idle. */
    private void tickNearbyPlayerGlance() {
        if (this.getTarget() != null && this.getTarget().isAlive()) {
            this.abortSocialGlance();
            return;
        }
        if (this.isUsingItem()) {
            this.abortSocialGlance();
            return;
        }

        if (this.socialGlanceLookAwayTicks > 0) {
            this.tickSocialGlanceLookAway();
            return;
        }

        if (this.socialGlanceActiveTicks > 0) {
            this.tickSocialGlanceActive();
            return;
        }

        if (this.socialGlanceIdleTicks > 0) {
            this.socialGlanceIdleTicks--;
            return;
        }

        Player closest = this.findClosestVeryNearPlayer();
        if (closest != null) {
            this.socialGlancePlayerUuid = closest.getUUID();
            this.socialGlanceActiveTicks = 50 + this.random.nextInt(55);
        } else {
            this.socialGlanceIdleTicks = 45 + this.random.nextInt(75);
        }
    }

    private void abortSocialGlance() {
        this.socialGlanceActiveTicks = 0;
        this.socialGlanceLookAwayTicks = 0;
        this.socialGlancePlayerUuid = null;
        this.socialGlanceIdleTicks = 70 + this.random.nextInt(90);
    }

    private @Nullable Player findClosestVeryNearPlayer() {
        Player closest = null;
        double best = Double.MAX_VALUE;
        for (Player p : this.level().getEntitiesOfClass(Player.class, this.getBoundingBox().inflate(4.0D, 2.5D, 4.0D), pl -> pl.isAlive() && !pl.isSpectator())) {
            double d = p.distanceToSqr(this);
            if (d <= NEAR_PLAYER_GLANCE_DIST_SQR && d < best) {
                best = d;
                closest = p;
            }
        }
        return closest;
    }

    private void tickSocialGlanceActive() {
        this.socialGlanceActiveTicks--;
        Player player = this.resolveSocialGlancePlayer();
        if (player == null || !player.isAlive() || player.distanceToSqr(this) > NEAR_PLAYER_GLANCE_ABORT_DIST_SQR) {
            this.abortSocialGlance();
            return;
        }
        this.getLookControl().setLookAt(player, 30.0F, 25.0F);
        this.rotateBodyTowardLiving(player, 8.5F);
        if (this.socialGlanceActiveTicks == 0) {
            this.socialGlancePlayerUuid = null;
            this.socialGlanceLookAwayTicks = 16 + this.random.nextInt(18);
        }
    }

    private void tickSocialGlanceLookAway() {
        this.socialGlanceLookAwayTicks--;
        float yawRad = this.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yawRad);
        double fz = Mth.cos(yawRad);
        Vec3 eye = this.getEyePosition();
        this.getLookControl().setLookAt(eye.x + fx * 2.5D, eye.y - 0.1D, eye.z + fz * 2.5D, 22.0F, 18.0F);
        if (this.socialGlanceLookAwayTicks == 0) {
            this.socialGlanceIdleTicks = 100 + this.random.nextInt(120);
        }
    }

    private @Nullable Player resolveSocialGlancePlayer() {
        if (this.socialGlancePlayerUuid == null) {
            return null;
        }
        return this.level().getPlayerByUUID(this.socialGlancePlayerUuid);
    }

    /** Rotates body (and synced yaw) toward an entity, limited degrees per tick for a slow villager-like turn. */
    private void rotateBodyTowardLiving(LivingEntity entity, float maxYawStepDegrees) {
        Vec3 eye = this.getEyePosition();
        Vec3 target = entity.getEyePosition();
        Vec3 diff = target.subtract(eye);
        double horiz = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        if (horiz < 1.0E-4D) {
            return;
        }
        float targetYaw = (float) (Mth.atan2(diff.z, diff.x) * Mth.RAD_TO_DEG) - 90.0F;
        float yaw = this.getYRot();
        float delta = Mth.wrapDegrees(targetYaw - yaw);
        float step = Mth.clamp(delta, -maxYawStepDegrees, maxYawStepDegrees);
        float newYaw = yaw + step;
        this.setYRot(newYaw);
        this.yRotO = newYaw;
        this.setYBodyRot(newYaw);
        this.yBodyRotO = newYaw;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isDowned()) {
            if (!source.is(DamageTypes.GENERIC_KILL) && !source.is(DamageTypes.FELL_OUT_OF_WORLD)) {
                return false;
            }
        }
        return super.hurt(source, amount);
    }

    @Override
    public void die(DamageSource cause) {
        if (this.level().isClientSide) {
            return;
        }
        if (cause.is(DamageTypes.GENERIC_KILL) || cause.is(DamageTypes.FELL_OUT_OF_WORLD)) {
            this.rememberLastDeathForSummoner();
            super.die(cause);
            return;
        }
        if (this.isDowned()) {
            this.rememberLastDeathForSummoner();
            super.die(cause);
            return;
        }
        this.enterDownedState();
    }

    /**
     * Server: crash-site appearance after the heli intro — same downed gameplay as fatal damage, without the
     * death sound.
     */
    public void applyHeliIntroSpawnDowned() {
        if (this.level().isClientSide() || !(this.level() instanceof ServerLevel)) {
            return;
        }
        this.applyCoreDownedState();
        this.notifyNearPlayersDownedPlea();
    }

    private void rememberLastDeathForSummoner() {
        if (this.level() instanceof ServerLevel serverLevel) {
            KelvinSummonerWorldData.get(serverLevel.getServer()).remember(serverLevel, this.blockPosition());
        }
    }

    @Override
    public boolean isPushable() {
        return !this.isDowned() && super.isPushable();
    }

    private void enterDownedState() {
        this.applyCoreDownedState();
        this.playSound(SoundEvents.PLAYER_DEATH, 1.0F, (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
        this.notifyNearPlayersDownedPlea();
    }

    /** Shared body for downed state (lethal and intro spawn). */
    private void applyCoreDownedState() {
        this.setHealth(1.0F);
        this.stopUsingItem();
        this.eatingAnimHealPending = false;
        this.pendingMainHandRestoreAfterEat = null;
        this.pendingHealAfterEat = 0.0F;
        this.setDowned(true);
        this.clearFire();
        this.removeAllEffects();
        if (this.level() instanceof ServerLevel sl) {
            this.entityData.set(DATA_HELI_INTRO_REVEAL_HIGHLIGHT_TICKS, 0);
            this.clearHeliIntroRevealScoreboard(sl);
        }
        this.setPose(Pose.SWIMMING);
        this.setDeltaMovement(Vec3.ZERO);
        this.setNoAi(true);
        UUID bondedKeep = Optional.ofNullable(this.bondedOwnerUuid)
                .or(() -> this.getFollowingId())
                .or(() -> this.getProtectPatronId())
                .orElse(null);
        this.clearFollowing(true);
        this.bondedOwnerUuid = bondedKeep;
        this.getNavigation().stop();
        this.rememberLastDeathForSummoner();
    }

    private void exitDownedState(ServerPlayer reviver) {
        if (reviver.getServer() != null) {
            KelvinSummonerWorldData.get(reviver.getServer()).clear();
        }
        this.setDowned(false);
        this.setNoAi(false);
        this.setPose(Pose.STANDING);
        this.setHealth(this.getMaxHealth());
        ItemStack appleEffects = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);
        appleEffects.finishUsingItem(this.level(), this);
        this.playSound(SoundEvents.TOTEM_USE, 1.0F, 1.0F);
        this.level().broadcastEntityEvent(this, (byte) 35);
        if (this.level() instanceof ServerLevel serverLevel) {
            KelvinRevivalRewards.tryFirstRevive(serverLevel, reviver, this);
        }
    }

    private void notifyNearPlayersDownedPlea() {
        ServerPlayer target = this.findPlayerToNotifyFoodRequest();
        if (target != null) {
            target.sendSystemMessage(downedPleaMessage());
        }
    }

    private static Component downedPleaMessage() {
        return Component.literal("<")
                .withStyle(ChatFormatting.WHITE)
                .append(Component.literal("Kelvin").withStyle(ChatFormatting.BLUE))
                .append(Component.literal(
                                "> Please find me an enchanted golden apple and give it to me so I can regain my strength.")
                        .withStyle(ChatFormatting.WHITE));
    }

    private void restoreDownedAfterLoad() {
        this.setDowned(true);
        this.setHealth(1.0F);
        this.setPose(Pose.SWIMMING);
        this.setNoAi(true);
        this.clearFollowing(true);
        this.getNavigation().stop();
    }

    /**
     * True when Kelvin may eat from inventory or accept food from a player: at 40% health or below (until full),
     * or while protect mode is on (until full) unless a hostile is close enough to be in a real fight.
     */
    private boolean shouldEatToReplenishHealth() {
        float health = this.getHealth();
        float maxHealth = this.getMaxHealth();
        if (health >= maxHealth - 1.0E-3F) {
            return false;
        }
        if (this.isCloseCombatTargetBlockingRecovery()) {
            return false;
        }
        if (this.getHealth() <= maxHealth * 0.4F + 1.0E-3F) {
            return true;
        }
        return this.isProtectOwner();
    }

    /** 40% health or below: gates the “no food” chat nag (automatic eating uses {@link #shouldEatToReplenishHealth()}). */
    private boolean isHealthLowEnoughForFoodRequestChat() {
        return this.getHealth() <= this.getMaxHealth() * 0.4F + 1.0E-3F;
    }

    private boolean hasEdibleFoodInInventory() {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (this.isEdibleFood(this.kelvinInventory.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /** Throttled chat when hurt and no food, to the followed player or nearest player. */
    private void tryNotifyNoFoodRequest() {
        if (!this.isHealthLowEnoughForFoodRequestChat()) {
            return;
        }
        if (this.hasEdibleFoodInInventory() || this.isUsingItem()) {
            return;
        }
        if (this.noFoodMessageCooldown > 0) {
            return;
        }
        ServerPlayer target = this.findPlayerToNotifyFoodRequest();
        if (target == null) {
            this.noFoodMessageCooldown = NO_FOOD_RETRY_NO_PLAYER_TICKS;
            return;
        }
        this.noFoodMessageCooldown = NO_FOOD_MESSAGE_COOLDOWN_TICKS;
        target.sendSystemMessage(noFoodChatMessage());
    }

    private static Component noFoodChatMessage() {
        return Component.literal("<")
                .withStyle(ChatFormatting.WHITE)
                .append(Component.literal("Kelvin").withStyle(ChatFormatting.BLUE))
                .append(Component.literal("> My health is low, please give me food so I can gain health.")
                        .withStyle(ChatFormatting.WHITE));
    }

    @Nullable
    private ServerPlayer findPlayerToNotifyFoodRequest() {
        if (this.level().getServer() != null) {
            ServerPlayer followed = this.getFollowingId()
                    .map(id -> this.level().getServer().getPlayerList().getPlayer(id))
                    .orElse(null);
            if (followed != null && followed.level() == this.level() && followed.distanceToSqr(this) <= 256.0D) {
                return followed;
            }
        }
        Player nearest = this.level().getNearestPlayer(this, 16.0D);
        return nearest instanceof ServerPlayer sp ? sp : null;
    }

    private void tryEatFoodFromInventory() {
        if (!this.shouldEatToReplenishHealth()) {
            return;
        }
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = this.kelvinInventory.getItem(i);
            if (!this.isEdibleFood(stack)) {
                continue;
            }
            this.beginConsumingFoodAnim(i, stack);
            return;
        }
    }

    private boolean isEdibleFood(ItemStack stack) {
        return !stack.isEmpty() && stack.getFoodProperties(this) != null;
    }

    /**
     * Puts one food in the main-hand slot (inventory index 0) and starts the vanilla eat use animation;
     * healing and restoring the previous main-hand stack happen in {@link #completeUsingItem()}.
     */
    private void beginConsumingFoodAnim(int sourceSlot, ItemStack foodInSlot) {
        if (this.level().isClientSide || this.isUsingItem() || foodInSlot.isEmpty()) {
            return;
        }
        FoodProperties food = foodInSlot.getFoodProperties(this);
        if (food == null) {
            return;
        }
        float missing = this.getMaxHealth() - this.getHealth();
        float heal = this.computeHealFromFood(food, missing);

        ItemStack oneForAnim = foodInSlot.copy();
        oneForAnim.setCount(1);
        foodInSlot.shrink(1);
        ItemStack remainder = foodInSlot.isEmpty() ? ItemStack.EMPTY : foodInSlot.copy();
        if (sourceSlot != 0) {
            this.kelvinInventory.setItem(sourceSlot, remainder);
        }

        final ItemStack restoreMainAfterEat =
                sourceSlot == 0 ? remainder : this.kelvinInventory.getItem(0).copy();
        this.kelvinInventory.setItem(0, oneForAnim);

        int useDuration = oneForAnim.getUseDuration();
        if (useDuration <= 0) {
            this.kelvinInventory.setItem(0, restoreMainAfterEat);
            this.syncEquipmentFromInventory();
            this.heal(heal);
            this.playSound(SoundEvents.GENERIC_EAT, 0.5F, (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
            return;
        }

        this.eatingAnimHealPending = true;
        this.pendingHealAfterEat = heal;
        this.pendingMainHandRestoreAfterEat = restoreMainAfterEat;
        this.syncEquipmentFromInventory();
        this.startUsingItem(InteractionHand.MAIN_HAND);
        this.playSound(SoundEvents.GENERIC_EAT, 0.35F, (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
    }

    private void beginEatingFoodFromHand(ItemStack oneFood, float heal) {
        if (this.level().isClientSide || this.isUsingItem() || oneFood.isEmpty()) {
            return;
        }
        int useDuration = oneFood.getUseDuration();
        ItemStack prevMain = this.kelvinInventory.getItem(0).copy();
        this.kelvinInventory.setItem(0, oneFood);
        if (useDuration <= 0) {
            this.kelvinInventory.setItem(0, prevMain);
            this.syncEquipmentFromInventory();
            this.heal(heal);
            this.playSound(SoundEvents.GENERIC_EAT, 0.5F, (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
            return;
        }
        this.eatingAnimHealPending = true;
        this.pendingHealAfterEat = heal;
        this.pendingMainHandRestoreAfterEat = prevMain;
        this.syncEquipmentFromInventory();
        this.startUsingItem(InteractionHand.MAIN_HAND);
        this.playSound(SoundEvents.GENERIC_EAT, 0.35F, (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
    }

    @Override
    public void completeUsingItem() {
        if (!this.level().isClientSide && this.eatingAnimHealPending) {
            this.eatingAnimHealPending = false;
            float heal = this.pendingHealAfterEat;
            this.pendingHealAfterEat = 0.0F;
            this.stopUsingItem();
            this.heal(heal);
            if (this.pendingMainHandRestoreAfterEat != null) {
                this.kelvinInventory.setItem(0, this.pendingMainHandRestoreAfterEat);
                this.pendingMainHandRestoreAfterEat = null;
            }
            this.syncEquipmentFromInventory();
            return;
        }
        super.completeUsingItem();
    }

    private float healAmountFromFood(ItemStack stack) {
        FoodProperties food = stack.getFoodProperties(this);
        if (food == null) {
            return 0.0F;
        }
        float missing = this.getMaxHealth() - this.getHealth();
        return this.computeHealFromFood(food, missing);
    }

    /** Uses nutrition and saturation so common foods meaningfully restore Kelvin's larger health pool. */
    private float computeHealFromFood(FoodProperties food, float missing) {
        if (missing <= 0.0F) {
            return 0.0F;
        }
        float fromStats =
                (float) food.getNutrition() * 2.0F + Math.max(0.0F, food.getSaturationModifier()) * 4.0F;
        return Math.min(missing, Math.max(1.0F, fromStats));
    }

    private void openKelvinMenu(ServerPlayer player) {
        if (!this.isAlive() || player.distanceToSqr(this) > PLAYER_INTERACTION_MAX_DIST_SQR) {
            return;
        }
        NetworkHooks.openScreen(
                player,
                new SimpleMenuProvider(
                        (id, inv, p) -> new KelvinInventoryMenu(ModMenus.KELVIN_INVENTORY.get(), id, inv, this),
                        Component.empty()),
                buf -> buf.writeVarInt(this.getId()));
    }

    private static final double GO_MINE_ITEM_PICKUP = 5.75D;

    private void tryPickupNearbyItems() {
        this.tryPickupNearbyItemsOnce(this.getBoundingBox().inflate(ITEM_PICKUP_BOX, 0.5, ITEM_PICKUP_BOX));
    }

    /** Broader, multi-pass pickup while Go Mine runs (collects loose drops Kelvin created or walks past). */
    private void tryPickupNearbyItemsGoMine() {
        var box = this.getBoundingBox().inflate(GO_MINE_ITEM_PICKUP, 1.25D, GO_MINE_ITEM_PICKUP);
        for (int pass = 0; pass < 24; pass++) {
            if (!this.tryPickupNearbyItemsOnce(box)) {
                break;
            }
        }
    }

    /**
     * @return {@code true} if at least one item stack was partially or fully absorbed (caller may retry).
     */
    private boolean tryPickupNearbyItemsOnce(AABB searchBox) {
        for (ItemEntity itemEntity : this.level().getEntitiesOfClass(ItemEntity.class, searchBox)) {
            if (!itemEntity.isAlive() || itemEntity.hasPickUpDelay()) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            int before = stack.getCount();
            ItemStack remaining = this.addItemStackToKelvin(stack);
            if (remaining.getCount() != before) {
                itemEntity.setItem(remaining);
                if (remaining.isEmpty()) {
                    itemEntity.discard();
                }
                this.playSound(SoundEvents.ITEM_PICKUP, 0.2F, ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
                return true;
            }
        }
        return false;
    }

    /** Merge into Kelvin's 41 slots (same rules as a generic chest). */
    ItemStack addItemStackToKelvin(ItemStack stack) {
        ItemStack remaining = this.tryAutoEquipArmour(stack);
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack slot = this.kelvinInventory.getItem(i);
            if (ItemStack.isSameItemSameTags(slot, remaining) && !slot.isEmpty() && slot.getCount() < slot.getMaxStackSize()) {
                int room = slot.getMaxStackSize() - slot.getCount();
                int move = Math.min(room, remaining.getCount());
                slot.grow(move);
                remaining.shrink(move);
                this.kelvinInventory.setItem(i, slot);
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (this.kelvinInventory.getItem(i).isEmpty()) {
                int move = Math.min(remaining.getMaxStackSize(), remaining.getCount());
                ItemStack one = remaining.split(move);
                this.kelvinInventory.setItem(i, one);
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return remaining;
    }

    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        if (this.level().isClientSide || !itemEntity.isAlive()) {
            return;
        }
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return;
        }
        int before = stack.getCount();
        ItemStack remaining = this.addItemStackToKelvin(stack);
        itemEntity.setItem(remaining);
        if (remaining.isEmpty()) {
            itemEntity.discard();
        }
        if (remaining.getCount() != before) {
            this.playSound(
                    SoundEvents.ITEM_PICKUP,
                    0.2F,
                    ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        if (this.isDowned()) {
            if (held.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
                this.exitDownedState(serverPlayer);
                return InteractionResult.CONSUME;
            }
            if (player.isShiftKeyDown()) {
                this.openKelvinMenu(serverPlayer);
                return InteractionResult.CONSUME;
            }
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            this.openKelvinMenu(serverPlayer);
            return InteractionResult.CONSUME;
        }
        if (!held.isEmpty()) {
            ItemStack afterDeposit = this.addItemStackToKelvin(held.copy());
            int absorbed = held.getCount() - afterDeposit.getCount();
            if (absorbed > 0) {
                if (!player.getAbilities().instabuild) {
                    held.shrink(absorbed);
                }
                return InteractionResult.CONSUME;
            }
        }
        if (this.shouldEatToReplenishHealth() && this.isEdibleFood(held)) {
            float heal = this.healAmountFromFood(held);
            if (heal > 0.0F) {
                ItemStack one = held.copy();
                one.setCount(1);
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
                this.beginEatingFoodFromHand(one, heal);
                return InteractionResult.CONSUME;
            }
        }
        this.openKelvinMenu(serverPlayer);
        return InteractionResult.CONSUME;
    }

    @Override
    protected void dropEquipment() {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = this.kelvinInventory.getItem(i);
            if (!stack.isEmpty()) {
                this.spawnAtLocation(stack);
                this.kelvinInventory.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private void swapKelvinInventorySlots(int slotA, int slotB) {
        if (slotA == slotB) {
            return;
        }
        ItemStack stackA = this.kelvinInventory.getItem(slotA).copy();
        ItemStack stackB = this.kelvinInventory.getItem(slotB).copy();
        this.kelvinInventory.setItem(slotA, stackB);
        this.kelvinInventory.setItem(slotB, stackA);
    }

    private int findBestSwordInventorySlot() {
        int best = -1;
        double bestDmg = -1.0D;
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack stack = this.kelvinInventory.getItem(i);
            if (!stack.is(ItemTags.SWORDS) || stack.isEmpty()) {
                continue;
            }
            double dmg = sumAddValueModifiers(stack, EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE);
            if (best < 0
                    || dmg > bestDmg + 1.0e-6D
                    || (Math.abs(dmg - bestDmg) < 1.0e-6D
                            && stack.getDamageValue() < this.kelvinInventory.getItem(best).getDamageValue())) {
                bestDmg = dmg;
                best = i;
            }
        }
        return best;
    }

    void restoreCombatMainHandSwap() {
        if (this.level().isClientSide || this.protectCombatSwapSourceSlot < 0) {
            return;
        }
        int other = this.protectCombatSwapSourceSlot;
        this.protectCombatSwapSourceSlot = -1;
        this.swapKelvinInventorySlots(0, other);
        this.syncEquipmentFromInventory();
    }

    private static final int PATRON_LAST_HURT_WINDOW_TICKS = 100;
    /** How far Kelvin may be from the patron and still run protect targeting. */
    private static final double PATRON_AGGRO_MAX_DIST_SQR = 96.0D * 96.0D;
    /** Hostile mobs within this horizontal distance of the patron (or Kelvin if alone) are valid protect targets. */
    private static final double PROTECT_HOSTILE_RADIUS_SQR = 32.0D * 32.0D;
    private static final int PROTECT_HOSTILE_SCAN_INTERVAL = 5;

    @Nullable
    private ServerPlayer resolvePatronServerPlayer() {
        Optional<UUID> patronId = this.getProtectPatronId();
        if (patronId.isEmpty() || this.level().getServer() == null) {
            return null;
        }
        ServerPlayer patron = this.level().getServer().getPlayerList().getPlayer(patronId.get());
        if (patron == null || patron.level() != this.level() || !patron.isAlive()) {
            return null;
        }
        return patron;
    }

    /** Zombies, skeletons, slimes, ghasts, etc. — not players, pets, or other Kelvins. */
    private boolean isHostileMobForProtect(LivingEntity living, @Nullable ServerPlayer patron) {
        if (!living.isAlive() || living == this) {
            return false;
        }
        if (living instanceof Player) {
            return false;
        }
        if (living instanceof KelvinEntity) {
            return false;
        }
        if (patron != null && living instanceof TamableAnimal pet && patron.getUUID().equals(pet.getOwnerUUID())) {
            return false;
        }
        if (living instanceof Monster) {
            return true;
        }
        return living.getType().getCategory() == MobCategory.MONSTER;
    }

    /**
     * Re-applies attack target from the patron's {@link LivingEntity#getLastHurtByMob()} so Kelvin keeps
     * defending even if an event was missed or the target was cleared one tick. Threat must be within 32 blocks
     * of the patron.
     */
    private void tickPatronThreatFromLastHurt() {
        if (this.level().isClientSide || !this.isProtectOwner()) {
            return;
        }
        ServerPlayer patron = this.resolvePatronServerPlayer();
        if (patron == null) {
            return;
        }
        if (!this.protectsPlayer(patron.getUUID())) {
            return;
        }
        if (this.distanceToSqr(patron) > PATRON_AGGRO_MAX_DIST_SQR) {
            return;
        }
        LivingEntity threat = patron.getLastHurtByMob();
        if (threat == null || !threat.isAlive() || threat == this || threat.level() != this.level()) {
            return;
        }
        if (!this.isHostileMobForProtect(threat, patron)) {
            return;
        }
        if (Math.min(threat.distanceToSqr(patron), threat.distanceToSqr(this)) > PROTECT_HOSTILE_RADIUS_SQR) {
            return;
        }
        if (patron.tickCount - patron.getLastHurtByMobTimestamp() > PATRON_LAST_HURT_WINDOW_TICKS) {
            return;
        }
        this.setTarget(threat);
    }

    /**
     * When protect mode is on and nothing else has priority, acquire the nearest hostile mob within 32 blocks
     * of the patron or of Kelvin (whichever is closer to the mob). If the patron is not loaded, only Kelvin's
     * position is used.
     */
    private void tickProtectHostilesNearPatron() {
        if (this.level().isClientSide || !this.isProtectOwner() || this.isDowned()) {
            return;
        }
        ServerPlayer patron = this.resolvePatronServerPlayer();

        LivingEntity cur = this.getTarget();
        if (cur != null) {
            if (!cur.isAlive() || cur.level() != this.level()) {
                this.setTarget(null);
            } else if (!this.isHostileMobForProtect(cur, patron)) {
                this.setTarget(null);
            } else {
                double dPatron = patron != null ? cur.distanceToSqr(patron) : Double.MAX_VALUE;
                double dKelvin = cur.distanceToSqr(this);
                if (Math.min(dPatron, dKelvin) > PROTECT_HOSTILE_RADIUS_SQR) {
                    this.setTarget(null);
                }
            }
        }
        if (this.getTarget() != null) {
            return;
        }
        if (this.tickCount % PROTECT_HOSTILE_SCAN_INTERVAL != 0) {
            return;
        }

        Vec3 p = patron != null ? patron.position() : this.position();
        Vec3 k = this.position();
        AABB search = new AABB(p, k).inflate(32.0D, 24.0D, 32.0D);
        List<LivingEntity> candidates = this.level().getEntitiesOfClass(LivingEntity.class, search, e -> true);
        LivingEntity nearest = null;
        double bestDistSqr = Double.MAX_VALUE;
        for (LivingEntity mob : candidates) {
            if (mob == this || !mob.isAlive() || mob.level() != this.level()) {
                continue;
            }
            if (!this.isHostileMobForProtect(mob, patron)) {
                continue;
            }
            if (!this.canAttack(mob)) {
                continue;
            }
            double dKelvin = mob.distanceToSqr(this);
            double score = patron != null ? Math.min(mob.distanceToSqr(patron), dKelvin) : dKelvin;
            if (score > PROTECT_HOSTILE_RADIUS_SQR) {
                continue;
            }
            if (score < bestDistSqr) {
                nearest = mob;
                bestDistSqr = score;
            }
        }
        if (nearest != null) {
            this.setTarget(nearest);
        }
    }

    /**
     * Drops ally players as targets, and while Kelvin has any non-player combat target, pulls the best
     * sword from his inventory into the main-hand slot (not only during protect mode).
     */
    private void tickCombatMainHandAndAllyTargets() {
        if (this.goMineController.isActive()) {
            return;
        }
        LivingEntity target = this.getTarget();
        if (target instanceof Player player && this.isAllyPlayer(player)) {
            this.setTarget(null);
            this.restoreCombatMainHandSwap();
            return;
        }
        if (target == null || !target.isAlive() || target.level() != this.level()) {
            if (target != null && (!target.isAlive() || target.level() != this.level())) {
                this.setTarget(null);
            }
            this.restoreCombatMainHandSwap();
            return;
        }
        if (target instanceof Player) {
            return;
        }
        if (this.isUsingItem()) {
            return;
        }
        ItemStack main = this.kelvinInventory.getItem(0);
        if (main.is(ItemTags.SWORDS)) {
            return;
        }
        int swordSlot = this.findBestSwordInventorySlot();
        if (swordSlot < 0 || swordSlot == 0) {
            return;
        }
        if (this.protectCombatSwapSourceSlot >= 0 && this.protectCombatSwapSourceSlot != swordSlot) {
            this.restoreCombatMainHandSwap();
            swordSlot = this.findBestSwordInventorySlot();
            if (swordSlot <= 0 || this.kelvinInventory.getItem(0).is(ItemTags.SWORDS)) {
                return;
            }
        }
        if (this.protectCombatSwapSourceSlot >= 0) {
            return;
        }
        this.protectCombatSwapSourceSlot = swordSlot;
        this.swapKelvinInventorySlots(0, swordSlot);
        this.syncEquipmentFromInventory();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(TAG_DOWNED, this.isDowned());
        tag.putBoolean(TAG_PROTECT_OWNER, this.isProtectOwner());
        this.getProtectPatronId().ifPresent(uuid -> tag.putUUID(TAG_PROTECT_PATRON, uuid));
        if (this.bondedOwnerUuid != null) {
            tag.putUUID(TAG_BONDED_OWNER, this.bondedOwnerUuid);
        }
        this.getFollowingId().ifPresent(uuid -> tag.putUUID(TAG_FOLLOWING, uuid));
        ListTag list = new ListTag();
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            CompoundTag stackTag = new CompoundTag();
            this.kelvinInventory.getItem(i).save(stackTag);
            list.add(stackTag);
        }
        tag.put(TAG_ITEMS, list);
        this.goMineController.addSaveData(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(TAG_FOLLOWING)) {
            this.entityData.set(DATA_FOLLOWING, Optional.of(tag.getUUID(TAG_FOLLOWING)));
        } else {
            this.entityData.set(DATA_FOLLOWING, Optional.empty());
        }
        if (tag.hasUUID(TAG_BONDED_OWNER)) {
            this.bondedOwnerUuid = tag.getUUID(TAG_BONDED_OWNER);
        } else {
            this.bondedOwnerUuid = null;
        }
        this.entityData.set(DATA_PROTECT_OWNER, tag.getBoolean(TAG_PROTECT_OWNER));
        if (tag.hasUUID(TAG_PROTECT_PATRON)) {
            this.entityData.set(DATA_PROTECT_PATRON, Optional.of(tag.getUUID(TAG_PROTECT_PATRON)));
        } else if (tag.getBoolean(TAG_PROTECT_OWNER) && this.getFollowingId().isPresent()) {
            this.entityData.set(DATA_PROTECT_PATRON, this.getFollowingId());
        } else {
            this.entityData.set(DATA_PROTECT_PATRON, Optional.empty());
        }
        if (this.bondedOwnerUuid == null) {
            this.bondedOwnerUuid = this.getProtectPatronId().or(this::getFollowingId).orElse(null);
        }
        if (tag.contains(TAG_ITEMS, 9)) {
            ListTag list = tag.getList(TAG_ITEMS, 10);
            for (int i = 0; i < INVENTORY_SIZE && i < list.size(); i++) {
                this.kelvinInventory.setItem(i, ItemStack.of(list.getCompound(i)));
            }
        }
        if (!this.level().isClientSide) {
            this.setPersistenceRequired();
            this.syncEquipmentFromInventory();
            if (tag.getBoolean(TAG_DOWNED)) {
                this.restoreDownedAfterLoad();
            }
            if (!this.hasCustomName()) {
                this.setCustomName(Component.literal("Kelvin"));
                this.setCustomNameVisible(true);
            }
            if (this.level() instanceof ServerLevel serverLevel) {
                this.goMineController.readSaveData(tag, serverLevel);
                if (this.goMineController.isActive()) {
                    UUID u = this.goMineController.getOrdererUuid();
                    if (u != null && this.level().getServer() != null) {
                        ServerPlayer reconnect = this.level().getServer().getPlayerList().getPlayer(u);
                        if (reconnect != null && reconnect.level() == this.level()) {
                            ModNetwork.sendGoMineHud(
                                    reconnect,
                                    true,
                                    this.goMineController.getSessionCollectedTotal(),
                                    this.goMineController.getDepositChest(),
                                    this.goMineController.getTargetBlock());
                        }
                    }
                }
            }
        }
    }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions) {
        return 1.62F;
    }
}
