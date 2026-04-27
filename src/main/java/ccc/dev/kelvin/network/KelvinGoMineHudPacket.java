package ccc.dev.kelvin.network;

import java.util.function.Supplier;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

public final class KelvinGoMineHudPacket {
    private final boolean active;
    private final int collectedTotal;
    private final boolean hasChest;
    private final long chestPackedPos;
    private final boolean hasBlock;
    private final ResourceLocation blockId;

    public KelvinGoMineHudPacket(
            boolean active, int collectedTotal, boolean hasChest, long chestPackedPos, boolean hasBlock, ResourceLocation blockId) {
        this.active = active;
        this.collectedTotal = collectedTotal;
        this.hasChest = hasChest;
        this.chestPackedPos = chestPackedPos;
        this.hasBlock = hasBlock;
        this.blockId = blockId;
    }

    public static void encode(KelvinGoMineHudPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.active);
        buf.writeVarInt(msg.collectedTotal);
        buf.writeBoolean(msg.hasChest);
        if (msg.hasChest) {
            buf.writeLong(msg.chestPackedPos);
        }
        buf.writeBoolean(msg.hasBlock);
        if (msg.hasBlock) {
            buf.writeResourceLocation(msg.blockId);
        }
    }

    public static KelvinGoMineHudPacket decode(FriendlyByteBuf buf) {
        boolean active = buf.readBoolean();
        int collected = buf.readVarInt();
        boolean hasChest = buf.readBoolean();
        long chest = 0L;
        if (hasChest) {
            chest = buf.readLong();
        }
        boolean hasBlock = buf.readBoolean();
        ResourceLocation blockRl = Objects.requireNonNullElse(
                ResourceLocation.tryParse("minecraft:stone"), BuiltInRegistries.BLOCK.getKey(Blocks.STONE));
        if (hasBlock) {
            blockRl = buf.readResourceLocation();
        }
        return new KelvinGoMineHudPacket(active, collected, hasChest, chest, hasBlock, blockRl);
    }

    public static void handle(KelvinGoMineHudPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(
                () -> DistExecutor.unsafeRunWhenOn(
                        Dist.CLIENT,
                        () -> () -> {
                            BlockPos chest = msg.hasChest ? BlockPos.of(msg.chestPackedPos) : null;
                            Block block = msg.hasBlock ? BuiltInRegistries.BLOCK.get(msg.blockId) : null;
                            ccc.dev.kelvin.client.KelvinGoMineHud.applyFromServer(
                                    msg.active, msg.collectedTotal, chest, block);
                        }));
        ctx.setPacketHandled(true);
    }
}
