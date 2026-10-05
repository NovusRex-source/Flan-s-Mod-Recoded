package com.flansmod.recoded.registry

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.gun.Parts
import com.flansmod.recoded.gun.Clothing
import com.flansmod.recoded.item.ClothingItem
import com.flansmod.recoded.item.PartItem
import com.flansmod.recoded.bench.WeaponAssemblyRecipe
import com.flansmod.recoded.bench.WeaponsBenchBlock
import com.flansmod.recoded.bench.WeaponsBenchMenu
import com.flansmod.recoded.bench.WeaponMenu
import com.flansmod.recoded.bench.WeaponMenuData
import com.flansmod.recoded.bench.VehicleMenu
import com.flansmod.recoded.bench.VehicleMenuData
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.entity.GrenadeEntity
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.item.VehicleItem
import com.flansmod.recoded.item.VehicleUpgradeItem
import com.flansmod.recoded.gun.VehicleUpgrades
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
            .ignoreSwapAnimation()
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

    /** Which [com.flansmod.recoded.gun.ClothingDefinition] a clothing stack represents. */
    val CLOTHING: DataComponentType<Identifier> = register("clothing") {
        persistent(Identifier.CODEC).networkSynchronized(Identifier.STREAM_CODEC)
    }

    /** Which [com.flansmod.recoded.gun.VehicleDefinition] a vehicle stack represents. */
    val VEHICLE: DataComponentType<Identifier> = register("vehicle") {
        persistent(Identifier.CODEC).networkSynchronized(Identifier.STREAM_CODEC)
    }

    /** Which [com.flansmod.recoded.gun.VehicleUpgradeDefinition] an upgrade stack represents. */
    val VEHICLE_UPGRADE: DataComponentType<Identifier> = register("vehicle_upgrade") {
        persistent(Identifier.CODEC).networkSynchronized(Identifier.STREAM_CODEC)
    }

    /** Upgrades installed in a picked-up vehicle, by slot. */
    val VEHICLE_UPGRADES: DataComponentType<Map<String, Identifier>> = register("vehicle_upgrades") {
        persistent(Codec.unboundedMap(Codec.STRING, Identifier.CODEC))
            .networkSynchronized(ByteBufCodecs.map(::HashMap, ByteBufCodecs.STRING_UTF8, Identifier.STREAM_CODEC))
    }

    /** Damage taken by a picked-up vehicle: `hull` plus each damaged part. */
    val VEHICLE_DAMAGE: DataComponentType<Map<String, Float>> = register("vehicle_damage") {
        persistent(Codec.unboundedMap(Codec.STRING, Codec.FLOAT))
            .networkSynchronized(ByteBufCodecs.map(::HashMap, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.FLOAT))
    }

    /** Fuel left in a picked-up vehicle. */
    val FUEL: DataComponentType<Int> = register("fuel") {
        persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT)
    }

    /** Selected fire mode of a gun stack (see GunDefinition.fire_modes). */
    val FIRE_MODE: DataComponentType<FireMode> = register("fire_mode") {
        persistent(Codec.STRING.xmap({ FireMode.valueOf(it.uppercase()) }, { it.name.lowercase() }))
            .networkSynchronized(ByteBufCodecs.VAR_INT.map({ FireMode.entries[it] }, FireMode::ordinal))
            .ignoreSwapAnimation()
    }

    /** Set by the server while a reload is in progress (not saved); keeps a launcher's round visible during it. */
    val RELOADING: DataComponentType<Boolean> = register("reloading") {
        networkSynchronized(ByteBufCodecs.BOOL).ignoreSwapAnimation()
    }

    /** Which [com.flansmod.recoded.gun.PartDefinition] a part stack represents. */
    val PART: DataComponentType<Identifier> = register("part") {
        persistent(Identifier.CODEC).networkSynchronized(Identifier.STREAM_CODEC)
    }

    /** Magazine contents: on a magazine item, or the magazine inserted in a gun. */
    val MAGAZINE: DataComponentType<MagazineContents> = register("magazine") {
        // Changes on every shot: must not replay the held-item swap animation.
        persistent(MagazineContents.CODEC).networkSynchronized(MagazineContents.STREAM_CODEC).ignoreSwapAnimation()
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

    val PART: PartItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("part"),
        PartItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("part")))),
    )

    val CLOTHING: ClothingItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("clothing"),
        ClothingItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("clothing"))).stacksTo(1)),
    )

    val WEAPONS_BENCH: BlockItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("weapons_bench"),
        BlockItem(FlansBlocks.WEAPONS_BENCH, Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("weapons_bench"))).useBlockDescriptionPrefix()),
    )

    val MAGAZINE: MagazineItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("magazine"),
        MagazineItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("magazine"))).stacksTo(1)),
    )

    val GRENADE: GrenadeItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("grenade"),
        GrenadeItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("grenade"))).stacksTo(16)),
    )

    val VEHICLE: VehicleItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("vehicle"),
        VehicleItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("vehicle"))).stacksTo(1)),
    )

    val VEHICLE_UPGRADE: VehicleUpgradeItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("vehicle_upgrade"),
        VehicleUpgradeItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("vehicle_upgrade"))).stacksTo(1)),
    )

    init {
        Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, FlansMod.id("guns"),
            FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.flansmod.guns"))
                .icon { ItemStack(AMMO) } // a bare gun has no model; the ammo item falls back to a vanilla one
                .displayItems { _, output ->
                    fun <T : Any> ids(all: Map<Identifier, T>) = all.keys.filterNot(coveredByPackTab).sorted()
                    output.accept(WEAPONS_BENCH)
                    ids(Parts.all).forEach { output.accept(PartItem.stackFor(it)) }
                    ids(Guns.all).filterNot { Guns[it]!!.mounted }.forEach { output.accept(GunItem.stackFor(it)) }
                    ids(Magazines.all).forEach { output.accept(MagazineItem.stackFor(it, full = true)) }
                    ids(AmmoTypes.all).forEach { output.accept(AmmoItem.stackFor(it)) }
                    ids(Attachments.all).forEach { output.accept(AttachmentItem.stackFor(it)) }
                    ids(Grenades.all).filter { Grenades[it]!!.throwable }.forEach { output.accept(GrenadeItem.stackFor(it)) }
                    ids(Clothing.all).forEach { output.accept(ClothingItem.stackFor(it)) }
                    ids(Vehicles.all).forEach { output.accept(VehicleItem.stackFor(it)) }
                    ids(VehicleUpgrades.all).forEach { output.accept(VehicleUpgradeItem.stackFor(it)) }
                }
                .build(),
        )
    }

    /** Set on the client: ids whose content pack has its own creative tab are left out of the generic tab. */
    var coveredByPackTab: (Identifier) -> Boolean = { false }

    fun init() = Unit
}

