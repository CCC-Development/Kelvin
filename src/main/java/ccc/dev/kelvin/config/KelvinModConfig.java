package ccc.dev.kelvin.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class KelvinModConfig {
    private KelvinModConfig() {}

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue DISABLE_CUTSCENE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("CCC Kelvin intro");
        DISABLE_CUTSCENE =
                builder.comment(
                                "When true, new players skip the helicopter cutscene (camera, letterbox, flying heli). "
                                        + "The crash site, Kelvin, and related loot chests still spawn.")
                        .define("disableCutscene", true);
        SPEC = builder.build();
    }

    public static boolean disableCutscene() {
        return DISABLE_CUTSCENE.get();
    }
}
