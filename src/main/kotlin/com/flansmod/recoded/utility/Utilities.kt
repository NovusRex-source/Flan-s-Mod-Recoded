package com.flansmod.recoded.utility

import com.flansmod.recoded.FlansMod
import com.mojang.serialization.Codec
import net.minecraft.core.Registry
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.world.item.ItemStack

/**
 * Field utilities (gear types `map`, `flashlight`, `compass`; the definitions are ordinary gear from content packs):
 * - **Field map:** right click opens a terrain map around you (client: `UtilityClient`), click sets a waypoint.
 * - **Flashlight:** right click switches it on; while it is held, the beam lights the spot it points at ([Flashlights]).
 * - **Compass:** while held, a heading strip on the HUD with the map's waypoint.
 */
object Utilities {
    /** A flashlight that is switched on. */
    val ACTIVE: DataComponentType<Boolean> = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, FlansMod.id("active"),
        DataComponentType.builder<Boolean>().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build())

    /** Set by the client: opens the field map screen for this map item (right click on the client side). */
    var openMap: (ItemStack) -> Unit = {}

    fun init() {
        Flashlights.init()
    }
}

val ItemStack.isSwitchedOn: Boolean get() = getOrDefault(Utilities.ACTIVE, false)
