package ccc.dev.kelvin.client;

import ccc.dev.kelvin.network.ModNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class KelvinGoMineBlockScreen extends Screen {
    private static final int LIST_ROWS = 12;
    private static final int ROW_HEIGHT = 18;
    private static final int ICON_SIZE = 16;
    private static final int TEXT_PAD = ICON_SIZE + 6;
    private static final int SIDE_PAD = 16;

    private final int kelvinEntityId;
    private EditBox search;
    private String lastFilter = "";
    private List<Block> filtered = List.of();
    private int scroll;
    private int listLeft;
    private int listTop;
    private int listWidth;
    private int listHeight;

    public KelvinGoMineBlockScreen(int kelvinEntityId) {
        super(Component.translatable("gui.ccc_kelvin.go_mine_pick_block"));
        this.kelvinEntityId = kelvinEntityId;
    }

    @Override
    protected void init() {
        this.listLeft = SIDE_PAD;
        this.listTop = 54;
        this.listWidth = this.width - SIDE_PAD * 2;
        this.listHeight = LIST_ROWS * ROW_HEIGHT;
        this.search = new EditBox(this.font, SIDE_PAD, 24, this.width - SIDE_PAD * 2, 20, Component.translatable("gui.ccc_kelvin.go_mine_search"));
        this.search.setMaxLength(128);
        this.search.setResponder(s -> {
            if (!s.equals(this.lastFilter)) {
                this.lastFilter = s;
                this.rebuildFilter();
                this.scroll = 0;
            }
        });
        this.addRenderableWidget(this.search);
        this.rebuildFilter();
        this.setInitialFocus(this.search);
    }

    private static String displayName(Block b) {
        return Component.translatable(b.getDescriptionId()).getString();
    }

    private void rebuildFilter() {
        String q = this.lastFilter.trim().toLowerCase(Locale.ROOT);
        List<Block> out = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR || b == Blocks.BARRIER) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
            if (id == null) {
                continue;
            }
            String path = id.getPath().toLowerCase(Locale.ROOT);
            String ns = id.getNamespace().toLowerCase(Locale.ROOT);
            String idStr = id.toString().toLowerCase(Locale.ROOT);
            String disp = displayName(b).toLowerCase(Locale.ROOT);
            if (q.isEmpty()
                    || idStr.contains(q)
                    || path.contains(q)
                    || ns.contains(q)
                    || disp.contains(q)) {
                out.add(b);
            }
        }
        out.sort(Comparator.comparing(KelvinGoMineBlockScreen::displayName, String.CASE_INSENSITIVE_ORDER));
        this.filtered = out;
    }

    private static ItemStack iconStackFor(Block b) {
        ItemStack stack = new ItemStack(b.asItem());
        if (stack.isEmpty()) {
            stack = new ItemStack(Items.BARRIER);
        }
        return stack;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        guiGraphics.drawString(this.font, this.title, SIDE_PAD, 8, 0xFFFFFF, false);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int maxScroll = Math.max(0, this.filtered.size() - LIST_ROWS);
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));
        int y = this.listTop;
        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = this.scroll + i;
            if (idx >= this.filtered.size()) {
                break;
            }
            Block b = this.filtered.get(idx);
            boolean hover =
                    mouseX >= this.listLeft
                            && mouseX < this.listLeft + this.listWidth
                            && mouseY >= y
                            && mouseY < y + ROW_HEIGHT;
            int color = hover ? 0xFFFFFF : 0xCCCCCC;
            guiGraphics.renderItem(iconStackFor(b), this.listLeft + 1, y + 1);
            Component line = Component.translatable(b.getDescriptionId());
            guiGraphics.drawString(this.font, line, this.listLeft + TEXT_PAD, y + 5, color, false);
            y += ROW_HEIGHT;
        }
        if (this.filtered.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("gui.ccc_kelvin.go_mine_no_match"), this.listLeft, this.listTop + 4, 0x888888, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (mouseX < this.listLeft
                || mouseX >= this.listLeft + this.listWidth
                || mouseY < this.listTop
                || mouseY >= this.listTop + this.listHeight) {
            return false;
        }
        int row = (int) ((mouseY - this.listTop) / ROW_HEIGHT);
        int idx = this.scroll + row;
        if (idx < 0 || idx >= this.filtered.size()) {
            return false;
        }
        Block chosen = this.filtered.get(idx);
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(chosen);
        ModNetwork.sendGoMineStart(this.kelvinEntityId, key);
        this.onClose();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, this.filtered.size() - LIST_ROWS);
        if (maxScroll > 0) {
            this.scroll = Math.max(0, Math.min(maxScroll, this.scroll - (int) Math.signum(delta) * 3));
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static void open(int kelvinEntityId) {
        Minecraft.getInstance().setScreen(new KelvinGoMineBlockScreen(kelvinEntityId));
    }
}
