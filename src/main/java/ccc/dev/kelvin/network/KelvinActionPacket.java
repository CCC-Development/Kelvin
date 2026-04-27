package ccc.dev.kelvin.network;

import ccc.dev.kelvin.entity.KelvinEntity;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

public final class KelvinActionPacket {
    public static final byte ACTION_FOLLOW = 0;
    public static final byte ACTION_STOP = 1;
    public static final byte ACTION_PROTECT_TOGGLE = 2;

    private final int kelvinEntityId;
    private final byte action;

    public KelvinActionPacket(int kelvinEntityId, byte action) {
        this.kelvinEntityId = kelvinEntityId;
        this.action = action;
    }

    public static void encode(KelvinActionPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.kelvinEntityId);
        buf.writeByte(msg.action);
    }

    public static KelvinActionPacket decode(FriendlyByteBuf buf) {
        return new KelvinActionPacket(buf.readInt(), buf.readByte());
    }

    public static void handle(KelvinActionPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> handleServer(msg, ctx.getSender()));
        ctx.setPacketHandled(true);
    }

    private static void handleServer(KelvinActionPacket msg, ServerPlayer player) {
        if (player == null) {
            return;
        }
        Entity entity = player.level().getEntity(msg.kelvinEntityId);
        if (!(entity instanceof KelvinEntity kelvin) || !kelvin.isAlive()) {
            return;
        }
        if (player.distanceToSqr(kelvin) > KelvinEntity.PLAYER_INTERACTION_MAX_DIST_SQR) {
            return;
        }
        if (msg.action == ACTION_FOLLOW) {
            kelvin.setFollowing(player);
            if (kelvin.isProtectOwner()) {
                kelvin.assignProtectPatron(player.getUUID());
            }
            ModNetwork.sendFollowStatusTo(player, KelvinFollowStatusPacket.STATUS_FOLLOWING, kelvin.getId());
        } else if (msg.action == ACTION_STOP) {
            kelvin.clearFollowing();
            ModNetwork.sendFollowStatusTo(player, KelvinFollowStatusPacket.STATUS_STOPPED, -1);
        } else if (msg.action == ACTION_PROTECT_TOGGLE) {
            boolean next = !kelvin.isProtectOwner();
            kelvin.setProtectOwner(next);
            if (next) {
                kelvin.assignProtectPatron(player.getUUID());
            }
        }
    }
}
