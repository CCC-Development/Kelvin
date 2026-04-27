package ccc.dev.kelvin.network;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** Server → client: show orange revival hint above the hotbar with Kelvin's coordinates and dimension. */
public final class KelvinSummonerRevivalHudPacket {
    private final BlockPos pos;
    private final ResourceLocation dimension;

    public KelvinSummonerRevivalHudPacket(BlockPos pos, ResourceLocation dimension) {
        this.pos = pos;
        this.dimension = dimension;
    }

    public static void encode(KelvinSummonerRevivalHudPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeResourceLocation(msg.dimension);
    }

    public static KelvinSummonerRevivalHudPacket decode(FriendlyByteBuf buf) {
        return new KelvinSummonerRevivalHudPacket(buf.readBlockPos(), buf.readResourceLocation());
    }

    public static void handle(KelvinSummonerRevivalHudPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(
                () -> DistExecutor.unsafeRunWhenOn(
                        Dist.CLIENT,
                        () -> () ->
                                ccc.dev.kelvin.client.KelvinSummonerHud.onRevivalHintFromServer(msg.pos, msg.dimension)));
        ctx.setPacketHandled(true);
    }
}
