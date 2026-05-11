package ccc.dev.kelvin.client;

import ccc.dev.kelvin.cutscene.HeliIntroCutsceneIds;
import ccc.dev.kelvin.entity.ApacheHelicopterEntity;
import ccc.dev.kelvin.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Client-side intro cutscene state (camera / HUD / letterbox). */
@OnlyIn(Dist.CLIENT)
public final class HeliIntroCutsceneClient {
    private static boolean active;
    private static int helicopterEntityId = -1;
    private static int ticksRemaining;
    private static int totalDurationTicks;
    /** Player look when the cutscene packet is applied (camera pan starts here). */
    private static float capturedStartYaw;
    private static float capturedStartPitch;
    /** Client level game time when the cutscene timeline started (after helicopter exists on client). */
    private static long cutsceneStartGameTime;
    /**
     * When {@code false}, HUD/camera stay in a loading gate (no letterbox): captured look, but {@link #getAgeTicks}
     * stays at 0 and {@link #ticksRemaining} does not count down until the helicopter entity is present.
     */
    private static boolean cutsceneTimelineRunning;
    /**
     * Impact arrived (e.g. server packet) before the client timeline started — explosion already played; apply
     * letterbox / reserve timing once {@link #beginCutsceneTimelineNow()} runs.
     */
    private static boolean pendingImpactTimelineSync;
    /** Ticks spent waiting for the helicopter while armed; forces timeline start to match server timeout. */
    private static int cutsceneReadyStallTicks;
    /**
     * Cutscene age (ticks) when impact was confirmed (entity sync or server packet); drives letterbox and camera
     * pan-out timing.
     */
    private static float impactAgeTicks = -1.0F;
    /** Cutscene age window for impact screen shake (client). */
    private static float impactShakeStartAge = -1.0F;
    private static float impactShakeEndAge   = -1.0F;

    /**
     * Camera direction the frame before the helicopter was discarded — used as the "from" angle
     * for the post-impact pan-back so the return sweep starts from wherever the camera actually was.
     */
    private static float impactLookYaw   = 0.0F;
    private static float impactLookPitch = 0.0F;

    /** Most recent camera yaw/pitch set by {@code onComputeCameraAngles} (updated every render frame). */
    private static float lastKnownCameraYaw   = 0.0F;
    private static float lastKnownCameraPitch = 0.0F;

    private HeliIntroCutsceneClient() {}

    public static void start(int entityId, int durationTicks) {
        Minecraft mc = Minecraft.getInstance();
        active = true;
        helicopterEntityId = entityId;
        totalDurationTicks = Math.max(1, durationTicks);
        ticksRemaining = totalDurationTicks;
        cutsceneTimelineRunning = false;
        cutsceneReadyStallTicks = 0;
        cutsceneStartGameTime = 0L;
        if (mc.player != null) {
            capturedStartYaw = mc.player.getYRot();
            capturedStartPitch = mc.player.getXRot();
        } else {
            capturedStartYaw = 0.0F;
            capturedStartPitch = 0.0F;
        }
        impactAgeTicks      = -1.0F;
        impactShakeStartAge = -1.0F;
        impactShakeEndAge   = -1.0F;
        impactLookYaw       = capturedStartYaw;
        impactLookPitch     = capturedStartPitch;
        lastKnownCameraYaw  = capturedStartYaw;
        lastKnownCameraPitch = capturedStartPitch;
        pendingImpactTimelineSync = false;
    }

    public static boolean isActive() {
        return active;
    }

    /** When false, letterbox/camera timeline is frozen until chunks + helicopter are ready on the client. */
    public static boolean isCutsceneTimelineRunning() {
        return cutsceneTimelineRunning;
    }

