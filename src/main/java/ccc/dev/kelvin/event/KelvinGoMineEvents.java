package ccc.dev.kelvin.event;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.ModItems;
import ccc.dev.kelvin.item.KelvinSummonerItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class KelvinGoMineEvents {
    private KelvinGoMineEvents() {}

    /**
     * Left-click a storage block. Registered in {@link ccc.dev.kelvin.network.ModNetworkSetup} with
     * {@code receiveCancelled = true} so binding still runs when another mod cancels the event.
     */
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level() instanceof ServerLevel sl
                && KelvinSummonerItem.onGoMineSummonerLeftPunchBlock(player, sl, event.getPos())) {
            event.setCanceled(true);
        }
    }

    /**
     * Right-click normally opens a chest before the item's {@code useOn} runs; intercept here with highest priority
     * and cancel when we bound or already reported an error to the player.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (tryBindWithSummonerInHands(player, event.getPos())) {
            event.setCanceled(true);
        }
    }

    private static boolean tryBindWithSummonerInHands(ServerPlayer player, BlockPos pos) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(ModItems.KELVIN_SUMMONER.get())) {
                continue;
            }
            if (KelvinSummonerItem.tryBindGoMineChest(player, player.level(), pos)) {
                return true;
            }
        }
        return false;
    }
}
