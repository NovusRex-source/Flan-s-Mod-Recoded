package com.flansmod.recoded.registry

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.GunItem
import com.mojang.serialization.Codec
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab
import net.minecraft.core.Registry
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.item.Item

object FlansComponents {
    /** Which [com.flansmod.recoded.gun.GunDefinition] a gun stack represents. */
    val GUN: DataComponentType<Identifier> = register("gun") {
        persistent(Identifier.CODEC).networkSynchronized(Identifier.STREAM_CODEC)
    }

    /** Rounds currently loaded. */
    val AMMO: DataComponentType<Int> = register("ammo") {
        persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT)
    }

    private fun <T : Any> register(name: String, build: DataComponentType.Builder<T>.() -> Unit): DataComponentType<T> =
        Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, FlansMod.id(name), DataComponentType.builder<T>().apply(build).build())

    fun init() = Unit
}

object FlansItems {
    val GUN: GunItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("gun"),
        GunItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("gun"))).stacksTo(1)),
    )

    init {
        Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, FlansMod.id("guns"),
            FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.flansmod.guns"))
                .icon { GunItem.stackFor(Guns.all.keys.firstOrNull()) }
                .displayItems { _, output -> Guns.all.keys.sorted().forEach { output.accept(GunItem.stackFor(it)) } }
                .build(),
        )
    }

    fun init() = Unit
}

object FlansDamageTypes {
    val GUN: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, FlansMod.id("gun"))
}
