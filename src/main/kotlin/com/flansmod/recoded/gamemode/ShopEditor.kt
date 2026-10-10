package com.flansmod.recoded.gamemode

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.fortification.Fortifications
import com.flansmod.recoded.fuel.FuelCanItem
import com.flansmod.recoded.gear.GearItem
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.VehicleUpgrades
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.AttachmentItem
import com.flansmod.recoded.item.ClothingItem
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.StructureItem
import com.flansmod.recoded.item.VehicleItem
import com.flansmod.recoded.item.VehicleUpgradeItem
import com.flansmod.recoded.registry.FlansMenus
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.world.item.BlockItem
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

/** Catalog groups of the shop editor (the first one shows everything). */
enum class ShopCategory { ALL, GUNS, AMMO, ATTACHMENTS, EXPLOSIVES, CLOTHING, GEAR, VEHICLES, STRUCTURES, OTHER;
    val key get() = "gui.flansmod.shop.category.${name.lowercase()}"
}

/**
 * Everything a shop can sell, so managers do not need to own an item to put it on sale: the generated shop (with its
 * prices and amounts) plus every attachment, magazine, ammunition type and vehicle upgrade of the loaded content and
 * all fortification blocks. Guns, explosives, uniforms, vehicles and gear are [sided] (like the generated shop, only
 * shown for their own [faction] when filtering); ammunition, attachments, upgrades and the rest suit every side.
 */
object ShopCatalog {
    data class Item(val category: ShopCategory, val sided: Boolean, val faction: Identifier?, val entry: ShopEntry, val search: String)

    private var cache: List<Item>? = null

    init {
        Content.onChanged += { cache = null }
    }

    val all: List<Item> get() = cache ?: build().also { cache = it }

    private fun build(): List<Item> {
        val entries = Battles.defaultShop(null).toMutableList()
        fun add(price: Int, stack: ItemStack) {
            if (entries.none { ItemStack.isSameItemSameComponents(it.stack, stack) }) entries += ShopEntry(price, stack)
        }
        Magazines.all.entries.filter { !it.value.internal }.sortedBy { it.value.caliber }.forEach { (id, m) -> add(if (m.capacity <= 20) 25 else 40, MagazineItem.stackFor(id, full = true)) }
        AmmoTypes.all.entries.sortedBy { it.value.caliber }.forEach { (id, a) -> add(if (a.projectile != null) 120 else 30, AmmoItem.stackFor(id, if (a.projectile != null) 2 else 16)) }
        Attachments.all.entries.sortedBy { it.value.name }.forEach { (id, _) -> add(75, AttachmentItem.stackFor(id)) }
        VehicleUpgrades.all.entries.sortedBy { it.key.path }.forEach { (id, _) -> add(300, VehicleUpgradeItem.stackFor(id)) }
        Fortifications.ALL.forEach { add(40, ItemStack(it, 16)) }
        return entries.map { e ->
            val sided = e.stack.item.let { it is GunItem || it is GrenadeItem || it is ClothingItem || it is VehicleItem || it is GearItem }
            Item(categoryOf(e.stack), sided, Battles.factionOf(e.stack), e, searchText(e.stack))
        }
            .sortedBy { it.category.ordinal } // stable: keeps the generated shop's order inside a group
    }

    private fun categoryOf(stack: ItemStack): ShopCategory = when (val item = stack.item) {
        is GunItem -> ShopCategory.GUNS
        is MagazineItem, is AmmoItem -> ShopCategory.AMMO
        is AttachmentItem -> ShopCategory.ATTACHMENTS
        is GrenadeItem -> ShopCategory.EXPLOSIVES
        is ClothingItem -> ShopCategory.CLOTHING
        is GearItem -> ShopCategory.GEAR
        is VehicleItem, is VehicleUpgradeItem, is FuelCanItem -> ShopCategory.VEHICLES
        is StructureItem -> ShopCategory.STRUCTURES
        is BlockItem -> if (item.block in Fortifications.ALL) ShopCategory.STRUCTURES else ShopCategory.OTHER
        else -> if (stack.`is`(com.flansmod.recoded.registry.FlansItems.WRENCH)) ShopCategory.VEHICLES else ShopCategory.OTHER
    }

