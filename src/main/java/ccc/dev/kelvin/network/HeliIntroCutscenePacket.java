package ccc.dev.kelvin.network;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Tells the client to play the helicopter intro (camera, HUD, letterbox). */
public final class HeliIntroCutscenePacket {
    private final int helicopterEntityId;
    private final int durationTicks;

    public HeliIntroCutscenePacket(int helicopterEntityId, int durationTicks) {
        this.helicopterEntityId = helicopterEntityId;
        this.durationTicks = durationTicks;
    }

    public static void encode(HeliIntroCutscenePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.helicopterEntityId);
        buf.writeVarInt(msg.durationTicks);
    }

    public static HeliIntroCutscenePacket decode(FriendlyByteBuf buf) {
        return new HeliIntroCutscenePacket(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(HeliIntroCutscenePacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        final int id = msg.helicopterEntityId;
        final int dur = msg.durationTicks;
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ccc.dev.kelvin.client.HeliIntroCutsceneClient.start(id, dur)));
        ctx.setPacketHandled(true);
    }
}
