package ccc.dev.kelvin.entity;

import ccc.dev.kelvin.cutscene.HeliIntroCutsceneIds;
import ccc.dev.kelvin.event.HeliIntroImpactEvent;
import java.util.List;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.NetworkHooks;

/** Client-rendered glTF display entity (Apache-style helicopter). No AI; used for {@code /spawn-heli}. */
public final class ApacheHelicopterEntity extends Entity {

    private static final EntityDataAccessor<Boolean> DATA_INTRO_IMPACTED =
            SynchedEntityData.defineId(ApacheHelicopterEntity.class, EntityDataSerializers.BOOLEAN);

    private double introCrashX;
    private double introCrashY;
    private double introCrashZ;
    private double introCruiseY;
    /** Walkable surface Y (top of block column) for ground contact tests. */
    private double introFeetY;
    /** 0 = cruise toward column, 1 = dive to impact, 2 = grounded. */
    private byte introFlightPhase;
    private boolean introDiveRumblePlayed;
    private boolean introImpactEffectsPlayed;
    /**
     * Server-only: while {@code true}, intro cruise/dive does not advance (client is still loading the helicopter /
     * chunks). Uses {@link #introFlightTicks} instead of {@link #tickCount} for intro pacing.
     */
    private boolean introFlightPausedForClient;
    /** Server-only ticks spent in intro flight logic (increments only when not paused). */
    private int introFlightTicks;

