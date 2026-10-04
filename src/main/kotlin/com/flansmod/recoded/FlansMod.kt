package com.flansmod.recoded

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object FlansMod : ModInitializer {
    const val MOD_ID = "flansmod"
    val LOGGER = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        LOGGER.info("Flan's Mod: Recoded initializing")
    }
}
