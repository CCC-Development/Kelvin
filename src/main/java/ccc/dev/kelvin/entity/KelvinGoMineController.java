package ccc.dev.kelvin.entity;

import ccc.dev.kelvin.network.ModNetwork;
import ccc.dev.kelvin.world.inventory.KelvinInventoryMenu;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.slf4j.Logger;

/**
 * Server-side: Kelvin seeks and breaks a chosen block type, accumulates drops into a bound chest (stacks of 64 by
 * default, or each piece of ancient debris as soon as it is mined), and reports totals to the ordering player. For ore
 * jobs (anything except cobble fill), he uses full loaded-chunk knowledge — wide box scan, generated-chunk spiral, and
 * stair-tunnel carving when the navigator has no path, or when a path is “active” but Kelvin is horizontally
 * colliding (e.g. wrong ore or stone in the way) so carving still starts. Nether ancient debris instead uses a Y
 * 12–15 straight-line strip tunnel (no remote chunk spiral), corridor-only debris detection, then the same deposit
 * rules. If lava shows up ahead in the tunnel wedge, he turns 90° toward the clearest horizontal escape.
 */
public final class KelvinGoMineController {
    private static final Logger GO_MINE_LOG = LogUtils.getLogger();
    private static final AtomicBoolean LOGGED_GO_MINE_CHUNK_CCE = new AtomicBoolean();

    /**
     * Bidirectional ore ↔ deepslate-ore pairing. When the player targets either variant, Kelvin
     * accepts and mines both — they drop the same item and generate in the same vein. Without this,
     * deepslate diamond ore (DEEPSLATE_DIAMOND_ORE) is invisible when targetBlock is DIAMOND_ORE and
     * vice-versa, causing Kelvin to ignore ores sitting right next to him in the deep cave layer.
     */
    private static final java.util.Map<Block, Block> DEEPSLATE_ORE_PAIRS;
    static {
        java.util.Map<Block, Block> m = new java.util.HashMap<>();
        m.put(Blocks.COAL_ORE,      Blocks.DEEPSLATE_COAL_ORE);
        m.put(Blocks.DEEPSLATE_COAL_ORE, Blocks.COAL_ORE);
        m.put(Blocks.IRON_ORE,      Blocks.DEEPSLATE_IRON_ORE);
        m.put(Blocks.DEEPSLATE_IRON_ORE, Blocks.IRON_ORE);
        m.put(Blocks.COPPER_ORE,    Blocks.DEEPSLATE_COPPER_ORE);
        m.put(Blocks.DEEPSLATE_COPPER_ORE, Blocks.COPPER_ORE);
        m.put(Blocks.GOLD_ORE,      Blocks.DEEPSLATE_GOLD_ORE);
        m.put(Blocks.DEEPSLATE_GOLD_ORE, Blocks.GOLD_ORE);
        m.put(Blocks.REDSTONE_ORE,  Blocks.DEEPSLATE_REDSTONE_ORE);
        m.put(Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.REDSTONE_ORE);
        m.put(Blocks.EMERALD_ORE,   Blocks.DEEPSLATE_EMERALD_ORE);
        m.put(Blocks.DEEPSLATE_EMERALD_ORE, Blocks.EMERALD_ORE);
        m.put(Blocks.LAPIS_ORE,     Blocks.DEEPSLATE_LAPIS_ORE);
        m.put(Blocks.DEEPSLATE_LAPIS_ORE, Blocks.LAPIS_ORE);
        m.put(Blocks.DIAMOND_ORE,   Blocks.DEEPSLATE_DIAMOND_ORE);
        m.put(Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DIAMOND_ORE);
        DEEPSLATE_ORE_PAIRS = java.util.Collections.unmodifiableMap(m);
    }
    private static final double MINE_REACH_SQR = 5.5 * 5.5;
    /** Eye-to-target (block center), slightly generous vs survival reach so tall columns still work. */
    private static final double MINE_REACH_EYE_SQR = 6.0 * 6.0;
    private static final double CHEST_REACH_SQR = 4.0 * 4.0;
    private static final int SEARCH_RADIUS = 36;
    private static final int SEARCH_Y = 24;
    /** Loaded-chunk “x-ray” box for ore jobs (not cobble fill): server scans blocks Kelvin could not see yet. */
    private static final int SEARCH_RADIUS_LOOSE = 48;
    private static final int SEARCH_Y_LOOSE = 36;
    /** Prefer horizontal / high layers: penalises mining deep under Kelvin's feet (stops pit-spiral patterns). */
    private static final int GO_MINE_BELOW_FEET_PENALTY = 12;
    private static final int GO_MINE_SAME_COLUMN_UNDER_PENALTY = 28;
    private static final int GO_MINE_DEEP_UNDER_SURFACE_DIVISOR = 4;
    private static final int PATH_STALL_SKIP_TICKS = 70;
    /** Nether ancient debris: start carving toward the ore sooner when vanilla path has no route. */
    private static final int PATH_STALL_SKIP_TICKS_NETHER_DEBRIS = 26;
    /** Iron and other ores: pathfinder often has no route through solid rock — carve quickly.
     *  Kept low (5) so that after each repick only ~1-2 blocks of backward navigation can occur
     *  before tunneling resumes, preventing Kelvin from walking in circles. In open caves the
     *  pathfinder succeeds (stall never fires) so cave ores are still mined normally. */
    private static final int PATH_STALL_SKIP_TICKS_AGGRESSIVE_ORE = 5;
    private static final int PROXIMITY_STUCK_SKIP_TICKS = 100;
    private static final int GO_MINE_SKIPPED_GOALS_CAP = 64;
    private static final int DEPOSIT_PATH_STALL_TICKS = 10;
    private static final int DEPOSIT_PATH_STALL_TICKS_VERTICAL = 6;
    private static final int DEPOSIT_COLLISION_STALL_TICKS = 12;
    private static final int DEPOSIT_COLLISION_STALL_TICKS_VERTICAL = 8;
    /** Chest-return mining: allow breaking overhead / nearby without strict eye reach (staircase out of caves). */
    private static final int DEPOSIT_ESCAPE_MAX_HORIZ_OFF = 2;
    private static final int DEPOSIT_ESCAPE_MIN_DY = -1;
    private static final int DEPOSIT_ESCAPE_MAX_DY = 12;
    /**
     * Chunk offsets (dx, dz) from an anchor chunk, sorted by Chebyshev distance so nearer chunks are tried first.
     * Ores appear at their real locations once chunks exist — same outcome as using the world seed for generation,
     * without any client-visible “seed lookup”.
     */
    private static final int[][] GO_MINE_CHUNK_OFFSETS = buildGoMineChunkOffsetSpiral(12);
    private static final int[][] GO_MINE_CHUNK_OFFSETS_WIDE_ORE = buildGoMineChunkOffsetSpiral(16);
    private static final int[][] GO_MINE_CHUNK_OFFSETS_NETHER_DEBRIS = buildGoMineChunkOffsetSpiral(20);
    /** Nether ancient debris branch-mining strip (feet block Y). */
    private static final int NETHER_DEBRIS_STRIP_Y_MIN = 12;
    private static final int NETHER_DEBRIS_STRIP_Y_MAX = 15;
    /** Forward steps along strip axis to scan for lava (feet-relative wedge). */
    private static final int NETHER_STRIP_LAVA_SCAN_FORWARD = 10;
    private static final int NETHER_STRIP_LAVA_SCAN_SIDE = 2;
    /** Ticks after a lava reroute before scanning again (avoids spinning when surrounded). */
    private static final int NETHER_STRIP_LAVA_REROUTE_COOLDOWN_TICKS = 40;

    private static int[][] buildGoMineChunkOffsetSpiral(int maxChebyshev) {
        record O(int dx, int dz, int c) {}
        List<O> list = new ArrayList<>((2 * maxChebyshev + 1) * (2 * maxChebyshev + 1));
        for (int dx = -maxChebyshev; dx <= maxChebyshev; dx++) {
            for (int dz = -maxChebyshev; dz <= maxChebyshev; dz++) {
                list.add(new O(dx, dz, Math.max(Math.abs(dx), Math.abs(dz))));
            }
        }
        list.sort(Comparator.comparingInt(o -> o.c));
        int[][] out = new int[list.size()][2];
        for (int i = 0; i < list.size(); i++) {
            O o = list.get(i);
            out[i][0] = o.dx;
            out[i][1] = o.dz;
        }
        return out;
    }

    private static final String TAG_SWAP_PEER = "KelvinGoMineSwapPeer";
    private static final String TAG_ACTIVE = "KelvinGoMineActive";
    private static final String TAG_BLOCK = "KelvinGoMineBlock";
    private static final String TAG_ORDERER = "KelvinGoMineOrderer";
    private static final String TAG_CHEST = "KelvinGoMineChest";
    private static final String TAG_TOTAL = "KelvinGoMineTotal";
    private static final String TAG_NETHER_STRIP_DIR = "KelvinGoMineNetherStripDir";

    private final KelvinEntity kelvin;

    @Nullable
    private Block targetBlock;
    @Nullable
    private UUID ordererUuid;
    @Nullable
    private BlockPos depositChest;
    private Set<Item> depositItems = Set.of();
    private int sessionCollectedTotal;
    @Nullable
    private BlockPos breakTarget;
    private float breakProgress;
    @Nullable
    private BlockPos walkTargetBlock;
    private int searchThrottle;
    @Nullable
    private ChunkPos goMineRemoteSearchAnchor;
    private int goMineRemoteSearchIndex;
    /** After {@link #GO_MINE_CHUNK_OFFSETS} has been fully scanned without a hit. */
    private boolean goMineRemoteProspectExhausted;
    /** When true, path to {@link #depositChest} without requiring a full stack, then report no ore and stop. */
    private boolean goMineFailureReturnToChest;
    /** While {@code >= 0}, main-hand slot 0 was swapped with this slot for mining. */
    private int goMineHandSwapPeer = -1;
    /** Prospects that could not be reached (path or vertical); cleared when cap exceeded. */
    private final Set<BlockPos> goMineSkippedGoals = new HashSet<>();
    private int goMineSeekStallTicks;
    private int goMineStuckNearGoalTicks;
    private int goMinePillarCooldown;
    /** When hauling to the deposit chest, navigation stuck / wedged counters (bridge, pillar, mine up). */
    private int goMineDepositPathlessTicks;
    private int goMineDepositWedgedTicks;
    /** Path claims active but distance to chest barely decreases (snagged on geometry). */
    private int goMineDepositNoProgressTicks;
    private double goMineDepositLastDistSqr = -1.0D;
    /** When set, {@link #tickBreaking} uses a looser reach box for mining toward the deposit chest. */
    private boolean depositEscapeBreakActive;
    /** Carving a 2-high tunnel toward {@link #goMineTunnelGoalPos} when pathfinding cannot reach nether debris. */
    private boolean goMineStrictTunnelActive;
    @Nullable
    private BlockPos goMineTunnelGoalPos;
    /**
     * Set to the position of a just-broken tunnel block. While non-null, {@link #tickSeekAndWalk} waits for
     * navigation to complete (Kelvin physically stepping into the cleared cell) before picking the next break.
     * This ensures he actually advances along the staircase rather than standing still and tunnelling remotely.
     */
    @Nullable
    private BlockPos goMineTunnelNavTarget;
    /** Chunk spiral used for the current remote search (wider for nether ancient debris). */
    @Nullable
    private int[][] goMineRemoteChunkOffsetTable;
    /** Horizontal tunnel axis for Nether ancient debris strip mining. */
    @Nullable
    private Direction goMineNetherStripDir;
    /** When true, {@link #walkTargetBlock} is a synthetic forward anchor, not a debris block. */
    private boolean goMineNetherStripMarch;
    /** After rerouting strip for lava; while {@code > 0}, lava wedge scan is skipped. */
    private int goMineNetherLavaRerouteCooldown;

    public KelvinGoMineController(KelvinEntity kelvin) {
        this.kelvin = kelvin;
    }

