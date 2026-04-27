package ccc.dev.kelvin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * Per-save flag: the Kelvin heli crash NBT was placed in the world. Only the first intro impact
 * that wins the race (same storage as other overworld global data) places the structure.
 */
public final class HeliCrashSiteStructureData extends SavedData {
    public static final String DATA_ID = CccKelvinMod.MOD_ID + "_heli_crash_site_structure";

    private boolean structurePlaced;

    public HeliCrashSiteStructureData() {}

    public static HeliCrashSiteStructureData get(MinecraftServer server) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(HeliCrashSiteStructureData::load, HeliCrashSiteStructureData::new, DATA_ID);
    }

    public static HeliCrashSiteStructureData load(CompoundTag tag) {
        HeliCrashSiteStructureData d = new HeliCrashSiteStructureData();
        d.structurePlaced = tag.getBoolean("Placed");
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("Placed", structurePlaced);
        return tag;
    }

    public boolean isStructurePlaced() {
        return structurePlaced;
    }

    public void markPlaced() {
        if (!structurePlaced) {
            structurePlaced = true;
            setDirty();
        }
    }
}
