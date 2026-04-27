package ccc.dev.kelvin.network;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server → client: follow mode and which Kelvin entity to measure distance to; stop message still timed on client.
 */
public final class KelvinFollowStatusPacket {
    public static final byte STATUS_FOLLOWING = 0;
    public static final byte STATUS_STOPPED = 1;

    private final byte status;
    /** When {@link #STATUS_FOLLOWING}, the Kelvin entity id in the player’s level; otherwise {@code -1}. */
    private final int followingKelvinEntityId;

    public KelvinFollowStatusPacket(byte status, int followingKelvinEntityId) {
        this.status = status;
        this.followingKelvinEntityId = followingKelvinEntityId;
    }

    public static void encode(KelvinFollowStatusPacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.status);
        buf.writeInt(msg.followingKelvinEntityId);
    }

    public static KelvinFollowStatusPacket decode(FriendlyByteBuf buf) {
        return new KelvinFollowStatusPacket(buf.readByte(), buf.readInt());
    }

    public static void handle(KelvinFollowStatusPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(
                () -> DistExecutor.unsafeRunWhenOn(
                        Dist.CLIENT,
                        () -> () ->
                                ccc.dev.kelvin.client.KelvinFollowHud.onStatusFromServer(
                                        msg.status, msg.followingKelvinEntityId)));
        ctx.setPacketHandled(true);
    }
}
