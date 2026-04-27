package ccc.dev.kelvin.event;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.entity.KelvinEntity;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import javax.annotation.Nullable;

@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class KelvinProtectOwnerEvents {
    /** Player hurt → Kelvin reacts only if Kelvin is this close to the player. */
    private static final double NOTIFY_RADIUS = 32.0D;
    /** Only retaliate against attackers this close to the hurt player. */
    private static final double ATTACKER_TO_PLAYER_RADIUS_SQR = 32.0D * 32.0D;

    private KelvinProtectOwnerEvents() {}

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().isClientSide) {
            return;
        }
        notifyKelvinsOfThreat(player, event.getSource());
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().isClientSide) {
            return;
        }
        notifyKelvinsOfThreat(player, event.getSource());
    }

    private static void notifyKelvinsOfThreat(ServerPlayer player, DamageSource source) {
        LivingEntity attacker = resolveLivingMobAttacker(source);
        if (attacker == null) {
            return;
        }
        if (attacker instanceof KelvinEntity) {
            return;
        }
        if (attacker instanceof TamableAnimal pet && player.getUUID().equals(pet.getOwnerUUID())) {
            return;
        }
        if (attacker.distanceToSqr(player) > ATTACKER_TO_PLAYER_RADIUS_SQR) {
            return;
        }
        AABB box = player.getBoundingBox().inflate(NOTIFY_RADIUS);
        List<KelvinEntity> allies = player.level().getEntitiesOfClass(KelvinEntity.class, box, ke ->
                ke.isAlive()
                        && !ke.isRemoved()
                        && !ke.isDowned()
                        && ke.protectsPlayer(player.getUUID()));
        for (KelvinEntity kelvin : allies) {
            kelvin.setTarget(attacker);
        }
    }

    /**
     * Resolves skeleton shooters (and similar) from arrows/projectiles; vanilla {@link DamageSource#getEntity()}
     * is not always a {@link LivingEntity}.
     */
    @Nullable
    private static LivingEntity resolveLivingMobAttacker(DamageSource source) {
        LivingEntity fromEntity = tryLivingMobAttacker(source.getEntity());
        if (fromEntity != null) {
            return fromEntity;
        }
        return tryLivingMobAttacker(source.getDirectEntity());
    }

    @Nullable
    private static LivingEntity tryLivingMobAttacker(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
            return living.isAlive() ? living : null;
        }
        if (entity instanceof Projectile projectile) {
            Entity owner = projectile.getOwner();
            if (owner instanceof LivingEntity living && !(owner instanceof Player)) {
                return living.isAlive() ? living : null;
            }
        }
        return null;
    }
}
