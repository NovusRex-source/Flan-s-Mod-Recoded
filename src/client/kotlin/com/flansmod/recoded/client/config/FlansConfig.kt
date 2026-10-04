package com.flansmod.recoded.client.config

import com.flansmod.recoded.FlansMod
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import me.shedaniel.autoconfig.AutoConfig
import me.shedaniel.autoconfig.AutoConfigClient
import me.shedaniel.autoconfig.ConfigData
import me.shedaniel.autoconfig.annotation.Config
import me.shedaniel.autoconfig.annotation.ConfigEntry
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer

/** Client-only preferences, edited through Cloth Config's generated screen (reachable via Mod Menu). */
@Config(name = FlansMod.MOD_ID)
class FlansConfig : ConfigData {
    @JvmField @ConfigEntry.BoundedDiscrete(min = 0, max = 200)
    var recoilMultiplier = 100

    @JvmField @ConfigEntry.BoundedDiscrete(min = 10, max = 100)
    var adsSensitivity = 60

    @JvmField var toggleAim = false
    @JvmField var showAmmoHud = true
    @JvmField var hideCrosshairWhileAiming = true

    companion object {
        fun register() {
            AutoConfig.register(FlansConfig::class.java, ::GsonConfigSerializer)
        }

        val get: FlansConfig get() = AutoConfig.getConfigHolder(FlansConfig::class.java).config
    }
}

class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory() = ConfigScreenFactory { parent ->
        AutoConfigClient.getConfigScreen(FlansConfig::class.java, parent).get()
    }
}
