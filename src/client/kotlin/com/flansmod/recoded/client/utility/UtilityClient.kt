package com.flansmod.recoded.client.utility

import com.flansmod.recoded.gear.gear
import com.flansmod.recoded.utility.Utilities
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/** Client side of the field utilities: the field map screen, the compass HUD and the waypoint they share. */
object UtilityClient {
    /** The waypoint set on the field map (this session only); shown on the map and the compass. */
    var waypoint: BlockPos? = null

    fun init() {
        Utilities.openMap = { stack -> stack.gear?.let { Minecraft.getInstance().gui.setScreen(FieldMapScreen(it)) } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> waypoint = null }
        CompassHud.init()
    }
}
