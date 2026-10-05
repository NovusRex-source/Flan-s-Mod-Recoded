package com.flansmod.recoded.registry

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.entity.GrenadeEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.AttachmentItem
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
import net.minecraft.world.item.ItemStack

object FlansComponents {
    /** Which [com.flansmod.recoded.gun.GunDefinition] a gun stack represents. */
    val GUN: DataComponentType<Identifier> = register("gun") {
        persistent(Identifier.CODEC).networkSynchronized(Identifier.STREAM_CODEC)
    }

    /** Installed attachments on a gun, by slot. */
    val ATTACHMENTS: DataComponentType<Map<String, Identifier>> = register("attachments") {
        persistent(Codec.unboundedMap(Codec.STRING, Identifier.CODEC))
            .networkSynchronized(ByteBufCodecs.map(::HashMap, ByteBufCodecs.STRING_UTF8, Identifier.STREAM_CODEC))
    }

    /** Which [com.flansmod.recoded.gun.AttachmentDefinition] an attachment stack represents. */
    val ATTACHMENT: DataComponentType<Identifier> = register("attachment") {
        persistent(Identifier.CODEC).networkSynchronized(Identifier.STREAM_CODEC)
    }

    /** Which [com.flansmod.recoded.gun.AmmoDefinition] an ammo stack represents. */
    val AMMO_TYPE: DataComponentType<Identifier> = register("ammo_type") {
        persistent(Identifier.CODEC).networkSynchronized(Identifier.STREAM_CODEC)
    }

    /** Which [com.flansmod.recoded.gun.GrenadeDefinition] a grenade stack represents. */
    val GRENADE: DataComponentType<Identifier> = register("grenade") {
        persistent(Identifier.CODEC).networkSynchronized(Identifier.STREAM_CODEC)
    }

    /** Magazine contents: on a magazine item, or the magazine inserted in a gun. */
    val MAGAZINE: DataComponentType<MagazineContents> = register("magazine") {
        persistent(MagazineContents.CODEC).networkSynchronized(MagazineContents.STREAM_CODEC)
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

    val ATTACHMENT: AttachmentItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("attachment"),
        AttachmentItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("attachment")))),
    )

    val AMMO: AmmoItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("ammo"),
        AmmoItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("ammo")))),
    )

    val MAGAZINE: MagazineItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("magazine"),
        MagazineItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("magazine"))).stacksTo(1)),
    )

    val GRENADE: GrenadeItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("grenade"),
        GrenadeItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("grenade"))).stacksTo(16)),
    )

    init {
        Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, FlansMod.id("guns"),
            FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.flansmod.guns"))
                .icon { ItemStack(AMMO) } // a bare gun has no model; the ammo item falls back to a vanilla one
                .displayItems { _, output ->
                    fun <T : Any> ids(all: Map<Identifier, T>) = all.keys.filterNot(coveredByPackTab).sorted()
                    ids(Guns.all).forEach { output.accept(GunItem.stackFor(it)) }
                    ids(Magazines.all).forEach { output.accept(MagazineItem.stackFor(it, full = true)) }
                    ids(AmmoTypes.all).forEach { output.accept(AmmoItem.stackFor(it)) }
                    ids(Attachments.all).forEach { output.accept(AttachmentItem.stackFor(it)) }
                    ids(Grenades.all).filter { Grenades[it]!!.throwable }.forEach { output.accept(GrenadeItem.stackFor(it)) }
                }
                .build(),
        )
    }

    /** Set on the client: ids whose content pack has its own creative tab are left out of the generic tab. */
    var coveredByPackTab: (Identifier) -> Boolean = { false }

    fun init() = Unit
}

object FlansEntities {
    private val GRENADE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, FlansMod.id("grenade"))

    val GRENADE: EntityType<GrenadeEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE, GRENADE_KEY,
        EntityType.Builder.of(::GrenadeEntity, MobCategory.MISC).sized(0.25f, 0.25f).clientTrackingRange(4).updateInterval(10).build(GRENADE_KEY),
    )

    fun init() = Unit
}

object FlansDamageTypes {
    val GUN: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, FlansMod.id("gun"))
    /** Armour-piercing rounds; tagged `minecraft:bypasses_armor`. */
    val GUN_AP: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, FlansMod.id("gun_ap"))
}
