package ccc.dev.kelvin.event;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.HeliCrashSiteStructureData;
import ccc.dev.kelvin.world.GolfLootChestPlacer;
import ccc.dev.kelvin.world.GolfLootChestWorldData;
import ccc.dev.kelvin.world.GolfLootChestWorldData.GolfLootSite;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GolfLootChestEvents {
    private GolfLootChestEvents() {}

    /**
     * Fills in a second loot site on worlds that had only one (older mod versions), and no-ops once both exist.
     */
    @SubscribeEvent
    public static void onOverworldLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) {
            return;
        }
        if (sl.dimension() != Level.OVERWORLD) {
            return;
        }
        MinecraftServer server = sl.getServer();
        if (!HeliCrashSiteStructureData.get(server).isStructurePlaced()) {
            return;
        }
        GolfLootChestPlacer.tryPlaceNearCrashSite(sl, server);
    }

    @SubscribeEvent
    public static void onRightClickChest(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!event.getLevel().getBlockState(event.getPos()).is(Blocks.CHEST)) {
            return;
        }
        final var chestPos = event.getPos();
        ServerLevel level = player.serverLevel();
        GolfLootChestWorldData data = GolfLootChestWorldData.get(level.getServer());
        GolfLootSite site = data.findSiteForChest(chestPos);
        if (site == null || site.isBeaconCleared()) {
            return;
        }

        level.getServer().execute(() -> {
            GolfLootSite s = data.findSiteForChest(chestPos);
            if (s == null || s.isBeaconCleared()) {
                return;
            }
            if (!(player.containerMenu instanceof ChestMenu)) {
                return;
            }
            double d = player.distanceToSqr(
                    chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);
            if (d > 80) {
                return;
            }
            GolfLootChestPlacer.clearBeaconDecorations(level, data, s);
        });
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        LevelAccessor level = event.getLevel();
        if (level.isClientSide() || !(level instanceof ServerLevel sl)) {
            return;
        }
        GolfLootChestWorldData data = GolfLootChestWorldData.get(sl.getServer());
        if (data.isDecorationProtected(event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) {
            return;
        }
        GolfLootChestWorldData data = GolfLootChestWorldData.get(sl.getServer());
        event.getAffectedBlocks().removeIf(data::isDecorationProtected);
    }
}
