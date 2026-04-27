package ccc.dev.kelvin.event;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.KelvinSummonerWorldData;
import ccc.dev.kelvin.entity.KelvinEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Maintains a direct set of all currently-loaded {@link KelvinEntity} instances on the server side.
 *
 * <p>This exists because {@code ServerLevel.getEntitiesOfClass(KelvinEntity.class, HUGE_AABB, ...)}
 * can silently return empty results when Kelvin's entity section has HIDDEN visibility (i.e. the
 * chunk is within render distance but not within the simulation/entity-ticking distance). The
 * event-driven tracker bypasses the AABB section lookup entirely by holding a direct reference to
 * each KelvinEntity that has joined any server level.
 */
@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class KelvinEntityTracker {
    private KelvinEntityTracker() {}

    private static final Set<KelvinEntity> LOADED = new LinkedHashSet<>();

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof KelvinEntity k) {
            LOADED.add(k);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof KelvinEntity k) {
            LOADED.remove(k);
            // Persist Kelvin's last known position so the summoner can point players toward him
            // even after his chunk unloads (not just when he is downed/killed).
            if (event.getLevel() instanceof ServerLevel sl && sl.getServer() != null) {
                KelvinSummonerWorldData data = KelvinSummonerWorldData.get(sl.getServer());
                if (data.getLastKnown() == null && !k.isRemoved()) {
                    data.remember(sl, k.blockPosition());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOADED.clear();
    }

    /**
     * Returns all live, non-removed Kelvin entities that have joined any server level since the
     * last server start. Dead/removed entities are pruned from the set lazily here.
     */
    public static List<KelvinEntity> getLoadedKelvins() {
        LOADED.removeIf(KelvinEntity::isRemoved);
        return new ArrayList<>(LOADED);
    }
}
