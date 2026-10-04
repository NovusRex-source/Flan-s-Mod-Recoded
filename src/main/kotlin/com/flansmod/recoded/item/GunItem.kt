package com.flansmod.recoded.item

import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
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
        line("ammo", stack.ammo, def.magazine)
        line("damage", if (def.pellets > 1) "${def.damage}×${def.pellets}" else def.damage)
        line("rpm", def.rpm, Component.translatable("item.flansmod.gun.mode.${def.fireMode.name.lowercase()}"))
        def.ammo?.let { line("ammo_item", Component.translatable(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(it.item).descriptionId)) }
    }

    // Right click is aiming (handled client side), so the vanilla use action does nothing.
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult = InteractionResult.FAIL

    // Left click fires instead of mining.
    override fun canDestroyBlock(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, user: LivingEntity) = false

    override fun isBarVisible(stack: ItemStack) = stack.definition != null
    override fun getBarWidth(stack: ItemStack) = stack.definition?.let { 13 * stack.ammo / it.magazine.coerceAtLeast(1) } ?: 0
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

        fun stackFor(id: Identifier?): ItemStack = ItemStack(FlansItems.GUN).apply {
            if (id == null) return@apply
            set(FlansComponents.GUN, id)
            set(FlansComponents.AMMO, Guns[id]?.magazine ?: 0)
        }
    }
}

val ItemStack.gunId: Identifier? get() = get(FlansComponents.GUN)
val ItemStack.definition: GunDefinition? get() = if (item is GunItem) Guns[gunId] else null
var ItemStack.ammo: Int
    get() = getOrDefault(FlansComponents.AMMO, 0)
    set(value) { set(FlansComponents.AMMO, value) }
