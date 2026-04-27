package ccc.dev.kelvin.client;

import ccc.dev.kelvin.entity.KelvinEntity;
import ccc.dev.kelvin.network.KelvinActionPacket;
import ccc.dev.kelvin.network.ModNetwork;
import ccc.dev.kelvin.world.inventory.KelvinInventoryMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class KelvinInventoryScreen extends AbstractContainerScreen<KelvinInventoryMenu> {
    private static final ResourceLocation INVENTORY_LOCATION =
            ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");
    private static final ResourceLocation GUI_ICONS_LOCATION =
            ResourceLocation.withDefaultNamespace("textures/gui/icons.png");

    /** Entity preview: slightly right and slightly up from prior layout. */
    private static final int PREVIEW_CENTER_X = 50;
    private static final int PREVIEW_CENTER_Y = 76;
    /**
     * Hearts drawn above the GUI texture top; smaller value = lower on screen.
     * Uses heart row on {@code icons.png} (v=0), not v=9 (armour icons — do not use for hearts).
     */
    private static final int HEART_ROW_OFFSET_ABOVE_TOP = 10;
    private static final int PREVIEW_SIZE = 30;

    @Nullable private Button followToggleButton;
    @Nullable private Button protectToggleButton;
    @Nullable private Button goMineButton;

    public KelvinInventoryScreen(KelvinInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 256;
        this.imageHeight = KelvinInventoryMenu.GUI_IMAGE_HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        KelvinEntity kelvin = this.menu.getKelvin();
        if (kelvin == null || kelvin.isRemoved() || !kelvin.isAlive()) {
            return;
        }
        int id = kelvin.getId();
        int bx = this.leftPos + 182;
        int by = this.topPos + 18;
        this.followToggleButton =
                this.addRenderableWidget(Button.builder(this.followButtonLabel(kelvin), b -> {
                            KelvinEntity k = this.menu.getKelvin();
                            if (k == null || !k.isAlive()) {
                                return;
                            }
                            int kid = k.getId();
                            if (kelvinFollowingLocalPlayer(k)) {
                                ModNetwork.sendKelvinAction(kid, KelvinActionPacket.ACTION_STOP);
                            } else {
                                ModNetwork.sendKelvinAction(kid, KelvinActionPacket.ACTION_FOLLOW);
                            }
                        })
                        .bounds(bx, by, 92, 20)
                        .build());
        this.protectToggleButton =
                this.addRenderableWidget(Button.builder(this.protectButtonLabel(kelvin), b -> {
                            ModNetwork.sendKelvinAction(id, KelvinActionPacket.ACTION_PROTECT_TOGGLE);
                        })
                        .bounds(bx, by + 24, 92, 20)
                        .build());
        this.goMineButton =
                this.addRenderableWidget(Button.builder(
                                Component.literal("     ")
                                        .append(Component.translatable("gui.ccc_kelvin.go_mine_button")),
                                b -> {
                            KelvinEntity k = this.menu.getKelvin();
                            if (k == null || !k.isAlive()) {
                                return;
                            }
                            KelvinGoMineBlockScreen.open(k.getId());
                        })
                        .bounds(bx, by + 48, 92, 20)
                        .build());
    }

    private static boolean kelvinFollowingLocalPlayer(KelvinEntity kelvin) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        return kelvin.getFollowingId().filter(mc.player.getUUID()::equals).isPresent();
    }

    private static Component followButtonLabel(KelvinEntity kelvin) {
        MutableComponent line = Component.literal("Follow Me ");
        if (kelvinFollowingLocalPlayer(kelvin)) {
            line.append(Component.literal("ON").withStyle(Style.EMPTY.withBold(true).withColor(ChatFormatting.GREEN)));
        } else {
            line.append(Component.literal("OFF").withStyle(Style.EMPTY.withBold(true).withColor(ChatFormatting.DARK_GRAY)));
        }
        return line;
    }

    private static Component protectButtonLabel(KelvinEntity kelvin) {
        MutableComponent line = Component.literal("Protect Me ");
        if (kelvin.isProtectOwner()) {
            line.append(Component.literal("ON").withStyle(Style.EMPTY.withBold(true).withColor(ChatFormatting.GREEN)));
        } else {
            line.append(Component.literal("OFF").withStyle(Style.EMPTY.withBold(true).withColor(ChatFormatting.DARK_GRAY)));
        }
        return line;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        KelvinEntity kelvin = this.menu.getKelvin();
        if (kelvin != null && !kelvin.isRemoved() && kelvin.isAlive()) {
            if (this.followToggleButton != null) {
                this.followToggleButton.setMessage(followButtonLabel(kelvin));
            }
            if (this.protectToggleButton != null) {
                this.protectToggleButton.setMessage(protectButtonLabel(kelvin));
            }
        }
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (this.goMineButton != null) {
            guiGraphics.renderItem(
                    new ItemStack(Items.DIAMOND_PICKAXE), this.goMineButton.getX() + 3, this.goMineButton.getY() + 2);
        }
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // No title or "Inventory" text — Kelvin-only UI.
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        guiGraphics.blit(INVENTORY_LOCATION, x, y, 0, 0, 176, 166);

        KelvinEntity kelvin = this.menu.getKelvin();
        if (kelvin != null && !kelvin.isRemoved() && kelvin.isAlive()) {
            this.renderKelvinHearts(guiGraphics, kelvin, x + 8, y - HEART_ROW_OFFSET_ABOVE_TOP);

            int px = x + PREVIEW_CENTER_X;
            int py = y + PREVIEW_CENTER_Y;
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    guiGraphics,
                    px,
                    py,
                    PREVIEW_SIZE,
                    (float) px - mouseX,
                    (float) (py - PREVIEW_SIZE) - mouseY,
                    kelvin);
        }
    }

    /**
     * Vanilla-style red hearts using {@code textures/gui/icons.png}. Up to 10 hearts per row; lower row is the
     * first 20 HP, upper row is the next 20 HP (and further rows stack upward if max health is higher).
     */
    private void renderKelvinHearts(GuiGraphics guiGraphics, KelvinEntity kelvin, int startX, int startYBottomRow) {
        float health = kelvin.getHealth();
        float maxHealth = kelvin.getMaxHealth();
        int heartPairs = Mth.ceil(maxHealth / 2.0F);
        final int perRow = 10;
        final int maxRows = 6;
        int maxDisplayPairs = perRow * maxRows;
        int displayPairs = Math.min(heartPairs, maxDisplayPairs);
        for (int pair = 0; pair < displayPairs; pair++) {
            int col = pair % perRow;
            int rowFromBottom = pair / perRow;
            int hx = startX + col * 8;
            int hy = startYBottomRow - rowFromBottom * 10;
            /* Empty heart outline — u=16,v=0 on icons.png (v=9 is armour, not hearts). */
            guiGraphics.blit(GUI_ICONS_LOCATION, hx, hy, 16, 0, 9, 9, 256, 256);
            float slice = health - pair * 2.0F;
            if (slice >= 2.0F) {
                guiGraphics.blit(GUI_ICONS_LOCATION, hx, hy, 52, 0, 9, 9, 256, 256);
            } else if (slice > 0.0F) {
                guiGraphics.blit(GUI_ICONS_LOCATION, hx, hy, 61, 0, 9, 9, 256, 256);
            }
        }
        if (heartPairs > maxDisplayPairs) {
            guiGraphics.drawString(
                    this.font,
                    "+" + (heartPairs - maxDisplayPairs),
                    startX + perRow * 8 + 2,
                    startYBottomRow - (maxRows - 1) * 10 + 1,
                    0xFFAA00,
                    false);
        }
    }
}
