package com.mrcrayfish.vehicle.datagen;

import com.mrcrayfish.vehicle.init.ModBlocks;
import com.mrcrayfish.vehicle.init.ModItems;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.recipes.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Author: MrCrayfish
 */
public class RecipeGen extends RecipeProvider
{
    public RecipeGen(DataGenerator generator)
    {
        super(generator.getPackOutput());
    }

    @Override
    protected void buildRecipes(Consumer<FinishedRecipe> consumer)
    {
        netheriteSmithing(consumer, ModItems.DIAMOND_ELECTRIC_ENGINE.get(), ModItems.NETHERITE_ELECTRIC_ENGINE.get());

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.DIAMOND_ELECTRIC_ENGINE::get)
                .pattern(" U ")
                .pattern("UEU")
                .pattern(" U ")
                .define('U', Tags.Items.GEMS_DIAMOND)
                .define('E', ModItems.GOLD_ELECTRIC_ENGINE.get())
                .unlockedBy("has_diamond", has(Tags.Items.GEMS_DIAMOND))
                .unlockedBy("has_gold_electric_engine", has(ModItems.GOLD_ELECTRIC_ENGINE.get()))
                .save(consumer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.GOLD_ELECTRIC_ENGINE::get)
                .pattern(" U ")
                .pattern("UEU")
                .pattern(" U ")
                .define('U', Tags.Items.INGOTS_GOLD)
                .define('E', ModItems.IRON_ELECTRIC_ENGINE.get())
                .unlockedBy("has_gold_ingot", has(Tags.Items.INGOTS_GOLD))
                .unlockedBy("has_iron_electric_engine", has(ModItems.IRON_ELECTRIC_ENGINE.get()))
                .save(consumer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.IRON_ELECTRIC_ENGINE::get)
                .pattern("IRI")
                .pattern("TBT")
                .pattern("IPI")
                .define('I', Tags.Items.INGOTS_IRON)
                .define('R', Items.REPEATER)
                .define('T', Items.REDSTONE_TORCH)
                .define('P', ModItems.PANEL.get())
                .define('B', Items.REDSTONE_BLOCK)
                .unlockedBy("has_iron_ingot", has(Tags.Items.INGOTS_IRON))
                .unlockedBy("has_repeater", has(Items.REPEATER))
                .unlockedBy("has_redstone_torch", has(Items.REDSTONE_TORCH))
                .unlockedBy("has_panel", has(ModItems.PANEL.get()))
                .unlockedBy("has_redstone_block", has(Items.REDSTONE_BLOCK))
                .save(consumer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.HAMMER::get)
                .pattern("III")
                .pattern(" G ")
                .pattern(" W ")
                .define('I', Tags.Items.INGOTS_IRON)
                .define('G', Tags.Items.INGOTS_GOLD)
                .define('W', Items.BLACK_WOOL)
                .unlockedBy("has_iron_ingot", has(Tags.Items.INGOTS_IRON))
                .unlockedBy("has_gold_ingot", has(Tags.Items.INGOTS_GOLD))
                .unlockedBy("has_black_wool", has(Items.BLACK_WOOL))
                .save(consumer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.WRENCH::get)
                .pattern("I")
                .pattern("G")
                .pattern("W")
                .define('I', Tags.Items.INGOTS_IRON)
                .define('G', Tags.Items.INGOTS_GOLD)
                .define('W', Items.BLACK_WOOL)
                .unlockedBy("has_iron_ingot", has(Tags.Items.INGOTS_IRON))
                .unlockedBy("has_gold_ingot", has(Tags.Items.INGOTS_GOLD))
                .unlockedBy("has_black_wool", has(Items.BLACK_WOOL))
                .save(consumer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModBlocks.JACK.get())
                .pattern("IPI")
                .pattern("IRI")
                .define('I', Tags.Items.INGOTS_GOLD)
                .define('P', Items.PISTON)
                .define('R', Items.REDSTONE)
                .unlockedBy("has_gold_ingot", has(Tags.Items.INGOTS_GOLD))
                .unlockedBy("has_piston", has(Items.PISTON))
                .unlockedBy("has_redstone", has(Items.REDSTONE))
                .save(consumer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.KEY::get)
                .pattern("WII")
                .define('W', Items.BLACK_WOOL)
                .define('I', Tags.Items.INGOTS_GOLD)
                .unlockedBy("has_black_wool", has(Items.BLACK_WOOL))
                .unlockedBy("has_iron_ingot", has(Tags.Items.INGOTS_IRON))
                .save(consumer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, ModItems.PANEL::get, 2)
                .pattern("III")
                .pattern("III")
                .define('I', Tags.Items.NUGGETS_IRON)
                .unlockedBy("has_iron_nugget", has(Tags.Items.NUGGETS_IRON))
                .save(consumer);

    }

    protected static void netheriteSmithing(Consumer<FinishedRecipe> consumer, Item inputItem, Item resultItem) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(resultItem);

        SmithingTransformRecipeBuilder.smithing(
                        Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                        Ingredient.of(inputItem),
                        Ingredient.of(Items.NETHERITE_INGOT),
                        RecipeCategory.MISC,
                        resultItem
                )
                .unlocks("has_netherite_ingot", RecipeProvider.has(Items.NETHERITE_INGOT))
                .save(consumer, new ResourceLocation(id.getNamespace(), id.getPath() + "_smithing"));
    }

}
