package ccc.dev.kelvin.event;

import ccc.dev.kelvin.entity.ApacheHelicopterEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.Event;

/** Fired on the Forge bus when the intro Apache reaches its crash pose (before wreck promotion). */
public final class HeliIntroImpactEvent extends Event {
    private final ServerLevel level;
    private final ApacheHelicopterEntity helicopter;

    public HeliIntroImpactEvent(ServerLevel level, ApacheHelicopterEntity helicopter) {
        this.level = level;
        this.helicopter = helicopter;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public ApacheHelicopterEntity getHelicopter() {
        return helicopter;
    }
}
