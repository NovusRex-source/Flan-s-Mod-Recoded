package com.flansmod.recoded.gamemode

import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.registry.FlansMenus
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/** Which team's shop the editor shows, and the teams to switch between. */
@Serializable
data class ShopEditorView(val pos: Long, val teams: List<String>, val team: Int, val rgb: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ShopEditorView> = ByteBufCodecs.stringUtf8(1 shl 16).map(
            { Content.JSON.decodeFromString(serializer(), it) }, { Content.JSON.encodeToString(serializer(), it) },
        ).cast()
    }
}

/**
 * Shop editor of the Battle Master (managers only): one team's shop, laid out exactly like the flag post shows it
 * (pages of 27). Items are placed as copies - nothing leaves your inventory:
 * - click a shop slot with an item on the cursor: put it there (right click: a single one), priced like the generated
 *   shop would price it; shift-click an item in your inventory: add it to the first free slot
 * - click a shop item: select it; the price buttons ([PRICE] + 0..5: -100, -10, -1, +1, +10, +100) change its price
 * - [REMOVE] the selected item, [RESET] back to the generated shop, [PREV]/[NEXT] page, [TEAM] + n edit another team.
 * Every change is saved on the Battle Master at once ([BattleMasterBlockEntity.shops]).
 * Data: page, selected slot (-1 = none), the selected price (two 16-bit halves), number of pages.
 */
class ShopEditorMenu(
    id: Int, inventory: Inventory, val view: ShopEditorView, private val master: BattleMasterBlockEntity? = null,
    private val shop: SimpleContainer = SimpleContainer(TeamFlagBlockEntity.PAGE), val data: ContainerData = SimpleContainerData(5),
) : AbstractContainerMenu(FlansMenus.SHOP_EDITOR, id) {
    private val team = view.teams.getOrNull(view.team) ?: ""
    private val entries: MutableList<ShopEntry> = master?.let { Battles.shop(it, team).toMutableList() } ?: mutableListOf()

    init {
        for (i in 0 until TeamFlagBlockEntity.PAGE) addSlot(object : Slot(shop, i, 8 + i % 9 * 18, 18 + i / 9 * 18) {
            override fun mayPickup(player: Player) = false
            override fun mayPlace(stack: ItemStack) = false
        })
        addStandardInventorySlots(inventory, 8, 85)
        addDataSlots(data)
        data.set(1, -1)
        refresh()
    }

    val page get() = data.get(0)
    val selected get() = data.get(1)
    val price get() = (data.get(2) and 0xFFFF) or (data.get(3) shl 16)
    val pages get() = data.get(4).coerceAtLeast(1)

    private fun index(slot: Int) = page * TeamFlagBlockEntity.PAGE + slot

    private fun refresh() {
        if (master == null) return
        for (i in 0 until TeamFlagBlockEntity.PAGE) shop.setItem(i, entries.getOrNull(index(i))?.takeIf { !it.stack.isEmpty }?.display() ?: ItemStack.EMPTY)
        val price = selected.takeIf { it >= 0 }?.let { entries.getOrNull(index(it))?.price } ?: 0
        data.set(2, price and 0xFFFF)
        data.set(3, price ushr 16)
        // One page beyond the last item to add more.
        data.set(4, (entries.indexOfLast { !it.stack.isEmpty } + 1) / TeamFlagBlockEntity.PAGE + 1)
    }

    private fun save() {
        val master = master ?: return
        while (entries.isNotEmpty() && entries.last().stack.isEmpty) entries.removeAt(entries.lastIndex)
        master.shops = master.shops + (team to entries.toList())
        refresh()
    }

    private fun put(index: Int, stack: ItemStack) {
        while (entries.size <= index) entries += ShopEntry.EMPTY
        val old = entries[index]
        val price = if (!old.stack.isEmpty && ItemStack.isSameItemSameComponents(old.stack, stack)) old.price else Battles.suggestedPrice(stack)
        entries[index] = ShopEntry(price, stack.copy())
    }

    override fun clicked(slotId: Int, button: Int, clickType: ContainerInput, player: Player) {
        if (slotId !in 0 until TeamFlagBlockEntity.PAGE) return super.clicked(slotId, button, clickType, player)
        if (player !is ServerPlayer || master?.canManage(player) != true) return
        val index = index(slotId)
        val carried = carried
        when {
            !carried.isEmpty -> {
                put(index, if (button == 1) carried.copyWithCount(1) else carried)
                data.set(1, slotId)
            }
            clickType == ContainerInput.QUICK_MOVE -> {
                if (index < entries.size) entries[index] = ShopEntry.EMPTY
                data.set(1, -1)
            }
            else -> data.set(1, slotId.takeIf { entries.getOrNull(index)?.stack?.isEmpty == false } ?: -1)
        }
        save()
    }

    /** Shift-click in the inventory: a copy goes into the first free shop slot (the item stays yours). */
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val stack = slots.getOrNull(index)?.item ?: return ItemStack.EMPTY
        if (index < TeamFlagBlockEntity.PAGE || stack.isEmpty || player !is ServerPlayer || master?.canManage(player) != true) return ItemStack.EMPTY
        val free = entries.indexOfFirst { it.stack.isEmpty }.takeIf { it >= 0 } ?: entries.size
        put(free, stack)
        save()
        return ItemStack.EMPTY
    }

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val master = master ?: return false
        if (player !is ServerPlayer || !master.canManage(player)) return false
        when (id) {
            PREV -> data.set(0, (page - 1).coerceAtLeast(0)).also { data.set(1, -1) }
            NEXT -> data.set(0, (page + 1).coerceAtMost(pages - 1)).also { data.set(1, -1) }
            in PRICE until PRICE + STEPS.size -> {
                val entry = selected.takeIf { it >= 0 }?.let { entries.getOrNull(index(it)) }?.takeIf { !it.stack.isEmpty } ?: return false
                entries[index(selected)] = entry.copy(price = (entry.price + STEPS[id - PRICE]).coerceIn(0, 1_000_000))
                save()
            }
            REMOVE -> {
                if (selected >= 0 && index(selected) < entries.size) entries[index(selected)] = ShopEntry.EMPTY
                data.set(1, -1)
                save()
            }
            RESET -> {
                master.shops = master.shops - team
                open(player, master, view.team)
                return true
            }
            in TEAM until TEAM + view.teams.size -> {
                open(player, master, id - TEAM)
                return true
            }
            else -> return false
        }
        refresh()
        return true
    }

    override fun stillValid(player: Player) = master == null || (!master.isRemoved && master.blockPos.closerToCenterThan(player.position(), 8.0))

    companion object {
        const val PREV = 0
        const val NEXT = 1
        const val PRICE = 10
        const val REMOVE = 20
        const val RESET = 21
        const val TEAM = 30
        val STEPS = intArrayOf(-100, -10, -1, 1, 10, 100)

        fun open(player: ServerPlayer, master: BattleMasterBlockEntity, team: Int) {
            val teams = master.settings.teams
            val t = teams.getOrNull(team) ?: return
            val view = ShopEditorView(master.blockPos.asLong(), teams.map { it.name }, team, t.teamColor.rgb())
            player.openMenu(object : ExtendedMenuProvider<ShopEditorView> {
                override fun getScreenOpeningData(player: ServerPlayer) = view
                override fun getDisplayName(): Component = Component.translatable("container.flansmod.shop_editor", t.name)
                override fun createMenu(id: Int, inventory: Inventory, player: Player) = ShopEditorMenu(id, inventory, view, master)
            })
        }
    }
}
