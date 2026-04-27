package com.mrcrayfish.vehicle.client.handler;

import com.mrcrayfish.controllable.Controllable;
import com.mrcrayfish.controllable.client.Action;
import com.mrcrayfish.controllable.client.ActionVisibility;
import com.mrcrayfish.controllable.client.binding.BindingContext;
import com.mrcrayfish.controllable.client.binding.BindingRegistry;
import com.mrcrayfish.controllable.client.binding.ButtonBinding;
import com.mrcrayfish.controllable.client.binding.ButtonBindings;
import com.mrcrayfish.controllable.client.input.Buttons;
import com.mrcrayfish.controllable.event.ControllerEvents;
import com.mrcrayfish.vehicle.client.ClientHandler;
import com.mrcrayfish.vehicle.entity.HelicopterEntity;
import com.mrcrayfish.vehicle.entity.LandVehicleEntity;
import com.mrcrayfish.vehicle.entity.PoweredVehicleEntity;
import com.mrcrayfish.vehicle.entity.VehicleEntity;
import com.mrcrayfish.vehicle.network.PacketHandler;
import com.mrcrayfish.vehicle.network.message.MessageCycleSeats;
import com.mrcrayfish.vehicle.network.message.MessageHitchTrailer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * Manages controller input
 *
 * Author: MrCrayfish
 */
@OnlyIn(Dist.CLIENT)
public class ControllerHandler
{
    public static final ButtonBinding ACCELERATE = new ButtonBinding(Buttons.A, "vehicle.button.accelerate", "button.categories.vehicle", BindingContext.IN_GAME);
    public static final ButtonBinding REVERSE = new ButtonBinding(Buttons.B, "vehicle.button.brake", "button.categories.vehicle", BindingContext.IN_GAME);
    public static final ButtonBinding HANDBRAKE = new ButtonBinding(Buttons.RIGHT_BUMPER, "vehicle.button.handbrake", "button.categories.vehicle", BindingContext.IN_GAME);
    public static final ButtonBinding HORN = new ButtonBinding(Buttons.LEFT_THUMB_STICK, "vehicle.button.horn", "button.categories.vehicle", BindingContext.IN_GAME);
    public static final ButtonBinding HITCH_TRAILER = new ButtonBinding(Buttons.X, "vehicle.button.hitch_trailer", "button.categories.vehicle", BindingContext.IN_GAME);
    public static final ButtonBinding RESET_CAMERA = new ButtonBinding(Buttons.SELECT, "vehicle.button.reset_camera", "button.categories.vehicle", BindingContext.IN_GAME);
    public static final ButtonBinding CYCLE_SEATS = new ButtonBinding(Buttons.DPAD_LEFT, "vehicle.button.cycle_seats", "button.categories.vehicle", BindingContext.IN_GAME);

    public static final ButtonBinding ASCEND = new ButtonBinding(Buttons.A, "vehicle.button.ascend", "button.categories.vehicle", BindingContext.IN_GAME);
    public static final ButtonBinding DESCEND = new ButtonBinding(Buttons.B, "vehicle.button.descend", "button.categories.vehicle", BindingContext.IN_GAME);

