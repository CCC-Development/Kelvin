package ccc.dev.kelvin.item;

import ccc.dev.kelvin.KelvinSummonerWorldData;
import ccc.dev.kelvin.ModItems;
import ccc.dev.kelvin.entity.KelvinEntity;
import ccc.dev.kelvin.event.KelvinEntityTracker;
import ccc.dev.kelvin.network.ModNetwork;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

public final class KelvinSummonerItem extends Item {
    /** Full horizontal bounds for scanning loaded Kelvins (only loaded chunks are checked). */
    private static final double WORLD_XZ = 30_000_000.0;
    private static final int USE_COOLDOWN_TICKS = 20;

    public KelvinSummonerItem(Properties properties) {
        super(properties);
    }

    /**
     * Right-click (or {@link ccc.dev.kelvin.event.KelvinGoMineEvents} left-click) a storage block while Go Mine is
     * active to set Kelvin's deposit chest.
     */
    public static boolean tryBindGoMineChest(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || player.getServer() == null) {
            return false;
        }
        KelvinEntity kelvin = findClosestGoMiningKelvinFor(player, serverLevel, pos);
        if (kelvin == null) {
            return false;
        }
        return kelvin.getGoMineController().trySetDepositChest(serverLevel, player, pos);
    }

    /**
     * Server-side {@code LeftClickBlock}: punch with the summoner on item storage would otherwise break the block;
     * cancel and run Go Mine deposit bind (or send a short message). On the integrated client, also call
     * {@link #shouldCancelClientSummonerPunchOnStorage} so the client does not start mining.
     *
     * @return {@code true} if the break should be cancelled (handled or a message was sent)
     */
    public static boolean onGoMineSummonerLeftPunchBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!isHoldingSummoner(player) || !level.isLoaded(pos)) {
            return false;
        }
        if (!isItemStorageBlock(level, pos)) {
            return false;
        }
        KelvinEntity kelvin = findClosestGoMiningKelvinFor(player, level, pos);
        if (kelvin == null) {
            player.sendSystemMessage(
                    Component.literal("Set a deposit by punching a chest only when Kelvin is in Go Mine and you started the run.")
                            .withStyle(ChatFormatting.GRAY));
            return true;
        }
        if (kelvin.getGoMineController().trySetDepositChest(level, player, pos)) {
            return true;
        }
        player.sendSystemMessage(
                Component.literal("Kelvin is not in Go Mine, or you did not start this mining run.")
                        .withStyle(ChatFormatting.GRAY));
        return true;
    }

    /**
     * Client-only: match server cancel for summoner + storage punch so singleplayer / local play does not mine the
     * chest before the server event runs.
     */
    public static boolean shouldCancelClientSummonerPunchOnStorage(Player player, Level level, BlockPos pos) {
        return isHoldingSummoner(player) && level.isLoaded(pos) && isItemStorageBlock(level, pos);
    }

    private static boolean isHoldingSummoner(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).is(ModItems.KELVIN_SUMMONER.get())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isItemStorageBlock(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, null).isPresent();
    }

    private static KelvinEntity findClosestGoMiningKelvinFor(ServerPlayer player, ServerLevel chestLevel, BlockPos chestPos) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return null;
        }
        Vec3 center = Vec3.atCenterOf(chestPos);
        UUID orderer = player.getUUID();

        KelvinEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (KelvinEntity k : KelvinEntityTracker.getLoadedKelvins()) {
            if (!aliveKelvin(k) || k.level().dimension() != chestLevel.dimension()) {
                continue;
            }
            if (!k.getGoMineController().isOrderedBy(orderer)) {
                continue;
            }
            double d = k.position().distanceToSqr(center);
            if (d < bestDist) {
                bestDist = d;
                best = k;
            }
        }
        if (best != null) {
            return best;
        }

        for (ServerLevel sl : server.getAllLevels()) {
            if (sl.dimension() != chestLevel.dimension()) {
                continue;
            }
            int y0 = sl.getMinBuildHeight();
            int y1 = sl.getMaxBuildHeight();
            AABB box = new AABB(-WORLD_XZ, y0, -WORLD_XZ, WORLD_XZ, y1, WORLD_XZ);
            List<KelvinEntity> list = sl.getEntitiesOfClass(KelvinEntity.class, box, KelvinSummonerItem::aliveKelvin);
            for (KelvinEntity k : list) {
                if (!k.getGoMineController().isOrderedBy(orderer)) {
                    continue;
                }
                double d = k.position().distanceToSqr(center);
                if (d < bestDist) {
                    bestDist = d;
                    best = k;
                }
            }
        }
        return best;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(ctx.getPlayer() instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (tryBindGoMineChest(serverPlayer, level, ctx.getClickedPos())) {
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }
        if (player.getCooldowns().isOnCooldown(stack.getItem())) {
            serverPlayer.sendSystemMessage(
                    Component.literal("Kelvin Summoner is cooling down — try again in a moment.")
                            .withStyle(ChatFormatting.GRAY));
            return InteractionResultHolder.pass(stack);
        }
        player.getCooldowns().addCooldown(stack.getItem(), USE_COOLDOWN_TICKS);

        MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            serverPlayer.sendSystemMessage(
                    Component.literal("Kelvin Summoner cannot run (no server).").withStyle(ChatFormatting.RED));
            return InteractionResultHolder.pass(stack);
        }

        KelvinEntity kelvin = findAnyLoadedKelvin(serverPlayer, server);

        if (kelvin != null) {
            ResourceLocation dim = kelvin.level().dimension().location();
            if (kelvin.isDowned()) {
                BlockPos pos = kelvin.blockPosition();
                ModNetwork.sendSummonerRevivalHud(serverPlayer, pos, dim);
                serverPlayer.sendSystemMessage(revivalCoordsMessage(pos, dim));
                return InteractionResultHolder.success(stack);
            }
            kelvin.interruptForSummonerCommand();
            KelvinSummonerWorldData.get(server).clear();
            if (!kelvin.summonBringToPlayer(serverPlayer)) {
                serverPlayer.sendSystemMessage(
                        Component.literal(
                                        "Kelvin was found in another dimension but could not be moved here. Try again at world spawn or a safer location.")
                                .withStyle(ChatFormatting.RED));
                return InteractionResultHolder.success(stack);
            }
            serverPlayer.sendSystemMessage(
                    Component.literal("Kelvin has been brought to you.").withStyle(ChatFormatting.GREEN));
            return InteractionResultHolder.success(stack);
        }

        KelvinSummonerWorldData.LastKnown last = KelvinSummonerWorldData.get(server).getLastKnown();
        if (last != null) {
            ModNetwork.sendSummonerRevivalHud(serverPlayer, last.pos(), last.dimension());
            serverPlayer.sendSystemMessage(revivalCoordsMessage(last.pos(), last.dimension()));
            serverPlayer.sendSystemMessage(
                    Component.literal(
                                    "Kelvin is not loaded anywhere right now (no active chunks contain him). "
                                            + "Travel toward the location above or load that area — any player can use this summoner.")
                            .withStyle(ChatFormatting.GOLD));
        } else {
            serverPlayer.sendSystemMessage(
                    Component.literal(
                                    "No Kelvin is loaded in this world, and no last position has been saved yet. "
                                            + "When Kelvin is hurt or downed, his coordinates are remembered for everyone.")
                            .withStyle(ChatFormatting.GOLD));
        }
        return InteractionResultHolder.success(stack);
    }

    private static Component revivalCoordsMessage(BlockPos pos, ResourceLocation dimension) {
        String dim = KelvinSummonerWorldData.describeDimension(dimension);
        return Component.literal(
                        "Kelvin is in " + dim + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                                + " — he needs to be revived.")
                .withStyle(ChatFormatting.GOLD);
    }

    /**
     * Any loaded Kelvin in the world; prefers the one in your dimension closest to you.
     *
     * <p>Primary lookup uses {@link KelvinEntityTracker} which is populated by entity join/leave
     * events and is not affected by Minecraft's entity-section visibility (HIDDEN vs TRACKED).
     * A secondary AABB-based scan across all server levels acts as a safety net for any entity
     * that somehow was not captured by the tracker.
     */
    private static KelvinEntity findAnyLoadedKelvin(ServerPlayer player, MinecraftServer server) {
        List<KelvinEntity> sameDim = new ArrayList<>();
        List<KelvinEntity> otherDim = new ArrayList<>();

        // Primary: event-driven tracker — works regardless of entity-section visibility.
        for (KelvinEntity k : KelvinEntityTracker.getLoadedKelvins()) {
            if (k.level().isClientSide() || !aliveKelvin(k)) {
                continue;
            }
            if (k.level().dimension() == player.level().dimension()) {
                sameDim.add(k);
            } else {
                otherDim.add(k);
            }
        }

        // Fallback: per-level AABB scan for entities not yet captured by the tracker.
        if (sameDim.isEmpty() && otherDim.isEmpty()) {
            for (ServerLevel sl : server.getAllLevels()) {
                int y0 = sl.getMinBuildHeight();
                int y1 = sl.getMaxBuildHeight();
                AABB box = new AABB(-WORLD_XZ, y0, -WORLD_XZ, WORLD_XZ, y1, WORLD_XZ);
                List<KelvinEntity> found =
                        sl.getEntitiesOfClass(KelvinEntity.class, box, KelvinSummonerItem::aliveKelvin);
                if (sl.dimension() == player.level().dimension()) {
                    sameDim.addAll(found);
                } else {
                    otherDim.addAll(found);
                }
            }
        }

        return sameDim.stream()
                .min(Comparator.comparingDouble(k -> k.distanceToSqr(player)))
                .orElseGet(() -> otherDim.stream().findAny().orElse(null));
    }

    private static boolean aliveKelvin(KelvinEntity k) {
        return k.isAlive() && !k.isRemoved();
    }
}