    /** Name plus item and definition ids: server-side names of vanilla items are English, mod items use their definition. */
    private fun searchText(stack: ItemStack): String = buildString {
        append(stack.hoverName.string).append(' ').append(BuiltInRegistries.ITEM.getKey(stack.item).path)
        stack.components.forEach { c -> (c.value() as? Identifier)?.let { append(' ').append(it.path) } }
    }.lowercase()

    /** [category] (everything for [ShopCategory.ALL]), only [faction]'s and general items if given, matching [query]. */
    fun filter(category: ShopCategory, faction: Identifier?, query: String): List<ShopEntry> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        return all.filter { (category == ShopCategory.ALL || it.category == category) && (faction == null || !it.sided || it.faction == faction) &&
            words.all(it.search::contains) }.map { it.entry }
    }
}

/** Shop editor search box: the catalog shows only matching items. */
data class ShopSearchPayload(val query: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ShopSearchPayload>(FlansMod.id("shop_search"))
        val CODEC: StreamCodec<FriendlyByteBuf, ShopSearchPayload> = ByteBufCodecs.stringUtf8(64).map(::ShopSearchPayload, ShopSearchPayload::query).cast()
    }
}

/** Which team's shop the editor shows, and the teams to switch between. */
@Serializable
data class ShopEditorView(val pos: Long, val teams: List<String>, val team: Int, val rgb: Int, val faction: String? = null) {
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
 * - click a catalog item (left panel, [ShopCatalog]): a copy goes into the selected empty shop slot, else the first free
 *   one, with the catalog's price; [CATEGORY] + n, [FACTION] (only the team's faction) and the search box
 *   ([ShopSearchPayload]) filter the catalog, [CATALOG_PREV]/[CATALOG_NEXT] page through it
 * - [SET_PRICE] + price sets an exact price, [COUNT] + 0..3 (-10, -1, +1, +10) the amount sold of the selected item
 * - [REMOVE] the selected item, [RESET] back to the generated shop, [PREV]/[NEXT] page, [TEAM] + n edit another team.
 * Every change is saved on the Battle Master at once ([BattleMasterBlockEntity.shops]).
 * Slots: shop page (0..26), player inventory, catalog page ([CATALOG_SLOT]..).
 * Data: page, selected slot (-1 = none), the selected price (two 16-bit halves), number of pages, catalog page,
 * catalog pages, category, faction filter (0/1), amount of the selected item.
 */