    /** Starts age, countdown, and notifies the server — call when the intro helicopter exists on the client. */
    public static void beginCutsceneTimelineNow() {
        if (!active || cutsceneTimelineRunning) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        cutsceneStartGameTime = mc.level.getGameTime();
        if (mc.player != null) {
            capturedStartYaw = mc.player.getYRot();
            capturedStartPitch = mc.player.getXRot();
        }
        cutsceneTimelineRunning = true;
        cutsceneReadyStallTicks = 0;
        if (pendingImpactTimelineSync) {
            impactAgeTicks = getAgeTicks(0.0F);
            ensurePostImpactTime();
            beginImpactShake();
            pendingImpactTimelineSync = false;
        }
        ModNetwork.sendHeliIntroCutsceneAckToServer(helicopterEntityId);
    }

    public static int getHelicopterEntityId() {
        return helicopterEntityId;
    }

    public static int getTotalDurationTicks() {
        return totalDurationTicks;
    }

    public static float getCapturedStartYaw() {
        return capturedStartYaw;
    }

    public static float getCapturedStartPitch() {
        return capturedStartPitch;
    }

    /** Camera direction frozen at the moment of confirmed impact (used as pan-out "from" angle). */
    public static float getImpactLookYaw() {
        return impactLookYaw;
    }

    public static float getImpactLookPitch() {
        return impactLookPitch;
    }

    /** Returns the cutscene age (ticks) when impact was first confirmed, or {@code -1} if not yet. */
    public static float getImpactAgeTicks() {
        return impactAgeTicks;
    }

    /**
     * Called by the camera render event every frame so we always know the last direction the camera
     * was pointing — captured at impact time to anchor the post-crash pan-out.
     */
    public static void setLastKnownCameraAngles(float yaw, float pitch) {
        lastKnownCameraYaw   = yaw;
        lastKnownCameraPitch = pitch;
    }

