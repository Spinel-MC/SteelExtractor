package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import com.steelextractor.mixin.AxeItemAccessor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory


class Strippables : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-strippables")

    override fun fileName(): String {
        return "steel-core/build/strippables.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonObject()

        for ((normal, stripped) in AxeItemAccessor.getStrippables()) {
            topLevelJson.addProperty(
                BuiltInRegistries.BLOCK.getKey(normal).path,
                BuiltInRegistries.BLOCK.getKey(stripped).path
            )
        }

        return topLevelJson
    }
}
