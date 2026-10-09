package com.flansmod.recoded.gear

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.Gear
import com.flansmod.recoded.gun.IdentifierSerializer
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.Tooltips
import com.flansmod.recoded.registry.FlansComponents
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.Consumable
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect
import net.minecraft.world.level.Level
import java.util.function.Consumer

/** What a piece of gear is. */
@Serializable
enum class GearType {
    /** Extra inventory (9/18/27 slots), carried anywhere in the inventory and shown on the wearer's back. */
    @SerialName("backpack") BACKPACK,
    /** Like a backpack, but only for ammunition, magazines, grenades and medical supplies. */
    @SerialName("pouch") POUCH,
    /** Used over [GearDefinition.consumeSeconds] (vanilla consumable), or applied to someone else with right click. */
    @SerialName("medical") MEDICAL,
    /** Zooms like a spyglass, with an optional overlay. */
    @SerialName("binoculars") BINOCULARS,
    /** Armour plate for the plate slots of modern armour: adds [GearDefinition.armor]/toughness, wears out with hits. */
    @SerialName("plate") PLATE,
    /**
     * Opens with the jump key while falling (carried in the backpack slot or anywhere in the inventory): slows the
     * fall to [GearDefinition.fallSpeed] and prevents fall damage until landing (see [Parachutes]).
     */
    @SerialName("parachute") PARACHUTE,
    /** Field map: right click opens a terrain map [GearDefinition.range] blocks around you (see the `utility` package). */
    @SerialName("map") MAP,
    /** Flashlight: right click switches it on; held, it lights where it points, up to [GearDefinition.range] blocks. */
    @SerialName("flashlight") FLASHLIGHT,
    /** Compass: held, a heading strip on the HUD that also points to the map's waypoint. */
    @SerialName("compass") COMPASS,
}

@Serializable
data class GearEffect(@Serializable(IdentifierSerializer::class) val effect: Identifier, val duration: Int = 100, val amplifier: Int = 0)

/**
 * Clothing accessories from `data/<ns>/flansmod/gear/<name>.json`: backpacks and pouches (extra slots, stored in
 * the item's vanilla `container` component), medical supplies (vanilla `consumable` with status effects, e.g.
 * instant health and regeneration) and binoculars.
 */
@Serializable
data class GearDefinition(
    val name: String,
    val type: GearType,
    /** Backpacks/pouches: 9, 18 or 27. */
    val slots: Int = 9,
    @SerialName("consume_seconds") val consumeSeconds: Float = 2f,
    val effects: List<GearEffect> = emptyList(),
    val zoom: Float = 4f,
    @Serializable(IdentifierSerializer::class) val overlay: Identifier? = null,
    @SerialName("max_stack") val maxStack: Int = 1,
    /** Plates: protection, durability in hits, movement speed change (like clothing). */
    val armor: Double = 0.0,
    val toughness: Double = 0.0,
    val durability: Int = 0,
    @SerialName("speed_modifier") val speedModifier: Double = 0.0,
    /** Field maps: radius shown (blocks); flashlights: beam reach (blocks). */
    val range: Double = 64.0,
    /** Flashlights: brightness of the lit spot (1-15). */
    @SerialName("light_level") val lightLevel: Int = 15,
    /** Parachutes: sink rate under the open canopy, blocks per tick. */
    @SerialName("fall_speed") val fallSpeed: Double = 0.2,
    /** Parachutes: item model of the open canopy, drawn above the wearer. */
    @Serializable(IdentifierSerializer::class) val canopy: Identifier? = null,
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
    @Serializable(IdentifierSerializer::class) val faction: Identifier? = null,
) {
    val isContainer get() = type == GearType.BACKPACK || type == GearType.POUCH
    val rows get() = (slots / 9).coerceIn(1, 3)

    fun effectInstances() = effects.mapNotNull { e ->
        BuiltInRegistries.MOB_EFFECT.get(e.effect).map { MobEffectInstance(it, e.duration, e.amplifier) }.orElse(null)
    }
}

