package com.flansmod.recoded

import com.flansmod.recoded.combat.AttachmentHandler
import com.flansmod.recoded.combat.Ballistics
import com.flansmod.recoded.combat.GunHandler
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.network.FlansNetworking
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import com.geckolib.animatable.GeoItem
import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

object FlansMod : ModInitializer {
    const val MOD_ID = "flansmod"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

    override fun onInitialize() {
        FlansComponents.init()
        FlansItems.init()
        GeoItem.registerSyncedAnimatable(FlansItems.GUN)
        FlansNetworking.init()
        Content.init()
        Ballistics.init()
        GunHandler.init()
        AttachmentHandler.init()
    }
}