    /** Monotonic cutscene age in ticks, including sub-tick partial for smooth camera. */
    public static float getAgeTicks(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !cutsceneTimelineRunning) {
            return 0.0F;
        }
        return (float) (mc.level.getGameTime() - cutsceneStartGameTime) + partialTick;
    }

    /**
     * When impact is known, camera return starts right after the crash (plus hold), not after the long opening
     * pan-in window — using {@code max(panIn, impact)} previously left full letterbox + crash view for many seconds
     * when impact happened during the pan-in.
     */
    public static float getPanOutStartAge(float total, int panIn, int panOut) {
        if (impactAgeTicks >= 0.0F) {
            return impactAgeTicks + (float) HeliIntroCutsceneIds.CAMERA_HOLD_AFTER_IMPACT_TICKS;
        }
        // Until impact, keep tracking the helicopter (no early pan-back while it is still flying).
        return Float.POSITIVE_INFINITY;
    }

    /**
     * Letterbox pan-out begins immediately after impact (plus {@link HeliIntroCutsceneIds#LETTERBOX_HOLD_TICKS_AFTER_IMPACT});
     * does not wait on camera pan-in or an extra post-impact delay.
     */
    public static float getLetterboxPanOutStartAge(float total, int panIn) {
        if (impactAgeTicks >= 0.0F) {
            return impactAgeTicks + (float) HeliIntroCutsceneIds.LETTERBOX_HOLD_TICKS_AFTER_IMPACT;
        }
        // Until impact, stay at full letterbox — bars only ease out on the post-impact timeline.
        return Float.POSITIVE_INFINITY;
    }

    /**
     * When the client entity id is reused or the heli is removed, {@code getEntity(id)} is no longer an
     * {@link ApacheHelicopterEntity} but may still be non-null — in that case we must register impact or black bars
     * and shake never start. Call from tick and from {@link HeliIntroCutsceneForgeClientEvents#onComputeCameraAngles}
     * before the viewport camera handler returns a frozen view when impact is still unknown.
     */
    public static void applyImpactIfHeliGone() {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.level == null || helicopterEntityId < 0 || !cutsceneTimelineRunning) {
            return;
        }
        if (impactAgeTicks >= 0.0F) {
            return;
        }
        Entity e = mc.level.getEntity(helicopterEntityId);
        if (e == null || !(e instanceof ApacheHelicopterEntity)) {
            onHelicopterMissing();
        }
    }

    /** Server-notified impact: explosion + screen shake + particles and timeline (same client frame). */
    public static void applyServerImpact(int entityId, double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || entityId != helicopterEntityId || mc.level == null) {
            return;
        }
        // Spawn VFX regardless of timeline state so the explosion is never silent/invisible.
        if (impactAgeTicks < 0.0F) {
            spawnImpactVfx(mc, x, y, z, true, true);
        } else {
            // Already registered (maybe from entity detection); just secondary smoke.
            spawnImpactVfx(mc, x, y, z, false, false);
            return;
        }
        // If the timeline hasn't started yet (helicopter was outside tracking range, or entity removal
        // arrived before the tick that would have called beginCutsceneTimelineNow), start it NOW so the
        // impact can be registered immediately — waiting for the stall timer (600 ticks) meant 20+ s of bars.
        if (!cutsceneTimelineRunning) {
            beginCutsceneTimelineNow();
        }
        // cutsceneTimelineRunning should now be true (beginCutsceneTimelineNow succeeded);
        // if it still failed (level null etc.) fall back to the stall-timer path.
        if (!cutsceneTimelineRunning) {
            pendingImpactTimelineSync = true;
            return;
        }
        impactAgeTicks  = getAgeTicks(0.0F);
        impactLookYaw   = lastKnownCameraYaw;
        impactLookPitch = lastKnownCameraPitch;
        ensurePostImpactTime();
        beginImpactShake();
    }

    private static void ensurePostImpactTime() {
        ticksRemaining = HeliIntroCutsceneIds.postImpactCutsceneTailTicks();
    }

    /**
     * When impact is known, end the cutscene only after the letterbox, camera pan-out, AND screen shake have all
     * finished — this keeps the cutscene alive so the shake remains visible with bars gone.
     */
    private static boolean shouldReleaseCutsceneAfterImpactVisuals() {
        if (impactAgeTicks < 0.0F) {
            return false;
        }
        float age = getAgeTicks(0.0F);
        float total = (float) totalDurationTicks;
        int[] pans = HeliIntroCutsceneIds.resolveCameraPanTicks(total);
        float letterboxEnd =
                getLetterboxPanOutStartAge(total, pans[0]) + (float) HeliIntroCutsceneIds.LETTERBOX_PAN_OUT_DURATION_TICKS;
        float cameraEnd =
                impactAgeTicks
                        + (float) HeliIntroCutsceneIds.CAMERA_HOLD_AFTER_IMPACT_TICKS
                        + (float) HeliIntroCutsceneIds.POST_IMPACT_CAMERA_PAN_OUT_TICKS;
        float shakeEnd = impactShakeEndAge >= 0.0F
                ? impactShakeEndAge
                : (impactAgeTicks + (float) HeliIntroCutsceneIds.IMPACT_SCREEN_SHAKE_TICKS);
        float releaseAt = Math.max(Math.max(letterboxEnd, cameraEnd), shakeEnd) + 1.0F;
        return age >= releaseAt;
    }

    private static void beginImpactShake() {
        impactShakeStartAge = getAgeTicks(0.0F);
        impactShakeEndAge = impactShakeStartAge + (float) HeliIntroCutsceneIds.IMPACT_SCREEN_SHAKE_TICKS;
    }

    private static float impactShakeEnvelope(float age) {
        if (impactShakeEndAge < 0.0F || impactShakeStartAge < 0.0F) {
            return 0.0F;
        }
        if (age >= impactShakeEndAge) {
            return 0.0F;
        }
        float span = impactShakeEndAge - impactShakeStartAge;
        float u = (age - impactShakeStartAge) / Math.max(1.0E-3F, span);
        float e = 1.0F - Mth.clamp(u, 0.0F, 1.0F);
        return e * e;
    }

    private static float impactShakeOsc(float age, float hz, float phase) {
        float env = impactShakeEnvelope(age);
        if (env <= 0.0F) {
            return 0.0F;
        }
        double t = (double) age * (double) hz * (Math.PI * 2.0) + (double) phase;
        return (float) Math.sin(t) * env;
    }

    public static float getImpactShakeYawDegrees(float age) {
        return impactShakeOsc(age, 2.15F, 0.0F) * HeliIntroCutsceneIds.IMPACT_SCREEN_SHAKE_YAW_PEAK_DEG;
    }

    public static float getImpactShakePitchDegrees(float age) {
        return impactShakeOsc(age, 2.9F, 1.17F) * HeliIntroCutsceneIds.IMPACT_SCREEN_SHAKE_PITCH_PEAK_DEG;
    }

    public static float getImpactShakeRollDegrees(float age) {
        return impactShakeOsc(age, 2.45F, 2.4F) * HeliIntroCutsceneIds.IMPACT_SCREEN_SHAKE_ROLL_PEAK_DEG;
    }

    /**
     * Plays the impact explosion through {@link Minecraft#getSoundManager()} instead of the level local helper so it
     * still runs when the cutscene timeline is not running yet or crash coordinates are briefly wrong; when the player
     * exists, the sound is positioned at the listener for reliable volume.
     */
    private static void playHeliIntroExplosion(Minecraft mc, double x, double y, double z) {
        var level = mc.level;
        if (level == null) {
            return;
        }
        RandomSource rnd = level.random;
        float pitch = 0.42F + rnd.nextFloat() * 0.12F;
        double sx = x;
        double sy = y + 0.5;
        double sz = z;
        if (mc.player != null) {
            sx = mc.player.getX();
            sy = mc.player.getEyeY();
            sz = mc.player.getZ();
        }
        mc.getSoundManager()
                .play(
                        new SimpleSoundInstance(
                                SoundEvents.GENERIC_EXPLODE,
                                SoundSource.MASTER,
                                7.0F,
                                pitch,
                                rnd,
                                sx,
                                sy,
                                sz));
        ((ClientLevel) level).playLocalSound(sx, sy, sz, SoundEvents.GENERIC_EXPLODE, SoundSource.MASTER, 5.0F, pitch, false);
    }

    private static void spawnImpactVfx(Minecraft mc, double x, double y, double z, boolean fullBurst, boolean playExplosion) {
        var level = mc.level;
        if (level == null || !level.isClientSide) {
            return;
        }
        if (fullBurst) {
            if (playExplosion) {
                playHeliIntroExplosion(mc, x, y, z);
            }
            level.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.5, z, 0.0, 0.0, 0.0);
        }
        int count = fullBurst ? 32 : 10;
        for (int i = 0; i < count; i++) {
            double ox = (level.random.nextDouble() - 0.5) * 4.5;
            double oy = level.random.nextDouble() * 2.2;
            double oz = (level.random.nextDouble() - 0.5) * 4.5;
            level.addParticle(ParticleTypes.LARGE_SMOKE, x + ox, y + 0.9 + oy, z + oz, 0.2, 0.12, 0.2);
        }
    }

    /**
     * Run at client tick start so impact reacts as soon as entity sync shows the helicopter stopped (if the packet is
     * delayed).
     */
    public static void clientTickStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (active) {
                clear();
            }
            return;
        }
        if (!active || helicopterEntityId < 0) {
            return;
        }
        if (!cutsceneTimelineRunning) {
            Entity heliCheck = mc.level.getEntity(helicopterEntityId);
            if (heliCheck instanceof ApacheHelicopterEntity) {
                beginCutsceneTimelineNow();
            }
        }
        if (!cutsceneTimelineRunning) {
            return;
        }
        applyImpactIfHeliGone();
        Entity e = mc.level.getEntity(helicopterEntityId);
        if (e == null || !(e instanceof ApacheHelicopterEntity)) {
            return;
        }
        ApacheHelicopterEntity ah = (ApacheHelicopterEntity) e;
        if (ah.isIntroImpacted() && impactAgeTicks < 0.0F) {
            impactAgeTicks  = getAgeTicks(0.0F);
            impactLookYaw   = lastKnownCameraYaw;
            impactLookPitch = lastKnownCameraPitch;
            ensurePostImpactTime();
            beginImpactShake();
            spawnImpactVfx(mc, ah.getX(), ah.getY(), ah.getZ(), true, true);
        }
    }

    /** Run once per client tick end (cutscene countdown, helicopter missing). */
    public static void clientTickEnd() {
        Minecraft mc = Minecraft.getInstance();
        if (!active) {
            return;
        }
        if (mc.level == null) {
            clear();
            return;
        }
        if (!cutsceneTimelineRunning) {
            cutsceneReadyStallTicks++;
            if (cutsceneReadyStallTicks >= HeliIntroCutsceneIds.INTRO_CLIENT_ACK_TIMEOUT_TICKS) {
                beginCutsceneTimelineNow();
            }
        }
        applyImpactIfHeliGone();
        if (cutsceneTimelineRunning) {
            // Hard deadline: if the countdown is almost over and impact was never signalled (packet
            // lost / entity never tracked), force-register it so shake + bar-drop always happen.
            if (impactAgeTicks < 0.0F
                    && ticksRemaining <= HeliIntroCutsceneIds.IMPACT_SCREEN_SHAKE_TICKS + 5) {
                impactAgeTicks  = getAgeTicks(0.0F);
                impactLookYaw   = lastKnownCameraYaw;
                impactLookPitch = lastKnownCameraPitch;
                ensurePostImpactTime();
                beginImpactShake();
            }
            if (shouldReleaseCutsceneAfterImpactVisuals()) {
                clear();
                return;
            }
            ticksRemaining--;
            if (ticksRemaining <= 0) {
                clear();
            }
        }
    }

    public static void clear() {
        active = false;
        helicopterEntityId    = -1;
        ticksRemaining        = 0;
        totalDurationTicks    = 0;
        cutsceneStartGameTime = 0L;
        impactAgeTicks        = -1.0F;
        impactShakeStartAge   = -1.0F;
        impactShakeEndAge     = -1.0F;
        impactLookYaw         = 0.0F;
        impactLookPitch       = 0.0F;
        lastKnownCameraYaw    = 0.0F;
        lastKnownCameraPitch  = 0.0F;
        cutsceneTimelineRunning   = false;
        cutsceneReadyStallTicks   = 0;
        pendingImpactTimelineSync = false;
    }

    /**
     * Called when the helicopter entity is no longer in the client level.
     *
     * <ul>
     *   <li>If impact is already confirmed, {@link #shouldReleaseCutsceneAfterImpactVisuals()} owns
     *       shutdown — do nothing here (shake must finish first).</li>
     *   <li>If impact was <em>not</em> confirmed (packet lost / entity removed before entity-sync
     *       arrived), treat entity disappearance as the impact signal so the bars drop immediately and
     *       the cutscene does not hang at full bars for the remaining DURATION_TICKS (up to 18 s).</li>
     * </ul>
     */
    public static void onHelicopterMissing() {
        if (!active || ticksRemaining <= 0 || !cutsceneTimelineRunning) {
            return;
        }
        if (impactAgeTicks >= 0.0F) {
            // Already handled — shouldReleaseCutsceneAfterImpactVisuals() will fire when ready.
            return;
        }
        // No confirmed impact yet but helicopter is gone — same timing as server packet (shake + bars + short tail).
        impactAgeTicks  = getAgeTicks(0.0F);
        impactLookYaw   = lastKnownCameraYaw;
        impactLookPitch = lastKnownCameraPitch;
        ensurePostImpactTime();
        beginImpactShake();
    }
}