/** The single item behind all gear; which one is stored in [FlansComponents.GEAR]. */
class GearItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component =
        stack.gear?.let { Component.translatableWithFallback("gear.${stack.gearId!!.toLanguageKey()}", it.name) } ?: super.getName(stack)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        val def = stack.gear ?: return
        Tooltips.category(add, "gear.${def.type.name.lowercase()}")
        Tooltips.faction(add, def.faction)
        fun line(key: String, vararg args: Any) = add.accept(Component.translatable("tooltip.flansmod.gear.$key", *args).withStyle(ChatFormatting.GRAY))
        when (def.type) {
            GearType.BACKPACK, GearType.POUCH -> {
                val used = stack.get(DataComponents.CONTAINER)?.nonEmptyItemCopyStream()?.count() ?: 0
                line("slots", used, def.slots)
                Tooltips.hint(add, if (def.type == GearType.BACKPACK) "tooltip.flansmod.gear.backpack_hint" else "tooltip.flansmod.gear.pouch_hint")
            }
            GearType.MEDICAL -> {
                def.effectInstances().forEach { add.accept(Component.literal(" ").append(it.effect.value().displayName)
                    .append(if (it.amplifier > 0) " ${it.amplifier + 1}" else "").withStyle(ChatFormatting.BLUE)) }
                Tooltips.hint(add, "tooltip.flansmod.gear.medical_hint")
            }
            GearType.BINOCULARS -> line("zoom", "%.0f".format(def.zoom))
            GearType.PLATE -> {
                line("plate_stats", "%.0f".format(def.armor), "%.1f".format(def.toughness))
                Tooltips.hint(add, "tooltip.flansmod.gear.plate_hint")
            }
            GearType.PARACHUTE -> Tooltips.hint(add, "tooltip.flansmod.gear.parachute_hint")
            GearType.MAP -> {
                line("map_radius", def.range.toInt())
                Tooltips.hint(add, "tooltip.flansmod.gear.map_hint")
            }
            GearType.FLASHLIGHT -> {
                line(if (stack.getOrDefault(com.flansmod.recoded.utility.Utilities.ACTIVE, false)) "flashlight_on" else "flashlight_off")
                Tooltips.hint(add, "tooltip.flansmod.gear.flashlight_hint")
            }
            GearType.COMPASS -> Tooltips.hint(add, "tooltip.flansmod.gear.compass_hint")
        }
    }

    override fun inventoryTick(stack: ItemStack, level: ServerLevel, owner: Entity, slot: EquipmentSlot?) {
        val type = stack.gear?.type
        if (type == GearType.MEDICAL && !stack.has(DataComponents.CONSUMABLE) || type == GearType.PLATE && !stack.has(DataComponents.MAX_DAMAGE)) applyDefinition(stack)
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        val def = stack.gear ?: return InteractionResult.PASS
        return when (def.type) {
            GearType.BACKPACK, GearType.POUCH -> {
                if (player is ServerPlayer) open(player, stack)
                InteractionResult.SUCCESS
            }
            GearType.BINOCULARS -> {
                player.startUsingItem(hand)
                player.playSound(SoundEvents.SPYGLASS_USE, 1f, 1f)
                InteractionResult.CONSUME
            }
            GearType.MEDICAL -> {
                if (!stack.has(DataComponents.CONSUMABLE)) applyDefinition(stack)
                super.use(level, player, hand) // vanilla consumable
            }
            GearType.PLATE -> InteractionResult.PASS // inserted through the plate slots of worn armour
            // Also opens with a right click while falling (the jump key works without holding it).
            GearType.PARACHUTE -> if (player is ServerPlayer && Parachutes.open(player)) InteractionResult.SUCCESS else InteractionResult.PASS
            GearType.MAP -> {
                if (level.isClientSide()) com.flansmod.recoded.utility.Utilities.openMap(stack)
                InteractionResult.SUCCESS
            }
            GearType.FLASHLIGHT -> {
                if (!level.isClientSide()) {
                    val on = !stack.getOrDefault(com.flansmod.recoded.utility.Utilities.ACTIVE, false)
                    stack.set(com.flansmod.recoded.utility.Utilities.ACTIVE, on)
                    level.playSound(null, player.blockPosition(), SoundEvents.STONE_BUTTON_CLICK_ON, player.soundSource, 0.5f, if (on) 1.6f else 1.2f)
                }
                InteractionResult.SUCCESS
            }
            GearType.COMPASS -> InteractionResult.PASS // shown on the HUD while held
        }
    }

    override fun getUseDuration(stack: ItemStack, entity: LivingEntity) = if (stack.gear?.type == GearType.BINOCULARS) 72000 else super.getUseDuration(stack, entity)
    override fun getUseAnimation(stack: ItemStack): ItemUseAnimation = if (stack.gear?.type == GearType.BINOCULARS) ItemUseAnimation.SPYGLASS else super.getUseAnimation(stack)

    /** Medics: right click someone else to treat them at once. */
    override fun interactLivingEntity(stack: ItemStack, player: Player, target: LivingEntity, hand: InteractionHand): InteractionResult {
        val def = stack.gear?.takeIf { it.type == GearType.MEDICAL } ?: return InteractionResult.PASS
        if (player.level() is ServerLevel) {
            def.effectInstances().forEach { target.addEffect(it, player) }
            target.level().playSound(null, target.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), player.soundSource, 0.8f, 1.3f)
            stack.consume(1, player)
            player.cooldowns.addCooldown(stack, (def.consumeSeconds * 20).toInt())
        }
        return InteractionResult.SUCCESS
    }

    companion object {
        fun stackFor(id: Identifier): ItemStack = ItemStack(GearItems.GEAR).apply {
            set(FlansComponents.GEAR, id)
            applyDefinition(this)
        }

        /** Stack size and (medical) the vanilla consumable component from the definition. */
        fun applyDefinition(stack: ItemStack) {
            val def = stack.gear ?: return
            if (def.maxStack != 1) stack.set(DataComponents.MAX_STACK_SIZE, def.maxStack.coerceIn(1, 99))
            if (def.type == GearType.PLATE && def.durability > 0 && !stack.has(DataComponents.MAX_DAMAGE)) {
                // Vanilla durability: both components (a stack only counts as damageable with `damage` present).
                stack.set(DataComponents.MAX_DAMAGE, def.durability)
                stack.set(DataComponents.DAMAGE, 0)
            }
            if (def.type == GearType.MEDICAL) stack.set(DataComponents.CONSUMABLE, Consumable(def.consumeSeconds, ItemUseAnimation.BRUSH,
                SoundEvents.ARMOR_EQUIP_LEATHER, false, listOf(ApplyStatusEffectsConsumeEffect(def.effectInstances()))))
        }

        /** Opens a backpack/pouch in [player]'s inventory as a vanilla chest screen. */
        fun open(player: ServerPlayer, stack: ItemStack) {
            val def = stack.gear?.takeIf { it.isContainer } ?: return
            player.openMenu(SimpleMenuProvider({ id, inventory, _ -> BackpackMenu(id, inventory, stack, def) }, stack.hoverName))
            player.level().playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), player.soundSource, 0.6f, 1.1f)
        }

        /** What may go into gear of [def]: never another backpack/pouch or a container item; pouches only take supplies. */
        fun fits(def: GearDefinition, stack: ItemStack): Boolean {
            if (!stack.item.canFitInsideContainerItems() || stack.gear?.isContainer == true) return false
            return def.type != GearType.POUCH || stack.item is AmmoItem || stack.item is MagazineItem || stack.item is GrenadeItem ||
                stack.gear?.type == GearType.MEDICAL
        }

        /** The first backpack (then pouch) in the inventory: the one worn on the back and opened with the backpack key. */
        fun firstContainer(inventory: Inventory): ItemStack? =
            (0 until inventory.containerSize).map(inventory::getItem).filter { it.gear?.isContainer == true }.minByOrNull { if (it.gear!!.type == GearType.BACKPACK) 0 else 1 }
    }
}

