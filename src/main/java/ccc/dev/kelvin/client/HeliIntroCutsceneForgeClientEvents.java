package ccc.dev.kelvin.client;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.cutscene.HeliIntroCutsceneIds;
import ccc.dev.kelvin.entity.ApacheHelicopterEntity;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HeliIntroCutsceneForgeClientEvents {
    private HeliIntroCutsceneForgeClientEvents() {}

    private static final double FOV_ZOOM = 0.78;

    private static float smoothStep(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static float lerpYawDegrees(float from, float to, float t) {
        return from + Mth.wrapDegrees(to - from) * t;
    }

    /** 0 = no bars, 1 = full letterbox height (smooth in at start, smooth out from {@code panOutStartAge}). */
    private static float letterboxHeightFactor(float age, int panIn, int panOut, float panOutStartAge) {
        boolean panOutKnown = !Float.isInfinite(panOutStartAge);
        // Use >= and instant 0 when panOut==0: strict > kept full bars for the whole tick when age == impactAge.
        if (panOutKnown && age + 1.0E-3F >= panOutStartAge) {
            if (panOut <= 1.0E-4F) {
                return 0.0F;
            }
            float outAge = age - panOutStartAge;
            float t = smoothStep(outAge / panOut);
            return 1.0F - t;
        }
        if (age < panIn) {
            return panIn > 1.0E-4F ? smoothStep(age / panIn) : 1.0F;
        }
        return 1.0F;
    }

    private static float[] computeHeliLookYawPitch(Minecraft mc, ApacheHelicopterEntity heli, float partial) {
        Vec3 eye = mc.player.getEyePosition(partial);
        Vec3 target = heli.getBoundingBox().getCenter();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double horizForPitch = Math.max(horiz, 1.0E-4);
        float yaw = (float) (Mth.atan2(dz, dx) * (double) Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-Mth.atan2(dy, horizForPitch) * (double) Mth.RAD_TO_DEG);
        pitch = Mth.clamp(pitch, -89.0F, 89.0F);
        return new float[] {yaw, pitch};
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            HeliIntroCutsceneClient.clientTickStart();
        } else if (event.phase == TickEvent.Phase.END) {
            HeliIntroCutsceneClient.clientTickEnd();
        }
    }

    @SubscribeEvent
    public static void onRenderArm(RenderArmEvent event) {
        if (!HeliIntroCutsceneClient.isActive()) {
            return;
        }
        if (event.getPlayer() != Minecraft.getInstance().player) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (!HeliIntroCutsceneClient.isActive()) {
            return;
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!HeliIntroCutsceneClient.isActive()) {
            return;
        }
        Input in = event.getInput();
        in.forwardImpulse = 0.0F;
        in.leftImpulse = 0.0F;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void hideVanillaOverlays(RenderGuiOverlayEvent.Pre event) {
        if (!HeliIntroCutsceneClient.isActive()) {
            return;
        }
        ResourceLocation id = event.getOverlay().id();
        if ("minecraft".equals(id.getNamespace())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void letterboxOnTop(RenderGuiEvent.Post event) {
        if (!HeliIntroCutsceneClient.isActive()) {
            return;
        }
        if (!HeliIntroCutsceneClient.isCutsceneTimelineRunning()) {
            return;
        }
        // Same as camera: do not wait for the next tick to notice id removal / id reuse.
        HeliIntroCutsceneClient.applyImpactIfHeliGone();
        float partial = event.getPartialTick();
        float age = HeliIntroCutsceneClient.getAgeTicks(partial);
        float total = HeliIntroCutsceneClient.getTotalDurationTicks();
        int[] pans = HeliIntroCutsceneIds.resolveCameraPanTicks(total);
        float panOutStart = HeliIntroCutsceneClient.getLetterboxPanOutStartAge(total, pans[0]);
        int letterboxPanDur = HeliIntroCutsceneIds.LETTERBOX_PAN_OUT_DURATION_TICKS;
        float barFactor = letterboxHeightFactor(age, pans[0], letterboxPanDur, panOutStart);
        if (barFactor <= 0.001F) {
            return;
        }
        Window w = event.getWindow();
        int gw = w.getGuiScaledWidth();
        int gh = w.getGuiScaledHeight();
        int maxBar = Math.max(22, gh * 12 / 100);
        int bar = Math.round(maxBar * barFactor);
        if (bar <= 0) {
            return;
        }
        int color = 0xFF000000;
        event.getGuiGraphics().fill(0, 0, gw, bar, color);
        event.getGuiGraphics().fill(0, gh - bar, gw, gh, color);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        float partial = (float) event.getPartialTick();
        if (!HeliIntroCutsceneClient.isActive()) {
            return;
        }
        if (!HeliIntroCutsceneClient.isCutsceneTimelineRunning()) {
            event.setYaw(HeliIntroCutsceneClient.getCapturedStartYaw());
            event.setPitch(Mth.clamp(HeliIntroCutsceneClient.getCapturedStartPitch(), -89.0F, 89.0F));
            event.setRoll(0.0F);
            return;
        }

        // Before testing presence: if id was recycled (non-null but not our Apache) or heli is gone, register impact.
        HeliIntroCutsceneClient.applyImpactIfHeliGone();
        Entity raw = mc.level.getEntity(HeliIntroCutsceneClient.getHelicopterEntityId());
        boolean heliPresent = raw instanceof ApacheHelicopterEntity;
        // If the entity is gone and impact was never confirmed, there is nothing useful to track —
        // freeze at start view until shouldReleaseCutsceneAfterImpactVisuals clears the cutscene.
        if (!heliPresent && HeliIntroCutsceneClient.getImpactAgeTicks() < 0.0F) {
            event.setYaw(HeliIntroCutsceneClient.getCapturedStartYaw());
            event.setPitch(Mth.clamp(HeliIntroCutsceneClient.getCapturedStartPitch(), -89.0F, 89.0F));
            event.setRoll(0.0F);
            return;
        }

        float age = HeliIntroCutsceneClient.getAgeTicks(partial);
        float total = HeliIntroCutsceneClient.getTotalDurationTicks();
        int[] pans = HeliIntroCutsceneIds.resolveCameraPanTicks(total);
        int panIn  = pans[0];
        int panOut = pans[1];
        float panOutStart = HeliIntroCutsceneClient.getPanOutStartAge(total, panIn, panOut);
        float startYaw   = HeliIntroCutsceneClient.getCapturedStartYaw();
        float startPitch = HeliIntroCutsceneClient.getCapturedStartPitch();

        // Live helicopter direction (only available while entity still exists).
        float heliYaw, heliPitch;
        if (heliPresent) {
            float[] hel = computeHeliLookYawPitch(mc, (ApacheHelicopterEntity) raw, partial);
            heliYaw   = hel[0];
            heliPitch = hel[1];
        } else {
            // Helicopter discarded after impact — fall back to the look captured at crash time.
            heliYaw   = HeliIntroCutsceneClient.getImpactLookYaw();
            heliPitch = HeliIntroCutsceneClient.getImpactLookPitch();
        }

        float yaw;
        float pitch;
        if (age < panIn) {
            // Smooth pan-in: player view → helicopter.
            float t = panIn > 1.0E-4F ? smoothStep(age / panIn) : 1.0F;
            yaw   = lerpYawDegrees(startYaw, heliYaw, t);
            pitch = Mth.lerp(t, startPitch, heliPitch);
        } else if (age > panOutStart) {
            // Short post-crash return to player (not the long opening panOut from resolveCameraPanTicks).
            int postOut = HeliIntroCutsceneIds.POST_IMPACT_CAMERA_PAN_OUT_TICKS;
            float fromYaw   = HeliIntroCutsceneClient.getImpactLookYaw();
            float fromPitch = HeliIntroCutsceneClient.getImpactLookPitch();
            float outAge = age - panOutStart;
            float t = postOut > 1.0E-4F ? smoothStep(outAge / postOut) : 1.0F;
            yaw   = lerpYawDegrees(fromYaw, startYaw, t);
            pitch = Mth.lerp(t, fromPitch, startPitch);
        } else {
            // Mid-cutscene: track helicopter.
            yaw   = heliYaw;
            pitch = heliPitch;
        }

        // Record the clean (pre-shake) direction for the pan-out anchor.
        HeliIntroCutsceneClient.setLastKnownCameraAngles(yaw, pitch);

        float shYaw   = HeliIntroCutsceneClient.getImpactShakeYawDegrees(age);
        float shPitch = HeliIntroCutsceneClient.getImpactShakePitchDegrees(age);
        float shRoll  = HeliIntroCutsceneClient.getImpactShakeRollDegrees(age);
        event.setYaw(yaw + shYaw);
        event.setPitch(Mth.clamp(pitch + shPitch, -89.0F, 89.0F));
        event.setRoll(shRoll);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!HeliIntroCutsceneClient.isActive()) {
            return;
        }
        if (!HeliIntroCutsceneClient.isCutsceneTimelineRunning()) {
            return;
        }
        float partial = (float) event.getPartialTick();
        float age = HeliIntroCutsceneClient.getAgeTicks(partial);
        float total = HeliIntroCutsceneClient.getTotalDurationTicks();
        int[] pans = HeliIntroCutsceneIds.resolveCameraPanTicks(total);
        int panIn = pans[0];
        int panOutLegacy = pans[1];
        float panOutStart = HeliIntroCutsceneClient.getPanOutStartAge(total, panIn, panOutLegacy);
        double mul;
        if (age < panIn) {
            float t = panIn > 1.0E-4F ? smoothStep(age / panIn) : 1.0F;
            mul = Mth.lerp(t, 1.0F, (float) FOV_ZOOM);
        } else if (age > panOutStart) {
            int postOut = HeliIntroCutsceneIds.POST_IMPACT_CAMERA_PAN_OUT_TICKS;
            float outAge = age - panOutStart;
            float t = postOut > 1.0E-4F ? smoothStep(outAge / postOut) : 1.0F;
            mul = Mth.lerp(t, (float) FOV_ZOOM, 1.0F);
        } else {
            mul = FOV_ZOOM;
        }
        event.setFOV(event.getFOV() * mul);
    }
}
