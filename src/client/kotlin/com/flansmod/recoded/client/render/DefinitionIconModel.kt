package com.flansmod.recoded.client.render

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.Parts
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.AttachmentItem
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.PartItem
import com.flansmod.recoded.item.ammoTypeId
import com.flansmod.recoded.item.attachmentId
import com.flansmod.recoded.item.grenadeId
import com.flansmod.recoded.item.loadedMagazine
import com.flansmod.recoded.item.partId
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.item.ItemModel
import net.minecraft.client.renderer.item.ItemModelResolver
import net.minecraft.client.renderer.item.ItemModels
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.resources.model.ResolvableModel
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.ItemOwner
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4fc

/**
 * Item model type `flansmod:definition_icon` for the generic content items (parts, ammo, magazines, attachments,
 * grenades): renders the item model named by the stack's definition `icon`, or [fallback] if there is none.
 * So stacks never need an explicit `minecraft:item_model` component (recipe results, JEI displays, /give).
 */
class DefinitionIconModel(private val fallback: ItemModel) : ItemModel {
    override fun update(state: ItemStackRenderState, stack: ItemStack, resolver: ItemModelResolver, context: ItemDisplayContext, level: ClientLevel?, owner: ItemOwner?, seed: Int) {
        val icon = iconOf(stack)
        if (icon == null) return fallback.update(state, stack, resolver, context, level, owner, seed)
        // The resolver uses an explicit item_model component first, so this delegates to the icon's model.
        val withIcon = stack.copy().apply { set(DataComponents.ITEM_MODEL, icon) }
        resolver.appendItemLayers(state, withIcon, context, level, owner, seed)
    }

    private fun iconOf(stack: ItemStack): Identifier? = when (stack.item) {
        is PartItem -> Parts[stack.partId]?.icon
        is AmmoItem -> AmmoTypes[stack.ammoTypeId]?.icon
        is AttachmentItem -> Attachments[stack.attachmentId]?.icon
        is GrenadeItem -> Grenades[stack.grenadeId]?.icon
        is MagazineItem -> Magazines[stack.loadedMagazine?.magazine]?.icon
        is com.flansmod.recoded.item.ClothingItem -> com.flansmod.recoded.gun.Clothing[stack.get(com.flansmod.recoded.registry.FlansComponents.CLOTHING)]?.icon
        is com.flansmod.recoded.item.VehicleItem -> com.flansmod.recoded.gun.Vehicles[stack.get(com.flansmod.recoded.registry.FlansComponents.VEHICLE)]?.icon
        is com.flansmod.recoded.item.VehicleUpgradeItem -> com.flansmod.recoded.gun.VehicleUpgrades[stack.get(com.flansmod.recoded.registry.FlansComponents.VEHICLE_UPGRADE)]?.icon
        else -> null
    }?.takeIf { it != BuiltInRegistries.ITEM.getKey(stack.item) } // never point back at this model (endless loop)

    class Unbaked(val fallback: ItemModel.Unbaked) : ItemModel.Unbaked {
        override fun type(): MapCodec<out ItemModel.Unbaked> = CODEC
        override fun bake(context: ItemModel.BakingContext, transformation: Matrix4fc): ItemModel = DefinitionIconModel(fallback.bake(context, transformation))
        override fun resolveDependencies(resolver: ResolvableModel.Resolver) = fallback.resolveDependencies(resolver)
    }

    companion object {
        val CODEC: MapCodec<Unbaked> = RecordCodecBuilder.mapCodec { i ->
            i.group(ItemModels.CODEC.fieldOf("fallback").forGetter(Unbaked::fallback)).apply(i, ::Unbaked)
        }

        fun register() {
            ItemModels.ID_MAPPER.put(FlansMod.id("definition_icon"), CODEC)
        }
    }
}
