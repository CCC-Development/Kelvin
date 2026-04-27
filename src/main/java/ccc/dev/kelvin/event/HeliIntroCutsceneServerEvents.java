package ccc.dev.kelvin.event;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.KelvinIntroLandedWorldData;
import ccc.dev.kelvin.ModEntities;
import ccc.dev.kelvin.cutscene.HeliCrashSiteStructurePlacer;
import ccc.dev.kelvin.cutscene.HeliIntroCutsceneIds;
import ccc.dev.kelvin.cutscene.HeliIntroWorldCrashSite;
import ccc.dev.kelvin.entity.ApacheHelicopterEntity;
import ccc.dev.kelvin.entity.KelvinEntity;
import ccc.dev.kelvin.network.ModNetwork;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HeliIntroCutsceneServerEvents {
    private HeliIntroCutsceneServerEvents() {}

    /**
     * Neighbor columns within 1–2 blocks of Kelvin's feet (try cardinals and diagonals first, then distance 2 on axes).
     */
    private static final int[][] CAMPFIRE_OFFSETS =
            new int[][] {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}
            };

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static final class Session {
        final int helicopterId;
        int ticksLeft;
        /** If true, first-time intro: set {@link HeliIntroCutsceneIds#PLAYER_SEEN_TAG} when the session ends. */
        final boolean markIntroDoneWhenFinished;
        /**
         * While true, {@link #ticksLeft} does not count down and the intro helicopter stays frozen until the client
         * has loaded it and sends {@link ccc.dev.kelvin.network.HeliIntroCutsceneAckPacket}.
         */
        boolean awaitingClientAck;
        int ackStallTicks;

        Session(int helicopterId, int ticksLeft, boolean markIntroDoneWhenFinished) {
            this.helicopterId = helicopterId;
            this.ticksLeft = ticksLeft;
            this.markIntroDoneWhenFinished = markIntroDoneWhenFinished;
            this.awaitingClientAck = true;
            this.ackStallTicks = 0;
        }
    }

    /** Stops any active cutscene for this player and starts a new one (does not set the "intro seen" flag). */
    public static void forcePlayCutscene(ServerPlayer sp) {
        sp.getServer().execute(() -> forcePlayCutsceneDeferred(sp));
    }

    private static void forcePlayCutsceneDeferred(ServerPlayer sp) {
        Session existing = SESSIONS.remove(sp.getUUID());
        if (existing != null) {
            discardFlyingIntroHeli(sp.serverLevel(), existing.helicopterId);
        }
        startCutsceneSession(sp, false);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (sp.getPersistentData().getBoolean(HeliIntroCutsceneIds.PLAYER_SEEN_TAG)) {
            return;
        }
        sp.getServer().execute(() -> beginIntroIfNeeded(sp));
    }

    private static void beginIntroIfNeeded(ServerPlayer sp) {
        if (sp.getPersistentData().getBoolean(HeliIntroCutsceneIds.PLAYER_SEEN_TAG)) {
            return;
        }
        if (SESSIONS.containsKey(sp.getUUID())) {
            return;
        }
        startCutsceneSession(sp, true);
    }

    private static void startCutsceneSession(ServerPlayer sp, boolean markIntroDoneWhenFinished) {
        if (SESSIONS.containsKey(sp.getUUID())) {
            return;
        }
        ServerLevel level = sp.serverLevel();
        ApacheHelicopterEntity heli = ModEntities.APACHE_HELICOPTER.get().create(level);
        if (heli == null) {
            return;
        }
        BlockPos crashSurface = HeliIntroWorldCrashSite.surfaceCrashBlock(level);
        Vec3 start = HeliIntroWorldCrashSite.introHeliSpawn(level, crashSurface);
        heli.setPos(start.x, start.y, start.z);
        float spawnYaw =
                ApacheHelicopterEntity.introYawTowardDxDz(
                        (crashSurface.getX() + 0.5) - start.x, (crashSurface.getZ() + 0.5) - start.z);
        heli.setYRot(spawnYaw);
        heli.setYHeadRot(spawnYaw);
        heli.setXRot(HeliIntroCutsceneIds.INTRO_CRUISE_PITCH_MIN);
        heli.configureIntroFlight(crashSurface);
        heli.getPersistentData().putBoolean(HeliIntroCutsceneIds.ENTITY_CUTSCENE_TAG, true);
        // Do not sync-load ChunkStatus.FULL along the route here: it forces surface worldgen on many chunks at once
        // and can crash or stall (e.g. IncompatibleClassChangeError in SurfaceRules with some dev classpaths).
        level.addFreshEntity(heli);
        heli.setIntroFlightPausedForClient(true);
        SESSIONS.put(
                sp.getUUID(),
                new Session(heli.getId(), HeliIntroCutsceneIds.DURATION_TICKS, markIntroDoneWhenFinished));
        // One tick later: client receives cutscene packet; timeline + server countdown stay gated until ack (see
        // HeliIntroCutsceneAckPacket) so letterbox does not run ahead of loaded chunks.
        level.getServer()
                .execute(
                        () -> {
                            if (!sp.isAlive() || sp.connection == null) {
                                return;
                            }
                            ModNetwork.sendHeliIntroCutscene(
                                    sp, heli.getId(), HeliIntroCutsceneIds.DURATION_TICKS);
                        });
    }

    /**
     * Client has the intro helicopter in-world and started the shared cutscene clock; resume flight and session
     * countdown.
     */
    public static void onClientHeliIntroTimelineReady(@Nullable ServerPlayer sp, int helicopterEntityId) {
        if (sp == null) {
            return;
        }
        Session s = SESSIONS.get(sp.getUUID());
        if (s == null || s.helicopterId != helicopterEntityId) {
            return;
        }
        releaseClientIntroAckGate(sp.getServer(), s);
    }

    private static void releaseClientIntroAckGate(MinecraftServer server, Session s) {
        boolean first = s.awaitingClientAck;
        s.awaitingClientAck = false;
        if (first) {
            s.ticksLeft = HeliIntroCutsceneIds.DURATION_TICKS;
        }
        s.ackStallTicks = 0;
        unpauseIntroHelicopter(server, s.helicopterId);
    }

    private static void unpauseIntroHelicopter(MinecraftServer server, int entityId) {
        for (ServerLevel sl : server.getAllLevels()) {
            Entity e = sl.getEntity(entityId);
            if (e instanceof ApacheHelicopterEntity heli) {
                heli.setIntroFlightPausedForClient(false);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        Session s = SESSIONS.remove(sp.getUUID());
        if (s != null) {
            discardFlyingIntroHeli(sp.serverLevel(), s.helicopterId);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> e = it.next();
            Session s = e.getValue();
            if (s.awaitingClientAck) {
                s.ackStallTicks++;
                if (s.ackStallTicks > HeliIntroCutsceneIds.INTRO_CLIENT_ACK_TIMEOUT_TICKS) {
                    releaseClientIntroAckGate(event.getServer(), s);
                }
                continue;
            }
            s.ticksLeft--;
            if (s.ticksLeft > 0) {
                continue;
            }
            ServerPlayer sp = event.getServer().getPlayerList().getPlayer(e.getKey());
            if (sp != null) {
                if (s.markIntroDoneWhenFinished) {
                    ServerLevel level = sp.serverLevel();
                    Entity heliEnt = level.getEntity(s.helicopterId);
                    if (heliEnt instanceof ApacheHelicopterEntity apache) {
                        apache.finishIntroIntoWreckIfNeeded(level);
                    }
                    sp.getPersistentData().putBoolean(HeliIntroCutsceneIds.PLAYER_SEEN_TAG, true);
                    MinecraftServer server = level.getServer();
                    if (server != null && KelvinIntroLandedWorldData.get(server).hasKelvinLanded()) {
                        sp.sendSystemMessage(
                                Component.literal("Kelvin has already landed, you need to find him.")
                                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF8C00))));
                    } else {
                        spawnKelvinNearIntroHelicopter(sp, level, server);
                    }
                }
            } else {
                for (ServerLevel sl : event.getServer().getAllLevels()) {
                    discardFlyingIntroHeli(sl, s.helicopterId);
                }
            }
            it.remove();
        }
    }

    /**
     * Removes only the in-flight intro helicopter (abandoned session). Permanent wrecks at the crash site are
     * never discarded.
     */
    private static void discardFlyingIntroHeli(ServerLevel level, int entityId) {
        Entity ent = level.getEntity(entityId);
        if (ent instanceof ApacheHelicopterEntity heli && heli.isIntroWreck()) {
            return;
        }
        if (ent != null) {
            ent.discard();
        }
    }

    private static boolean kelvinBoundingBoxIntersectsWater(ServerLevel level, KelvinEntity kelvin) {
        AABB box = kelvin.getBoundingBox();
        BlockPos min = BlockPos.containing(box.minX + 1.0E-3, box.minY + 1.0E-3, box.minZ + 1.0E-3);
        BlockPos max = BlockPos.containing(box.maxX - 1.0E-3, box.maxY - 1.0E-3, box.maxZ - 1.0E-3);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            if (level.getFluidState(p).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean trySpawnKelvinAt(
            ServerLevel level, KelvinEntity kelvin, ServerPlayer sp, double px, double py, double pz, @Nullable MinecraftServer server) {
        kelvin.moveTo(px, py, pz, ApacheHelicopterEntity.introYawTowardDxDz(sp.getX() - px, sp.getZ() - pz), 0.0F);
        kelvin.setYHeadRot(kelvin.getYRot());
        if (kelvinBoundingBoxIntersectsWater(level, kelvin)) {
            return false;
        }
        if (!level.noCollision(kelvin)) {
            return false;
        }
        kelvin.setCustomName(Component.literal("Kelvin"));
        kelvin.setCustomNameVisible(true);
        level.addFreshEntity(kelvin);
        kelvin.applyHeliIntroSpawnDowned();
        kelvin.beginHeliIntroRevealHighlight();
        placeLitCampfireNearKelvin(level, kelvin);
        if (server != null) {
            KelvinIntroLandedWorldData.get(server).markKelvinLanded();
        }
        return true;
    }

    private static void placeLitCampfireNearKelvin(ServerLevel level, KelvinEntity kelvin) {
        BlockPos anchor = kelvin.blockPosition();
        for (int[] o : CAMPFIRE_OFFSETS) {
            BlockPos column = anchor.offset(o[0], 0, o[1]);
            BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
            BlockPos firePos = ground.above();
            if (!level.getBlockState(firePos).isAir()) {
                continue;
            }
            BlockState below = level.getBlockState(firePos.below());
            if (!below.isFaceSturdy(level, firePos.below(), Direction.UP)) {
                continue;
            }
            BlockState fire =
                    Blocks.CAMPFIRE
                            .defaultBlockState()
                            .setValue(CampfireBlock.LIT, true)
                            .setValue(CampfireBlock.SIGNAL_FIRE, false)
                            .setValue(CampfireBlock.FACING, Direction.from2DDataValue(level.random.nextInt(4)));
            if (fire.canSurvive(level, firePos)) {
                level.setBlock(firePos, fire, 3);
                return;
            }
        }
    }

    private static void spawnKelvinNearIntroHelicopter(
            ServerPlayer sp, ServerLevel level, @Nullable MinecraftServer server) {
        KelvinEntity kelvin = ModEntities.KELVIN.get().create(level);
        if (kelvin == null) {
            return;
        }
        BlockPos crash = HeliIntroWorldCrashSite.surfaceCrashBlock(level);
        // Stand on the crash site: top of the impact column and structure, not a random offset away.
        for (int ring = 0; ring < 4; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos column = crash.offset(dx, 0, dz);
                    BlockPos floor = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
                    double px = floor.getX() + 0.5;
                    double py = floor.getY() + 1.0;
                    double pz = floor.getZ() + 0.5;
                    if (trySpawnKelvinAt(level, kelvin, sp, px, py, pz, server)) {
                        return;
                    }
                }
            }
        }
        BlockPos spPos = sp.blockPosition();
        for (int k = 0; k < 32; k++) {
            BlockPos t = spPos.offset(level.random.nextInt(15) - 7, 0, level.random.nextInt(15) - 7);
            BlockPos floor = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, t);
            double px = floor.getX() + 0.5;
            double py = floor.getY() + 1.0;
            double pz = floor.getZ() + 0.5;
            if (trySpawnKelvinAt(level, kelvin, sp, px, py, pz, server)) {
                return;
            }
        }
        kelvin.discard();
    }

    @SubscribeEvent
    public static void onHeliIntroImpact(HeliIntroImpactEvent event) {
        ApacheHelicopterEntity heli = event.getHelicopter();
        ServerLevel level = event.getLevel();
        if (level.getServer() == null) {
            return;
        }
        if (HeliCrashSiteStructurePlacer.tryPlaceOnFirstImpact(level.getServer(), level)) {
            CrashSiteAdvancementEvents.onPlaced(level);
        }
        UUID matching = null;
        for (Map.Entry<UUID, Session> e : SESSIONS.entrySet()) {
            if (e.getValue().helicopterId == heli.getId()) {
                matching = e.getKey();
                break;
            }
        }
        if (matching == null) {
            return;
        }
        Session s = SESSIONS.get(matching);
        if (s != null && !s.awaitingClientAck) {
            s.ticksLeft = HeliIntroCutsceneIds.postImpactCutsceneTailTicks();
        }
        ServerPlayer sp = level.getServer().getPlayerList().getPlayer(matching);
        if (sp != null) {
            ModNetwork.sendHeliIntroImpact(sp, heli.getId(), heli.getX(), heli.getY(), heli.getZ());
        }
    }
}
