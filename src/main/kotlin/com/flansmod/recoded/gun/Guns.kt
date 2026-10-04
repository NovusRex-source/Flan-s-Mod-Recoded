package com.flansmod.recoded.gun

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.network.ContentSyncPayload
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener
import net.minecraft.resources.FileToIdConverter
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.PreparableReloadListener

/**
 * Definitions of one kind, loaded from `data/<ns>/flansmod/<folder>/<name>.json` (works with `/reload`).
 * Server: filled by the reload listener. Client: replaced by [ContentSyncPayload].
 * In singleplayer both sides write the same data, which is harmless.
 */
open class DefinitionRegistry<T : Any>(val folder: String, val serializer: KSerializer<T>) {
    @Volatile
    var all: Map<Identifier, T> = emptyMap()
        private set

    operator fun get(id: Identifier?): T? = id?.let(all::get)

    fun replace(definitions: Map<Identifier, T>) {
        all = definitions
    }

    private val files = FileToIdConverter.json("${FlansMod.MOD_ID}/$folder")

    internal val listener = object : SimpleReloadListener<Map<Identifier, T>>() {
        override fun prepare(state: PreparableReloadListener.SharedState): Map<Identifier, T> =
            files.listMatchingResources(state.resourceManager()).mapNotNull { (file, resource) ->
                runCatching { resource.openAsReader().use { Content.JSON.decodeFromString(serializer, it.readText()) } }
                    .onFailure { FlansMod.LOGGER.error("Invalid {} definition {}: {}", folder, file, it.message) }
                    .getOrNull()?.let { files.fileToId(file) to it }
            }.toMap()

        override fun apply(definitions: Map<Identifier, T>, state: PreparableReloadListener.SharedState) {
            replace(definitions)
            FlansMod.LOGGER.info("Loaded {} {} definitions", definitions.size, folder)
        }
    }
}

object Guns : DefinitionRegistry<GunDefinition>("guns", GunDefinition.serializer())
object Attachments : DefinitionRegistry<AttachmentDefinition>("attachments", AttachmentDefinition.serializer())

/** Loading and client sync for all content-pack definitions. */
object Content {
    val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
        allowComments = true
    }

    fun init() {
        val loader = ResourceLoader.get(PackType.SERVER_DATA)
        listOf(Guns, Attachments).forEach { loader.registerReloadListener(FlansMod.id(it.folder), it.listener) }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> ServerPlayNetworking.send(handler.player, syncPayload()) }
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register { server, _, success -> if (success) resync(server) }
    }

    fun syncPayload() = ContentSyncPayload(Guns.all, Attachments.all)

    private fun resync(server: MinecraftServer) = PlayerLookup.all(server).forEach { ServerPlayNetworking.send(it, syncPayload()) }

    fun apply(payload: ContentSyncPayload) {
        Guns.replace(payload.guns)
        Attachments.replace(payload.attachments)
    }
}
