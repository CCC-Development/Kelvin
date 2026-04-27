package ccc.dev.kelvin.network;

import ccc.dev.kelvin.event.HeliIntroCutsceneServerEvents;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/** Client → server: viewing client has the intro helicopter loaded; start shared cutscene timeline. */
public final class HeliIntroCutsceneAckPacket {
    private final int helicopterEntityId;

    public HeliIntroCutsceneAckPacket(int helicopterEntityId) {
        this.helicopterEntityId = helicopterEntityId;
    }

    public static void encode(HeliIntroCutsceneAckPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.helicopterEntityId);
    }

    public static HeliIntroCutsceneAckPacket decode(FriendlyByteBuf buf) {
        return new HeliIntroCutsceneAckPacket(buf.readVarInt());
    }

    public static void handle(HeliIntroCutsceneAckPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(
                () -> {
                    ServerPlayer sp = ctx.getSender();
                    HeliIntroCutsceneServerEvents.onClientHeliIntroTimelineReady(sp, msg.helicopterEntityId);
                });
        ctx.setPacketHandled(true);
    }
}
