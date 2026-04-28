package ccc.dev.kelvin.cutscene;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.common.Tags;

/**
 * Intro helicopter must settle on real terrain (grass/dirt/stone/sand family), not on sugar cane, tall grass,
 * flowers, etc. — decorations are skipped when searching downward for support.
 */
public final class HeliIntroSolidGround {
    private HeliIntroSolidGround() {}

    /** Y coordinate of the top face of the highest acceptable solid support at (x,z). */
    public static int landingSurfaceY(ServerLevel level, int x, int z) {
        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos(x, 0, z);
        mut.set(x, level.getSeaLevel(), z);
        int startY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mut).getY();
        for (int y = startY; y > level.getMinBuildHeight() + 1; y--) {
            mut.set(x, y, z);
            BlockState st = level.getBlockState(mut);
            if (isSupportBlock(st)) {
                return y;
            }
            if (isPassThroughDecoration(st)) {
                continue;
            }
            if (st.isAir()) {
                continue;
            }
            // Mod blocks often lack vanilla dirt/stone tags but still count as surface footing.
            if (isGenericTerrainFooting(st)) {
                return y;
            }
            break;
        }
        return startY;
    }

    public static BlockPos landingSurfaceBlock(ServerLevel level, int x, int z) {
        int y = landingSurfaceY(level, x, z);
        return new BlockPos(x, y, z);
    }

    public static boolean isSupportBlock(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.is(BlockTags.LEAVES)) {
            return false;
        }
        if (state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.FARMLAND)) {
            return true;
        }
        if (state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.PLANKS)) {
            return true;
        }
        if (state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.MUD)
                || state.is(Blocks.PACKED_MUD)
                || state.is(Blocks.MUDDY_MANGROVE_ROOTS)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.SOUL_SAND)
                || state.is(Blocks.SOUL_SOIL)) {
            return true;
        }
        if (state.is(Tags.Blocks.STONE)
                || state.is(Tags.Blocks.COBBLESTONE)
                || state.is(Tags.Blocks.SAND)
                || state.is(Tags.Blocks.GRAVEL)
                || state.is(Tags.Blocks.SANDSTONE)
                || state.is(Tags.Blocks.END_STONES)
                || state.is(Tags.Blocks.NETHERRACK)
                || state.is(Tags.Blocks.OBSIDIAN)) {
            return true;
        }
        return isGenericTerrainFooting(state);
    }

    /**
     * Last-resort footing for mod blocks missing tags: solid collision, not fluid/leaves, not a typical replaceable
     * plant layer (those should pass {@link #isPassThroughDecoration} instead).
     */
    private static boolean isGenericTerrainFooting(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.is(BlockTags.LEAVES)) {
            return false;
        }
        return state.blocksMotion() && !state.canBeReplaced();
    }

    public static boolean isPassThroughDecoration(BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (state.is(BlockTags.LEAVES)) {
            return true;
        }
        if (state.is(Blocks.GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.VINE)
                || state.is(Blocks.MOSS_CARPET)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT)) {
            return true;
        }
        if (state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS)
                || state.is(BlockTags.CROPS)) {
            return true;
        }
        if (state.is(BlockTags.SAPLINGS)) {
            return true;
        }
        if (state.is(Blocks.SNOW)) {
            return true;
        }
        return false;
    }
}
