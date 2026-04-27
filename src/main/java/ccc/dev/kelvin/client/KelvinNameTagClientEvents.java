package ccc.dev.kelvin.client;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.entity.KelvinEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Puts Kelvin's current/max health (and heart) on the <strong>same</strong> vanilla nameplate as his name, to the
 * <strong>left</strong> of the label text. Uses {@link RenderNameTagEvent} so it is one draw with the name — no
 * separate billboard above the head.
 */
@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class KelvinNameTagClientEvents {

    private KelvinNameTagClientEvents() {}

    /**
     * Forces the nameplate render path for Kelvin so the combined HP + name string actually runs even when vanilla
     * {@code shouldShowName} alone would skip it (custom name can still be visible in some setups).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void kelvinAllowNameplate(RenderNameTagEvent event) {
        if (event.getEntity() instanceof KelvinEntity) {
            event.setResult(Event.Result.ALLOW);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void kelvinPrependHealthToNameplate(RenderNameTagEvent event) {
        if (event.getResult() == Event.Result.DENY) {
            return;
        }
        if (!(event.getEntity() instanceof KelvinEntity kelvin)) {
            return;
        }
        MutableComponent line = Component.empty();
        line.append(KelvinRenderer.kelvinHealthNameLine(kelvin));
        line.append(Component.literal(" "));
        line.append(event.getOriginalContent());
        event.setContent(line);
    }
}
