package ccc.dev.kelvin.event;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.cutscene.HeliCrashSiteStructurePlacer;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Awards the crash-site advancement when the player is in range of the heli template footprint: chunk watch, a
 * one-time pass when the NBT is first placed, and a throttled server tick (reliable if chunk events are missed).
 */
@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CrashSiteAdvancementEvents {
    private static final ResourceLocation WHO_SHOT =
            ResourceLocation.fromNamespaceAndPath(CccKelvinMod.MOD_ID, "who_shot_the_helicopter");
    private static final int EXPAND_ON_CHUNK_WATCH = 16;
    private static final int EXPAND_ON_JUST_PLACED = 64;

    private CrashSiteAdvancementEvents() {}

    public static void onPlaced(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.level().dimension() != Level.OVERWORLD) {
                continue;
            }
            if (!(sp.level() instanceof ServerLevel sl) || sl != level) {
                continue;
            }
            tryAwardForChunkAndExpand(server, sl, sp, sp.chunkPosition(), EXPAND_ON_JUST_PLACED);
        }
    }

    @SubscribeEvent
    public static void onChunkWatched(ChunkWatchEvent.Watch event) {
        if (event.getLevel().dimension() != Level.OVERWORLD) {
            return;
        }
        ServerPlayer sp = event.getPlayer();
        if (sp.level().dimension() != event.getLevel().dimension()) {
            return;
        }
        MinecraftServer server = sp.getServer();
        if (server == null) {
            return;
        }
        if (!(sp.level() instanceof ServerLevel pLevel) || pLevel != event.getLevel()) {
            return;
        }
        tryAwardForChunkAndExpand(server, pLevel, sp, event.getPos(), EXPAND_ON_CHUNK_WATCH);
    }

    @SubscribeEvent
    public static void onPlayerTickEnd(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof ServerPlayer sp)) {
            return;
        }
        if (sp.tickCount % 15 != 0) {
            return;
        }
        if (sp.level().dimension() != Level.OVERWORLD) {
            return;
        }
        if (!(sp.level() instanceof ServerLevel sl)) {
            return;
        }
        MinecraftServer server = sp.getServer();
        if (server == null) {
            return;
        }
        tryAwardForChunkAndExpand(server, sl, sp, sp.chunkPosition(), EXPAND_ON_CHUNK_WATCH);
    }

    private static void tryAwardForChunkAndExpand(
            MinecraftServer server, ServerLevel level, ServerPlayer sp, ChunkPos chunk, int expandBlocks) {
        Advancement advancement = server.getAdvancements().getAdvancement(WHO_SHOT);
        if (advancement == null) {
            return;
        }
        if (sp.getAdvancements().getOrStartProgress(advancement).isDone()) {
            return;
        }
        if (!HeliCrashSiteStructurePlacer.chunkOverlapsHeliCrashSiteFootprint(server, level, chunk, expandBlocks)) {
            return;
        }
        for (String criterion : advancement.getCriteria().keySet()) {
            sp.getAdvancements().award(advancement, criterion);
        }
    }
}
