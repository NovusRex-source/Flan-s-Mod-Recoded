package com.flansmod.recoded.item

import com.flansmod.recoded.gun.AmmoDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.caliberName
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.util.function.Consumer

/**
 * Shared tooltip lines so every Flan's item reads the same way: a category line first ("Magazine · 5.56 NATO"),
 * then details in grey and effects in colour.
 */
object Tooltips {
    fun category(add: Consumer<Component>, key: String, vararg args: Any) =
        add.accept(Component.translatable("tooltip.flansmod.$key", *args).withStyle(ChatFormatting.BLUE))

    fun detail(add: Consumer<Component>, key: String, vararg args: Any) =
        add.accept(Component.translatable("tooltip.flansmod.$key", *args).withStyle(ChatFormatting.GRAY))

    fun hint(add: Consumer<Component>, key: String) =
        add.accept(Component.translatable(key).withStyle(ChatFormatting.DARK_GRAY))

    /** Weapon class name, e.g. "Assault Rifle". */
    fun gunCategory(category: String?): Component =
        Component.translatableWithFallback("gun_category.flansmod.${category ?: "gun"}", category ?: "Gun")

    /** "Fits: M4A1, M16A4, +2" for the given guns (vehicle weapons are named too). */
    fun fits(add: Consumer<Component>, guns: List<Identifier>) {
        if (guns.isEmpty()) return
        val names = guns.mapNotNull { Guns[it]?.name }.sorted()
        val shown = names.take(4).joinToString(", ") + if (names.size > 4) ", +${names.size - 4}" else ""
        detail(add, "fits", shown)
    }

    /** Guns using magazines of [caliber] (detachable or built in). */
    fun gunsForCaliber(caliber: String): List<Identifier> =
        Guns.all.keys.filter { gun -> GunItem.acceptedMagazines(gun).any { Magazines[it]?.caliber == caliber } }

    fun gunsForMagazine(magazine: Identifier): List<Identifier> = Guns.all.keys.filter { magazine in GunItem.acceptedMagazines(it) }

    /** Coloured effect lines of an ammo type. */
    fun ammoEffects(add: Consumer<Component>, ammo: AmmoDefinition) {
        fun effect(key: String, color: ChatFormatting, vararg args: Any) =
            add.accept(Component.translatable("tooltip.flansmod.ammo.$key", *args).withStyle(color))
        if (ammo.damageMultiplier != 1f) effect("damage", if (ammo.damageMultiplier > 1f) ChatFormatting.GREEN else ChatFormatting.RED, "%+d%%".format(((ammo.damageMultiplier - 1) * 100).toInt()))
        ammo.pellets?.takeIf { it > 1 }?.let { effect("pellets", ChatFormatting.GRAY, it) }
        if (ammo.pellets == 1) effect("slug", ChatFormatting.GRAY)
        if (ammo.armorPiercing) effect("armor_piercing", ChatFormatting.GOLD)
        if (ammo.fireSeconds > 0f) effect("incendiary", ChatFormatting.RED)
        if (ammo.tracer != null) effect("tracer", ChatFormatting.YELLOW)
        if (ammo.projectile != null) effect("explosive", ChatFormatting.RED)
    }

    fun caliber(caliber: String): Component = caliberName(caliber)

    /** "Faction: Axis (Germany)" in the faction's colour, for definitions that belong to one. */
    fun faction(add: Consumer<Component>, faction: Identifier?) {
        val def = com.flansmod.recoded.gun.Factions[faction] ?: return
        add.accept(Component.translatable("tooltip.flansmod.faction", Component.literal(def.name).withColor(def.rgb)).withStyle(ChatFormatting.GRAY))
    }
}