    public static void init()
    {
        BindingRegistry registry = BindingRegistry.getInstance();
        registry.register(ACCELERATE);
        registry.register(REVERSE);
        registry.register(HANDBRAKE);
        registry.register(HORN);
        registry.register(HITCH_TRAILER);
        registry.register(RESET_CAMERA);
        registry.register(ASCEND);
        registry.register(DESCEND);
        registry.register(CYCLE_SEATS);

        ControllerEvents.INPUT.register((controller, newButton, originalButton, state) -> {
            if(Minecraft.getInstance().screen != null)
                return false;

            if(!state)
                return false;

            Player player = Minecraft.getInstance().player;
            if(player == null)
                return false;

            if(!(player.getVehicle() instanceof VehicleEntity))
                return false;

            int button = originalButton;
            if(button == ACCELERATE.getButton() || button == REVERSE.getButton() || button == HANDBRAKE.getButton() || button == HORN.getButton())
            {
                return true;
            }
            else if(button == HITCH_TRAILER.getButton())
            {
                VehicleEntity vehicle = (VehicleEntity) player.getVehicle();
                if(vehicle.canTowTrailers())
                {
                    PacketHandler.getPlayChannel().sendToServer(new MessageHitchTrailer(vehicle.getTrailer() == null));
                }
                return true;
            }
            else if(button == RESET_CAMERA.getButton())
            {
                player.setYRot(player.getVehicle().getYRot());
                player.setXRot(15F);
                return true;
            }
            else if(button == CYCLE_SEATS.getButton())
            {
                PacketHandler.getPlayChannel().sendToServer(new MessageCycleSeats());
                return true;
            }
            return false;
        });

        ControllerEvents.GATHER_ACTIONS.register((actionMap, visibility) -> {
            Minecraft mc = Minecraft.getInstance();
            if(mc.screen != null)
                return;

            Player player = mc.player;
            if(player == null)
                return;

            if(player.getVehicle() instanceof VehicleEntity vehicle)
            {
                actionMap.remove(ButtonBindings.ATTACK);
                actionMap.remove(ButtonBindings.OPEN_INVENTORY);

                actionMap.put(ButtonBindings.SNEAK, new Action(Component.literal("Exit Vehicle"), Action.Side.LEFT));

                if(vehicle.getProperties().getSeats().size() > 1)
                {
                    actionMap.put(CYCLE_SEATS, new Action(Component.literal("Cycle Seats"), Action.Side.LEFT));
                }

                if(vehicle.canTowTrailers())
                {
                    actionMap.put(HITCH_TRAILER, new Action(Component.literal("Hitch Trailer"), Action.Side.LEFT));
                }

                if(visibility == ActionVisibility.ALL)
                {
                    actionMap.put(RESET_CAMERA, new Action(Component.literal("Reset Camera"), Action.Side.LEFT));
                    actionMap.put(ACCELERATE, new Action(Component.literal("Accelerate"), Action.Side.RIGHT));

                    if(vehicle instanceof PoweredVehicleEntity)
                    {
                        if(((PoweredVehicleEntity) vehicle).getSpeed() > 0.05F)
                        {
                            actionMap.put(REVERSE, new Action(Component.literal("Brake"), Action.Side.RIGHT));
                        }
                        else
                        {
                            actionMap.put(REVERSE, new Action(Component.literal("Reverse"), Action.Side.RIGHT));
                        }

                        if(((PoweredVehicleEntity) vehicle).hasHorn())
                        {
                            actionMap.put(HORN, new Action(Component.literal("Horn"), Action.Side.RIGHT));
                        }
                    }
                }

                if(vehicle instanceof LandVehicleEntity)
                {
                    actionMap.put(HANDBRAKE, new Action(Component.literal("Handbrake"), Action.Side.RIGHT));
                }
                else if(vehicle instanceof HelicopterEntity)
                {
                    actionMap.put(ASCEND, new Action(Component.literal("Ascend"), Action.Side.RIGHT));
                    actionMap.put(DESCEND, new Action(Component.literal("Descend"), Action.Side.RIGHT));
                }
            }
            else if(player.getVehicle() == null)
            {
                if(mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY)
                {
                    Entity entity = ((EntityHitResult) mc.hitResult).getEntity();
                    if(entity instanceof VehicleEntity)
                    {
                        actionMap.put(ButtonBindings.USE_ITEM, new Action(Component.literal("Ride Vehicle"), Action.Side.RIGHT));
                    }
                }
            }
        });

        ControllerEvents.RENDER_MINI_PLAYER.register(() -> {
            Player player = Minecraft.getInstance().player;
            return player != null && player.getVehicle() instanceof VehicleEntity;
        });
    }

    public static boolean isRightClicking()
    {
        boolean isRightClicking = GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        var controller = Controllable.getController();
        isRightClicking |= ClientHandler.isControllableLoaded() && controller != null && controller.getLTriggerValue() != 0.0F;
        return isRightClicking;
    }
}
