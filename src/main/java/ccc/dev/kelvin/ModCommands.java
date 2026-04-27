package ccc.dev.kelvin;

import ccc.dev.kelvin.entity.ApacheHelicopterEntity;
import ccc.dev.kelvin.entity.KelvinEntity;
import ccc.dev.kelvin.event.HeliIntroCutsceneServerEvents;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID)
public final class ModCommands {
    private ModCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("spawn-kelvin")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    ServerLevel level = source.getLevel();
                    KelvinEntity kelvin = ModEntities.KELVIN.get().create(level);
                    if (kelvin == null) {
                        return 0;
                    }
                    Vec3 pos = source.getPosition();
                    kelvin.moveTo(pos.x, pos.y, pos.z, source.getRotation().y, 0.0F);
                    kelvin.setYHeadRot(source.getRotation().y);
                    kelvin.setCustomName(Component.literal("Kelvin"));
                    kelvin.setCustomNameVisible(true);
                    level.addFreshEntity(kelvin);
                    source.sendSuccess(() -> Component.literal("Spawned Kelvin"), true);
                    return 1;
                }));

        dispatcher.register(Commands.literal("spawn-heli")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    ServerLevel level = source.getLevel();
                    ApacheHelicopterEntity heli = ModEntities.APACHE_HELICOPTER.get().create(level);
                    if (heli == null) {
                        return 0;
                    }
                    Vec3 pos = source.getPosition();
                    heli.moveTo(pos.x, pos.y, pos.z, source.getRotation().y, 0.0F);
                    heli.setYHeadRot(source.getRotation().y);
                    level.addFreshEntity(heli);
                    source.sendSuccess(() -> Component.literal("Spawned helicopter (glTF)"), true);
                    return 1;
                }));

        dispatcher.register(Commands.literal("play_cutscene")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    ServerPlayer player = source.getPlayerOrException();
                    HeliIntroCutsceneServerEvents.forcePlayCutscene(player);
                    source.sendSuccess(() -> Component.literal("Playing helicopter cutscene"), true);
                    return 1;
                }));
    }
}