    public ApacheHelicopterEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_INTRO_IMPACTED, false);
    }

    /** Synced to clients so impact-only camera shake can trigger. */
    public boolean isIntroImpacted() {
        return this.entityData.get(DATA_INTRO_IMPACTED);
    }

    private void setIntroImpacted() {
        this.entityData.set(DATA_INTRO_IMPACTED, true);
    }

    /**
     * Server-only route for the intro helicopter: deterministic crash column from {@link BlockPos} surface
     * heightmap.
     */
    public void configureIntroFlight(BlockPos surfaceCrash) {
        this.introFeetY = surfaceCrash.getY() + 1.0;
        double halfH = 2.5;
        this.introCrashX = surfaceCrash.getX() + 0.5;
        this.introCrashZ = surfaceCrash.getZ() + 0.5;
        this.introCrashY = this.introFeetY + halfH;
        this.introCruiseY = this.introFeetY + HeliIntroCutsceneIds.INTRO_CRUISE_ABOVE_GROUND;
        this.introFlightPhase = 0;
        this.introDiveRumblePlayed = false;
        this.introImpactEffectsPlayed = false;
        this.introFlightTicks = 0;
    }

    /** Server-only: freeze or resume intro movement while the viewing client syncs. */
    public void setIntroFlightPausedForClient(boolean paused) {
        if (this.level().isClientSide) {
            return;
        }
        this.introFlightPausedForClient = paused;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    /** True once the intro helicopter is a permanent world decoration (saved with chunk). */
    public boolean isIntroWreck() {
        return this.getPersistentData().getBoolean(HeliIntroCutsceneIds.ENTITY_INTRO_WRECK_TAG);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        if (this.isIntroWreck()) {
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        if (!this.getPersistentData().getBoolean(HeliIntroCutsceneIds.ENTITY_CUTSCENE_TAG)) {
            return;
        }
        if (this.isIntroImpacted()) {
            this.setPos(this.introCrashX, this.introCrashY, this.introCrashZ);
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }

        if (!(this.level() instanceof ServerLevel sl)) {
            return;
        }
        if (this.introFlightPausedForClient) {
            return;
        }
        this.introFlightTicks++;

        int forceDiveAt = HeliIntroCutsceneIds.DURATION_TICKS - HeliIntroCutsceneIds.INTRO_FORCE_DIVE_LAST_TICKS;

        if (this.introFlightPhase == 0) {
            this.tickIntroCruise(sl, forceDiveAt);
        } else if (this.introFlightPhase == 1) {
            this.tickIntroDive(sl);
        }
    }

    private void tickIntroCruise(ServerLevel sl, int forceDiveAt) {
        Vec3 p = this.position();
        double dx = this.introCrashX - p.x;
        double dz = this.introCrashZ - p.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        boolean forceDive = this.introFlightTicks >= forceDiveAt;
        if (horiz < 5.5 || forceDive) {
            this.introFlightPhase = 1;
            this.introDiveRumblePlayed = true;
            return;
        }

        Vec3 dir = new Vec3(dx, 0.0, dz).normalize();
        double spd = HeliIntroCutsceneIds.HELI_SPEED;
        double nx = p.x + dir.x * spd;
        double nz = p.z + dir.z * spd;
        double hr = Mth.clamp(horiz / 72.0, 0.0, 1.0);
        double floorAboveImpact =
                Mth.lerp(HeliIntroCutsceneIds.INTRO_GLIDE_FLOOR_NEAR_ABOVE_IMPACT, HeliIntroCutsceneIds.INTRO_GLIDE_FLOOR_FAR_ABOVE_IMPACT, hr);
        double minCenterY = this.introCrashY + floorAboveImpact;
        double ny = Math.max(minCenterY, p.y - HeliIntroCutsceneIds.INTRO_GLIDE_DESCEND_PER_TICK);
        this.setPos(nx, ny, nz);
        // Minecraft horizontal facing: atan2(dz, dx) - 90° (0° = south); atan2(dx,dz) faces perpendicular to motion.
        float targetYaw = introYawTowardDxDz(dir.x, dir.z);
        this.setYawAndHeadSmooth(targetYaw, HeliIntroCutsceneIds.INTRO_YAW_MAX_STEP_DEG);
        float cruiseT = Mth.clamp(this.introFlightTicks / (float) Math.max(1, forceDiveAt), 0.0F, 1.0F);
        float targetPitch = Mth.lerp(cruiseT, HeliIntroCutsceneIds.INTRO_CRUISE_PITCH_MIN, HeliIntroCutsceneIds.INTRO_CRUISE_PITCH_MAX);
        this.setXRot(approachAngleDegrees(this.getXRot(), targetPitch, HeliIntroCutsceneIds.INTRO_HELI_PITCH_MAX_STEP_DEG));
        if (this.introFlightTicks >= HeliIntroCutsceneIds.INTRO_PARTICLE_START_TICK) {
            this.spawnIntroGlideTrailParticles(sl);
        }
    }

    private void tickIntroDive(ServerLevel sl) {
        Vec3 p = this.position();
        Vec3 target = new Vec3(this.introCrashX, this.introCrashY, this.introCrashZ);
        Vec3 diff = target.subtract(p);
        double len = diff.length();
        double horizToImpact = Math.hypot(p.x - this.introCrashX, p.z - this.introCrashZ);
        boolean overImpactColumn = horizToImpact < 6.75;
        // Entity is bottom-anchored (getBoundingBox().minY == getY()), so minY never reaches
        // introFeetY + 0.52 — the old check was impossible. Instead trigger when entity Y is
        // within one dive-step of introCrashY (helicopter centre is at/near ground level).
        boolean nearCrashAltitude = this.getY() <= this.introCrashY + HeliIntroCutsceneIds.INTRO_DIVE_VECTOR_SPEED;
        boolean hitGround = overImpactColumn && nearCrashAltitude;

        if (hitGround) {
            this.completeIntroCrash(sl, p);
            return;
        }

        double stepLen = Math.min(len, HeliIntroCutsceneIds.INTRO_DIVE_VECTOR_SPEED);
        Vec3 dir = diff.normalize();
        // Extra downward pull so the dive reads as falling, not drifting in mid-air.
        Vec3 step = dir.scale(stepLen).add(0.0, -0.14, 0.0);
        this.setPos(p.add(step));
        double horiz = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        double horizP = Math.max(horiz, 1.0E-3);
        float aimPitch = (float) (-Mth.atan2(diff.y, horizP) * (double) Mth.RAD_TO_DEG);
        float targetPitch = Mth.clamp(aimPitch + 26.0F, 48.0F, 86.0F);
        this.setXRot(approachAngleDegrees(this.getXRot(), targetPitch, HeliIntroCutsceneIds.INTRO_HELI_PITCH_MAX_STEP_DEG));
        float targetYaw = introYawTowardDxDz(diff.x, diff.z);
        this.setYawAndHeadSmooth(targetYaw, HeliIntroCutsceneIds.INTRO_YAW_MAX_STEP_DEG);

        if (this.introFlightTicks >= HeliIntroCutsceneIds.INTRO_PARTICLE_START_TICK) {
            this.spawnIntroDiveParticles(sl);
        }
    }

    /**
     * If the cutscene timer ended before natural touchdown, snap the configured wreck so Kelvin spawn / world state
     * stay consistent.
     */
    public void finishIntroIntoWreckIfNeeded(ServerLevel sl) {
        if (this.level() != sl || this.isIntroWreck()) {
            return;
        }
        if (!this.getPersistentData().getBoolean(HeliIntroCutsceneIds.ENTITY_CUTSCENE_TAG)) {
            return;
        }
        if (this.isIntroImpacted()) {
            return;
        }
        this.completeIntroCrash(sl, this.position());
    }

    private void completeIntroCrash(ServerLevel sl, Vec3 motionHint) {
        this.introFlightPhase = 2;
        this.setIntroImpacted();
        this.setPos(this.introCrashX, this.introCrashY, this.introCrashZ);
        this.setDeltaMovement(Vec3.ZERO);
        this.setXRot(88.0F);
        float impactYaw = introYawTowardDxDz(this.introCrashX - motionHint.x, this.introCrashZ - motionHint.z);
        this.setYRot(impactYaw);
        this.setYHeadRot(impactYaw);
        if (!this.introImpactEffectsPlayed) {
            this.introImpactEffectsPlayed = true;
            this.playIntroImpactBurst(sl);
        }
        MinecraftForge.EVENT_BUS.post(new HeliIntroImpactEvent(sl, this));
        this.removeOtherIntroWrecksNear(sl, 6.0);
        this.getPersistentData().remove(HeliIntroCutsceneIds.ENTITY_CUTSCENE_TAG);
        this.discard();
    }

    /**
     * Impact particles only — the cutscene player gets explosion + screen shake together on the client via
     * {@link ccc.dev.kelvin.network.HeliIntroImpactPacket} to avoid double audio and desync.
     */
    private void playIntroImpactBurst(ServerLevel sl) {
        this.broadcastParticles(
                sl,
                ParticleTypes.EXPLOSION_EMITTER,
                this.introCrashX,
                this.introCrashY + 0.5,
                this.introCrashZ,
                1,
                0.0,
                0.0,
                0.0,
                0.0);
        this.broadcastParticles(
                sl,
                ParticleTypes.LARGE_SMOKE,
                this.introCrashX,
                this.introCrashY + 1.2,
                this.introCrashZ,
                36,
                2.2,
                0.8,
                2.2,
                0.12);
        this.broadcastParticles(
                sl,
                ParticleTypes.CAMPFIRE_COSY_SMOKE,
                this.introCrashX,
                this.introCrashY + 1.5,
                this.introCrashZ,
                10,
                1.2,
                0.4,
                1.2,
                0.02);
    }

    private void spawnIntroGlideTrailParticles(ServerLevel sl) {
        double cx = this.getX();
        double cy = this.getY() + 0.6;
        double cz = this.getZ();
        for (int i = 0; i < 5; i++) {
            double ox = (this.random.nextDouble() - 0.5) * 4.0;
            double oy = this.random.nextDouble() * 1.8;
            double oz = (this.random.nextDouble() - 0.5) * 4.0;
            this.broadcastParticles(
                    sl,
                    ParticleTypes.FLAME,
                    cx + ox,
                    cy + oy,
                    cz + oz,
                    1,
                    0.06,
                    -0.22,
                    0.06,
                    0.02);
        }
        if (this.introFlightTicks % 3 == 0) {
            this.broadcastParticles(
                    sl,
                    ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    cx,
                    cy + 1.0,
                    cz,
                    1,
                    0.5,
                    0.1,
                    0.5,
                    0.01);
        }
    }

    private void spawnIntroDiveParticles(ServerLevel sl) {
        double cx = this.getX();
        double cy = this.getY() + this.random.nextDouble() * 1.4;
        double cz = this.getZ();
        for (int i = 0; i < 14; i++) {
            double ox = (this.random.nextDouble() - 0.5) * 6.0;
            double oy = this.random.nextDouble() * 3.0;
            double oz = (this.random.nextDouble() - 0.5) * 6.0;
            this.broadcastParticles(
                    sl,
                    ParticleTypes.FLAME,
                    cx + ox,
                    cy + oy,
                    cz + oz,
                    1,
                    0.08,
                    -0.35,
                    0.08,
                    0.03);
        }
        if (this.introFlightTicks % 2 == 0) {
            this.broadcastParticles(
                    sl,
                    ParticleTypes.SMALL_FLAME,
                    cx + (this.random.nextDouble() - 0.5) * 4.0,
                    cy + 0.8,
                    cz + (this.random.nextDouble() - 0.5) * 4.0,
                    3,
                    0.25,
                    0.15,
                    0.25,
                    0.02);
        }
        this.broadcastParticles(
                sl,
                ParticleTypes.CAMPFIRE_COSY_SMOKE,
                cx,
                cy + 1.2,
                cz,
                2,
                0.9,
                0.15,
                0.9,
                0.01);
    }

    /**
     * Horizontal yaw (degrees) for entity motion toward (dx, dz), matching vanilla facing (0° = south, -90° =
     * east).
     */
    public static float introYawTowardDxDz(double dx, double dz) {
        return (float) (Mth.atan2(dz, dx) * (double) Mth.RAD_TO_DEG) - 90.0F;
    }

    private void setYawAndHeadSmooth(float targetYawDeg, float maxStepDeg) {
        float next = approachAngleDegrees(this.getYRot(), targetYawDeg, maxStepDeg);
        this.setYRot(next);
        this.setYHeadRot(next);
    }

    private static float approachAngleDegrees(float current, float target, float maxStepDeg) {
        float delta = Mth.wrapDegrees(target - current);
        if (Math.abs(delta) <= maxStepDeg) {
            return target;
        }
        return current + Math.copySign(maxStepDeg, delta);
    }

    private void removeOtherIntroWrecksNear(ServerLevel sl, double radius) {
        AABB box = new AABB(this.introCrashX, this.introCrashY, this.introCrashZ, this.introCrashX, this.introCrashY, this.introCrashZ)
                .inflate(radius);
        List<ApacheHelicopterEntity> others =
                sl.getEntitiesOfClass(ApacheHelicopterEntity.class, box, h -> h != this && h.isIntroWreck());
        for (ApacheHelicopterEntity other : others) {
            other.discard();
        }
    }

    private void broadcastParticles(ServerLevel sl, ParticleOptions type,
            double x,
            double y,
            double z,
            int count,
            double dx,
            double dy,
            double dz,
            double speed) {
        for (ServerPlayer player : sl.players()) {
            sl.sendParticles(player, type, true, x, y, z, count, dx, dy, dz, speed);
        }
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }
}
