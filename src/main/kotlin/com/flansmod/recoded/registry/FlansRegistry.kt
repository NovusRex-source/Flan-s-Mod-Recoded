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
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
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
import com.flansmod.recoded.entity.MineEntity
import com.flansmod.recoded.fuel.FuelSynthesizerBlock
import com.flansmod.recoded.fuel.FuelSynthesizerBlockEntity
import com.flansmod.recoded.fuel.PetrolStationBlock
import com.flansmod.recoded.fuel.PetrolStationBlockEntity
import com.flansmod.recoded.gamemode.BattleMasterBlock
import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.TeamFlagBlock
import com.flansmod.recoded.gamemode.TeamFlagBlockEntity
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

    /** Contents of a fuel can. */
    val FUEL_CAN: DataComponentType<com.flansmod.recoded.fuel.FuelStack> = register("fuel_can") {
        persistent(com.flansmod.recoded.fuel.FuelStack.CODEC).networkSynchronized(com.flansmod.recoded.fuel.FuelStack.STREAM_CODEC.cast())
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
        // Identical magazines (same type, ammo and rounds - e.g. empty or full ones) stack.
        MagazineItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("magazine"))).stacksTo(16)),
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

    val WRENCH: com.flansmod.recoded.item.WrenchItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("wrench"),
        com.flansmod.recoded.item.WrenchItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("wrench"))).durability(250)),
    )

    val FUEL_CAN: com.flansmod.recoded.fuel.FuelCanItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id("fuel_can"),
        com.flansmod.recoded.fuel.FuelCanItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("fuel_can"))).stacksTo(1)),
    )

    val FUEL_SYNTHESIZER: BlockItem = blockItem("fuel_synthesizer", FlansBlocks.FUEL_SYNTHESIZER)
    val PETROL_STATION: BlockItem = blockItem("petrol_station", FlansBlocks.PETROL_STATION)
    val BATTLE_MASTER: BlockItem = blockItem("battle_master", FlansBlocks.BATTLE_MASTER)
    val TEAM_FLAG: BlockItem = blockItem("team_flag", FlansBlocks.TEAM_FLAG)

    private fun blockItem(name: String, block: net.minecraft.world.level.block.Block): BlockItem = Registry.register(
        BuiltInRegistries.ITEM, FlansMod.id(name),
        BlockItem(block, Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id(name))).useBlockDescriptionPrefix()),
    )

    // Creative tabs are client-only (com.flansmod.recoded.client.tab.TypeTabs): their contents come from synced definitions.

    fun init() = Unit
}

object FlansBlocks {
    private val BENCH_KEY = ResourceKey.create(Registries.BLOCK, FlansMod.id("weapons_bench"))

    val WEAPONS_BENCH: WeaponsBenchBlock = Registry.register(
        BuiltInRegistries.BLOCK, BENCH_KEY,
        WeaponsBenchBlock(BlockBehaviour.Properties.of().setId(BENCH_KEY).mapColor(MapColor.METAL).strength(2.5f).sound(SoundType.METAL)),
    )

    val FUEL_SYNTHESIZER: com.flansmod.recoded.fuel.FuelSynthesizerBlock = register("fuel_synthesizer", ::FuelSynthesizerBlock)
    val PETROL_STATION: com.flansmod.recoded.fuel.PetrolStationBlock = register("petrol_station", ::PetrolStationBlock)
    val BATTLE_MASTER: com.flansmod.recoded.gamemode.BattleMasterBlock = register("battle_master", ::BattleMasterBlock)
    val TEAM_FLAG: com.flansmod.recoded.gamemode.TeamFlagBlock = register("team_flag") { TeamFlagBlock(it.strength(2f)) }

