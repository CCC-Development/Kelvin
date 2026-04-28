package ccc.dev.kelvin.world;

import com.mrcrayfish.vehicle.init.ModBlocks;
import com.mrcrayfish.vehicle.init.ModEntities;
import com.mrcrayfish.vehicle.init.ModItems;
import com.mrcrayfish.vehicle.item.IDyeable;
import ccc.dev.kelvin.cutscene.HeliIntroSolidGround;
import ccc.dev.kelvin.cutscene.HeliIntroWorldCrashSite;
import ccc.dev.kelvin.world.GolfLootChestWorldData.GolfLootSite;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

/**
 * Places chests (wrench + golf cart crate), minimal iron beacon pads, and red stained glass. Runs once when the heli
 * crash structure is first placed.
 */
public final class GolfLootChestPlacer {
    private static final Logger LOG = LogUtils.getLogger();

    private GolfLootChestPlacer() {}

    /** Horizontal distance from crash column (blocks), inclusive range. */
    private static final int LOOT_MIN_DIST = 20;
    private static final int LOOT_DIST_SPAN = 11;

    /** Minimum horizontal distance between the two loot chests (blocks). */
    private static final int MIN_CHEST_SEPARATION = 28;

    /** Ring iterations when the primary offset fails (raised for crowded / modded terrain). */
    private static final int LOOT_RING_SEARCH_MAX = 16;

    private static int offsetFromSeed(long seed, long salt, int span) {
        long v = Long.rotateLeft(seed ^ salt, 17);
        return Math.floorMod((int) (v ^ (v >>> 32)), span);
    }

    public static void tryPlaceNearCrashSite(ServerLevel level, MinecraftServer server) {
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        GolfLootChestWorldData data = GolfLootChestWorldData.get(server);
        if (data.hasAllSites()) {
            return;
        }
        BlockPos crash = HeliIntroWorldCrashSite.surfaceCrashBlock(level);
        long seed = level.getSeed() ^ 0xB166E9E4C4A5L;

        if (data.getSites().isEmpty()) {
            if (!placeFirstSite(level, crash, seed, data)) {
                LOG.warn("Golf loot: could not place first chest near crash site.");
            }
        }
        if (data.hasAllSites()) {
            return;
        }
        if (data.getSites().isEmpty()) {
            return;
        }
        BlockPos avoid = data.getSites().get(0).getChestPos();
        if (!placeSecondSite(level, crash, seed, avoid, data)) {
            LOG.warn("Golf loot: could not place second chest far enough from first ({}).", avoid);
        }
    }

    private static boolean placeFirstSite(ServerLevel level, BlockPos crash, long seed, GolfLootChestWorldData data) {
        int dist = LOOT_MIN_DIST + offsetFromSeed(seed, 0xC4A5EEDL, LOOT_DIST_SPAN);
        int angleDeg = offsetFromSeed(seed >>> 20, 0x60F10F00L, 360);
        return placeAtOffsetWithRingSearch(level, crash, data, dist, angleDeg, null);
    }

    private static boolean placeSecondSite(
            ServerLevel level, BlockPos crash, long seed, BlockPos firstChest, GolfLootChestWorldData data) {
        double cx = firstChest.getX() + 0.5;
        double cz = firstChest.getZ() + 0.5;
        int minDistSq = MIN_CHEST_SEPARATION * MIN_CHEST_SEPARATION;

        for (int k = 0; k < 64; k++) {
            long sk = seed ^ Long.rotateLeft(0xC0FFEEB0B0L, k) ^ (k * 0x9E37L);
            int dist = LOOT_MIN_DIST + offsetFromSeed(sk, 0xD00D10L, LOOT_DIST_SPAN);
            int angleDeg = offsetFromSeed(sk >>> 24, 0xBEEF00L, 360);
            double rad = Math.toRadians(angleDeg);
            int dx = Mth.floor(Mth.cos((float) rad) * dist);
            int dz = Mth.floor(Mth.sin((float) rad) * dist);
            int gx = crash.getX() + dx;
            int gz = crash.getZ() + dz;
            double px = gx + 0.5;
            double pz = gz + 0.5;
            double ddx = px - cx;
            double ddz = pz - cz;
            if (ddx * ddx + ddz * ddz < minDistSq) {
                continue;
            }
            BlockPos grass = HeliIntroSolidGround.landingSurfaceBlock(level, gx, gz);
            if (tryPlaceAt(level, crash, grass, data)) {
                return true;
            }
            if (ringSearchFarFrom(level, crash, data, gx, gz, firstChest, minDistSq)) {
                return true;
            }
        }
        return false;
    }

