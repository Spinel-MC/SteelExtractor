package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.ChunkStageHashStorage
import com.steelextractor.SteelExtractor
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

class ChunkStageHashes : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-chunk-stage-hashes")

    override fun fileName(): String {
        return "steel-core/test_assets/chunk_stage_hashes.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val json = JsonObject()

        val worldSeed = server.overworld().seed
        json.addProperty("seed", worldSeed)

        if (worldSeed != 13579L) {
            logger.warn("World seed is $worldSeed, not 13579! Set level-seed=13579 in server.properties and delete the world folder.")
        }

        val allHashes = ChunkStageHashStorage.getAllHashes()
        val chunkGroups = allHashes.entries.groupBy { it.key.first }

        val chunksArray = JsonArray()

        for ((pos, entries) in chunkGroups.toSortedMap(compareBy({ it.x }, { it.z }))) {
            val chunkJson = JsonObject()
            chunkJson.addProperty("x", pos.x)
            chunkJson.addProperty("z", pos.z)

            val stagesJson = JsonObject()
            for ((key, hash) in entries.sortedBy { it.key.second }) {
                val stageName = key.second
                stagesJson.addProperty(stageName, hash)
            }
            chunkJson.add("stages", stagesJson)

            chunksArray.add(chunkJson)
        }

        json.add("chunks", chunksArray)
        json.addProperty("chunk_count", chunkGroups.size)

        logger.info("Extracted chunk stage hashes for ${chunkGroups.size} chunks")
        return json
    }
}