object FlansBlocks {
    private val BENCH_KEY = ResourceKey.create(Registries.BLOCK, FlansMod.id("weapons_bench"))

    val WEAPONS_BENCH: WeaponsBenchBlock = Registry.register(
        BuiltInRegistries.BLOCK, BENCH_KEY,
        WeaponsBenchBlock(BlockBehaviour.Properties.of().setId(BENCH_KEY).mapColor(MapColor.METAL).strength(2.5f).sound(SoundType.METAL)),
    )

    fun init() = Unit
}

object FlansMenus {
    val WEAPONS_BENCH: MenuType<WeaponsBenchMenu> = Registry.register(
        BuiltInRegistries.MENU, FlansMod.id("weapons_bench"), MenuType({ id, inventory -> WeaponsBenchMenu(id, inventory) }, FeatureFlags.VANILLA_SET),
    )

    val WEAPON: ExtendedMenuType<WeaponMenu, WeaponMenuData> = Registry.register(
        BuiltInRegistries.MENU, FlansMod.id("weapon"), ExtendedMenuType(::WeaponMenu, WeaponMenuData.STREAM_CODEC),
    )

    val VEHICLE: ExtendedMenuType<VehicleMenu, VehicleMenuData> = Registry.register(
        BuiltInRegistries.MENU, FlansMod.id("vehicle"), ExtendedMenuType(::VehicleMenu, VehicleMenuData.STREAM_CODEC),
    )

    fun init() = Unit
}

object FlansRecipes {
    val WEAPON_ASSEMBLY: RecipeType<WeaponAssemblyRecipe> = Registry.register(
        BuiltInRegistries.RECIPE_TYPE, FlansMod.id("weapon_assembly"),
        object : RecipeType<WeaponAssemblyRecipe> { override fun toString() = "flansmod:weapon_assembly" },
    )

    val WEAPON_ASSEMBLY_SERIALIZER: RecipeSerializer<WeaponAssemblyRecipe> = Registry.register(
        BuiltInRegistries.RECIPE_SERIALIZER, FlansMod.id("weapon_assembly"),
        RecipeSerializer(WeaponAssemblyRecipe.MAP_CODEC, WeaponAssemblyRecipe.STREAM_CODEC),
    )

    fun init() = Unit
}

object FlansEntities {
    private val GRENADE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, FlansMod.id("grenade"))

    val GRENADE: EntityType<GrenadeEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE, GRENADE_KEY,
        EntityType.Builder.of(::GrenadeEntity, MobCategory.MISC).sized(0.25f, 0.25f).clientTrackingRange(4).updateInterval(10).build(GRENADE_KEY),
    )

    private val DRIVEABLE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, FlansMod.id("vehicle"))

    /** Every content-pack vehicle; the real size comes from its definition ([DriveableEntity.getDimensions]). */
    val DRIVEABLE: EntityType<DriveableEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE, DRIVEABLE_KEY,
        EntityType.Builder.of(::DriveableEntity, MobCategory.MISC).sized(2f, 1.5f).clientTrackingRange(10).build(DRIVEABLE_KEY),
    )

    fun init() = DriveableEntity.registerDataSerializers()
}

object FlansDamageTypes {
    val GUN: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, FlansMod.id("gun"))
    /** Armour-piercing rounds; tagged `minecraft:bypasses_armor`. */
    val GUN_AP: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, FlansMod.id("gun_ap"))
    /** Run over by a vehicle. */
    val VEHICLE: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, FlansMod.id("vehicle"))
}
