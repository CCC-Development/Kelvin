package ccc.dev.kelvin.cutscene;

import ccc.dev.kelvin.CccKelvinMod;

/** NBT / packet constants for the one-time Apache helicopter intro cutscene. */
public final class HeliIntroCutsceneIds {
    private HeliIntroCutsceneIds() {}

    public static final String ENTITY_CUTSCENE_TAG = CccKelvinMod.MOD_ID + ":intro_heli";
    /** Legacy / editor: intro wreck marker (the scripted intro helicopter is discarded after impact instead). */
    public static final String ENTITY_INTRO_WRECK_TAG = CccKelvinMod.MOD_ID + ":intro_heli_wreck";
    public static final String PLAYER_SEEN_TAG = CccKelvinMod.MOD_ID + ":heli_intro_done";

    /** Cutscene length in ticks (server and client): ~18 s at 20 TPS — slow cruise/dive pacing. */
    public static final int DURATION_TICKS = 360;

    /**
     * Server: if the client never acknowledges intro readiness, unfreeze the helicopter and start the session countdown
     * after this many ticks (~30 s) so the world cannot soft-lock.
     */
    public static final int INTRO_CLIENT_ACK_TIMEOUT_TICKS = 600;

    /** Black bars drop instantly on impact: no hold, no fade. */
    public static final int LETTERBOX_HOLD_TICKS_AFTER_IMPACT = 0;

    /** 0 → letterboxHeightFactor returns 0 the tick after impact (instant drop). */
    public static final int LETTERBOX_PAN_OUT_DURATION_TICKS = 0;

    public static final int LETTERBOX_CLEAR_BEFORE_CUTSCENE_END_TICKS = 0;

    /** Camera begins returning to player view immediately after impact. */
    public static final int CAMERA_HOLD_AFTER_IMPACT_TICKS = 0;

    /**
     * Ease from crash view back to the player camera after impact. Keep short so gameplay resumes quickly; this is
     * separate from the long opening {@link #CAMERA_PAN_OUT_TICKS} used in {@link #resolveCameraPanTicks(float)}.
     */
    public static final int POST_IMPACT_CAMERA_PAN_OUT_TICKS = 7;

    /** Screen-shake duration after impact (ticks). Cutscene stays alive until shake finishes. */
    public static final int IMPACT_SCREEN_SHAKE_TICKS = 28;

    /** Peak camera jitter on impact — tuned for a violent crash feel. */
    public static final float IMPACT_SCREEN_SHAKE_YAW_PEAK_DEG = 12.0F;
    public static final float IMPACT_SCREEN_SHAKE_PITCH_PEAK_DEG = 8.0F;
    public static final float IMPACT_SCREEN_SHAKE_ROLL_PEAK_DEG = 10.0F;

    /** Camera ease from current view toward the helicopter at cutscene start (2 s at 20 TPS). */
    public static final int CAMERA_PAN_IN_TICKS = 40;

    /** Camera ease back to the captured view after crash (0.7 s at 20 TPS). */
    public static final int CAMERA_PAN_OUT_TICKS = 14;

    /**
     * Returns {@code { panIn, panOut }} — pan-in and pan-out use their own caps so a short pan-out
     * does not limit the long, cinematic pan-in.
     */
    public static int[] resolveCameraPanTicks(float totalDuration) {
        int total = Math.max(1, (int) totalDuration);
        int panIn  = Math.min(CAMERA_PAN_IN_TICKS,  Math.max(4, total / 5));
        int panOut = Math.min(CAMERA_PAN_OUT_TICKS, Math.max(4, total / 10));
        while (panIn + panOut > total - 4) {
            panIn  = Math.max(2, panIn  - 1);
            panOut = Math.max(2, panOut - 1);
        }
        return new int[] {panIn, panOut};
    }

    /**
     * Remaining server (and client {@code ticksRemaining}) ticks after impact — covers short post-crash camera return,
     * letterbox, and screen shake; see client {@code shouldReleaseCutsceneAfterImpactVisuals}.
     */
    public static int postImpactCutsceneTailTicks() {
        int cameraTail = CAMERA_HOLD_AFTER_IMPACT_TICKS + POST_IMPACT_CAMERA_PAN_OUT_TICKS;
        int letterboxTail =
                LETTERBOX_HOLD_TICKS_AFTER_IMPACT + LETTERBOX_PAN_OUT_DURATION_TICKS + LETTERBOX_CLEAR_BEFORE_CUTSCENE_END_TICKS;
        return Math.max(Math.max(cameraTail, letterboxTail), IMPACT_SCREEN_SHAKE_TICKS) + 2;
    }

    /** Cruise altitude above surface block at the crash column. */
    public static final double INTRO_CRUISE_ABOVE_GROUND = 32.0;

    /** Horizontal blocks per tick while cruising toward the crash column. */
    public static final double HELI_SPEED = 0.68;

    /**
     * If the helicopter has not reached the crash column yet, force the final dive in the last N ticks of
     * the cutscene window (must leave room for camera pan in/out).
     */
    public static final int INTRO_FORCE_DIVE_LAST_TICKS = 78;

    /** Blocks moved per tick along the final approach vector (gentler than a vertical slam). */
    public static final double INTRO_DIVE_VECTOR_SPEED = 0.95;

    /** Nose-down pitch while cruising (degrees). */
    public static final float INTRO_CRUISE_PITCH_MIN = 22.0F;

    /** Nose-down pitch at end of cruise (degrees). */
    public static final float INTRO_CRUISE_PITCH_MAX = 46.0F;

    /** Vertical drop per tick while gliding (cruise + approach); gentler so the route can settle toward dry land. */
    public static final double INTRO_GLIDE_DESCEND_PER_TICK = 0.12;

    /**
     * Minimum entity-center height above the impact center Y when far horizontally (lerps down as the
     * helicopter nears the column).
     */
    public static final double INTRO_GLIDE_FLOOR_FAR_ABOVE_IMPACT = 22.0;

    public static final double INTRO_GLIDE_FLOOR_NEAR_ABOVE_IMPACT = 7.0;

    /** Flame/smoke trail: start at first tick of the cutscene flight. */
    public static final int INTRO_PARTICLE_START_TICK = 0;

    /** Max yaw change per tick while turning toward velocity (degrees); keeps banking turns smooth. */
    public static final float INTRO_YAW_MAX_STEP_DEG = 6.5F;

    /** Max pitch change per tick for intro cruise and dive (degrees). */
    public static final float INTRO_HELI_PITCH_MAX_STEP_DEG = 7.0F;
}
