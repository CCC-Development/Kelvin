package ccc.dev.kelvin;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * Persists Kelvin's last known position for the summoner for everyone on the server — not tied to a specific player.
 */
public final class KelvinSummonerWorldData extends SavedData {
    public static final String DATA_ID = "ccc_kelvin_summoner";

    @Nullable
    private BlockPos lastPos;
    @Nullable
    private ResourceLocation lastDimension;

    public KelvinSummonerWorldData() {}

    public static KelvinSummonerWorldData get(MinecraftServer server) {
        DimensionDataStorage storage = server.overworld().getDataStorage();
        return storage.computeIfAbsent(KelvinSummonerWorldData::load, KelvinSummonerWorldData::new, DATA_ID);
    }

    public static KelvinSummonerWorldData load(CompoundTag tag) {
        KelvinSummonerWorldData d = new KelvinSummonerWorldData();
        if (tag.contains("LX") && tag.contains("LDim")) {
            d.lastPos = new BlockPos(tag.getInt("LX"), tag.getInt("LY"), tag.getInt("LZ"));
            d.lastDimension = ResourceLocation.tryParse(tag.getString("LDim"));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (this.lastPos != null && this.lastDimension != null) {
            tag.putInt("LX", this.lastPos.getX());
            tag.putInt("LY", this.lastPos.getY());
            tag.putInt("LZ", this.lastPos.getZ());
            tag.putString("LDim", this.lastDimension.toString());
        }
        return tag;
    }

    public void remember(ServerLevel kelvinLevel, BlockPos pos) {
        this.lastPos = pos.immutable();
        this.lastDimension = kelvinLevel.dimension().location();
        this.setDirty();
    }

    public void clear() {
        this.lastPos = null;
        this.lastDimension = null;
        this.setDirty();
    }

    public record LastKnown(BlockPos pos, ResourceLocation dimension) {}

    public @Nullable LastKnown getLastKnown() {
        if (this.lastPos == null || this.lastDimension == null) {
            return null;
        }
        return new LastKnown(this.lastPos, this.lastDimension);
    }

    public static String describeDimension(ResourceLocation dim) {
        if (Level.OVERWORLD.location().equals(dim)) {
            return "the Overworld";
        }
        if (Level.NETHER.location().equals(dim)) {
            return "the Nether";
        }
        if (Level.END.location().equals(dim)) {
            return "the End";
        }
        return dim.toString();
    }
}
