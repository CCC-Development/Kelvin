package ccc.dev.kelvin.network;

import ccc.dev.kelvin.CccKelvinMod;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetwork {
    /** Bumped when client–server packet shapes change so mismatched builds fail fast on join. */
    private static final String PROTOCOL = "10";
    private static int packetId;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(CccKelvinMod.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private ModNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(nextId(), KelvinActionPacket.class, KelvinActionPacket::encode,
                KelvinActionPacket::decode, KelvinActionPacket::handle);
        CHANNEL.registerMessage(nextId(), KelvinFollowStatusPacket.class, KelvinFollowStatusPacket::encode,
                KelvinFollowStatusPacket::decode, KelvinFollowStatusPacket::handle);
        CHANNEL.registerMessage(nextId(), KelvinSummonerRevivalHudPacket.class, KelvinSummonerRevivalHudPacket::encode,
                KelvinSummonerRevivalHudPacket::decode, KelvinSummonerRevivalHudPacket::handle);
        CHANNEL.registerMessage(nextId(), KelvinGoMineStartPacket.class, KelvinGoMineStartPacket::encode,
                KelvinGoMineStartPacket::decode, KelvinGoMineStartPacket::handle);
        CHANNEL.registerMessage(nextId(), KelvinGoMineHudPacket.class, KelvinGoMineHudPacket::encode,
                KelvinGoMineHudPacket::decode, KelvinGoMineHudPacket::handle);
        CHANNEL.registerMessage(nextId(), HeliIntroCutscenePacket.class, HeliIntroCutscenePacket::encode,
                HeliIntroCutscenePacket::decode, HeliIntroCutscenePacket::handle);
        CHANNEL.registerMessage(nextId(), HeliIntroImpactPacket.class, HeliIntroImpactPacket::encode,
                HeliIntroImpactPacket::decode, HeliIntroImpactPacket::handle);
        CHANNEL.registerMessage(nextId(), HeliIntroCutsceneAckPacket.class, HeliIntroCutsceneAckPacket::encode,
                HeliIntroCutsceneAckPacket::decode, HeliIntroCutsceneAckPacket::handle);
    }

    private static int nextId() {
        return packetId++;
    }

    public static void sendKelvinAction(int kelvinEntityId, byte action) {
        CHANNEL.sendToServer(new KelvinActionPacket(kelvinEntityId, action));
    }

    public static void sendFollowStatusTo(ServerPlayer player, byte status, int followingKelvinEntityId) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new KelvinFollowStatusPacket(status, followingKelvinEntityId));
    }

    public static void sendSummonerRevivalHud(ServerPlayer player, BlockPos pos, ResourceLocation dimension) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new KelvinSummonerRevivalHudPacket(pos, dimension));
    }

    public static void sendGoMineHud(
            ServerPlayer player, boolean active, int collectedTotal, @Nullable BlockPos chest, @Nullable Block targetBlock) {
        boolean hasChest = chest != null;
        long chestPacked = hasChest ? chest.asLong() : 0L;
        boolean hasBlock = targetBlock != null;
        ResourceLocation blockRl =
                hasBlock
                        ? BuiltInRegistries.BLOCK.getKey(targetBlock)
                        : Objects.requireNonNullElse(
                                ResourceLocation.tryParse("minecraft:stone"),
                                BuiltInRegistries.BLOCK.getKey(Blocks.STONE));
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new KelvinGoMineHudPacket(active, collectedTotal, hasChest, chestPacked, hasBlock, blockRl));
    }

    public static void sendGoMineStart(int kelvinEntityId, ResourceLocation blockId) {
        CHANNEL.sendToServer(new KelvinGoMineStartPacket(kelvinEntityId, blockId));
    }

    public static void sendHeliIntroCutscene(ServerPlayer player, int helicopterEntityId, int durationTicks) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new HeliIntroCutscenePacket(helicopterEntityId, durationTicks));
    }

    public static void sendHeliIntroImpact(ServerPlayer player, int helicopterEntityId, double x, double y, double z) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new HeliIntroImpactPacket(helicopterEntityId, x, y, z));
    }

    public static void sendHeliIntroCutsceneAckToServer(int helicopterEntityId) {
        CHANNEL.sendToServer(new HeliIntroCutsceneAckPacket(helicopterEntityId));
    }
}