    public boolean isActive() {
        return this.targetBlock != null;
    }

    public boolean isOrderedBy(UUID playerId) {
        return this.isActive() && playerId.equals(this.ordererUuid);
    }

    @Nullable
    public UUID getOrdererUuid() {
        return this.ordererUuid;
    }

    public int getSessionCollectedTotal() {
        return this.sessionCollectedTotal;
    }

    @Nullable
    public Block getTargetBlock() {
        return this.targetBlock;
    }

    @Nullable
    public BlockPos getDepositChest() {
        return this.depositChest;
    }

    /** Start mining {@code block} for {@code player}; fails if Kelvin cannot mine or player is too far. */
    public boolean start(ServerLevel level, ServerPlayer player, Block block) {
        if (this.kelvin.level() != level || this.kelvin.isDowned()) {
            return false;
        }
        if (player.distanceToSqr(this.kelvin) > KelvinEntity.PLAYER_INTERACTION_MAX_DIST_SQR) {
            return false;
        }
        this.kelvin.restoreCombatMainHandSwap();
        this.restoreGoMineHandTool();
        BlockState def = block.defaultBlockState();
        if (def.isAir() || def.getDestroySpeed(level, this.kelvin.blockPosition()) < 0.0F) {
            player.sendSystemMessage(
                    Component.literal("Kelvin cannot mine that block.").withStyle(ChatFormatting.RED));
            return false;
        }
        this.targetBlock = block;
        this.ordererUuid = player.getUUID();
        this.depositChest = null;
        this.applyGoMineSessionToolLock(level, block);
        this.depositItems = computeDepositItems(level, block, this.kelvin);
        this.sessionCollectedTotal = 0;
        this.breakTarget = null;
        this.breakProgress = 0.0F;
        this.walkTargetBlock = null;
        this.searchThrottle = 0;
        this.clearGoMineRemoteSearchState();
        this.goMineFailureReturnToChest = false;
        this.goMineSkippedGoals.clear();
        this.goMineSeekStallTicks = 0;
        this.goMineStuckNearGoalTicks = 0;
        this.goMinePillarCooldown = 0;
        this.resetDepositPathingAssistState();
        this.depositEscapeBreakActive = false;
        this.clearStrictTunnelTowardGoal();
        this.goMineNetherStripDir = null;
        this.goMineNetherStripMarch = false;
        this.goMineNetherLavaRerouteCooldown = 0;
        this.kelvin.getNavigation().stop();
        player.sendSystemMessage(
                Component.literal("Kelvin is mining ")
                        .append(Component.translatable(block.getDescriptionId()))
                        .append(Component.literal(". Use the summoner on a chest to set deposits."))
                        .withStyle(ChatFormatting.GREEN));
        ModNetwork.sendGoMineHud(player, true, this.sessionCollectedTotal, this.depositChest, block);
        return true;
    }

    public void stop(@Nullable ServerPlayer notifyPlayer) {
        if (!this.isActive()) {
            return;
        }
        ServerPlayer toNotify = notifyPlayer;
        if (toNotify == null && this.kelvin.level() instanceof ServerLevel sl && this.ordererUuid != null) {
            toNotify = sl.getServer().getPlayerList().getPlayer(this.ordererUuid);
        }
        this.restoreGoMineHandTool();
        this.targetBlock = null;
        this.ordererUuid = null;
        this.depositChest = null;
        this.depositItems = Set.of();
        this.breakTarget = null;
        this.breakProgress = 0.0F;
        this.walkTargetBlock = null;
        this.searchThrottle = 0;
        this.clearGoMineRemoteSearchState();
        this.goMineFailureReturnToChest = false;
        this.goMineSkippedGoals.clear();
        this.goMineSeekStallTicks = 0;
        this.goMineStuckNearGoalTicks = 0;
        this.goMinePillarCooldown = 0;
        this.resetDepositPathingAssistState();
        this.depositEscapeBreakActive = false;
        this.clearStrictTunnelTowardGoal();
        this.goMineNetherStripDir = null;
        this.goMineNetherStripMarch = false;
        this.goMineNetherLavaRerouteCooldown = 0;
        this.kelvin.getNavigation().stop();
        if (toNotify != null) {
            ModNetwork.sendGoMineHud(toNotify, false, 0, null, null);
        }
    }

