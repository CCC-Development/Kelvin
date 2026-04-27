package ccc.dev.kelvin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * One flag per save: whether Kelvin was already spawned from the helicopter intro. Used on dedicated servers so only
 * the first completion spawns him; later players still get the cutscene but must find the existing Kelvin.
 */
public final class KelvinIntroLandedWorldData extends SavedData {
    public static final String DATA_ID = CccKelvinMod.MOD_ID + "_kelvin_intro_landed";

    private boolean kelvinLanded;

    public KelvinIntroLandedWorldData() {}

    public static KelvinIntroLandedWorldData get(MinecraftServer server) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(KelvinIntroLandedWorldData::load, KelvinIntroLandedWorldData::new, DATA_ID);
    }

    public static KelvinIntroLandedWorldData load(CompoundTag tag) {
        KelvinIntroLandedWorldData d = new KelvinIntroLandedWorldData();
        d.kelvinLanded = tag.getBoolean("Landed");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("Landed", kelvinLanded);
        return tag;
    }

    public boolean hasKelvinLanded() {
        return kelvinLanded;
    }

    public void markKelvinLanded() {
        if (!kelvinLanded) {
            kelvinLanded = true;
            setDirty();
        }
    }
}
