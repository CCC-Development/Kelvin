package ccc.dev.kelvin.network;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Server → client: intro helicopter just impacted — immediate shake, letterbox timing, and local impact VFX. */
public final class HeliIntroImpactPacket {
    private final int helicopterEntityId;
    private final double impactX;
    private final double impactY;
    private final double impactZ;

    public HeliIntroImpactPacket(int helicopterEntityId, double impactX, double impactY, double impactZ) {
        this.helicopterEntityId = helicopterEntityId;
        this.impactX = impactX;
        this.impactY = impactY;
        this.impactZ = impactZ;
    }

    public static void encode(HeliIntroImpactPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.helicopterEntityId);
        buf.writeDouble(msg.impactX);
        buf.writeDouble(msg.impactY);
        buf.writeDouble(msg.impactZ);
    }

    public static HeliIntroImpactPacket decode(FriendlyByteBuf buf) {
        return new HeliIntroImpactPacket(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public static void handle(HeliIntroImpactPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        final int id = msg.helicopterEntityId;
        final double x = msg.impactX;
        final double y = msg.impactY;
        final double z = msg.impactZ;
        ctx.enqueueWork(
                () -> DistExecutor.unsafeRunWhenOn(
                        Dist.CLIENT,
                        () -> () -> ccc.dev.kelvin.client.HeliIntroCutsceneClient.applyServerImpact(id, x, y, z)));
        ctx.setPacketHandled(true);
    }
}
