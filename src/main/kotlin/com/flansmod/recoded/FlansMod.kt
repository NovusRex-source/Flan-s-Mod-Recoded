package com.flansmod.recoded

import com.flansmod.recoded.combat.AttachmentHandler
import com.flansmod.recoded.combat.Ballistics
import com.flansmod.recoded.combat.GunHandler
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.network.FlansNetworking
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import com.flansmod.recoded.registry.FlansEntities
import com.flansmod.recoded.registry.FlansBlocks
import com.flansmod.recoded.registry.FlansMenus
import com.flansmod.recoded.registry.FlansRecipes
import com.geckolib.animatable.GeoItem
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.recipe.v1.sync.RecipeSynchronization
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object FlansMod : ModInitializer {
    const val MOD_ID = "flansmod"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    /** The built-in content pack shipped in the jar under `resourcepacks/<name>`. */
    const val BUILTIN_PACK = "basic"

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

    override fun onInitialize() {
        FlansComponents.init()
        FlansBlocks.init()
        FlansMenus.init()
        FlansRecipes.init()
        // Since 1.21.2 vanilla does not send recipes to clients; recipe viewers need bench recipes there.
        RecipeSynchronization.synchronizeRecipeSerializer(FlansRecipes.WEAPON_ASSEMBLY_SERIALIZER)
        FlansItems.init()
        FlansEntities.init()
        GeoItem.registerSyncedAnimatable(FlansItems.GUN)
        FlansNetworking.init()
        Content.init()
        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent {
            ResourceLoader.registerBuiltinPack(id(BUILTIN_PACK), it, Component.literal("Flan's Mod: Basic Pack"), PackActivationType.DEFAULT_ENABLED)
        }
        Ballistics.init()
        GunHandler.init()
        AttachmentHandler.init()
        com.flansmod.recoded.bench.WeaponMenu.init()
    }
}