    private fun <B : net.minecraft.world.level.block.Block> register(name: String, create: (BlockBehaviour.Properties) -> B): B {
        val key = ResourceKey.create(Registries.BLOCK, FlansMod.id(name))
        return Registry.register(BuiltInRegistries.BLOCK, key,
            create(BlockBehaviour.Properties.of().setId(key).mapColor(MapColor.METAL).strength(3f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()))
    }

    fun init() = Unit
}

object FlansBlockEntities {
    val FUEL_SYNTHESIZER: net.minecraft.world.level.block.entity.BlockEntityType<com.flansmod.recoded.fuel.FuelSynthesizerBlockEntity> =
        register("fuel_synthesizer", ::FuelSynthesizerBlockEntity, FlansBlocks.FUEL_SYNTHESIZER)
    val PETROL_STATION: net.minecraft.world.level.block.entity.BlockEntityType<com.flansmod.recoded.fuel.PetrolStationBlockEntity> =
        register("petrol_station", ::PetrolStationBlockEntity, FlansBlocks.PETROL_STATION)

    private fun <T : net.minecraft.world.level.block.entity.BlockEntity> register(
        name: String, create: (net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState) -> T, block: net.minecraft.world.level.block.Block,
    ) = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, FlansMod.id(name),
        FabricBlockEntityTypeBuilder.create(create, block).build())

    val BATTLE_MASTER: net.minecraft.world.level.block.entity.BlockEntityType<com.flansmod.recoded.gamemode.BattleMasterBlockEntity> =
        register("battle_master", ::BattleMasterBlockEntity, FlansBlocks.BATTLE_MASTER)
    val TEAM_FLAG: net.minecraft.world.level.block.entity.BlockEntityType<com.flansmod.recoded.gamemode.TeamFlagBlockEntity> =
        register("team_flag", ::TeamFlagBlockEntity, FlansBlocks.TEAM_FLAG)

    fun init() {
        // Water from pipes/tanks of other mods (Fabric Transfer API).
        net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.registerForBlockEntity({ be, _ -> be.water }, FUEL_SYNTHESIZER)
    }
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

    val FUEL_SYNTHESIZER: MenuType<com.flansmod.recoded.fuel.FuelSynthesizerMenu> = Registry.register(
        BuiltInRegistries.MENU, FlansMod.id("fuel_synthesizer"), MenuType({ id, inventory -> com.flansmod.recoded.fuel.FuelSynthesizerMenu(id, inventory) }, FeatureFlags.VANILLA_SET),
    )

    val BATTLE_MASTER: ExtendedMenuType<com.flansmod.recoded.gamemode.BattleMasterMenu, com.flansmod.recoded.gamemode.BattleMasterView> = Registry.register(
        BuiltInRegistries.MENU, FlansMod.id("battle_master"),
        ExtendedMenuType({ id, inventory, view -> com.flansmod.recoded.gamemode.BattleMasterMenu(id, inventory, view) }, com.flansmod.recoded.gamemode.BattleMasterView.STREAM_CODEC),
    )

    val TEAM_FLAG: ExtendedMenuType<com.flansmod.recoded.gamemode.TeamFlagMenu, com.flansmod.recoded.gamemode.TeamFlagView> = Registry.register(
        BuiltInRegistries.MENU, FlansMod.id("team_flag"),
        ExtendedMenuType({ id, inventory, view -> com.flansmod.recoded.gamemode.TeamFlagMenu(id, inventory, view) }, com.flansmod.recoded.gamemode.TeamFlagView.STREAM_CODEC),
    )

    val PETROL_STATION: MenuType<com.flansmod.recoded.fuel.PetrolStationMenu> = Registry.register(
        BuiltInRegistries.MENU, FlansMod.id("petrol_station"), MenuType({ id, inventory -> com.flansmod.recoded.fuel.PetrolStationMenu(id, inventory) }, FeatureFlags.VANILLA_SET),
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

    private val MINE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, FlansMod.id("mine"))

    val MINE: EntityType<com.flansmod.recoded.entity.MineEntity> = Registry.register(
        BuiltInRegistries.ENTITY_TYPE, MINE_KEY,
        EntityType.Builder.of(::MineEntity, MobCategory.MISC).sized(0.5f, 0.2f).clientTrackingRange(6).updateInterval(20).build(MINE_KEY),
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
