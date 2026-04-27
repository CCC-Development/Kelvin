package ccc.dev.kelvin.cutscene;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic intro crash location per world seed (anchored to shared spawn). The helicopter route is
 * derived from the same inputs so every playthrough of a given world hits the same spot.
 */
public final class HeliIntroWorldCrashSite {
    private HeliIntroWorldCrashSite() {}

    /**
     * Cache so multiple intro code paths (spawn Kelvin, structure placer, etc.) do not each repeat a search that can
     * sync-generate thousands of chunks on large packs.
     */
    private static final Map<String, BlockPos> SURFACE_CRASH_CACHE = new ConcurrentHashMap<>();

    /** Max Chebyshev radius (blocks on XZ) from the seed anchor to search for dry land if the anchor is in water. */
    private static final int LAND_SEARCH_MAX_RADIUS = 64;

    /** Cap spawn-area fallback ring (was 128; each ring sync-loads chunk columns). */
    private static final int SPAWN_SEARCH_MAX_RADIUS = 48;

    /**
     * Stop after this many column evaluations (heightmap + fluid scan) so a bad ocean seed cannot freeze the server
     * for minutes on first login.
     */
    private static final int MAX_LAND_SEARCH_STEPS = 2500;

    /** Horizontal half-extent for dry checks — Apache entity width is 14 (+ margin); must cover full AABB. */
    private static final double CRASH_VOLUME_HALF_XZ = 8.0;

    /**
     * Surface block the helicopter aims for: top of a {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES} column
     * (avoids treetops / leaf canopies that made the old MOTION_BLOCKING spot “land” in mid-air). The impact volume
     * must be clear of fluids. If the seed anchor column is wet, a deterministic ring search picks the nearest
     * suitable column.
     */
    public static BlockPos surfaceCrashBlock(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        String key =
                level.dimension().location()
                        + "|"
                        + level.getSeed()
                        + "|"
                        + spawn.getX()
                        + ","
                        + spawn.getZ();
        return SURFACE_CRASH_CACHE.computeIfAbsent(key, k -> computeSurfaceCrashBlock(level));
    }

    private static BlockPos computeSurfaceCrashBlock(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        long seed = level.getSeed();
        int ox = offsetFromSeed(seed, 0x6C656C69L, 241) - 120;
        int oz = offsetFromSeed(seed >>> 20, 0x436F7074L, 241) - 120;
        int bx = spawn.getX() + ox;
        int bz = spawn.getZ() + oz;
        BlockPos anchor = new BlockPos(bx, level.getSeaLevel(), bz);
        CountingSteps steps = new CountingSteps();
        BlockPos first = solidFootingColumn(level, columnTopNoLeaves(level, anchor));
        if (isLandCrashColumn(level, first)) {
            return first;
        }
        for (int r = 1; r <= LAND_SEARCH_MAX_RADIUS; r++) {
            if (steps.exceeded()) {
                break;
            }
            BlockPos found = landColumnOnSearchRing(level, bx, bz, r, steps);
            if (found != null) {
                return found;
            }
        }
        for (int r = 0; r <= SPAWN_SEARCH_MAX_RADIUS; r++) {
            if (steps.exceeded()) {
                break;
            }
            BlockPos found = landColumnOnSearchRing(level, spawn.getX(), spawn.getZ(), r, steps);
            if (found != null) {
                return found;
            }
        }
        return first;
    }

    private static final class CountingSteps {
        int n;

        void add(int delta) {
            n += delta;
        }

        boolean exceeded() {
            return n >= MAX_LAND_SEARCH_STEPS;
        }
    }

    /**
     * True if the Apache impact AABB (same math as {@code ApacheHelicopterEntity#configureIntroFlight}) is clear of
     * fluids and water/lava blocks — avoids approving a column whose center is dry but wings hang over the ocean.
     */
    private static boolean isLandCrashColumn(ServerLevel level, BlockPos motionBlockingTop) {
        BlockState support = level.getBlockState(motionBlockingTop);
        if (support.isAir() || support.is(BlockTags.LEAVES) || !HeliIntroSolidGround.isSupportBlock(support)) {
            return false;
        }
        double feetY = motionBlockingTop.getY() + 1.0;
        double cx = motionBlockingTop.getX() + 0.5;
        double cz = motionBlockingTop.getZ() + 0.5;
        double halfH = 2.5;
        double centerY = feetY + halfH;
        double yMin = centerY - halfH - 1.0;
        double yMax = centerY + halfH + 1.0;
        int minX = Mth.floor(cx - CRASH_VOLUME_HALF_XZ);
        int maxX = Mth.ceil(cx + CRASH_VOLUME_HALF_XZ) - 1;
        int minZ = Mth.floor(cz - CRASH_VOLUME_HALF_XZ);
        int maxZ = Mth.ceil(cz + CRASH_VOLUME_HALF_XZ) - 1;
        int minY = Mth.floor(yMin);
        int maxY = Mth.ceil(yMax);
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    mut.set(x, y, z);
                    if (!level.getFluidState(mut).isEmpty()) {
                        return false;
                    }
                    if (level.getBlockState(mut).is(Blocks.WATER) || level.getBlockState(mut).is(Blocks.LAVA)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static BlockPos landColumnOnSearchRing(
            ServerLevel level, int cx, int cz, int r, CountingSteps steps) {
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                    continue;
                }
                if (steps.exceeded()) {
                    return null;
                }
                steps.add(1);
                BlockPos column = new BlockPos(cx + dx, level.getSeaLevel(), cz + dz);
                BlockPos top = solidFootingColumn(level, columnTopNoLeaves(level, column));
                if (isLandCrashColumn(level, top)) {
                    return top;
                }
            }
        }
        return null;
    }

    private static BlockPos columnTopNoLeaves(ServerLevel level, BlockPos column) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
    }

    /** Same XZ as hint, Y lowered through plants/cane to grass/dirt/stone/sand-style support. */
    private static BlockPos solidFootingColumn(ServerLevel level, BlockPos motionTopHint) {
        return HeliIntroSolidGround.landingSurfaceBlock(level, motionTopHint.getX(), motionTopHint.getZ());
    }

    /**
     * High start west of the crash column, altitude above terrain — fully determined by seed, spawn, and
     * dimension id.
     */
    public static Vec3 introHeliSpawn(ServerLevel level, BlockPos surfaceCrash) {
        long seed = level.getSeed() ^ (long) level.dimension().location().hashCode();
        // Closer approach (blocks west of crash) so the intro reads faster; small deterministic jitter on Z.
        int dist = 42 + offsetFromSeed(seed, 0x48_65_6C_69L, 44);
        double jitterZ = (offsetFromSeed(seed >>> 27, 0x41_70_61_63L, 21) - 10) * 0.08;
        double feetY = surfaceCrash.getY() + 1.0;
        double y = feetY + HeliIntroCutsceneIds.INTRO_CRUISE_ABOVE_GROUND;
        return new Vec3(surfaceCrash.getX() + 0.5 - dist, y, surfaceCrash.getZ() + 0.5 + jitterZ);
    }

    private static int offsetFromSeed(long seed, long salt, int span) {
        long v = Long.rotateLeft(seed ^ salt, 17);
        return Math.floorMod((int) (v ^ (v >>> 32)), span);
    }
}
