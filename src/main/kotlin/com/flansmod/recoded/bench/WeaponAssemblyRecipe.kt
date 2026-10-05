package com.flansmod.recoded.bench

import com.flansmod.recoded.registry.FlansRecipes
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.PlacementInfo
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeBookCategories
import net.minecraft.world.level.Level
import java.util.Optional

/**
 * A shaped Weapons Bench recipe on a grid of up to [WIDTH]x[HEIGHT] (vanilla's shaped pattern codec stops at 3x3).
 * JSON: `{"type": "flansmod:weapon_assembly", "pattern": [...], "key": {"A": <ingredient>}, "result": {...}}`.
 * Ingredients are vanilla ingredients, so Fabric's `fabric:components` works for parts and ammo.
 */
class WeaponAssemblyRecipe(val pattern: List<String>, val key: Map<Char, Ingredient>, val result: ItemStackTemplate) : Recipe<CraftingInput> {
    val width = pattern.maxOf { it.length }
    val height = pattern.size
    private val cells: List<Optional<Ingredient>> = pattern.flatMap { row -> row.padEnd(width).map { c -> Optional.ofNullable(key[c]) } }

    /** [input] must be trimmed to its non-empty bounds (see [CraftingInput.ofPositioned]). */
    override fun matches(input: CraftingInput, level: Level): Boolean {
        if (input.width() != width || input.height() != height || input.ingredientCount() != cells.count { it.isPresent }) return false
        return matches(input, mirrored = false) || matches(input, mirrored = true)
    }

    private fun matches(input: CraftingInput, mirrored: Boolean): Boolean {
        for (y in 0 until height) for (x in 0 until width) {
            val cell = cells[y * width + if (mirrored) width - 1 - x else x]
            val stack = input.getItem(x, y)
            if (!(cell.map { it.test(stack) }.orElse(stack.isEmpty))) return false
        }
        return true
    }

    override fun assemble(input: CraftingInput): ItemStack = result.create()
    override fun showNotification() = false
    override fun group() = ""
    override fun getSerializer() = FlansRecipes.WEAPON_ASSEMBLY_SERIALIZER
    override fun getType() = FlansRecipes.WEAPON_ASSEMBLY
    override fun placementInfo(): PlacementInfo = PlacementInfo.NOT_PLACEABLE
    override fun recipeBookCategory() = RecipeBookCategories.CRAFTING_EQUIPMENT

    companion object {
        const val WIDTH = 6
        const val HEIGHT = 4

        private val KEY_CODEC: Codec<Char> = Codec.STRING.comapFlatMap(
            { s -> if (s.length == 1 && s != " ") DataResult.success(s[0]) else DataResult.error { "Key must be a single non-space character: '$s'" } },
            Char::toString,
        )

        val MAP_CODEC = RecordCodecBuilder.mapCodec { i ->
            i.group(
                Codec.STRING.listOf().fieldOf("pattern").forGetter(WeaponAssemblyRecipe::pattern),
                Codec.unboundedMap(KEY_CODEC, Ingredient.CODEC).fieldOf("key").forGetter(WeaponAssemblyRecipe::key),
                ItemStackTemplate.CODEC.fieldOf("result").forGetter(WeaponAssemblyRecipe::result),
            ).apply(i, ::WeaponAssemblyRecipe)
        }.validate { r ->
            val unknown = r.pattern.joinToString("").filter { it != ' ' && it !in r.key }.toSet()
            when {
                r.pattern.isEmpty() || r.pattern.size > HEIGHT -> DataResult.error { "Pattern needs 1..$HEIGHT rows" }
                r.pattern.any { it.length > WIDTH } -> DataResult.error { "Pattern rows can be at most $WIDTH wide" }
                unknown.isNotEmpty() -> DataResult.error { "Pattern uses undefined keys $unknown" }
                else -> DataResult.success(r)
            }
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WeaponAssemblyRecipe> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), WeaponAssemblyRecipe::pattern,
            ByteBufCodecs.map(::HashMap, ByteBufCodecs.STRING_UTF8.map({ it[0] }, Char::toString), Ingredient.CONTENTS_STREAM_CODEC), { HashMap(it.key) },
            ItemStackTemplate.STREAM_CODEC, WeaponAssemblyRecipe::result,
        ) { pattern, key, result -> WeaponAssemblyRecipe(pattern, key, result) }
    }
}
