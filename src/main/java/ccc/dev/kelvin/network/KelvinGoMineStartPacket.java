package ccc.dev.kelvin.network;

import ccc.dev.kelvin.entity.KelvinEntity;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.network.NetworkEvent;

public final class KelvinGoMineStartPacket {
    private final int kelvinEntityId;
    private final ResourceLocation blockId;

    public KelvinGoMineStartPacket(int kelvinEntityId, ResourceLocation blockId) {
        this.kelvinEntityId = kelvinEntityId;
        this.blockId = blockId;
    }

    public static void encode(KelvinGoMineStartPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.kelvinEntityId);
        buf.writeResourceLocation(msg.blockId);
    }

    public static KelvinGoMineStartPacket decode(FriendlyByteBuf buf) {
        return new KelvinGoMineStartPacket(buf.readVarInt(), buf.readResourceLocation());
    }

    public static void handle(KelvinGoMineStartPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleServer(msg, ctx.getSender()));
        ctx.setPacketHandled(true);
    }

    private static void handleServer(KelvinGoMineStartPacket msg, ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Entity entity = player.level().getEntity(msg.kelvinEntityId);
        if (!(entity instanceof KelvinEntity kelvin) || !kelvin.isAlive()) {
            return;
        }
        if (player.distanceToSqr(kelvin) > KelvinEntity.PLAYER_INTERACTION_MAX_DIST_SQR) {
            return;
        }
        if (!BuiltInRegistries.BLOCK.containsKey(msg.blockId)) {
            return;
        }
        Block block = BuiltInRegistries.BLOCK.get(msg.blockId);
        if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
            return;
        }
        kelvin.getGoMineController().start(serverLevel, player, block);
    }
}
