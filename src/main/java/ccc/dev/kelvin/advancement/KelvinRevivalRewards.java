package ccc.dev.kelvin.advancement;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.entity.KelvinEntity;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** First-time Kelvin revive: advancement, fireworks, and enchanted golden apples. */
public final class KelvinRevivalRewards {
    public static final ResourceLocation HOMIE_FOR_LIFE =
            ResourceLocation.fromNamespaceAndPath(CccKelvinMod.MOD_ID, "homie_for_life");

    private KelvinRevivalRewards() {}

    public static void tryFirstRevive(ServerLevel level, ServerPlayer player, KelvinEntity kelvin) {
        Advancement advancement = level.getServer().getAdvancements().getAdvancement(HOMIE_FOR_LIFE);
        if (advancement == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(advancement);
        if (progress.isDone()) {
            return;
        }
        spawnColorfulFireworks(level, kelvin, player.getRandom());
        giveEnchantedGoldenApples(player);
        for (String criterion : advancement.getCriteria().keySet()) {
            player.getAdvancements().award(advancement, criterion);
        }
    }

    private static void giveEnchantedGoldenApples(ServerPlayer player) {
        ItemStack stack = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 3);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static void spawnColorfulFireworks(ServerLevel level, KelvinEntity kelvin, RandomSource random) {
        Vec3 base = kelvin.position().add(0.0D, kelvin.getBbHeight() * 0.35D, 0.0D);
        for (int i = 0; i < 6; i++) {
            ItemStack rocket = createCelebrationRocket(random);
            double ox = (random.nextDouble() - 0.5D) * 1.2D;
            double oz = (random.nextDouble() - 0.5D) * 1.2D;
            FireworkRocketEntity entity =
                    new FireworkRocketEntity(level, base.x + ox, base.y + random.nextDouble() * 0.4D, base.z + oz, rocket);
            entity.setDeltaMovement(
                    (random.nextDouble() - 0.5D) * 0.12D,
                    0.35D + random.nextDouble() * 0.25D,
                    (random.nextDouble() - 0.5D) * 0.12D);
            level.addFreshEntity(entity);
        }
    }

    private static ItemStack createCelebrationRocket(RandomSource random) {
        DyeColor[] palette = {
            DyeColor.RED,
            DyeColor.ORANGE,
            DyeColor.YELLOW,
            DyeColor.LIME,
            DyeColor.LIGHT_BLUE,
            DyeColor.MAGENTA,
            DyeColor.PINK,
            DyeColor.CYAN
        };
        int colorCount = 3 + random.nextInt(4);
        int[] colors = new int[colorCount];
        for (int i = 0; i < colorCount; i++) {
            colors[i] = palette[random.nextInt(palette.length)].getFireworkColor();
        }
        int[] fade = new int[2];
        fade[0] = palette[random.nextInt(palette.length)].getFireworkColor();
        fade[1] = palette[random.nextInt(palette.length)].getFireworkColor();

        CompoundTag explosion = new CompoundTag();
        explosion.putByte("Type", (byte) (random.nextBoolean() ? 1 : 2));
        explosion.putIntArray("Colors", colors);
        explosion.putIntArray("FadeColors", fade);
        explosion.putBoolean("Flicker", true);
        explosion.putBoolean("Trail", random.nextBoolean());

        ListTag explosions = new ListTag();
        explosions.add(explosion);

        CompoundTag fireworks = new CompoundTag();
        fireworks.putByte("Flight", (byte) (1 + random.nextInt(2)));
        fireworks.put("Explosions", explosions);

        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        rocket.getOrCreateTag().put("Fireworks", fireworks);
        return rocket;
    }
}
