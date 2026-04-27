package ccc.dev.kelvin.world.inventory;

import ccc.dev.kelvin.ModMenus;
import ccc.dev.kelvin.entity.KelvinEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

public final class KelvinInventoryMenu extends AbstractContainerMenu {
    /** Crafting: result 0, grid 1–4. Then Kelvin equipment + storage (same layout as player survival). */
    public static final int CRAFT_RESULT_SLOT = 0;
    public static final int CRAFT_GRID_FIRST = 1;
    public static final int CRAFT_GRID_LAST = 4;
    public static final int CRAFT_SLOT_COUNT = 5;

    /** Kelvin body inventory: hotbar 0–8, storage 9–35, armor feet→head 36–39, offhand 40 — indices in {@link KelvinEntity#getKelvinInventory()}. */
    public static final int KELVIN_SLOT_COUNT = 41;
    private static final int KELVIN_FIRST_SLOT = CRAFT_SLOT_COUNT;
    private static final int KELVIN_LAST_SLOT = KELVIN_FIRST_SLOT + KELVIN_SLOT_COUNT;

    private static final int PLAYER_FIRST_SLOT = KELVIN_LAST_SLOT;
    private static final int PLAYER_SLOT_COUNT = 36;
    private static final int TOTAL_SLOTS = PLAYER_FIRST_SLOT + PLAYER_SLOT_COUNT;

    private static final int HIDDEN_SLOT = -2000;

    /**
     * Extra height so hearts drawn above the panel are not clipped (baseline + up to six 10px-tall heart rows
     * for high max-health packs).
     */
    public static final int GUI_IMAGE_HEIGHT = 166 + 14 + 50;

    private final KelvinEntity kelvin;
    private final CraftingContainer craftSlots;
    private final ResultContainer resultSlots;

    public static KelvinInventoryMenu fromNetwork(int windowId, Inventory playerInv, FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        net.minecraft.world.entity.Entity entity = playerInv.player.level().getEntity(entityId);
        KelvinEntity kelvin = entity instanceof KelvinEntity k && k.isAlive() ? k : null;
        return new KelvinInventoryMenu(ModMenus.KELVIN_INVENTORY.get(), windowId, playerInv, kelvin);
    }