class ShopEditorMenu(
    id: Int, inventory: Inventory, val view: ShopEditorView, private val master: BattleMasterBlockEntity? = null,
    private val shop: SimpleContainer = SimpleContainer(TeamFlagBlockEntity.PAGE), val data: ContainerData = SimpleContainerData(10),
    private val catalog: SimpleContainer = SimpleContainer(CATALOG_SIZE),
) : AbstractContainerMenu(FlansMenus.SHOP_EDITOR, id) {
    private val team = view.teams.getOrNull(view.team) ?: ""
    private val entries: MutableList<ShopEntry> = master?.let { Battles.shop(it, team).toMutableList() } ?: mutableListOf()
    private val faction = view.faction?.let(Identifier::tryParse)
    private var query = ""
    private var catalogItems: List<ShopEntry> = emptyList()

    init {
        for (i in 0 until TeamFlagBlockEntity.PAGE) addSlot(ReadOnlySlot(shop, i, CATALOG_WIDTH + 8 + i % 9 * 18, 18 + i / 9 * 18))
        addStandardInventorySlots(inventory, CATALOG_WIDTH + 8, 85)
        for (i in 0 until CATALOG_SIZE) addSlot(ReadOnlySlot(catalog, i, 8 + i % CATALOG_COLUMNS * 18, CATALOG_Y + i / CATALOG_COLUMNS * 18))
        addDataSlots(data)
        data.set(1, -1)
        data.set(8, if (faction != null) 1 else 0)
        filterCatalog()
        refresh()
    }

    private class ReadOnlySlot(container: SimpleContainer, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
        override fun mayPickup(player: Player) = false
        override fun mayPlace(stack: ItemStack) = false
    }

    val page get() = data.get(0)
    val selected get() = data.get(1)
    val price get() = (data.get(2) and 0xFFFF) or (data.get(3) shl 16)
    val pages get() = data.get(4).coerceAtLeast(1)
    val catalogPage get() = data.get(5)
    val catalogPages get() = data.get(6).coerceAtLeast(1)
    val category get() = ShopCategory.entries.getOrElse(data.get(7)) { ShopCategory.ALL }
    val factionOnly get() = data.get(8) != 0
    val count get() = data.get(9)

    private fun index(slot: Int) = page * TeamFlagBlockEntity.PAGE + slot

    private fun refresh() {
        if (master == null) return
        for (i in 0 until TeamFlagBlockEntity.PAGE) shop.setItem(i, entries.getOrNull(index(i))?.takeIf { !it.stack.isEmpty }?.display() ?: ItemStack.EMPTY)
        val price = selected.takeIf { it >= 0 }?.let { entries.getOrNull(index(it))?.price } ?: 0
        data.set(2, price and 0xFFFF)
        data.set(3, price ushr 16)
        // One page beyond the last item to add more.
        data.set(4, (entries.indexOfLast { !it.stack.isEmpty } + 1) / TeamFlagBlockEntity.PAGE + 1)
        data.set(9, selected.takeIf { it >= 0 }?.let { entries.getOrNull(index(it))?.stack?.count } ?: 0)
        data.set(5, catalogPage.coerceIn(0, catalogPages - 1))
        for (i in 0 until CATALOG_SIZE) catalog.setItem(i, catalogItems.getOrNull(catalogPage * CATALOG_SIZE + i)?.display() ?: ItemStack.EMPTY)
    }

    /** New catalog filter (category, faction, search): back to its first page. */
    private fun filterCatalog() {
        catalogItems = ShopCatalog.filter(category, faction?.takeIf { factionOnly }, query)
        data.set(5, 0)
        data.set(6, (catalogItems.size + CATALOG_SIZE - 1) / CATALOG_SIZE)
    }

    /** Search box text from the client ([ShopSearchPayload]). */
    fun search(player: ServerPlayer, text: String) {
        if (master?.canManage(player) != true || text == query) return
        query = text
        filterCatalog()
        refresh()
        broadcastChanges()
    }

    /** A catalog item into the selected shop slot if that is empty, else the first free one (which gets selected). */
    private fun addFromCatalog(entry: ShopEntry) {
        val chosen = selected.takeIf { it >= 0 }?.let(::index)?.takeIf { entries.getOrNull(it)?.stack?.isEmpty != false }
        val target = chosen ?: entries.indexOfFirst { it.stack.isEmpty }.takeIf { it >= 0 } ?: entries.size
        while (entries.size <= target) entries += ShopEntry.EMPTY
        entries[target] = entry.copy(stack = entry.stack.copy())
        data.set(0, target / TeamFlagBlockEntity.PAGE)
        data.set(1, target % TeamFlagBlockEntity.PAGE)
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
        if (slotId in CATALOG_SLOT until CATALOG_SLOT + CATALOG_SIZE) {
            if (player !is ServerPlayer || master?.canManage(player) != true || !carried.isEmpty) return
            catalogItems.getOrNull(catalogPage * CATALOG_SIZE + slotId - CATALOG_SLOT)?.let(::addFromCatalog) ?: return
            return save()
        }
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
        if (index !in TeamFlagBlockEntity.PAGE until CATALOG_SLOT || stack.isEmpty || player !is ServerPlayer || master?.canManage(player) != true) return ItemStack.EMPTY
        val free = entries.indexOfFirst { it.stack.isEmpty }.takeIf { it >= 0 } ?: entries.size
        put(free, stack)
        save()
        return ItemStack.EMPTY
    }

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val master = master ?: return false
        if (player !is ServerPlayer || !master.canManage(player)) return false
        val selectedEntry = selected.takeIf { it >= 0 }?.let { entries.getOrNull(index(it)) }?.takeIf { !it.stack.isEmpty }
        when (id) {
            in SET_PRICE..SET_PRICE + MAX_PRICE -> {
                entries[index(selected)] = (selectedEntry ?: return false).copy(price = id - SET_PRICE)
                save()
            }
            in COUNT until COUNT + COUNT_STEPS.size -> {
                val entry = selectedEntry ?: return false
                entries[index(selected)] = entry.copy(stack = entry.stack.copyWithCount((entry.stack.count + COUNT_STEPS[id - COUNT]).coerceIn(1, entry.stack.maxStackSize)))
                save()
            }
            CATALOG_PREV -> data.set(5, (catalogPage - 1).coerceAtLeast(0))
            CATALOG_NEXT -> data.set(5, (catalogPage + 1).coerceAtMost(catalogPages - 1))
            FACTION -> {
                data.set(8, if (factionOnly || faction == null) 0 else 1)
                filterCatalog()
            }
            in CATEGORY until CATEGORY + ShopCategory.entries.size -> {
                data.set(7, id - CATEGORY)
                filterCatalog()
            }
            PREV -> data.set(0, (page - 1).coerceAtLeast(0)).also { data.set(1, -1) }
            NEXT -> data.set(0, (page + 1).coerceAtMost(pages - 1)).also { data.set(1, -1) }
            in PRICE until PRICE + STEPS.size -> {
                val entry = selectedEntry ?: return false
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
        const val CATALOG_PREV = 40
        const val CATALOG_NEXT = 41
        const val FACTION = 42
        const val CATEGORY = 50
        const val COUNT = 70
        /** Exact price: [SET_PRICE] + price (button ids are var-ints). */
        const val SET_PRICE = 1 shl 24
        const val MAX_PRICE = 1_000_000
        val STEPS = intArrayOf(-100, -10, -1, 1, 10, 100)
        val COUNT_STEPS = intArrayOf(-10, -1, 1, 10)

        /** Catalog panel left of the shop: 6 x 6 slots below its search box and filter buttons. */
        const val CATALOG_COLUMNS = 6
        const val CATALOG_SIZE = 36
        const val CATALOG_WIDTH = CATALOG_COLUMNS * 18 + 16
        const val CATALOG_Y = 58
        /** First catalog slot: after the shop page and the 36 player inventory slots. */
        const val CATALOG_SLOT = TeamFlagBlockEntity.PAGE + 36

        fun init() {
            PayloadTypeRegistry.serverboundPlay().register(ShopSearchPayload.TYPE, ShopSearchPayload.CODEC)
            ServerPlayNetworking.registerGlobalReceiver(ShopSearchPayload.TYPE) { payload, context ->
                (context.player().containerMenu as? ShopEditorMenu)?.search(context.player(), payload.query)
            }
        }

        fun open(player: ServerPlayer, master: BattleMasterBlockEntity, team: Int) {
            val teams = master.settings.teams
            val t = teams.getOrNull(team) ?: return
            val view = ShopEditorView(master.blockPos.asLong(), teams.map { it.name }, team, t.teamColor.rgb(), t.faction?.toString())
            player.openMenu(object : ExtendedMenuProvider<ShopEditorView> {
                override fun getScreenOpeningData(player: ServerPlayer) = view
                override fun getDisplayName(): Component = Component.translatable("container.flansmod.shop_editor", t.name)
                override fun createMenu(id: Int, inventory: Inventory, player: Player) = ShopEditorMenu(id, inventory, view, master)
            })
        }
    }
}
