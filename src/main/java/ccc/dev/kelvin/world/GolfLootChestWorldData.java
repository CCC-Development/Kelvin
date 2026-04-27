package ccc.dev.kelvin.world;

import ccc.dev.kelvin.CccKelvinMod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * Tracks up to two heli-crash golf-loot chests and their beacon decoration blocks so beams can be removed the first
 * time each chest is opened.
 */
public final class GolfLootChestWorldData extends SavedData {
    public static final String DATA_ID = CccKelvinMod.MOD_ID + "_golf_loot_chest";
    public static final int SITE_COUNT = 2;

    private final List<GolfLootSite> sites = new ArrayList<>();

    public GolfLootChestWorldData() {}

    public static GolfLootChestWorldData get(MinecraftServer server) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(GolfLootChestWorldData::load, GolfLootChestWorldData::new, DATA_ID);
    }

    public static GolfLootChestWorldData load(CompoundTag tag) {
        GolfLootChestWorldData d = new GolfLootChestWorldData();
        if (tag.contains("Sites", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Sites", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                d.sites.add(GolfLootSite.load(list.getCompound(i)));
            }
        } else if (tag.getBoolean("HasSite") && tag.contains("Chest", Tag.TAG_COMPOUND)) {
            BlockPos chest = NbtUtils.readBlockPos(tag.getCompound("Chest"));
            List<BlockPos> dec = new ArrayList<>();
            ListTag decList = tag.getList("Decorations", Tag.TAG_COMPOUND);
            for (int i = 0; i < decList.size(); i++) {
                dec.add(NbtUtils.readBlockPos(decList.getCompound(i)));
            }
            GolfLootSite site = new GolfLootSite(chest, dec);
            site.beaconCleared = tag.getBoolean("BeaconCleared");
            d.sites.add(site);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag sitesTag = new ListTag();
        for (GolfLootSite site : sites) {
            sitesTag.add(site.save());
        }
        tag.put("Sites", sitesTag);
        return tag;
    }

    public List<GolfLootSite> getSites() {
        return Collections.unmodifiableList(sites);
    }

    public boolean hasAllSites() {
        return sites.size() >= SITE_COUNT;
    }

    public void addPlacedSite(BlockPos chestPos, List<BlockPos> decorations) {
        sites.add(new GolfLootSite(chestPos, decorations));
        setDirty();
    }

    /** First matching site whose chest is at {@code pos}. */
    public GolfLootSite findSiteForChest(BlockPos pos) {
        for (GolfLootSite site : sites) {
            if (site.chestPos.equals(pos)) {
                return site;
            }
        }
        return null;
    }

    /**
     * True while the beam setup for that block is still active: players must not break beacon / glass / iron pad
     * until the paired chest has been opened once.
     */
    public boolean isDecorationProtected(BlockPos pos) {
        for (GolfLootSite site : sites) {
            if (site.beaconCleared) {
                continue;
            }
            for (BlockPos d : site.decorationPositions) {
                if (d.equals(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static final class GolfLootSite {
        private final BlockPos chestPos;
        private final List<BlockPos> decorationPositions;
        private boolean beaconCleared;

        GolfLootSite(BlockPos chestPos, List<BlockPos> decorationPositions) {
            this.chestPos = chestPos.immutable();
            this.decorationPositions = new ArrayList<>();
            for (BlockPos p : decorationPositions) {
                this.decorationPositions.add(p.immutable());
            }
        }

        static GolfLootSite load(CompoundTag tag) {
            BlockPos chest = NbtUtils.readBlockPos(tag.getCompound("Chest"));
            List<BlockPos> dec = new ArrayList<>();
            ListTag list = tag.getList("Decorations", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                dec.add(NbtUtils.readBlockPos(list.getCompound(i)));
            }
            GolfLootSite s = new GolfLootSite(chest, dec);
            s.beaconCleared = tag.getBoolean("BeaconCleared");
            return s;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.put("Chest", NbtUtils.writeBlockPos(chestPos));
            tag.putBoolean("BeaconCleared", beaconCleared);
            ListTag list = new ListTag();
            for (BlockPos p : decorationPositions) {
                list.add(NbtUtils.writeBlockPos(p));
            }
            tag.put("Decorations", list);
            return tag;
        }

        public BlockPos getChestPos() {
            return chestPos;
        }

        public List<BlockPos> getDecorationPositions() {
            return Collections.unmodifiableList(decorationPositions);
        }

        public boolean isBeaconCleared() {
            return beaconCleared;
        }

        void markBeaconCleared() {
            if (!beaconCleared) {
                beaconCleared = true;
            }
        }
    }
}