    public KelvinInventoryMenu(MenuType<?> type, int windowId, Inventory playerInv, KelvinEntity kelvin) {
        super(type, windowId);
        this.kelvin = kelvin;
        this.craftSlots = new TransientCraftingContainer(this, 2, 2);
        this.resultSlots = new ResultContainer();

        if (kelvin != null) {
            kelvin.startOpen(playerInv.player);
        }

        Player owner = playerInv.player;
        this.addSlot(new ResultSlot(owner, craftSlots, resultSlots, 0, 154, 26));
        this.addSlot(new Slot(craftSlots, 0, 98, 18));
        this.addSlot(new Slot(craftSlots, 1, 116, 18));
        this.addSlot(new Slot(craftSlots, 2, 98, 36));
        this.addSlot(new Slot(craftSlots, 3, 116, 36));

        if (kelvin != null) {
            addKelvinSlots(kelvin);
        }

        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                int index = col + row * 9 + 9;
                this.addSlot(new Slot(playerInv, index, HIDDEN_SLOT, HIDDEN_SLOT));
            }
        }
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInv, col, HIDDEN_SLOT, HIDDEN_SLOT));
        }

        if (kelvin != null && !kelvin.level().isClientSide) {
            this.updateCraftingResult();
        }
    }

    private void addKelvinSlots(KelvinEntity kelvin) {
        Container container = kelvin.getKelvinInventory();
        /* Match vanilla survival inventory.png slot grid (176×166) so items line up with the texture. */
        this.addSlot(new KelvinArmorSlot(container, kelvin, EquipmentSlot.FEET, 8, 62));
        this.addSlot(new KelvinArmorSlot(container, kelvin, EquipmentSlot.LEGS, 8, 44));
        this.addSlot(new KelvinArmorSlot(container, kelvin, EquipmentSlot.CHEST, 8, 26));
        this.addSlot(new KelvinArmorSlot(container, kelvin, EquipmentSlot.HEAD, 8, 8));
        this.addSlot(new Slot(container, 40, 77, 62));
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                int index = col + row * 9 + 9;
                this.addSlot(new Slot(container, index, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(container, col, 8 + col * 18, 142));
        }
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (this.kelvin != null && !this.kelvin.level().isClientSide && container == this.craftSlots) {
            this.updateCraftingResult();
        }
    }

    private void updateCraftingResult() {
        if (this.kelvin == null || this.kelvin.level().isClientSide) {
            return;
        }
        var server = this.kelvin.level().getServer();
        if (server == null) {
            return;
        }
        var recipeManager = server.getRecipeManager();
        var recipe = recipeManager.getRecipeFor(RecipeType.CRAFTING, this.craftSlots, this.kelvin.level());
        this.resultSlots.setRecipeUsed(recipe.orElse(null));
        ItemStack result = recipe.map(r -> r.assemble(this.craftSlots, this.kelvin.level().registryAccess())).orElse(ItemStack.EMPTY);
        this.resultSlots.setItem(0, result);
        this.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack remainder = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack taken = slot.getItem();
            remainder = taken.copy();
            if (index == CRAFT_RESULT_SLOT) {
                if (!this.moveItemStackTo(taken, KELVIN_FIRST_SLOT, TOTAL_SLOTS, true)) {
                    return ItemStack.EMPTY;
                }
                slot.onQuickCraft(taken, remainder);
            } else if (index >= CRAFT_GRID_FIRST && index <= CRAFT_GRID_LAST) {
                if (!this.moveItemStackTo(taken, KELVIN_FIRST_SLOT, TOTAL_SLOTS, false)) {
                    if (!this.moveItemStackTo(taken, PLAYER_FIRST_SLOT, TOTAL_SLOTS, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            } else if (index >= KELVIN_FIRST_SLOT && index < KELVIN_LAST_SLOT) {
                if (!this.moveItemStackTo(taken, CRAFT_GRID_FIRST, CRAFT_GRID_LAST + 1, false)) {
                    if (!this.moveItemStackTo(taken, PLAYER_FIRST_SLOT, TOTAL_SLOTS, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            } else if (index >= PLAYER_FIRST_SLOT) {
                if (!this.moveItemStackTo(taken, CRAFT_GRID_FIRST, CRAFT_GRID_LAST + 1, false)) {
                    if (!this.moveItemStackTo(taken, KELVIN_FIRST_SLOT, KELVIN_LAST_SLOT, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            }
            if (taken.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (taken.getCount() == remainder.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, taken);
            if (index == CRAFT_RESULT_SLOT) {
                this.updateCraftingResult();
            }
        }
        return remainder;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.kelvin != null
                && this.kelvin.isAlive()
                && this.kelvin.distanceToSqr(player) < KelvinEntity.PLAYER_INTERACTION_MAX_DIST_SQR;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        for (int i = 0; i < this.craftSlots.getContainerSize(); ++i) {
            ItemStack stack = this.craftSlots.removeItemNoUpdate(i);
            if (!stack.isEmpty()) {
                player.drop(stack, false);
            }
        }
        this.resultSlots.clearContent();
        if (this.kelvin != null) {
            this.kelvin.stopOpen(player);
        }
    }

    public KelvinEntity getKelvin() {
        return this.kelvin;
    }

    private static int armorContainerIndex(EquipmentSlot slot) {
        return switch (slot) {
            case FEET -> 36;
            case LEGS -> 37;
            case CHEST -> 38;
            case HEAD -> 39;
            default -> -1;
        };
    }

    private static final class KelvinArmorSlot extends Slot {
        private final KelvinEntity kelvin;
        private final EquipmentSlot type;

        KelvinArmorSlot(Container container, KelvinEntity kelvin, EquipmentSlot type, int x, int y) {
            super(container, armorContainerIndex(type), x, y);
            this.kelvin = kelvin;
            this.type = type;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.isEmpty() || stack.canEquip(this.type, this.kelvin);
        }
    }
}
