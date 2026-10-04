package com.flansmod.recoded.gun

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.network.GunSyncPayload
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener
import net.minecraft.resources.FileToIdConverter
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.PreparableReloadListener

/**
 * All gun definitions currently known on this side.
 *
 * Server: filled by the data reload listener from `data/<ns>/flansmod/guns/<name>.json` (works with `/reload`).
 * Client: replaced by [GunSyncPayload] on join and after every reload.
 * In singleplayer both sides write the same data, which is harmless.
 */
object Guns {
    @Volatile
    var all: Map<Identifier, GunDefinition> = emptyMap()
        private set

    operator fun get(id: Identifier?): GunDefinition? = id?.let(all::get)

    fun replace(definitions: Map<Identifier, GunDefinition>) {
        all = definitions
    }

    private val FILES = FileToIdConverter.json("${FlansMod.MOD_ID}/guns")

    val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
        allowComments = true
    }

    fun init() {
        ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(FlansMod.id("guns"), Loader)

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            ServerPlayNetworking.send(handler.player, GunSyncPayload(all))
        }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { server, _, success ->
            if (success) PlayerLookup.all(server).forEach { ServerPlayNetworking.send(it, GunSyncPayload(all)) }
        }
    }

    private object Loader : SimpleReloadListener<Map<Identifier, GunDefinition>>() {
        override fun prepare(state: PreparableReloadListener.SharedState): Map<Identifier, GunDefinition> =
            FILES.listMatchingResources(state.resourceManager()).mapNotNull { (file, resource) ->
                val id = FILES.fileToId(file)
                runCatching { resource.openAsReader().use { JSON.decodeFromString<GunDefinition>(it.readText()) } }
                    .onFailure { FlansMod.LOGGER.error("Invalid gun definition {}: {}", file, it.message) }
                    .getOrNull()?.let { id to it }
            }.toMap()

        override fun apply(definitions: Map<Identifier, GunDefinition>, state: PreparableReloadListener.SharedState) {
            replace(definitions)
            FlansMod.LOGGER.info("Loaded {} gun definitions", definitions.size)
        }
    }
}
