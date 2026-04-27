package ccc.dev.kelvin.cutscene;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.HeliCrashSiteStructureData;
import ccc.dev.kelvin.world.GolfLootChestPlacer;
import com.mojang.logging.LogUtils;
import com.google.common.collect.ImmutableList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/**
 * Spawns the packaged {@code data/ccc_kelvin/structures/kelvin_crash_site} template once per save, centered on the
 * deterministic intro crash column, when the first intro helicopter actually impacts the ground in the Overworld.
 */
@SuppressWarnings("removal")
public final class HeliCrashSiteStructurePlacer {
    private static final Logger LOG = LogUtils.getLogger();
    public static final ResourceLocation CRASH_TEMPLATE_ID =
            new ResourceLocation(CccKelvinMod.MOD_ID, "kelvin_crash_site");

    private HeliCrashSiteStructurePlacer() {}

    /**
     * True when a chunk column (XZ) intersects the heli crash template footprint, using the same worldCorner / size
     * math as {@link #tryPlaceOnFirstImpact}. The location is deterministic from seed; the NBT need not be placed yet.
     *
     * @param expandBlocksXz add this many blocks to each XZ side of the NBT AABB (e.g. 16 for one extra chunk)
     */
    public static boolean chunkOverlapsHeliCrashSiteFootprint(
            MinecraftServer server, ServerLevel level, ChunkPos chunk, int expandBlocksXz) {
        if (level.dimension() != Level.OVERWORLD) {
            return false;
        }
        return chunkOverlapsFootprint(server, level, chunk, expandBlocksXz);
    }

    private static boolean chunkOverlapsFootprint(
            MinecraftServer server, ServerLevel level, ChunkPos chunk, int expandBlocksXz) {
        BlockPos surface = HeliIntroWorldCrashSite.surfaceCrashBlock(level);
        StructureTemplate template = server.getStructureManager().get(CRASH_TEMPLATE_ID).orElse(null);
        int minX;
        int maxX;
        int minZ;
        int maxZ;
        if (template == null) {
            int r = 12 + expandBlocksXz;
            minX = surface.getX() - r;
            maxX = surface.getX() + r;
            minZ = surface.getZ() - r;
            maxZ = surface.getZ() + r;
        } else {
            Vec3i size = template.getSize();
            if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
                return false;
            }
            int wx = Mth.floor((double) surface.getX() + 0.5 - (double) size.getX() / 2.0);
            int wz = Mth.floor((double) surface.getZ() + 0.5 - (double) size.getZ() / 2.0);
            minX = wx - expandBlocksXz;
            maxX = wx + size.getX() - 1 + expandBlocksXz;
            minZ = wz - expandBlocksXz;
            maxZ = wz + size.getZ() - 1 + expandBlocksXz;
        }
        int cMinX = chunk.getBlockX(0);
        int cMaxX = chunk.getBlockX(15);
        int cMinZ = chunk.getBlockZ(0);
        int cMaxZ = chunk.getBlockZ(15);
        return !(maxX < cMinX || minX > cMaxX || maxZ < cMinZ || minZ > cMaxZ);
    }

    /** Y of template local 0: matches {@link #tryPlaceOnFirstImpact} origin. */
    private static int templateBaseYAtSurface(BlockPos surface) {
        return surface.getY() + 1 - 3;
    }

    /**
     * @return true if the template was placed this call (first impact only); false if already placed, wrong
     *     dimension, or template missing.
     */
    public static boolean tryPlaceOnFirstImpact(MinecraftServer server, ServerLevel impactLevel) {
        if (impactLevel.dimension() != Level.OVERWORLD) {
            return false;
        }
        HeliCrashSiteStructureData data = HeliCrashSiteStructureData.get(server);
        if (data.isStructurePlaced()) {
            return false;
        }
        StructureTemplate template = server.getStructureManager().get(CRASH_TEMPLATE_ID).orElse(null);
        if (template == null) {
            LOG.error(
                    "Missing heli crash structure NBT: {} (expected in-jar data/{}/structures/{}.nbt)",
                    CRASH_TEMPLATE_ID,
                    CccKelvinMod.MOD_ID,
                    CRASH_TEMPLATE_ID.getPath());
            return false;
        }
        BlockPos surface = HeliIntroWorldCrashSite.surfaceCrashBlock(impactLevel);
        Vec3i size = template.getSize();
        int wx = Mth.floor((double) surface.getX() + 0.5 - (double) size.getX() / 2.0);
        int wz = Mth.floor((double) surface.getZ() + 0.5 - (double) size.getZ() / 2.0);
        int baseY = templateBaseYAtSurface(surface);
        BlockPos worldCorner = new BlockPos(wx, baseY, wz);
        RandomSource random = impactLevel.getRandom();
        StructurePlaceSettings settings = new StructurePlaceSettings();
        settings
                .setRandom(random)
                .setIgnoreEntities(true)
                .setKeepLiquids(false)
                // Do not place template air: leaves existing world blocks where the NBT is air.
                .addProcessor(
                        new BlockIgnoreProcessor(ImmutableList.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR)));
        template.placeInWorld(impactLevel, worldCorner, BlockPos.ZERO, settings, random, Block.UPDATE_CLIENTS);
        data.markPlaced();
        GolfLootChestPlacer.tryPlaceNearCrashSite(impactLevel, server);
        return true;
    }
}