    private static boolean placeAtOffsetWithRingSearch(
            ServerLevel level,
            BlockPos crash,
            GolfLootChestWorldData data,
            int dist,
            int angleDeg,
            BlockPos avoidChest) {
        double rad = Math.toRadians(angleDeg);
        int dx = Mth.floor(Mth.cos((float) rad) * dist);
        int dz = Mth.floor(Mth.sin((float) rad) * dist);
        int gx = crash.getX() + dx;
        int gz = crash.getZ() + dz;

        if (avoidChest != null) {
            double cx = avoidChest.getX() + 0.5;
            double cz = avoidChest.getZ() + 0.5;
            double minSq = (double) MIN_CHEST_SEPARATION * MIN_CHEST_SEPARATION;
            double px = gx + 0.5;
            double pz = gz + 0.5;
            if ((px - cx) * (px - cx) + (pz - cz) * (pz - cz) < minSq) {
                return false;
            }
        }

        BlockPos grass = HeliIntroSolidGround.landingSurfaceBlock(level, gx, gz);
        if (tryPlaceAt(level, crash, grass, data)) {
            return true;
        }
        if (avoidChest != null) {
            return ringSearchFarFrom(
                    level, crash, data, gx, gz, avoidChest, (double) MIN_CHEST_SEPARATION * MIN_CHEST_SEPARATION);
        }
        for (int ring = 1; ring <= LOOT_RING_SEARCH_MAX; ring++) {
            for (int oz = -ring; oz <= ring; oz++) {
                for (int ox = -ring; ox <= ring; ox++) {
                    if (Math.max(Math.abs(ox), Math.abs(oz)) != ring) {
                        continue;
                    }
                    BlockPos g2 = HeliIntroSolidGround.landingSurfaceBlock(level, gx + ox, gz + oz);
                    if (tryPlaceAt(level, crash, g2, data)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean ringSearchFarFrom(
            ServerLevel level,
            BlockPos crash,
            GolfLootChestWorldData data,
            int gx,
            int gz,
            BlockPos avoidChest,
            double minDistSq) {
        double cx = avoidChest.getX() + 0.5;
        double cz = avoidChest.getZ() + 0.5;
        for (int ring = 1; ring <= LOOT_RING_SEARCH_MAX; ring++) {
            for (int oz = -ring; oz <= ring; oz++) {
                for (int ox = -ring; ox <= ring; ox++) {
                    if (Math.max(Math.abs(ox), Math.abs(oz)) != ring) {
                        continue;
                    }
                    double px = gx + ox + 0.5;
                    double pz = gz + oz + 0.5;
                    double ddx = px - cx;
                    double ddz = pz - cz;
                    if (ddx * ddx + ddz * ddz < minDistSq) {
                        continue;
                    }
                    BlockPos g2 = HeliIntroSolidGround.landingSurfaceBlock(level, gx + ox, gz + oz);
                    if (tryPlaceAt(level, crash, g2, data)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean tryPlaceAt(ServerLevel level, BlockPos crash, BlockPos grassSupport, GolfLootChestWorldData data) {
        BlockPos chestPos = grassSupport.above();
        if (!canPlaceChestHere(level, chestPos)) {
            return false;
        }
        Direction chestFacing = Direction.getNearest(
                crash.getX() - chestPos.getX(), 0, crash.getZ() - chestPos.getZ());

        int gy = grassSupport.getY();
        int gx = grassSupport.getX();
        int gz = grassSupport.getZ();

        int pcx = gx + 4;
        int pcz = gz;
        BlockPos beaconBaseCenter = new BlockPos(pcx, gy, pcz);
        BlockPos beaconPos = beaconBaseCenter.above();
        BlockPos glassPos = beaconPos.above();

        // Do not require canSeeSky — biome/worldgen mods with dense leaves or unusual skylight blocked all placements.
        // Vanilla beacon beams still need sky above the glass when players clear foliage.

        List<BlockPos> decorations = new ArrayList<>();
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                BlockPos ip = new BlockPos(pcx + ox, gy, pcz + oz);
                if (!level.getBlockState(ip).getFluidState().isEmpty()) {
                    return false;
                }
                decorations.add(ip);
            }
        }
        decorations.add(beaconPos);
        decorations.add(glassPos);

        for (BlockPos ip : new ArrayList<>(decorations)) {
            if (ip.getY() == gy) {
                level.setBlock(ip, Blocks.IRON_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        level.setBlock(beaconPos, Blocks.BEACON.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(glassPos, Blocks.RED_STAINED_GLASS.defaultBlockState(), Block.UPDATE_ALL);

        level.setBlock(
                chestPos,
                Blocks.CHEST.defaultBlockState()
                        .setValue(ChestBlock.FACING, chestFacing)
                        .setValue(ChestBlock.TYPE, ChestType.SINGLE),
                Block.UPDATE_ALL);

        if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chestEntity)) {
            LOG.warn("Golf loot chest: chest block entity missing at {}", chestPos);
            return false;
        }

        ItemStack wrench = new ItemStack(ModItems.WRENCH.get());
        ResourceLocation golfId =
                Objects.requireNonNull(ForgeRegistries.ENTITY_TYPES.getKey(ModEntities.GOLF_CART.get()), "golf_cart");
        CompoundTag blockEntityTag = new CompoundTag();
        blockEntityTag.putString("vehicle", golfId.toString());
        blockEntityTag.putInt(IDyeable.NBT_KEY, 0xFFFFFF);
        CompoundTag itemTag = new CompoundTag();
        itemTag.put("BlockEntityTag", blockEntityTag);
        ItemStack golfCrate = new ItemStack(ModBlocks.VEHICLE_CRATE.get());
        golfCrate.setTag(itemTag);

        chestEntity.setItem(0, wrench);
        chestEntity.setItem(1, golfCrate);

        data.addPlacedSite(chestPos, decorations);
        LOG.info("Placed golf loot chest at {} with red beacon marker near crash site.", chestPos);
        return true;
    }

    private static boolean canPlaceChestHere(ServerLevel level, BlockPos chestPos) {
        if (level.isOutsideBuildHeight(chestPos)) {
            return false;
        }
        if (!level.getFluidState(chestPos).isEmpty() && level.getFluidState(chestPos).getType() != Fluids.EMPTY) {
            return false;
        }
        BlockPos below = chestPos.below();
        if (!HeliIntroSolidGround.isSupportBlock(level.getBlockState(below))) {
            return false;
        }
        return level.getBlockState(chestPos).canBeReplaced();
    }

    public static void clearBeaconDecorations(ServerLevel level, GolfLootChestWorldData data, GolfLootSite site) {
        if (site.isBeaconCleared()) {
            return;
        }
        for (BlockPos p : site.getDecorationPositions()) {
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockStateCheck.replaceWithAirIfOurDecoration(level, p);
        }
        site.markBeaconCleared();
        data.setDirty();
    }

    /** Avoid wiping player-built blocks if positions were reused. */
    private static final class BlockStateCheck {
        static void replaceWithAirIfOurDecoration(ServerLevel level, BlockPos p) {
            var st = level.getBlockState(p);
            if (st.is(Blocks.IRON_BLOCK) || st.is(Blocks.BEACON) || st.is(Blocks.RED_STAINED_GLASS)) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }
}
