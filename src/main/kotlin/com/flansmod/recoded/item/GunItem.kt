package com.flansmod.recoded.item

import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.withAttachments
import com.flansmod.recoded.gun.withAmmo
import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import com.geckolib.animatable.GeoItem
import com.geckolib.animatable.client.GeoRenderProvider
import com.geckolib.animatable.manager.AnimatableManager
import com.geckolib.animation.AnimationController
import com.geckolib.animation.RawAnimation
import com.geckolib.animation.`object`.PlayState
import com.geckolib.renderer.GeoItemRenderer
import com.geckolib.util.GeckoLibUtil
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.BlockPos
import java.util.function.Consumer

/**
 * The single item behind every gun. Which gun a stack is comes from the [FlansComponents.GUN] component,
 * so content packs can add guns without registering items (and guns survive `/reload`).
 */
class GunItem(properties: Properties) : Item(properties), GeoItem {
    private val cache = GeckoLibUtil.createInstanceCache(this)

    override fun getName(stack: ItemStack): Component =
        stack.definition?.let { Component.translatableWithFallback("gun.${stack.gunId!!.toLanguageKey()}", it.name) }
            ?: super.getName(stack)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        val def = stack.definition ?: return add.accept(Component.translatable("item.flansmod.gun.unknown").withStyle(ChatFormatting.RED))
        fun line(key: String, vararg args: Any) = add.accept(Component.translatable("item.flansmod.gun.$key", *args).withStyle(ChatFormatting.GRAY))
        val mag = stack.loadedMagazine
        if (mag == null) line("no_magazine")
        else {
            line("magazine", mag.definition?.name ?: mag.magazine.toString(), mag.rounds, mag.capacity)
            mag.ammo?.let { line("ammo_type", AmmoItem.displayName(it)) }
        }
        val shot = def.withAmmo(mag?.ammoDefinition)
        line("damage", if (shot.pellets > 1) "${shot.damage}×${shot.pellets}" else shot.damage)
        line("rpm", def.rpm, Component.translatable("item.flansmod.gun.mode.${stack.fireMode.name.lowercase()}"))
        stack.attachments.values.mapNotNull { Attachments[it] }.forEach {
            add.accept(Component.literal(" + ").append(Component.translatableWithFallback("attachment.flansmod.${'$'}{it.slot}", it.slot)).append(": ${'$'}{it.name}").withStyle(ChatFormatting.DARK_AQUA))
        }
    }

    // Right click is aiming (handled client side), so the vanilla use action does nothing.
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult = InteractionResult.FAIL

    // Left click fires instead of mining.
    override fun canDestroyBlock(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, user: LivingEntity) = false

    override fun isBarVisible(stack: ItemStack) = stack.loadedMagazine != null
    override fun getBarWidth(stack: ItemStack) = stack.loadedMagazine?.let { 13 * it.rounds / it.capacity.coerceAtLeast(1) } ?: 0
    override fun getBarColor(stack: ItemStack) = 0xE0B040

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
        controllers.add(
            AnimationController<GunItem>(CONTROLLER, 0) { PlayState.STOP }
                .triggerableAnim(ANIM_SHOOT, RawAnimation.begin().thenPlay(ANIM_SHOOT))
                .triggerableAnim(ANIM_RELOAD, RawAnimation.begin().thenPlay(ANIM_RELOAD))
        )
    }

    override fun getAnimatableInstanceCache() = cache

    override fun createGeoRenderer(consumer: Consumer<GeoRenderProvider>) {
        consumer.accept(object : GeoRenderProvider {
            private val renderer by lazy { rendererFactory!!() }
            override fun getGeoItemRenderer(): GeoItemRenderer<*> = renderer
        })
    }

    companion object {
        const val CONTROLLER = "main"
        const val ANIM_SHOOT = "shoot"
        const val ANIM_RELOAD = "reload"

        /** Set by the client entrypoint; the renderer classes live in the client source set. */
        var rendererFactory: (() -> GeoItemRenderer<*>)? = null

        /** A gun stack; [loaded] inserts a full magazine of the first accepted type (creative tab, commands). */
        fun stackFor(id: Identifier?, loaded: Boolean = true): ItemStack = ItemStack(FlansItems.GUN).apply {
            if (id == null) return@apply
            set(FlansComponents.GUN, id)
            if (loaded) acceptedMagazines(id).firstOrNull()?.let { MagazineContents.full(it) }?.let { set(FlansComponents.MAGAZINE, it) }
        }

        /** Magazine ids usable in gun [id]: listed by the gun, or listing the gun themselves. */
        fun acceptedMagazines(id: Identifier): List<Identifier> {
            val gun = Guns[id] ?: return emptyList()
            return (gun.magazines + Magazines.all.filter { (magId, mag) -> mag.fits(magId, id, gun) }.keys).distinct().filter { Magazines[it] != null }
        }
    }
}

val ItemStack.gunId: Identifier? get() = get(FlansComponents.GUN)

/** Installed attachments by slot. */
var ItemStack.attachments: Map<String, Identifier>
    get() = getOrDefault(FlansComponents.ATTACHMENTS, emptyMap())
    set(value) { if (value.isEmpty()) remove(FlansComponents.ATTACHMENTS) else set(FlansComponents.ATTACHMENTS, value) }

/** The gun definition without attachments. */
val ItemStack.baseDefinition: GunDefinition? get() = if (item is GunItem) Guns[gunId] else null

/** The effective gun stats: base definition with all installed attachments applied. */
val ItemStack.definition: GunDefinition?
    get() {
        val base = baseDefinition ?: return null
        val installed = attachments.values.mapNotNull { Attachments[it] }
        return if (installed.isEmpty()) base else base.withAttachments(installed)
    }
/** The magazine inserted in this gun (also used for magazine items' own contents). */
var ItemStack.loadedMagazine: MagazineContents?
    get() = get(FlansComponents.MAGAZINE)
    set(value) { if (value == null) remove(FlansComponents.MAGAZINE) else set(FlansComponents.MAGAZINE, value) }

/** Rounds in the inserted magazine. Setting it requires a magazine to be inserted. */
var ItemStack.ammo: Int
    get() = loadedMagazine?.rounds ?: 0
    set(value) { loadedMagazine = loadedMagazine?.withRounds(value) }

/** Selected fire mode, falling back to the gun's default if the stored one is not available. */
var ItemStack.fireMode: FireMode
    get() {
        val gun = definition ?: return FireMode.SAFE
        return get(FlansComponents.FIRE_MODE)?.takeIf { it in gun.availableModes } ?: gun.fireMode
    }
    set(value) { set(FlansComponents.FIRE_MODE, value) }

/** Effective stats for the next shot: attachments plus the loaded ammo type. */
val ItemStack.shotDefinition: GunDefinition? get() = definition?.withAmmo(loadedMagazine?.ammoDefinition)