/**
 * A vanilla generic chest menu over a backpack's contents (so the client uses the vanilla chest screen). Its slots
 * only take what fits ([GearItem.fits]); the backpack itself cannot be moved while open; changes are written back
 * into the item's `container` component.
 */
class BackpackMenu(id: Int, inventory: Inventory, private val backpack: ItemStack, def: GearDefinition) :
    ChestMenu(TYPES[def.rows - 1], id, inventory, BackpackContainer(backpack, def.rows * 9), def.rows) {
    init {
        for (i in 0 until def.rows * 9) {
            val old = slots[i]
            slots[i] = object : Slot(old.container, old.containerSlot, old.x, old.y) {
                override fun mayPlace(stack: ItemStack) = GearItem.fits(def, stack)
            }.also { it.index = i }
        }
        for (i in def.rows * 9 until slots.size) {
            val old = slots[i]
            if (old.item === backpack) slots[i] = object : Slot(old.container, old.containerSlot, old.x, old.y) {
                override fun mayPickup(player: Player) = false
                override fun mayPlace(stack: ItemStack) = false
            }.also { it.index = i }
        }
    }

    override fun stillValid(player: Player) = GearSlots.back(player) === backpack ||
        (0 until player.inventory.containerSize).any { player.inventory.getItem(it) === backpack }

    override fun removed(player: Player) {
        super.removed(player)
        if (GearSlots.back(player) === backpack) GearSlots.setBack(player, backpack) // save + sync the changed contents
    }

    private class BackpackContainer(private val backpack: ItemStack, size: Int) : SimpleContainer(size) {
        init {
            val items = NonNullList.withSize(size, ItemStack.EMPTY)
            backpack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items)
            items.forEachIndexed { i, s -> super.setItem(i, s) }
        }

        override fun setChanged() {
            super.setChanged()
            backpack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items))
        }
    }

    companion object {
        private val TYPES: List<MenuType<ChestMenu>> = listOf(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3)
    }
}

/** Client → server: open the first backpack in the inventory (backpack key). */
object OpenBackpackPayload : CustomPacketPayload {
    val TYPE = CustomPacketPayload.Type<OpenBackpackPayload>(FlansMod.id("open_backpack"))
    val CODEC: StreamCodec<net.minecraft.network.FriendlyByteBuf, OpenBackpackPayload> = StreamCodec.unit(this)
    override fun type() = TYPE
}

object GearItems {
    val GEAR: GearItem = net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, FlansMod.id("gear"),
        GearItem(Item.Properties().setId(ResourceKey.create(Registries.ITEM, FlansMod.id("gear"))).stacksTo(1)))

    fun init() {
        PayloadTypeRegistry.serverboundPlay().register(OpenBackpackPayload.TYPE, OpenBackpackPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(OpenBackpackPayload.TYPE) { _, ctx ->
            val player = ctx.player()
            (GearSlots.back(player).takeIf { !it.isEmpty } ?: GearItem.firstContainer(player.inventory))?.let { GearItem.open(player, it) }
        }
        GearSlots.init()
        Parachutes.init()
    }
}

val ItemStack.gearId: Identifier? get() = get(FlansComponents.GEAR)
val ItemStack.gear: GearDefinition? get() = if (item is GearItem) Gear[gearId] else null