    /**
     * Binds deposit chest if the player ordered this Kelvin's mining job and the block has item storage.
     *
     * @return {@code true} if handled (bound or error message)
     */
    public boolean trySetDepositChest(ServerLevel level, ServerPlayer player, BlockPos chestPos) {
        if (!this.isActive() || !player.getUUID().equals(this.ordererUuid)) {
            return false;
        }
        if (!level.isLoaded(chestPos)) {
            return false;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(chestPos)) > 8.0 * 8.0) {
            player.sendSystemMessage(
                    Component.literal("Stand closer to the chest.").withStyle(ChatFormatting.GRAY));
            return true;
        }
        if (this.kelvin.level() != level || this.kelvin.blockPosition().distSqr(chestPos) > 160.0 * 160.0) {
            player.sendSystemMessage(
                    Component.literal("That chest is too far from Kelvin.").withStyle(ChatFormatting.RED));
            return true;
        }
        BlockEntity be = level.getBlockEntity(chestPos);
        if (be == null) {
            player.sendSystemMessage(
                    Component.literal("That block cannot store items.").withStyle(ChatFormatting.RED));
            return true;
        }
        if (!be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent()) {
            player.sendSystemMessage(
                    Component.literal("Kelvin needs a chest or barrel (or similar storage).")
                            .withStyle(ChatFormatting.RED));
            return true;
        }
        this.depositChest = chestPos.immutable();
        player.sendSystemMessage(
                Component.literal("Kelvin will deposit mined items here.").withStyle(ChatFormatting.GREEN));
        ModNetwork.sendGoMineHud(player, true, this.sessionCollectedTotal, this.depositChest, this.targetBlock);
        return true;
    }

    public void tickServer() {
        if (!this.isActive()) {
            return;
        }
        Level level = this.kelvin.level();
        if (!(level instanceof ServerLevel serverLevel) || this.kelvin.isDowned()) {
            return;
        }
        Player orderer = this.ordererUuid != null ? level.getPlayerByUUID(this.ordererUuid) : null;
        if (!(orderer instanceof ServerPlayer serverOrderer) || !orderer.isAlive() || orderer.level() != level) {
            this.stop(null);
            return;
        }
        if (this.kelvin.isUsingItem() || this.kelvin.getVehicle() != null) {
            return;
        }

        if (this.breakTarget != null) {
            this.tickBreaking(serverLevel, serverOrderer);
            return;
        }

        if (this.depositChest != null) {
            boolean fullInv = this.countMatchingInInventory() >= this.depositHaulItemThreshold();
            if (fullInv) {
                this.goMineFailureReturnToChest = false;
                this.tickDepositTrip(serverLevel, serverOrderer, false);
                return;
            }
            if (this.goMineFailureReturnToChest) {
                this.tickDepositTrip(serverLevel, serverOrderer, true);
                return;
            }
        }

        this.tickSeekAndWalk(serverLevel, serverOrderer);
    }

    private void finishNoOreFoundMessageAndStop(ServerPlayer orderer) {
        this.clearGoMineRemoteSearchState();
        this.goMineFailureReturnToChest = false;
        Block tb = this.targetBlock;
        if (tb != null) {
            orderer.sendSystemMessage(
                    Component.literal("Kelvin couldn't find any ")
                            .append(Component.translatable(tb.getDescriptionId()))
                            .append(Component.literal(" nearby."))
                            .withStyle(ChatFormatting.GRAY));
        } else {
            orderer.sendSystemMessage(
                    Component.literal("Kelvin couldn't find any of that ore nearby.").withStyle(ChatFormatting.GRAY));
        }
        this.stop(orderer);
    }

    private void tickDepositTrip(ServerLevel level, ServerPlayer orderer, boolean returnBecauseNoOreFound) {
        BlockPos chest = this.depositChest;
        if (chest == null || !level.isLoaded(chest)) {
            if (returnBecauseNoOreFound) {
                this.finishNoOreFoundMessageAndStop(orderer);
                return;
            }
            orderer.sendSystemMessage(
                    Component.literal("Kelvin's deposit chest was unloaded or removed.").withStyle(ChatFormatting.GOLD));
            this.depositChest = null;
            ModNetwork.sendGoMineHud(orderer, true, this.sessionCollectedTotal, null, this.targetBlock);
            return;
        }
        BlockEntity be = level.getBlockEntity(chest);
        if (be == null || !be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent()) {
            if (returnBecauseNoOreFound) {
                this.finishNoOreFoundMessageAndStop(orderer);
                return;
            }
            orderer.sendSystemMessage(
                    Component.literal("Kelvin cannot access the deposit chest anymore.").withStyle(ChatFormatting.GOLD));
            this.depositChest = null;
            ModNetwork.sendGoMineHud(orderer, true, this.sessionCollectedTotal, null, this.targetBlock);
            return;
        }
        double distSqr = this.kelvin.distanceToSqr(Vec3.atCenterOf(chest));
        if (distSqr > CHEST_REACH_SQR) {
            double dyUp = chest.getY() + 0.5 - this.kelvin.getY();
            BlockPos feet = this.kelvin.blockPosition();
            int hdx = Math.abs(chest.getX() - feet.getX());
            int hdz = Math.abs(chest.getZ() - feet.getZ());
            boolean alignedUnderChest = hdx <= 2 && hdz <= 2;
            boolean pathing = this.kelvin.getNavigation().isInProgress();
            if (this.goMineDepositLastDistSqr >= 0.0D && distSqr >= this.goMineDepositLastDistSqr - 0.5D) {
                this.goMineDepositNoProgressTicks++;
            } else {
                this.goMineDepositNoProgressTicks = 0;
            }
            this.goMineDepositLastDistSqr = distSqr;
            boolean pathSnagged =
                    !pathing || this.kelvin.horizontalCollision || this.goMineDepositNoProgressTicks >= 14;
            if (pathSnagged) {
                this.goMineDepositPathlessTicks++;
            } else {
                this.goMineDepositPathlessTicks = 0;
            }
            if (this.kelvin.horizontalCollision) {
                this.goMineDepositWedgedTicks++;
            } else {
                this.goMineDepositWedgedTicks = 0;
            }
            int pathNeed = dyUp > 3.5D ? DEPOSIT_PATH_STALL_TICKS_VERTICAL : DEPOSIT_PATH_STALL_TICKS;
            int collNeed = dyUp > 3.5D ? DEPOSIT_COLLISION_STALL_TICKS_VERTICAL : DEPOSIT_COLLISION_STALL_TICKS;
            boolean depositStuck =
                    this.goMineDepositPathlessTicks >= pathNeed
                            || this.goMineDepositWedgedTicks >= collNeed
                            || this.goMineDepositNoProgressTicks >= 28;
            boolean forceVerticalAssist = alignedUnderChest && dyUp > 1.2D;
            if (depositStuck || forceVerticalAssist) {
                if (this.tryDepositClimbAssist(level, chest)) {
                    this.resetDepositPathingAssistState();
                    return;
                }
            }
            this.navigateDepositTowardChestHorizontalFirst(chest, dyUp, alignedUnderChest);
            return;
        }
        this.resetDepositPathingAssistState();
        this.kelvin.getNavigation().stop();
        if (returnBecauseNoOreFound) {
            this.finishNoOreFoundMessageAndStop(orderer);
            return;
        }
        this.playChestDepositOpenVisual(level, chest);
        boolean deposited = this.depositUpToHaulCap(level, be);
        this.playChestDepositCloseVisual(level, chest);
        if (deposited) {
            ModNetwork.sendGoMineHud(orderer, true, this.sessionCollectedTotal, this.depositChest, this.targetBlock);
        }
    }

    /**
     * Stair / pillar / mine toward the deposit chest when pathing cannot raise Kelvin to the chest Y (e.g. pit under
     * the chest).
     */
    private boolean tryDepositClimbAssist(ServerLevel level, BlockPos chest) {
        if (this.tryBeginDepositStairMine(level, chest)) {
            return true;
        }
        if (this.tryBeginDepositAscendMine(level, chest)) {
            return true;
        }
        if (this.tryGoMineScaffold(level, chest)) {
            return true;
        }
        BlockPos mine = this.findGoMineBlockingTowardChest(level, chest);
        if (mine == null) {
            mine = this.findGoMineCeilingBlockAboveFeet(level, chest.getY());
        }
        if (mine != null && !this.isProtectedDepositStoragePos(mine)) {
            this.depositEscapeBreakActive = true;
            this.breakTarget = mine.immutable();
            this.breakProgress = 0.0F;
            this.kelvin.getNavigation().stop();
            return true;
        }
        return false;
    }

    /**
     * Path toward the chest. When already under the chest column but below it, never ask the navigator for the
     * chest's Y (impossible straight climb) — stay on Kelvin's Y until climb assist raises him.
     */
    private void navigateDepositTowardChestHorizontalFirst(
            BlockPos chest, double dyUp, boolean alignedUnderChest) {
        double hx = chest.getX() + 0.5 - this.kelvin.getX();
        double hz = chest.getZ() + 0.5 - this.kelvin.getZ();
        double horizSqr = hx * hx + hz * hz;
        if (alignedUnderChest && dyUp > 1.0D) {
            this.kelvin
                    .getNavigation()
                    .moveTo(chest.getX() + 0.5, this.kelvin.getY(), chest.getZ() + 0.5, 1.25D);
            return;
        }
        if (dyUp > 5.0 && horizSqr > 3.5 * 3.5) {
            this.kelvin
                    .getNavigation()
                    .moveTo(chest.getX() + 0.5, this.kelvin.getY(), chest.getZ() + 0.5, 1.25D);
        } else {
            this.kelvin.getNavigation().moveTo(chest.getX() + 0.5, chest.getY(), chest.getZ() + 0.5, 1.2D);
        }
    }

    private void playChestDepositOpenVisual(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        Block block = st.getBlock();
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.ENDER_CHEST) {
            level.playSound(
                    null,
                    pos,
                    block == Blocks.ENDER_CHEST ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.CHEST_OPEN,
                    SoundSource.BLOCKS,
                    0.5F,
                    level.random.nextFloat() * 0.1F + 0.9F);
            level.blockEvent(pos, block, 1, 1);
        } else if (block instanceof BarrelBlock && st.hasProperty(BarrelBlock.OPEN)) {
            level.playSound(null, pos, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.1F + 0.9F);
            level.setBlock(pos, st.setValue(BarrelBlock.OPEN, true), Block.UPDATE_ALL_IMMEDIATE);
        }
    }

    private void playChestDepositCloseVisual(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        Block block = st.getBlock();
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.ENDER_CHEST) {
            level.playSound(
                    null,
                    pos,
                    block == Blocks.ENDER_CHEST ? SoundEvents.ENDER_CHEST_CLOSE : SoundEvents.CHEST_CLOSE,
                    SoundSource.BLOCKS,
                    0.5F,
                    level.random.nextFloat() * 0.1F + 0.9F);
            level.blockEvent(pos, block, 1, 0);
        } else if (block instanceof BarrelBlock && st.hasProperty(BarrelBlock.OPEN)) {
            level.playSound(null, pos, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.5F, level.random.nextFloat() * 0.1F + 0.9F);
            level.setBlock(pos, st.setValue(BarrelBlock.OPEN, false), Block.UPDATE_ALL_IMMEDIATE);
        }
    }

    /** How many matching deposit items must be held before Kelvin walks to the chest (1 per debris when applicable). */
    private int depositHaulItemThreshold() {
        if (this.depositChest != null && this.targetBlock == Blocks.ANCIENT_DEBRIS) {
            return 1;
        }
        return 64;
    }

    private void clearStrictTunnelTowardGoal() {
        this.goMineStrictTunnelActive = false;
        this.goMineTunnelGoalPos = null;
        this.goMineTunnelNavTarget = null;
    }

    private boolean isAncientDebrisNetherJob(ServerLevel level) {
        return this.targetBlock == Blocks.ANCIENT_DEBRIS && level.dimension() == Level.NETHER;
    }

    private void ensureNetherStripDirection(ServerLevel level) {
        if (!this.isAncientDebrisNetherJob(level)) {
            return;
        }
        if (this.goMineNetherStripDir == null) {
            this.goMineNetherStripDir = Direction.from2DDataValue(level.random.nextInt(4));
        }
    }

    private boolean isWalkTargetStillValid(ServerLevel level) {
        if (this.walkTargetBlock == null) {
            return true;
        }
        if (this.goMineNetherStripMarch) {
            if (!level.isLoaded(this.walkTargetBlock)) {
                return false;
            }
            return !this.isNetherStripMarchGoalStale(this.kelvin.blockPosition(), this.walkTargetBlock);
        }
        return this.isValidProspect(level, this.walkTargetBlock);
    }

    /**
     * Synthetic march anchor is stale when Kelvin has caught up (or passed) so we place a new forward marker along the
     * strip axis.
     */
    private boolean isNetherStripMarchGoalStale(BlockPos feet, BlockPos goal) {
        if (this.goMineNetherStripDir == null) {
            return true;
        }
        int fx = goal.getX() - feet.getX();
        int fz = goal.getZ() - feet.getZ();
        int forward = fx * this.goMineNetherStripDir.getStepX() + fz * this.goMineNetherStripDir.getStepZ();
        return forward < 2;
    }

    private void pickNetherStripWalkTarget(ServerLevel level) {
        this.ensureNetherStripDirection(level);
        if (this.goMineNetherStripDir == null) {
            return;
        }
        BlockPos debris = this.findAncientDebrisInStripCorridor(level);
        if (debris != null) {
            this.walkTargetBlock = debris;
            this.goMineNetherStripMarch = false;
            return;
        }
        this.walkTargetBlock = this.computeNetherStripMarchGoal(level);
        this.goMineNetherStripMarch = this.walkTargetBlock != null;
    }

    @Nullable
    private BlockPos computeNetherStripMarchGoal(ServerLevel level) {
        if (this.goMineNetherStripDir == null) {
            return null;
        }
        BlockPos feet = this.kelvin.blockPosition();
        int stripY = Mth.clamp(feet.getY(), NETHER_DEBRIS_STRIP_Y_MIN, NETHER_DEBRIS_STRIP_Y_MAX);
        Direction dir = this.goMineNetherStripDir;
        for (int dist = 8; dist >= 2; dist -= 2) {
            BlockPos g =
                    new BlockPos(
                            feet.getX() + dir.getStepX() * dist,
                            stripY,
                            feet.getZ() + dir.getStepZ() * dist);
            if (level.isLoaded(g)) {
                return g;
            }
        }
        return new BlockPos(
                feet.getX() + dir.getStepX() * 4,
                stripY,
                feet.getZ() + dir.getStepZ() * 4);
    }

    @Nullable
    private BlockPos findAncientDebrisInStripCorridor(ServerLevel level) {
        if (this.goMineNetherStripDir == null || this.targetBlock != Blocks.ANCIENT_DEBRIS) {
            return null;
        }
        BlockPos feet = this.kelvin.blockPosition();
        Direction dir = this.goMineNetherStripDir;
        Direction side = dir.getClockWise(Direction.Axis.Y);
        int bestScore = Integer.MAX_VALUE;
        BlockPos best = null;
        for (int forward = -1; forward <= 40; forward++) {
            BlockPos base = feet.relative(dir, forward);
            for (int sideOff = -1; sideOff <= 1; sideOff++) {
                BlockPos c = sideOff == 0 ? base : base.relative(side, sideOff);
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = c.above(dy);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    if (this.goMineSkippedGoals.contains(p) || this.isProtectedDepositStoragePos(p)) {
                        continue;
                    }
                    BlockState st = level.getBlockState(p);
                    if (!st.is(Blocks.ANCIENT_DEBRIS) || st.getDestroySpeed(level, p) < 0.0F) {
                        continue;
                    }
                    int score = forward * 6 + Math.abs(sideOff) * 4 + Math.abs(dy) * 2;
                    if (forward < 0) {
                        score += 80;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = p.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean isNetherStripClearableBlock(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || this.isProtectedDepositStoragePos(pos)) {
            return false;
        }
        BlockState st = level.getBlockState(pos);
        if (st.isAir() || st.is(Blocks.BEDROCK) || st.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        return true;
    }

    private boolean isNetherStripTunnelableBlock(ServerLevel level, BlockPos pos) {
        return this.isNetherStripClearableBlock(level, pos);
    }

    /**
     * 2-high carve straight along the strip axis (netherrack etc.) when the march anchor is not valid for
     * {@link #tryStrictTunnelTowardGoal}.
     */
    private boolean tryNetherStripTunnelForward(ServerLevel level) {
        if (!this.isAncientDebrisNetherJob(level) || this.goMineNetherStripDir == null) {
            return false;
        }
        BlockPos feet = this.kelvin.blockPosition();
        int stripY = Mth.clamp(feet.getY(), NETHER_DEBRIS_STRIP_Y_MIN, NETHER_DEBRIS_STRIP_Y_MAX);
        BlockPos synthetic =
                new BlockPos(
                        feet.getX() + this.goMineNetherStripDir.getStepX() * 12,
                        stripY,
                        feet.getZ() + this.goMineNetherStripDir.getStepZ() * 12);
        for (BlockPos p : this.strictTunnelBreakCandidates(feet, synthetic, false)) {
            if (!this.isNetherStripTunnelableBlock(level, p)) {
                continue;
            }
            if (!this.canTunnelMineReach(p)) {
                continue;
            }
            this.goMineStrictTunnelActive = true;
            this.goMineTunnelGoalPos = synthetic.immutable();
            this.breakTarget = p.immutable();
            this.breakProgress = 0.0F;
            this.depositEscapeBreakActive = false;
            return true;
        }
        return false;
    }

    private boolean isBlockOrFluidLava(ServerLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (st.is(Blocks.LAVA)) {
            return true;
        }
        return st.getFluidState().is(FluidTags.LAVA);
    }

    /**
     * Counts lava source/flow in the wedge Kelvin would open next (ahead along {@code stripDir}, a few blocks wide and
     * tall).
     */
    private int countLavaInNetherStripWedge(ServerLevel level, Direction stripDir) {
        BlockPos feet = this.kelvin.blockPosition();
        Direction side = stripDir.getClockWise(Direction.Axis.Y);
        int count = 0;
        for (int f = 0; f <= NETHER_STRIP_LAVA_SCAN_FORWARD; f++) {
            BlockPos base = feet.relative(stripDir, f);
            for (int s = -NETHER_STRIP_LAVA_SCAN_SIDE; s <= NETHER_STRIP_LAVA_SCAN_SIDE; s++) {
                BlockPos c = s == 0 ? base : base.relative(side, s);
                for (int dy = -2; dy <= 4; dy++) {
                    BlockPos p = c.above(dy);
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    if (this.isBlockOrFluidLava(level, p)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private boolean netherStripLavaThreatAlong(ServerLevel level, Direction stripDir) {
        return this.countLavaInNetherStripWedge(level, stripDir) > 0;
    }

    /** Picks one of the other three horizontal axes with the fewest lava in the same wedge scan. */
    private Direction pickBestNetherStripDirAvoidingLava(ServerLevel level, Direction current) {
        Direction first = current.getClockWise(Direction.Axis.Y);
        Direction best = first;
        int bestCount = this.countLavaInNetherStripWedge(level, first);
        Direction d = first.getClockWise(Direction.Axis.Y);
        for (int i = 0; i < 2; i++) {
            int c = this.countLavaInNetherStripWedge(level, d);
            if (c < bestCount) {
                bestCount = c;
                best = d;
            }
            d = d.getClockWise(Direction.Axis.Y);
        }
        return best;
    }

    private void rerouteNetherStripAwayFromLava(ServerLevel level) {
        Direction cur = this.goMineNetherStripDir;
        if (cur == null) {
            return;
        }
        this.goMineNetherStripDir = this.pickBestNetherStripDirAvoidingLava(level, cur);
        this.goMineNetherLavaRerouteCooldown = NETHER_STRIP_LAVA_REROUTE_COOLDOWN_TICKS;
        this.clearStrictTunnelTowardGoal();
        this.walkTargetBlock = null;
        this.goMineNetherStripMarch = false;
        this.searchThrottle = 0;
        this.goMineSeekStallTicks = 0;
        this.goMineStuckNearGoalTicks = 0;
        this.kelvin.getNavigation().stop();
    }

    private int[][] chunkOffsetTableForJob(ServerLevel level) {
        if (this.isAncientDebrisNetherJob(level)) {
            return GO_MINE_CHUNK_OFFSETS_NETHER_DEBRIS;
        }
        if (this.wantsOmniscientOreTargeting()) {
            return GO_MINE_CHUNK_OFFSETS_WIDE_ORE;
        }
        return GO_MINE_CHUNK_OFFSETS;
    }

    /**
     * Ore / precious-block jobs (anything except cobble fill): Kelvin uses full server-side knowledge of loaded
     * chunks — wide scans, chunk generation, and carving when the navigator has no path.
     */
    private boolean wantsOmniscientOreTargeting() {
        Block t = this.targetBlock;
        if (t == null) {
            return false;
        }
        return t != Blocks.COBBLESTONE && t != Blocks.COBBLED_DEEPSLATE;
    }

    private int prospectSearchRadius() {
        return this.wantsOmniscientOreTargeting() ? SEARCH_RADIUS_LOOSE : SEARCH_RADIUS;
    }

    private int prospectSearchY() {
        return this.wantsOmniscientOreTargeting() ? SEARCH_Y_LOOSE : SEARCH_Y;
    }

    private boolean depositUpToHaulCap(ServerLevel level, BlockEntity be) {
        IItemHandler handler =
                be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        if (handler == null) {
            return false;
        }
        int cap = this.depositHaulItemThreshold();
        int remaining = cap;
        var inv = this.kelvin.getKelvinInventory();
        for (int slot = 0; slot < KelvinInventoryMenu.KELVIN_SLOT_COUNT && remaining > 0; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty() || !this.isDepositItem(stack)) {
                continue;
            }
            int take = Math.min(stack.getCount(), remaining);
            ItemStack extracted = stack.copy().split(take);
            ItemStack leftover = ItemHandlerHelper.insertItem(handler, extracted, false);
            int inserted = extracted.getCount() - leftover.getCount();
            if (inserted > 0) {
                remaining -= inserted;
                stack.shrink(inserted);
                inv.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
            if (!leftover.isEmpty()) {
                ItemStack back = inv.getItem(slot);
                if (back.isEmpty()) {
                    inv.setItem(slot, leftover);
                } else {
                    back.grow(leftover.getCount());
                    inv.setItem(slot, back);
                }
                break;
            }
        }
        return remaining < cap;
    }

    private void tickBreaking(ServerLevel level, ServerPlayer orderer) {
        BlockPos pos = this.breakTarget;
        if (pos == null) {
            return;
        }
        Block block = this.targetBlock;
        if (block == null || !level.isLoaded(pos)) {
            this.abortBreaking();
            return;
        }
        if (this.isProtectedDepositStoragePos(pos)) {
            this.abortBreaking();
            return;
        }
        if (this.depositEscapeBreakActive) {
            if (!this.isDepositEscapeBreakTarget(pos)) {
                this.abortBreaking();
                return;
            }
        } else if (this.goMineStrictTunnelActive) {
            if (!this.isStrictTunnelBreakTarget(pos)) {
                this.abortBreaking();
                return;
            }
        } else if (this.kelvin.getEyePosition(1.0F).distanceToSqr(Vec3.atCenterOf(pos)) > MINE_REACH_EYE_SQR) {
            this.abortBreaking();
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            this.abortBreaking();
            return;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            this.abortBreaking();
            return;
        }
        ItemStack tool = this.bestDiggingTool(state);
        float digSpeed = Math.max(1.0F, tool.getDestroySpeed(state));
        this.breakProgress += digSpeed / (hardness * 30.0F);
        SoundType st = state.getSoundType();
        if (this.kelvin.tickCount % 4 == 0) {
            level.playSound(
                    null,
                    pos,
                    st.getHitSound(),
                    SoundSource.BLOCKS,
                    (st.getVolume() + 1.0F) / 8.0F,
                    st.getPitch() * 0.5F);
        }
        /* Swing cadence: every tick resets synced swing ticks and freezes the pose; ~5t matches CLIENT_SWING length. */
        if (this.kelvin.tickCount % 5 == 0) {
            this.kelvin.swing(InteractionHand.MAIN_HAND, true);
        }
        if (this.breakProgress < 1.0F) {
            return;
        }
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            this.restoreGoMineHandTool();
            orderer.sendSystemMessage(
                    Component.literal("Mob griefing is off — Kelvin cannot break blocks.")
                            .withStyle(ChatFormatting.RED));
            this.stop(orderer);
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDrops(state, level, pos, be, this.kelvin, tool);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
        level.levelEvent(2001, pos, Block.getId(state));
        this.kelvin.playSound(st.getBreakSound(), 1.0F, 0.9F + level.random.nextFloat() * 0.2F);
        int gained = 0;
        for (ItemStack d : drops) {
            if (d.isEmpty()) {
                continue;
            }
            ItemStack toAbsorb = d.copy();
            int before = toAbsorb.getCount();
            ItemStack leftover = this.kelvin.addItemStackToKelvin(toAbsorb);
            int absorbed = before - leftover.getCount();
            if (absorbed > 0 && this.isDepositItem(d)) {
                gained += absorbed;
            }
            if (!leftover.isEmpty()) {
                ItemEntity loose =
                        new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.12, pos.getZ() + 0.5, leftover);
                loose.setPickUpDelay(0);
                loose.setDeltaMovement(Vec3.ZERO);
                level.addFreshEntity(loose);
            }
        }
        this.sessionCollectedTotal += gained;
        this.breakTarget = null;
        this.breakProgress = 0.0F;
        if (!this.goMineStrictTunnelActive) {
            this.walkTargetBlock = null;
        }
        this.depositEscapeBreakActive = false;
        /* Vein sweeping: if the block just mined was a target ore, scan its 6 adjacent faces for more
         * ore of the same type. Set the nearest neighbor as the next walk target so Kelvin mines the
         * entire vein before moving on, instead of walking away after the first ore block. Works for
         * both tunnel-mode breaks (where walkTargetBlock would otherwise stay on the far ore) and
         * direct proximity breaks (where walkTargetBlock was already cleared to null). */
        if (this.isAcceptableMineSource(state)
                && this.wantsOmniscientOreTargeting()
                && !this.isAncientDebrisNetherJob(level)) {
            BlockPos veinNext = null;
            int bestVeinDist = Integer.MAX_VALUE;
            for (Direction dir : Direction.values()) {
                BlockPos nb = pos.relative(dir);
                if (!level.isLoaded(nb)
                        || this.isProtectedDepositStoragePos(nb)
                        || this.goMineSkippedGoals.contains(nb)) {
                    continue;
                }
                BlockState nst = level.getBlockState(nb);
                if (!this.isAcceptableMineSource(nst)
                        || nst.isAir()
                        || nst.getDestroySpeed(level, nb) < 0.0F) {
                    continue;
                }
                int d = this.kelvin.blockPosition().distManhattan(nb);
                if (d < bestVeinDist) {
                    bestVeinDist = d;
                    veinNext = nb.immutable();
                }
            }
            if (veinNext != null) {
                /* Override whatever the current walk target was (the original far ore or null) and
                 * point Kelvin directly at the adjacent vein block. Clear tunnel state so the normal
                 * proximity path handles the 1-block reach rather than the tunnel carver. */
                this.clearStrictTunnelTowardGoal();
                this.walkTargetBlock = veinNext;
            }
        }
        if (this.goMineStrictTunnelActive && this.walkTargetBlock != null) {
            /* Navigate into the block we just cleared (pos, now air). The pathfinder has a trivial 1-2
             * block path so Kelvin physically steps forward or drops one level along the staircase.
             * goMineTunnelNavTarget tells tickSeekAndWalk to wait until he arrives before picking the
             * next break — otherwise the tunnel fires immediately and stops this navigation. */
            this.kelvin.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.25D);
            this.goMineTunnelNavTarget = pos.immutable();
        } else {
            this.kelvin.getNavigation().stop();
        }
        ModNetwork.sendGoMineHud(orderer, true, this.sessionCollectedTotal, this.depositChest, block);
    }

    private void abortBreaking() {
        this.breakTarget = null;
        this.breakProgress = 0.0F;
        this.walkTargetBlock = null;
        this.depositEscapeBreakActive = false;
        this.clearStrictTunnelTowardGoal();
    }

    private boolean isProtectedDepositStoragePos(BlockPos pos) {
        return this.depositChest != null && this.depositChest.equals(pos);
    }

    private boolean isDepositEscapeBreakTarget(BlockPos pos) {
        if (this.isProtectedDepositStoragePos(pos)) {
            return false;
        }
        BlockPos feet = this.kelvin.blockPosition();
        int dx = Math.abs(pos.getX() - feet.getX());
        int dz = Math.abs(pos.getZ() - feet.getZ());
        if (dx > DEPOSIT_ESCAPE_MAX_HORIZ_OFF || dz > DEPOSIT_ESCAPE_MAX_HORIZ_OFF) {
            return false;
        }
        int dy = pos.getY() - feet.getY();
        return dy >= DEPOSIT_ESCAPE_MIN_DY && dy <= DEPOSIT_ESCAPE_MAX_DY;
    }

    private int pathStallSkipLimit(ServerLevel level) {
        if (this.isAncientDebrisNetherJob(level)) {
            return PATH_STALL_SKIP_TICKS_NETHER_DEBRIS;
        }
        if (this.wantsOmniscientOreTargeting()) {
            return PATH_STALL_SKIP_TICKS_AGGRESSIVE_ORE;
        }
        return PATH_STALL_SKIP_TICKS;
    }

    /**
     * When the navigator still reports a path but Kelvin is snagged on geometry (e.g. solid wall between him and a
     * picked ore, or a wrong ore the ray does not target), we must still count "stall" and carve — otherwise
     * {@code goMineSeekStallTicks} is reset every tick and strict tunneling never starts.
     */
    private boolean shouldCountPathStallWhileNavigatingFarGoal(ServerLevel level, boolean navigating) {
        if (!navigating || !this.wantsOmniscientOreTargeting() || this.isAncientDebrisNetherJob(level)) {
            return false;
        }
        return this.kelvin.horizontalCollision;
    }

    private boolean canTunnelMineReach(BlockPos pos) {
        if (this.eyeCanMineReach(pos)) {
            return true;
        }
        BlockPos feet = this.kelvin.blockPosition();
        int man = feet.distManhattan(pos);
        int ady = Math.abs(pos.getY() - feet.getY());
        if (this.wantsOmniscientOreTargeting()) {
            return man <= 3 && ady <= 3;
        }
        return man <= 2 && ady <= 2;
    }

    private boolean isStrictTunnelBreakTarget(BlockPos pos) {
        BlockPos g = this.goMineTunnelGoalPos;
        if (g == null) {
            return false;
        }
        BlockPos feet = this.kelvin.blockPosition();
        double maxReach = this.wantsOmniscientOreTargeting() ? 9.0 * 9.0 : 7.5 * 7.5;
        if (pos.distSqr(feet) > maxReach) {
            return false;
        }
        Vec3 toG = Vec3.atCenterOf(g).subtract(Vec3.atBottomCenterOf(feet));
        Vec3 toP = Vec3.atCenterOf(pos).subtract(Vec3.atBottomCenterOf(feet));
        if (toG.lengthSqr() < 1.0E-4D || toP.lengthSqr() < 1.0E-6D) {
            return false;
        }
        double minDot = this.wantsOmniscientOreTargeting() ? 0.2D : 0.38D;
        if (toP.normalize().dot(toG.normalize()) < minDot) {
            return false;
        }
        int dy = pos.getY() - feet.getY();
        return dy >= -2 && dy <= 6;
    }

    /**
     * @param includeLateralWedges Overworld ore jobs: also try blocks beside the first forward step (wrong-ore
     *     obstructions, offset ores) — not used for the Nether strip stripper.
     */
    private List<BlockPos> strictTunnelBreakCandidates(
            BlockPos feet, BlockPos goal, boolean includeLateralWedges) {
        List<BlockPos> out = new ArrayList<>();
        int dx = goal.getX() - feet.getX();
        int dz = goal.getZ() - feet.getZ();
        int dy = goal.getY() - feet.getY();
        Direction horiz = null;
        if (Math.abs(dx) >= Math.abs(dz)) {
            if (dx != 0) {
                horiz = dx > 0 ? Direction.EAST : Direction.WEST;
            }
        } else if (dz != 0) {
            horiz = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        if (horiz != null) {
            BlockPos step = feet.relative(horiz);
            BlockPos step2 = step.relative(horiz);
            BlockPos step3 = step2.relative(horiz);
            /*
             * When the ore is significantly BELOW Kelvin's current Y (surface-to-underground case), carve a
             * descending staircase by putting "step.below()" — the floor of the NEXT step — BEFORE the horizontal
             * body blocks. Without this, once Kelvin descends one block the horizontal candidates at his new depth
             * are solid stone and appear first in the list, so he tunnels flat indefinitely instead of continuing
             * downward.
             *
             * Staircase order per step: [floor, body, head] repeated, then drop under feet.
             * Normal order is used when the goal is at roughly the same Y or above.
             */
            boolean staircaseDown = includeLateralWedges && dy < -5;
            if (staircaseDown) {
                out.add(step.below());       // floor of 1st stair step — drives descent
                out.add(step);               // body of 1st step
                out.add(step.above());       // head of 1st step
                out.add(step2.below());      // floor of 2nd step
                out.add(step2);              // body of 2nd step
                out.add(step2.above());      // head of 2nd step
                out.add(step3.below());      // floor of 3rd step
                out.add(step3);              // body of 3rd step
                out.add(step3.above());      // head of 3rd step
                out.add(feet.below());       // drop under Kelvin to advance one level
                /* Lateral wedges alongside the staircase — ores embedded in the side walls of the
                 * descent corridor.  These come last so they don't pull Kelvin sideways when the main
                 * staircase path is clear; they're only reached when every descent candidate is air. */
                out.add(step.relative(horiz.getCounterClockWise(Direction.Axis.Y)));
                out.add(step.relative(horiz.getCounterClockWise(Direction.Axis.Y)).above());
                out.add(step.relative(horiz.getClockWise(Direction.Axis.Y)));
                out.add(step.relative(horiz.getClockWise(Direction.Axis.Y)).above());
            } else {
                out.add(step);
                out.add(step.above());
                out.add(step2);
                out.add(step2.above());
                /* Third 2-tall slice: thick walls, wrong-ore obstructions, or diagonally offset ores. */
                out.add(step3);
                out.add(step3.above());
                if (includeLateralWedges) {
                    out.add(step.relative(horiz.getCounterClockWise(Direction.Axis.Y)));
                    out.add(step.relative(horiz.getCounterClockWise(Direction.Axis.Y)).above());
                    out.add(step.relative(horiz.getClockWise(Direction.Axis.Y)));
                    out.add(step.relative(horiz.getClockWise(Direction.Axis.Y)).above());
                }
                if (dy > 0) {
                    out.add(feet.above(2));
                    out.add(step.above(2));
                    out.add(step2.above(2));
                }
                if (dy < 0) {
                    out.add(step.below());
                    out.add(feet.below());
                    out.add(step2.below());
                }
            }
        } else if (dy > 0) {
            out.add(feet.above(2));
            out.add(feet.above(1));
        } else if (dy < 0) {
            out.add(feet.below());
            out.add(feet.relative(Direction.DOWN, 2));
        }
        return out;
    }

    /**
     * When vanilla path has no route through solid terrain, mine a 2-high step (and a second cell forward) toward the
     * chosen ore — server already “sees” that ore via wide scans; this makes Kelvin act on it without walking in
     * circles.
     */
    private boolean tryStrictTunnelTowardGoal(ServerLevel level, BlockPos goal) {
        if (!this.wantsOmniscientOreTargeting() || !this.isValidProspect(level, goal)) {
            return false;
        }
        BlockPos feet = this.kelvin.blockPosition();
        boolean overworldOreLateral = this.wantsOmniscientOreTargeting() && !this.isAncientDebrisNetherJob(level);
        for (BlockPos p : this.strictTunnelBreakCandidates(feet, goal, overworldOreLateral)) {
            if (!level.isLoaded(p) || this.isProtectedDepositStoragePos(p)) {
                continue;
            }
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.getDestroySpeed(level, p) < 0.0F) {
                continue;
            }
            if (!this.canTunnelMineReach(p)) {
                continue;
            }
            this.goMineStrictTunnelActive = true;
            this.goMineTunnelGoalPos = goal.immutable();
            this.breakTarget = p.immutable();
            this.breakProgress = 0.0F;
            this.depositEscapeBreakActive = false;
            return true;
        }
        return false;
    }

    /** One swap at Go Mine job start / reload; main hand stays the mining tool until {@link #stop}. */
    private void applyGoMineSessionToolLock(ServerLevel level, Block block) {
        BlockState def = blockForToolSelection(block).defaultBlockState();
        int best = this.findBestDiggingToolSlot(def);
        if (best > 0) {
            this.swapInventorySlots(0, best);
            this.goMineHandSwapPeer = best;
        } else {
            this.goMineHandSwapPeer = -1;
        }
    }

    private void restoreGoMineHandTool() {
        if (this.goMineHandSwapPeer < 0) {
            return;
        }
        this.swapInventorySlots(0, this.goMineHandSwapPeer);
        this.goMineHandSwapPeer = -1;
    }

    private void swapInventorySlots(int slotA, int slotB) {
        SimpleContainer inv = this.kelvin.getKelvinInventory();
        ItemStack a = inv.getItem(slotA).copy();
        ItemStack b = inv.getItem(slotB).copy();
        inv.setItem(slotA, b);
        inv.setItem(slotB, a);
    }

    private int findBestDiggingToolSlot(BlockState state) {
        int bestSlot = 0;
        float bestSpeed = this.kelvin.getKelvinInventory().getItem(0).getDestroySpeed(state);
        if (bestSpeed < 1.0F) {
            bestSpeed = 1.0F;
        }
        for (int i = 1; i < KelvinInventoryMenu.KELVIN_SLOT_COUNT; i++) {
            ItemStack stack = this.kelvin.getKelvinInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float spd = stack.getDestroySpeed(state);
            if (spd > bestSpeed) {
                bestSpeed = spd;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /**
     * Shorter delays while spiraling unloaded chunks (avoids standing still for seconds between scans); slightly
     * longer when a walk goal exists so we do not thrash repicks.
     */
    private int searchThrottleAfterPick(@Nullable BlockPos picked) {
        if (picked != null) {
            return 5;
        }
        if (this.wantsOmniscientOreTargeting()) {
            return this.goMineRemoteSearchAnchor != null ? 1 : 2;
        }
        if (this.goMineRemoteProspectExhausted) {
            return 10;
        }
        if (this.goMineRemoteSearchAnchor != null) {
            return 1;
        }
        return 4;
    }

    private void tickSeekAndWalk(ServerLevel level, ServerPlayer orderer) {
        if (this.walkTargetBlock != null && !this.isWalkTargetStillValid(level)) {
            this.clearStrictTunnelTowardGoal();
            this.walkTargetBlock = null;
            this.goMineNetherStripMarch = false;
            this.searchThrottle = 0;
        }
        /* After a tunnel break, wait for Kelvin to walk into the cleared cell before picking the next
         * block to break. Without this wait, the top tunnel block fires immediately and stops the
         * navigation before Kelvin moves, so he would stand still and tunnel remotely rather than
         * advancing along the staircase. */
        if (this.goMineTunnelNavTarget != null) {
            if (this.kelvin.getNavigation().isInProgress()) {
                return;
            }
            this.goMineTunnelNavTarget = null;
        }
        if (this.isAncientDebrisNetherJob(level)) {
            this.ensureNetherStripDirection(level);
            if (this.goMineNetherLavaRerouteCooldown > 0) {
                this.goMineNetherLavaRerouteCooldown--;
            } else if (this.goMineNetherStripDir != null
                    && this.netherStripLavaThreatAlong(level, this.goMineNetherStripDir)) {
                this.rerouteNetherStripAwayFromLava(level);
                return;
            }
            BlockPos feet = this.kelvin.blockPosition();
            if (feet.getY() > NETHER_DEBRIS_STRIP_Y_MAX) {
                BlockPos under = feet.below();
                if (this.isNetherStripClearableBlock(level, under)) {
                    this.breakTarget = under.immutable();
                    this.breakProgress = 0.0F;
                    this.clearStrictTunnelTowardGoal();
                    this.depositEscapeBreakActive = false;
                    this.kelvin.getNavigation().stop();
                    return;
                }
            } else if (feet.getY() < NETHER_DEBRIS_STRIP_Y_MIN) {
                BlockPos upGoal = new BlockPos(feet.getX(), NETHER_DEBRIS_STRIP_Y_MIN + 2, feet.getZ());
                if (this.tryGoMineScaffold(level, upGoal)) {
                    this.kelvin.getNavigation().stop();
                    return;
                }
            }
        }
        if (this.walkTargetBlock == null) {
            if (this.isAncientDebrisNetherJob(level)) {
                if (this.searchThrottle > 0) {
                    this.searchThrottle--;
                } else {
                    this.pickNetherStripWalkTarget(level);
                    this.searchThrottle = this.searchThrottleAfterPick(this.walkTargetBlock);
                }
            } else {
                boolean omniscient = this.wantsOmniscientOreTargeting();
                if (!omniscient && this.searchThrottle > 0) {
                    this.searchThrottle--;
                } else {
                    BlockPos picked = this.findNearestTargetBlock(level);
                    if (picked != null) {
                        this.clearGoMineRemoteSearchState();
                    } else {
                        picked = this.advanceRemoteOreSearch(level, orderer);
                    }
                    if (picked != null
                            && (this.walkTargetBlock == null || !picked.equals(this.walkTargetBlock))) {
                        this.goMineStuckNearGoalTicks = 0;
                        this.goMineSeekStallTicks = 0;
                    }
                    this.walkTargetBlock = picked;
                    this.searchThrottle = this.searchThrottleAfterPick(picked);
                }
            }
        }
        if (this.walkTargetBlock == null) {
            this.goMineSeekStallTicks = 0;
            this.kelvin.getNavigation().stop();
            return;
        }
        BlockPos goal = this.walkTargetBlock;
        double distSqrFeet = this.kelvin.blockPosition().distSqr(goal);

        /* If a target ore has come within arm's reach while Kelvin was navigating or tunneling toward a
         * different, more distant one, switch to mine the closer ore immediately. Without this check,
         * walkTargetBlock stays locked on the far ore and Kelvin walks straight past visible nearby ones. */
        if (this.wantsOmniscientOreTargeting() && distSqrFeet > MINE_REACH_SQR) {
            BlockPos closer = this.findNearestOreInReach(level);
            if (closer != null && !closer.equals(goal)) {
                double closerDist = this.kelvin.blockPosition().distSqr(closer);
                /* Switch when the nearby ore is in immediate proximity (≤ MINE_REACH_SQR) or is
                 * significantly closer (5+ blocks) than the locked target — avoids thrashing between
                 * ores at similar distances while still catching ores right at hand. */
                if (closerDist <= MINE_REACH_SQR || closerDist < distSqrFeet - 25.0) {
                    this.clearStrictTunnelTowardGoal();
                    this.walkTargetBlock = closer;
                    goal = closer;
                    distSqrFeet = closerDist;
                }
            }
        }

        boolean navigating = this.kelvin.getNavigation().isInProgress();
        boolean proximity = distSqrFeet <= MINE_REACH_SQR || this.alignedUnderGoalColumn(goal);

        if (this.goMineStrictTunnelActive && this.breakTarget == null && !proximity) {
            boolean tunneled =
                    this.goMineNetherStripMarch && this.isAncientDebrisNetherJob(level)
                            ? this.tryNetherStripTunnelForward(level)
                            : this.tryStrictTunnelTowardGoal(level, goal);
            if (tunneled) {
                this.goMineSeekStallTicks = 0;
                this.kelvin.getNavigation().stop();
                return;
            }
        }

        if (!this.alignedUnderGoalColumn(goal) && distSqrFeet > MINE_REACH_SQR) {
            if (navigating) {
                if (this.shouldCountPathStallWhileNavigatingFarGoal(level, true)) {
                    this.goMineSeekStallTicks++;
                } else {
                    this.goMineSeekStallTicks = 0;
                }
            } else {
                this.goMineSeekStallTicks++;
            }
            if (this.goMineSeekStallTicks > 0) {
                int stallLimit = this.pathStallSkipLimit(level);
                if (this.goMineSeekStallTicks >= stallLimit) {
                    boolean carved =
                            this.goMineNetherStripMarch && this.isAncientDebrisNetherJob(level)
                                    ? this.tryNetherStripTunnelForward(level)
                                    : this.tryStrictTunnelTowardGoal(level, goal);
                    if (carved) {
                        this.goMineSeekStallTicks = 0;
                        this.kelvin.getNavigation().stop();
                        return;
                    }
                    if (this.wantsOmniscientOreTargeting()) {
                        this.abandonWalkGoalForRepick(goal);
                    } else {
                        this.retireUnreachableGoal(goal);
                    }
                    return;
                }
            }
        } else {
            this.goMineSeekStallTicks = 0;
        }

        if (proximity) {
            if (this.goMineStrictTunnelActive && distSqrFeet > MINE_REACH_SQR) {
                /* Kelvin is aligned above the ore (alignedUnderGoalColumn) but the ore is still too far
                 * below to mine directly. Keep the staircase descent running rather than clearing tunnel
                 * state and switching to the slow view-ray approach. */
                boolean tunneled =
                        this.goMineNetherStripMarch && this.isAncientDebrisNetherJob(level)
                                ? this.tryNetherStripTunnelForward(level)
                                : this.tryStrictTunnelTowardGoal(level, goal);
                if (tunneled) {
                    this.goMineSeekStallTicks = 0;
                    this.kelvin.getNavigation().stop();
                    return;
                }
                /* Tunnel found nothing (candidates all air / out of reach) — fall through to the normal
                 * proximity handler which will use the view ray to break the next block below. */
            }
            this.clearStrictTunnelTowardGoal();
            BlockPos next = this.resolveNextBreakAlongView(level, goal);
            if (this.isProtectedDepositStoragePos(next)) {
                next = null;
            }
            if (next != null && this.eyeCanMineReach(next)) {
                this.breakTarget = next;
                this.breakProgress = 0.0F;
                this.goMineStuckNearGoalTicks = 0;
                this.walkTargetBlock = null;
                this.kelvin.getNavigation().stop();
                return;
            }
            if ((this.goMineNetherStripMarch && this.isAncientDebrisNetherJob(level)
                            ? this.tryNetherStripTunnelForward(level)
                            : this.wantsOmniscientOreTargeting()
                                    && (next == null || !this.eyeCanMineReach(next))
                                    && this.tryStrictTunnelTowardGoal(level, goal))) {
                this.goMineStuckNearGoalTicks = 0;
                this.kelvin.getNavigation().stop();
                return;
            }
            if (next != null) {
                if (this.goMinePillarCooldown > 0) {
                    this.goMinePillarCooldown--;
                    this.goMineStuckNearGoalTicks++;
                } else if (this.tryGoMineScaffold(level, goal)) {
                    this.goMinePillarCooldown = 2;
                    this.goMineStuckNearGoalTicks = 0;
                    this.kelvin.getNavigation().stop();
                    return;
                } else {
                    this.goMineStuckNearGoalTicks++;
                }
                if (this.goMineStuckNearGoalTicks >= PROXIMITY_STUCK_SKIP_TICKS) {
                    if (this.wantsOmniscientOreTargeting()) {
                        this.abandonWalkGoalForRepick(goal);
                    } else {
                        this.retireUnreachableGoal(goal);
                    }
                }
                return;
            }
            if (this.wantsOmniscientOreTargeting()) {
                this.abandonWalkGoalForRepick(goal);
            } else {
                this.retireUnreachableGoal(goal);
            }
            return;
        }
        this.goMineStuckNearGoalTicks = 0;
        this.kelvin
                .getNavigation()
                .moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.25D);
    }

    private boolean isValidProspect(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || this.targetBlock == null) {
            return false;
        }
        BlockState st = level.getBlockState(pos);
        return this.isAcceptableMineSource(st)
                && st.getDestroySpeed(level, pos) >= 0.0F
                && !st.isAir();
    }

    /**
     * Blocks Kelvin may path to and break for this job.
     * <ul>
     *   <li>Cobblestone/cobbled-deepslate jobs also accept natural stone/deepslate (which is what
     *       actually exists in-world before it is mined).</li>
     *   <li>Any ore job accepts <em>both</em> the regular and deepslate variant of that ore (e.g.
     *       targeting DIAMOND_ORE also accepts DEEPSLATE_DIAMOND_ORE). The two variants generate in
     *       the same vein, drop the same items, and share the same ore tag — treating them as
     *       separate blocks only makes sense in the registry, not for mining purposes.</li>
     * </ul>
     */
    private boolean isAcceptableMineSource(BlockState st) {
        Block t = this.targetBlock;
        if (t == null) {
            return false;
        }
        if (st.is(t)) {
            return true;
        }
        if (t == Blocks.COBBLESTONE) {
            return st.is(Blocks.STONE) || st.is(Blocks.INFESTED_STONE);
        }
        if (t == Blocks.COBBLED_DEEPSLATE) {
            return st.is(Blocks.DEEPSLATE) || st.is(Blocks.INFESTED_DEEPSLATE);
        }
        Block alt = DEEPSLATE_ORE_PAIRS.get(t);
        return alt != null && st.is(alt);
    }

    /**
     * First solid along a ray from Kelvin's eyes toward the goal block's center. That is what we break next: either
     * the goal itself or an obstruction (e.g. leaves) on the way, without choosing unrelated blocks underfoot.
     */
    @Nullable
    private BlockPos resolveNextBreakAlongView(ServerLevel level, BlockPos goalPos) {
        Vec3 eye = this.kelvin.getEyePosition(1.0F);
        Vec3 into = Vec3.atCenterOf(goalPos);
        BlockHitResult hit =
                level.clip(new ClipContext(eye, into, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.kelvin));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos hitPos = hit.getBlockPos();
        BlockState st = level.getBlockState(hitPos);
        if (st.isAir() || st.getDestroySpeed(level, hitPos) < 0.0F) {
            return null;
        }
        return hitPos.immutable();
    }

    /**
     * Fast scan for a target ore within immediate arm's reach (≤ 8 blocks Euclidean). Used every tick
     * to notice ores that entered reach while Kelvin was navigating or tunneling toward a different,
     * more distant ore — without this, Kelvin walks straight past visible ores locked on a far target.
     */
    @Nullable
    private BlockPos findNearestOreInReach(ServerLevel level) {
        if (this.targetBlock == null || this.isAncientDebrisNetherJob(level)) {
            return null;
        }
        BlockPos origin = this.kelvin.blockPosition();
        int bestDist = Integer.MAX_VALUE;
        BlockPos bestPos = null;
        int rad = 8;
        for (int dy = -rad; dy <= rad; dy++) {
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    int dSqr = dx * dx + dy * dy + dz * dz;
                    if (dSqr == 0 || dSqr > rad * rad) {
                        continue;
                    }
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (!level.isLoaded(p)
                            || this.goMineSkippedGoals.contains(p)
                            || this.isProtectedDepositStoragePos(p)) {
                        continue;
                    }
                    BlockState st = level.getBlockState(p);
                    if (!this.isAcceptableMineSource(st)
                            || st.isAir()
                            || st.getDestroySpeed(level, p) < 0.0F) {
                        continue;
                    }
                    int d = origin.distManhattan(p);
                    if (d < bestDist) {
                        bestDist = d;
                        bestPos = p.immutable();
                    }
                }
            }
        }
        return bestPos;
    }

    private BlockPos findNearestTargetBlock(ServerLevel level) {
        if (this.targetBlock == null) {
            return null;
        }
        if (this.isAncientDebrisNetherJob(level)) {
            return null;
        }
        BlockPos origin = this.kelvin.blockPosition();
        int bestScore = Integer.MAX_VALUE;
        BlockPos bestPos = null;
        int rad = this.prospectSearchRadius();
        int yExt = this.prospectSearchY();
        for (int dy = -yExt; dy <= yExt; dy++) {
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    try {
                        if (!level.isLoaded(p)) {
                            continue;
                        }
                        BlockState st = level.getBlockState(p);
                        if (!this.isAcceptableMineSource(st) || st.isAir() || st.getDestroySpeed(level, p) < 0.0F) {
                            continue;
                        }
                        if (this.goMineSkippedGoals.contains(p)) {
                            continue;
                        }
                        if (this.isProtectedDepositStoragePos(p)) {
                            continue;
                        }
                        int score = this.scoreGoMineProspect(level, origin, p);
                        if (score < bestScore
                                || (score == bestScore && bestPos != null && p.getY() > bestPos.getY())) {
                            bestScore = score;
                            bestPos = p.immutable();
                        }
                    } catch (ClassCastException ex) {
                        if (LOGGED_GO_MINE_CHUNK_CCE.compareAndSet(false, true)) {
                            GO_MINE_LOG.warn(
                                    "Go Mine: chunk query failed with ClassCastException (ChunkPos/Comparable; often Valkyrien Skies + mixins). Skipping bad positions — update those mods if this spams.",
                                    ex);
                        }
                    }
                }
            }
        }
        return bestPos;
    }

    /**
     * Lower is better. Keeps Kelvin working near his current level and toward the local surface instead of always
     * snapping to the closest ore under his feet (which caused “dig down and spiral” pits that are hard to exit).
     */
    private int scoreGoMineProspect(ServerLevel level, BlockPos origin, BlockPos p) {
        if (this.targetBlock == Blocks.ANCIENT_DEBRIS && level.dimension() == Level.NETHER) {
            int dMan = origin.distManhattan(p);
            int dy = Math.abs(p.getY() - origin.getY());
            return dMan * 4 + dy * 2;
        }
        if (this.wantsOmniscientOreTargeting()) {
            int dMan = origin.distManhattan(p);
            int dy = Math.abs(p.getY() - origin.getY());
            return dMan * 3 + dy * 2;
        }
        int dMan = origin.distManhattan(p);
        int score = dMan * 3;
        int belowFeet = origin.getY() - p.getY();
        if (belowFeet > 1) {
            score += (belowFeet - 1) * GO_MINE_BELOW_FEET_PENALTY;
        }
        if (p.getX() == origin.getX() && p.getZ() == origin.getZ() && p.getY() < origin.getY()) {
            score += GO_MINE_SAME_COLUMN_UNDER_PENALTY;
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ());
        int depthBelowSurface = surface - p.getY() - 1;
        if (depthBelowSurface > 0) {
            score += Math.min(96, depthBelowSurface) / GO_MINE_DEEP_UNDER_SURFACE_DIVISOR;
        }
        return score;
    }

    private ItemStack bestDiggingTool(BlockState state) {
        ItemStack best = ItemStack.EMPTY;
        float bestSpeed = 1.0F;
        for (int i = 0; i < KelvinInventoryMenu.KELVIN_SLOT_COUNT; i++) {
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

    private int countMatchingInInventory() {
        int sum = 0;
        for (int i = 0; i < KelvinInventoryMenu.KELVIN_SLOT_COUNT; i++) {
            ItemStack s = this.kelvin.getKelvinInventory().getItem(i);
            if (!s.isEmpty() && this.isDepositItem(s)) {
                sum += s.getCount();
            }
        }
        return sum;
    }

    private boolean isDepositItem(ItemStack stack) {
        return this.depositItems.contains(stack.getItem());
    }

    private static Set<Item> computeDepositItems(ServerLevel level, Block target, KelvinEntity kelvin) {
        BlockPos at = kelvin.blockPosition();
        ItemStack tool = kelvin.getKelvinInventory().getItem(0);
        Set<Item> set = new HashSet<>();
        for (Block source : depositRepresentativeBlocks(target)) {
            BlockState def = source.defaultBlockState();
            for (ItemStack s : Block.getDrops(def, level, at, null, kelvin, tool)) {
                if (!s.isEmpty()) {
                    set.add(s.getItem());
                }
            }
        }
        if (set.isEmpty()) {
            Item it = target.asItem();
            if (it != Items.AIR) {
                set.add(it);
            }
        }
        return Set.copyOf(set);
    }

    /** Blocks whose drops are merged into {@link #depositItems} for the chosen mine target. */
    private static List<Block> depositRepresentativeBlocks(Block target) {
        if (target == Blocks.COBBLESTONE) {
            return List.of(Blocks.STONE, Blocks.COBBLESTONE);
        }
        if (target == Blocks.COBBLED_DEEPSLATE) {
            return List.of(Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE);
        }
        Block alt = DEEPSLATE_ORE_PAIRS.get(target);
        if (alt != null) {
            return List.of(target, alt);
        }
        return List.of(target);
    }

    /**
     * Block state used to pick the session digging tool: prefer the harder “natural” block when the HUD target is
     * the smelted/cobbled product (e.g. cobble job → pick for stone).
     */
    private static Block blockForToolSelection(Block target) {
        if (target == Blocks.COBBLESTONE) {
            return Blocks.STONE;
        }
        if (target == Blocks.COBBLED_DEEPSLATE) {
            return Blocks.DEEPSLATE;
        }
        return target;
    }

    public void addSaveData(CompoundTag tag) {
        if (!this.isActive() || this.targetBlock == null) {
            return;
        }
        tag.putBoolean(TAG_ACTIVE, true);
        tag.putString(TAG_BLOCK, BuiltInRegistries.BLOCK.getKey(this.targetBlock).toString());
        if (this.ordererUuid != null) {
            tag.putUUID(TAG_ORDERER, this.ordererUuid);
        }
        if (this.depositChest != null) {
            tag.putLong(TAG_CHEST, this.depositChest.asLong());
        }
        tag.putInt(TAG_TOTAL, this.sessionCollectedTotal);
        tag.putInt(TAG_SWAP_PEER, this.goMineHandSwapPeer);
        if (this.targetBlock == Blocks.ANCIENT_DEBRIS && this.goMineNetherStripDir != null) {
            tag.putByte(TAG_NETHER_STRIP_DIR, (byte) this.goMineNetherStripDir.get2DDataValue());
        }
    }

    public void readSaveData(CompoundTag tag, ServerLevel level) {
        if (!tag.getBoolean(TAG_ACTIVE)) {
            return;
        }
        String key = tag.getString(TAG_BLOCK);
        ResourceLocation rl = ResourceLocation.tryParse(key);
        if (rl == null) {
            return;
        }
        Block b = BuiltInRegistries.BLOCK.get(rl);
        if (b == null || b == Blocks.AIR) {
            return;
        }
        this.targetBlock = b;
        if (tag.hasUUID(TAG_ORDERER)) {
            this.ordererUuid = tag.getUUID(TAG_ORDERER);
        }
        if (tag.contains(TAG_CHEST)) {
            this.depositChest = BlockPos.of(tag.getLong(TAG_CHEST));
        }
        this.sessionCollectedTotal = tag.getInt(TAG_TOTAL);
        if (tag.contains(TAG_SWAP_PEER)) {
            int p = tag.getInt(TAG_SWAP_PEER);
            this.goMineHandSwapPeer =
                    p >= 0 && p < KelvinInventoryMenu.KELVIN_SLOT_COUNT ? p : -1;
        } else {
            this.goMineHandSwapPeer = -1;
        }
        this.depositItems = computeDepositItems(level, b, this.kelvin);
        this.breakTarget = null;
        this.breakProgress = 0.0F;
        this.walkTargetBlock = null;
        this.clearGoMineRemoteSearchState();
        this.goMineFailureReturnToChest = false;
        this.goMineSkippedGoals.clear();
        this.goMineSeekStallTicks = 0;
        this.goMineStuckNearGoalTicks = 0;
        this.goMinePillarCooldown = 0;
        this.resetDepositPathingAssistState();
        this.depositEscapeBreakActive = false;
        this.clearStrictTunnelTowardGoal();
        this.goMineNetherStripMarch = false;
        this.goMineNetherLavaRerouteCooldown = 0;
        if (b == Blocks.ANCIENT_DEBRIS && tag.contains(TAG_NETHER_STRIP_DIR)) {
            int v = tag.getByte(TAG_NETHER_STRIP_DIR);
            if (v >= 0 && v < 4) {
                this.goMineNetherStripDir = Direction.from2DDataValue(v);
            } else {
                this.goMineNetherStripDir = null;
            }
        } else {
            this.goMineNetherStripDir = null;
        }
    }


    private void resetDepositPathingAssistState() {
        this.goMineDepositPathlessTicks = 0;
        this.goMineDepositWedgedTicks = 0;
        this.goMineDepositNoProgressTicks = 0;
        this.goMineDepositLastDistSqr = -1.0D;
    }

    private void clearGoMineRemoteSearchState() {
        this.goMineRemoteSearchAnchor = null;
        this.goMineRemoteSearchIndex = 0;
        this.goMineRemoteProspectExhausted = false;
        this.goMineRemoteChunkOffsetTable = null;
    }

    /**
     * Loads nearby chunks (same terrain the seed would produce) and scans section palettes for the job block. Silent
     * until {@link #onRemoteOreSearchExhausted} runs after the spiral is done.
     */
    @Nullable
    private BlockPos advanceRemoteOreSearch(ServerLevel level, ServerPlayer orderer) {
        if (this.isAncientDebrisNetherJob(level)) {
            return null;
        }
        if (this.targetBlock == null || this.goMineRemoteProspectExhausted) {
            return null;
        }
        if (this.goMineRemoteSearchAnchor == null) {
            this.goMineRemoteSearchAnchor = this.kelvin.chunkPosition();
            this.goMineRemoteSearchIndex = 0;
            this.goMineRemoteChunkOffsetTable = this.chunkOffsetTableForJob(level);
        }
        int[][] offsets = this.goMineRemoteChunkOffsetTable;
        if (offsets == null) {
            offsets = this.chunkOffsetTableForJob(level);
            this.goMineRemoteChunkOffsetTable = offsets;
        }
        int budget =
                this.isAncientDebrisNetherJob(level) || this.wantsOmniscientOreTargeting() ? 8 : 2;
        Predicate<BlockState> rough =
                st -> this.isAcceptableMineSource(st) && !st.isAir();
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        ChunkPos anchor = this.goMineRemoteSearchAnchor;
        while (budget-- > 0 && this.goMineRemoteSearchIndex < offsets.length) {
            int[] off = offsets[this.goMineRemoteSearchIndex++];
            int cx = anchor.x + off[0];
            int cz = anchor.z + off[1];
            ChunkAccess ch;
            try {
                ch = ((ServerChunkCache) level.getChunkSource()).getChunk(cx, cz, ChunkStatus.FULL, true);
            } catch (Throwable t) {
                continue;
            }
            if (ch == null) {
                continue;
            }
            BlockPos hit = this.scanChunkForOreGoal(level, ch, rough, mut);
            if (hit != null) {
                this.clearGoMineRemoteSearchState();
                return hit;
            }
        }
        if (this.goMineRemoteSearchIndex >= offsets.length) {
            this.goMineRemoteProspectExhausted = true;
            this.goMineRemoteSearchAnchor = null;
            this.onRemoteOreSearchExhausted(orderer);
        }
        return null;
    }

    private void onRemoteOreSearchExhausted(ServerPlayer orderer) {
        if (this.depositChest != null) {
            this.goMineFailureReturnToChest = true;
            return;
        }
        this.finishNoOreFoundMessageAndStop(orderer);
    }

    @Nullable
    private BlockPos scanChunkForOreGoal(
            ServerLevel level,
            ChunkAccess chunk,
            Predicate<BlockState> roughMatch,
            BlockPos.MutableBlockPos scratch) {
        ChunkPos cp = chunk.getPos();
        int bx = cp.getMinBlockX();
        int bz = cp.getMinBlockZ();
        int yMin = level.getMinBuildHeight();
        int yMax = level.getMaxBuildHeight() - 1;
        if (this.targetBlock == Blocks.ANCIENT_DEBRIS && level.dimension() == Level.NETHER) {
            yMin = Math.max(yMin, 8);
            yMax = Math.min(yMax, 119);
        }
        for (int secY = chunk.getMinSection(); secY < chunk.getMaxSection(); secY++) {
            LevelChunkSection sec = chunk.getSection(chunk.getSectionIndexFromSectionY(secY));
            if (sec.hasOnlyAir()) {
                continue;
            }
            if (!sec.maybeHas(roughMatch)) {
                continue;
            }
            int yBase = SectionPos.sectionToBlockCoord(secY);
            for (int ly = 0; ly < 16; ly++) {
                int y = yBase + ly;
                if (y < yMin || y > yMax) {
                    continue;
                }
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        BlockState st = sec.getBlockState(lx, ly, lz);
                        if (!this.isAcceptableMineSource(st) || st.isAir()) {
                            continue;
                        }
                        scratch.set(bx + lx, y, bz + lz);
                        if (st.getDestroySpeed(level, scratch) < 0.0F) {
                            continue;
                        }
                        if (this.isProtectedDepositStoragePos(scratch)) {
                            continue;
                        }
                        if (this.goMineSkippedGoals.contains(scratch)) {
                            continue;
                        }
                        return scratch.immutable();
                    }
                }
            }
        }
        return null;
    }

    /** Count of consecutive clear cells from {@code feet.above(1)} upward (used to detect 2-high “trap” pockets). */
    private int headroomCellsAboveFeet(ServerLevel level, BlockPos feet) {
        int n = 0;
        for (int dy = 1; dy <= 6; dy++) {
            BlockPos p = feet.above(dy);
            if (!level.isLoaded(p)) {
                break;
            }
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.canBeReplaced()) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    /**
     * When the chest is not straight above, or Kelvin is in a 2-high pocket under the column, mine blocks
     * <em>ahead</em> toward the chest (head / body level) so he can step into the next cell and staircase instead of
     * only digging straight up.
     */
    private boolean tryBeginDepositStairMine(ServerLevel level, BlockPos chest) {
        if (chest.getY() + 0.5 <= this.kelvin.getY() + 0.2D) {
            return false;
        }
        BlockPos feet = this.kelvin.blockPosition();
        int cx = chest.getX() - feet.getX();
        int cz = chest.getZ() - feet.getZ();
        Direction[] tryOrder;
        if (cx == 0 && cz == 0) {
            if (this.headroomCellsAboveFeet(level, feet) < 2) {
                return false;
            }
            tryOrder =
                    new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        } else {
            Direction primary =
                    Math.abs(cx) >= Math.abs(cz)
                            ? (cx > 0 ? Direction.EAST : Direction.WEST)
                            : (cz > 0 ? Direction.SOUTH : Direction.NORTH);
            tryOrder =
                    new Direction[] {
                        primary,
                        primary.getClockWise(Direction.Axis.Y),
                        primary.getCounterClockWise(Direction.Axis.Y),
                        primary.getOpposite()
                    };
        }
        for (Direction d : tryOrder) {
            BlockPos step = feet.relative(d);
            BlockPos[] stairChain = {
                step.above(1),
                step.above(2),
                step,
                step.above(3),
            };
            for (BlockPos p : stairChain) {
                if (!this.isDepositEscapeBreakTarget(p) || !level.isLoaded(p)) {
                    continue;
                }
                BlockState st = level.getBlockState(p);
                if (st.isAir() || st.getDestroySpeed(level, p) < 0.0F) {
                    continue;
                }
                if (this.isProtectedDepositStoragePos(p)) {
                    continue;
                }
                this.depositEscapeBreakActive = true;
                this.breakTarget = p.immutable();
                this.breakProgress = 0.0F;
                this.kelvin.getNavigation().stop();
                return true;
            }
        }
        return false;
    }

    /**
     * Mine the first solid block above Kelvin's feet column so a 1-high tunnel can become 2-high and pillaring can
     * run (must run before {@link #tryGoMineScaffold}).
     */
    private boolean tryBeginDepositAscendMine(ServerLevel level, BlockPos chest) {
        if (chest.getY() + 0.5 <= this.kelvin.getY() + 0.2D) {
            return false;
        }
        BlockPos feet = this.kelvin.blockPosition();
        for (int dy = 1; dy <= 6; dy++) {
            BlockPos p = feet.above(dy);
            if (!level.isLoaded(p)) {
                break;
            }
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.getDestroySpeed(level, p) < 0.0F) {
                continue;
            }
            if (this.isProtectedDepositStoragePos(p)) {
                continue;
            }
            this.depositEscapeBreakActive = true;
            this.breakTarget = p.immutable();
            this.breakProgress = 0.0F;
            this.kelvin.getNavigation().stop();
            return true;
        }
        return false;
    }

    /**
     * First blocking block along a short ray toward the deposit chest (same idea as
     * {@link KelvinStuckEscapeController}'s follow escape).
     */
    @Nullable
    private BlockPos findGoMineBlockingTowardChest(ServerLevel level, BlockPos chest) {
        Vec3 eye = this.kelvin.getEyePosition(1.0F);
        Vec3 toward = Vec3.atCenterOf(chest).subtract(eye);
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
        if (this.isProtectedDepositStoragePos(pos) || pos.equals(chest)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F) {
            return null;
        }
        if (!this.isDepositEscapeBreakTarget(pos)) {
            return null;
        }
        return pos.immutable();
    }

    /**
     * Lowest solid block above Kelvin's feet when the chest is higher — staircases / mines upward one layer at a
     * time when pillaring cannot run (solid floor tunnel).
     */
    @Nullable
    private BlockPos findGoMineCeilingBlockAboveFeet(ServerLevel level, int chestBlockY) {
        if (chestBlockY + 0.5 <= this.kelvin.getY() + 0.35D) {
            return null;
        }
        BlockPos feet = this.kelvin.blockPosition();
        for (int dy = 1; dy <= 12; dy++) {
            BlockPos p = feet.above(dy);
            if (!level.isLoaded(p)) {
                break;
            }
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.getDestroySpeed(level, p) < 0.0F) {
                continue;
            }
            if (this.isProtectedDepositStoragePos(p)) {
                continue;
            }
            return p.immutable();
        }
        return null;
    }

    /**
     * Drop the current walk goal without blacklisting it — used when path + carve failed for an ore job so the next
     * tick can pick the same vein again after a fresh omniscient scan.
     */
    private void abandonWalkGoalForRepick(BlockPos goal) {
        this.clearStrictTunnelTowardGoal();
        if (this.walkTargetBlock != null && this.walkTargetBlock.equals(goal)) {
            this.walkTargetBlock = null;
            this.goMineNetherStripMarch = false;
        }
        this.goMineSeekStallTicks = 0;
        this.goMineStuckNearGoalTicks = 0;
        this.searchThrottle = 0;
        this.kelvin.getNavigation().stop();
    }

    private void retireUnreachableGoal(BlockPos goal) {
        this.clearStrictTunnelTowardGoal();
        if (this.goMineSkippedGoals.size() >= GO_MINE_SKIPPED_GOALS_CAP) {
            this.goMineSkippedGoals.clear();
        }
        this.goMineSkippedGoals.add(goal.immutable());
        if (this.walkTargetBlock != null && this.walkTargetBlock.equals(goal)) {
            this.walkTargetBlock = null;
            this.goMineNetherStripMarch = false;
        }
        this.goMineSeekStallTicks = 0;
        this.goMineStuckNearGoalTicks = 0;
        this.searchThrottle = 0;
        this.kelvin.getNavigation().stop();
    }

    private boolean eyeCanMineReach(BlockPos pos) {
        return this.kelvin.getEyePosition(1.0F).distanceToSqr(Vec3.atCenterOf(pos)) <= MINE_REACH_EYE_SQR;
    }

    /**
     * Same 3×3 column as the goal so Kelvin can work upward through leaves even when the log is out
     * of foot reach. NOT triggered when the goal is more than 5 blocks below Kelvin's feet — in that
     * case the ore is on a cave floor (or deep underground) and navigation must walk him down to the
     * correct level first. Without this guard, proximity fires too early, stops all navigation, and
     * Kelvin is locked in a 100-tick stuck loop unable to reach the ore.
     */
    private boolean alignedUnderGoalColumn(BlockPos goal) {
        BlockPos feet = this.kelvin.blockPosition();
        int dy = goal.getY() - feet.getY();
        return dy >= -5
                && Math.abs(goal.getX() - feet.getX()) <= 1
                && Math.abs(goal.getZ() - feet.getZ()) <= 1;
    }

    private boolean hasSupportUnderFeetForScaffold(ServerLevel level) {
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

    private boolean placementWouldCollideWithKelvin(BlockPos placePos) {
        AABB blockBox = new AABB(placePos);
        return blockBox.inflate(0.002D).intersects(this.kelvin.getBoundingBox().deflate(0.04D, 0.08D, 0.04D));
    }

    private int findSolidBuildingBlockSlotGoMine(ServerLevel level, BlockPos refBelow) {
        int strict = this.findSolidBuildingBlockSlotGoMineStrict(level, refBelow);
        if (strict >= 0) {
            return strict;
        }
        for (int i = 1; i < KelvinInventoryMenu.KELVIN_SLOT_COUNT; i++) {
            if (i == this.goMineHandSwapPeer) {
                continue;
            }
            ItemStack stack = this.kelvin.getKelvinInventory().getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                continue;
            }
            Block block = blockItem.getBlock();
            if (block == Blocks.TNT || block == Blocks.SAND || block == Blocks.GRAVEL) {
                continue;
            }
            BlockState def = block.defaultBlockState();
            if (def.isAir() || def.getCollisionShape(level, refBelow).isEmpty()) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private int findSolidBuildingBlockSlotGoMineStrict(ServerLevel level, BlockPos refBelow) {
        for (int i = 1; i < KelvinInventoryMenu.KELVIN_SLOT_COUNT; i++) {
            if (i == this.goMineHandSwapPeer) {
                continue;
            }
            ItemStack stack = this.kelvin.getKelvinInventory().getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
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

    private boolean placeGoMineBuildingBlock(
            ServerLevel level, int slot, BlockPos placePos, BlockHitResult hit, boolean verticalPillarStyle) {
        SimpleContainer inv = this.kelvin.getKelvinInventory();
        ItemStack stack = inv.getItem(slot);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        Block block = blockItem.getBlock();
        BlockPlaceContext ctx = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack, hit);
        BlockState placeState = block.getStateForPlacement(ctx);
        if (placeState == null || !placeState.canSurvive(level, placePos)) {
            return false;
        }
        if (this.placementWouldCollideWithKelvin(placePos)) {
            return false;
        }
        if (!level.setBlock(placePos, placeState, Block.UPDATE_ALL_IMMEDIATE)) {
            return false;
        }
        stack.shrink(1);
        if (stack.isEmpty()) {
            inv.setItem(slot, ItemStack.EMPTY);
        } else {
            inv.setItem(slot, stack);
        }
        SoundType sound = placeState.getSoundType();
        this.kelvin.playSound(sound.getPlaceSound(), (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        this.kelvin.getNavigation().stop();
        if (verticalPillarStyle && placePos.getY() == this.kelvin.blockPosition().getY()) {
            this.kelvin.setPos(
                    placePos.getX() + 0.5D,
                    placePos.getY() + 1.0D + 1.0E-3D,
                    placePos.getZ() + 0.5D);
            this.kelvin.setDeltaMovement(Vec3.ZERO);
            this.kelvin.deferFollowNavigationForPillarAscent();
        } else if (verticalPillarStyle) {
            this.kelvin.deferFollowNavigationForPillarAscent();
            this.kelvin.setDeltaMovement(Vec3.ZERO);
        } else {
            this.kelvin.getJumpControl().jump();
        }
        return true;
    }

    private boolean tryStackStandingOnSolidGoMine(
            ServerLevel level, BlockPos feet, BlockPos belowFeet, BlockState feetState) {
        if (!feetState.isAir() && !feetState.canBeReplaced()) {
            return false;
        }
        int slot = this.findSolidBuildingBlockSlotGoMine(level, feet);
        if (slot < 0) {
            return false;
        }
        BlockHitResult hit =
                new BlockHitResult(Vec3.atCenterOf(belowFeet).relative(Direction.UP, 0.35D), Direction.UP, belowFeet, false);
        return this.placeGoMineBuildingBlock(level, slot, feet, hit, true);
    }

    private boolean tryStackUnderFeetAirGoMine(ServerLevel level, BlockPos belowFeet) {
        BlockState belowState = level.getBlockState(belowFeet);
        if (!belowState.isAir() && !belowState.canBeReplaced()) {
            return false;
        }
        int slot = this.findSolidBuildingBlockSlotGoMine(level, belowFeet);
        if (slot < 0) {
            return false;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(belowFeet), Direction.UP, belowFeet, false);
        return this.placeGoMineBuildingBlock(level, slot, belowFeet, hit, true);
    }

    /**
     * Rise or side-step toward a goal column (ore block or deposit chest): bridges one block toward the anchor on
     * XZ, then pillars when the feet cell is air / void — same patterns as {@link KelvinStuckEscapeController}.
     */
    private boolean tryGoMineScaffold(ServerLevel level, BlockPos goal) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return false;
        }
        if (!this.hasSupportUnderFeetForScaffold(level)) {
            return false;
        }
        BlockPos feet = this.kelvin.blockPosition();
        BlockState feetState = level.getBlockState(feet);
        BlockPos belowFeet = feet.below();
        BlockState belowState = level.getBlockState(belowFeet);
        Vec3 g = Vec3.atCenterOf(goal);
        double dx = g.x - this.kelvin.getX();
        double dz = g.z - this.kelvin.getZ();
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
            int slot = this.findSolidBuildingBlockSlotGoMine(level, under);
            if (slot < 0) {
                continue;
            }
            BlockHitResult hit =
                    new BlockHitResult(Vec3.atCenterOf(under).relative(Direction.UP, 0.35D), Direction.UP, under, false);
            if (this.placeGoMineBuildingBlock(level, slot, at, hit, false)) {
                return true;
            }
        }
        if (!belowState.isAir() && !belowState.canBeReplaced()) {
            if (this.tryStackStandingOnSolidGoMine(level, feet, belowFeet, feetState)) {
                return true;
            }
        }
        return this.tryStackUnderFeetAirGoMine(level, belowFeet);
    }
}
