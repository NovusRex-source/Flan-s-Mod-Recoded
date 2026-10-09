package com.flansmod.recoded.client.compat

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.bench.WeaponAssemblyRecipe
import com.flansmod.recoded.bench.WeaponsBenchBlock
import com.flansmod.recoded.bench.WeaponsBenchMenu
import com.flansmod.recoded.client.bench.WeaponsBenchScreen
import com.flansmod.recoded.registry.FlansItems
import com.flansmod.recoded.registry.FlansMenus
import com.flansmod.recoded.registry.FlansRecipes
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter
import mezz.jei.api.ingredients.subtypes.UidContext
import mezz.jei.api.registration.ISubtypeRegistration
import com.flansmod.recoded.item.loadedMagazine
import com.flansmod.recoded.registry.FlansComponents
import net.minecraft.world.item.ItemStack
import mezz.jei.api.runtime.IJeiRuntime
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.category.IRecipeCategory
import mezz.jei.api.recipe.types.IRecipeHolderType
import mezz.jei.api.recipe.types.IRecipeType
import mezz.jei.api.registration.IGuiHandlerRegistration
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.registration.IRecipeRegistration
import mezz.jei.api.registration.IRecipeTransferRegistration
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.Rect2i
import mezz.jei.api.gui.handlers.IGuiContainerHandler
import com.flansmod.recoded.client.gamemode.BattleMasterScreen
import com.flansmod.recoded.client.gamemode.ShopEditorScreen
import com.flansmod.recoded.client.gamemode.SideButtons
import com.flansmod.recoded.client.gamemode.TeamFlagScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.crafting.RecipeHolder

/**
 * JEI integration (optional dependency): shows Weapons Bench recipes on the real 6x4 grid, lists the bench as
 * crafting station, adds the "+" button that moves ingredients into the grid, and makes the bench's arrow open
 * the recipe list. Recipes come from Fabric's recipe sync, so it also works on dedicated servers.
 */
@JeiPlugin
class FlansJeiPlugin : IModPlugin {
    override fun getPluginUid() = FlansMod.id("jei")

    /** One item per content type, told apart by components: tell JEI which component makes the subtype. */
    override fun registerItemSubtypes(registration: ISubtypeRegistration) {
        registration.registerFromDataComponentTypes(FlansItems.GUN, FlansComponents.GUN)
        registration.registerFromDataComponentTypes(FlansItems.PART, FlansComponents.PART)
        registration.registerFromDataComponentTypes(FlansItems.AMMO, FlansComponents.AMMO_TYPE)
        registration.registerFromDataComponentTypes(FlansItems.ATTACHMENT, FlansComponents.ATTACHMENT)
        registration.registerFromDataComponentTypes(FlansItems.GRENADE, FlansComponents.GRENADE)
        registration.registerFromDataComponentTypes(FlansItems.VEHICLE, FlansComponents.VEHICLE)
        registration.registerFromDataComponentTypes(FlansItems.VEHICLE_UPGRADE, FlansComponents.VEHICLE_UPGRADE)
        // Magazines: the type matters, not how many rounds are inside.
        registration.registerSubtypeInterpreter(FlansItems.MAGAZINE, object : ISubtypeInterpreter<ItemStack> {
            override fun getSubtypeData(stack: ItemStack, context: UidContext) = stack.loadedMagazine?.magazine
        })
    }

    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        registration.addRecipeCategories(AssemblyCategory(registration.jeiHelpers.guiHelper))
    }

    override fun registerRecipes(registration: IRecipeRegistration) {
        registration.addRecipes(TYPE, benchRecipes())
    }

    override fun registerRecipeCatalysts(registration: IRecipeCatalystRegistration) {
        registration.addCraftingStation(TYPE, FlansItems.WEAPONS_BENCH)
    }

    override fun registerRecipeTransferHandlers(registration: IRecipeTransferRegistration) {
        // Recipe input slots are added in grid order, so they map 1:1 onto menu slots 1..24.
        registration.addRecipeTransferHandler(
            WeaponsBenchMenu::class.java, FlansMenus.WEAPONS_BENCH, TYPE,
            WeaponsBenchMenu.GRID_START, WeaponAssemblyRecipe.WIDTH * WeaponAssemblyRecipe.HEIGHT,
            WeaponsBenchMenu.INVENTORY_START, WeaponsBenchMenu.INVENTORY_SIZE,
        )
    }

    override fun onRuntimeAvailable(jeiRuntime: IJeiRuntime) {
        runtime = jeiRuntime
    }

    override fun onRuntimeUnavailable() {
        runtime = null
    }

    override fun registerGuiHandlers(registration: IGuiHandlerRegistration) {
        registration.addRecipeClickArea(WeaponsBenchScreen::class.java, 120, 44, 24, 18, TYPE)
        // Battle screens have a button column right of the panel: keep JEI's item list clear of it.
        fun <T : AbstractContainerScreen<*>> buttonsBeside(screen: Class<T>) = registration.addGuiContainerHandler(screen, object : IGuiContainerHandler<T> {
            override fun getGuiExtraAreas(containerScreen: T): List<Rect2i> = (containerScreen as SideButtons).sideAreas()
        })
        buttonsBeside(BattleMasterScreen::class.java)
        buttonsBeside(TeamFlagScreen::class.java)
        buttonsBeside(ShopEditorScreen::class.java)
    }

    private class AssemblyCategory(guiHelper: IGuiHelper) : IRecipeCategory<RecipeHolder<WeaponAssemblyRecipe>> {
        private val icon: IDrawable = guiHelper.createDrawableItemLike(FlansItems.WEAPONS_BENCH)
        private val arrow = guiHelper.recipeArrow

        override fun getRecipeType(): IRecipeType<RecipeHolder<WeaponAssemblyRecipe>> = TYPE
        override fun getTitle() = WeaponsBenchBlock.TITLE
        override fun getWidth() = 6 * 18 + 44
        override fun getHeight() = 4 * 18
        override fun getIcon() = icon

        override fun setRecipe(builder: IRecipeLayoutBuilder, holder: RecipeHolder<WeaponAssemblyRecipe>, focuses: IFocusGroup) {
            val recipe = holder.value()
            for (y in 0 until WeaponAssemblyRecipe.HEIGHT) for (x in 0 until WeaponAssemblyRecipe.WIDTH) {
                val slot = builder.addInputSlot(x * 18 + 1, y * 18 + 1).setStandardSlotBackground()
                recipe.pattern.getOrNull(y)?.getOrNull(x)?.let(recipe.key::get)?.let { slot.add(it.display()) }
            }
            builder.addOutputSlot(6 * 18 + 26, 28).setOutputSlotBackground().add(recipe.result)
        }

        override fun draw(holder: RecipeHolder<WeaponAssemblyRecipe>, slots: IRecipeSlotsView, graphics: GuiGraphicsExtractor, mouseX: Double, mouseY: Double) {
            arrow.draw(graphics, 6 * 18 + 2, 28)
        }
    }

    companion object {
        val TYPE: IRecipeHolderType<WeaponAssemblyRecipe> = IRecipeType.create(FlansRecipes.WEAPON_ASSEMBLY)

        /** The live JEI runtime while a world is loaded (used by tests). */
        var runtime: IJeiRuntime? = null
            private set

        /** Bench recipes as synced to this client by Fabric's recipe sync. */
        private fun benchRecipes(): List<RecipeHolder<WeaponAssemblyRecipe>> =
            Minecraft.getInstance().level?.recipeAccess()?.synchronizedRecipes?.getAllOfType(FlansRecipes.WEAPON_ASSEMBLY)?.toList().orEmpty()
    }
}
