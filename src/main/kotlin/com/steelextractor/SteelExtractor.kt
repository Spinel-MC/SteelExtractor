package com.steelextractor

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.steelextractor.extractors.Attributes
import com.steelextractor.extractors.Classes
import com.steelextractor.extractors.BlockEntities
import com.steelextractor.extractors.Blocks
import com.steelextractor.extractors.Entities
import com.steelextractor.extractors.EntityEvents
import com.steelextractor.extractors.Fluids
import com.steelextractor.extractors.GameRulesExtractor
import com.steelextractor.extractors.Items
import com.steelextractor.extractors.MenuTypes
import com.steelextractor.extractors.MobEffects
import com.steelextractor.extractors.Packets
import com.steelextractor.extractors.LevelEvents
import com.steelextractor.extractors.SoundEvents
import com.steelextractor.extractors.SoundTypes
import com.steelextractor.extractors.MultiNoiseBiomeParameters
import com.steelextractor.extractors.BiomeHashes
import com.steelextractor.extractors.ChunkStageHashes
import com.steelextractor.extractors.Weathering
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.status.ChunkStatus
import com.steelextractor.extractors.PoiTypesExtractor
import com.steelextractor.extractors.Potions
import com.steelextractor.extractors.StructureStarts
import com.steelextractor.extractors.Tags
import kotlinx.io.IOException
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.measureTimeMillis

object SteelExtractor : ModInitializer {
    private val logger = LoggerFactory.getLogger("steel-extractor")
    private const val HALF_SIZE = 25

    /** Set to false to skip chunk generation and chunk stage hash extraction. */
    private const val ENABLE_CHUNK_EXTRACTION = true

    override fun onInitialize() {
        logger.info("Hello Fabric world!")

        val test = BuiltInRegistries.BLOCK.byId(5);
        logger.info(test.toString())

        val test2 = BuiltInRegistries.FLUID.byId(2)
        logger.info(test2.toString())

        val immediateExtractors = arrayOf(
            Blocks(),
            BlockEntities(),
            Items(),
            Packets(),
            MenuTypes(),
            Entities(),
            EntityEvents(),
            Fluids(),
            GameRulesExtractor(),
            Classes(),
            Attributes(),
            MobEffects(),
            Potions(),
            SoundTypes(),
            SoundEvents(),
            MultiNoiseBiomeParameters(),
            BiomeHashes(),
            LevelEvents(),
            Tags(),
            StructureStarts(),
            Weathering(),
            PoiTypesExtractor()
        )


        val chunkStageExtractor = ChunkStageHashes()

        if (ENABLE_CHUNK_EXTRACTION) {
            ServerLifecycleEvents.SERVER_STARTING.register { _ ->
                logger.info("Setting up chunk stage hash tracking (${HALF_SIZE * 2}x${HALF_SIZE * 2} chunks)")
                val chunksToTrack = mutableSetOf<ChunkPos>()
                for (x in -HALF_SIZE until HALF_SIZE) {
                    for (z in -HALF_SIZE until HALF_SIZE) {
                        chunksToTrack.add(ChunkPos(x, z))
                    }
                }
                ChunkStageHashStorage.startTracking(chunksToTrack)
            }
        } else {
            logger.info("Chunk extraction DISABLED")
        }

        val outputDirectory: Path
        try {
            outputDirectory = Files.createDirectories(Paths.get("steel_extractor_output"))
        } catch (e: IOException) {
            logger.info("Failed to create output directory.", e)
            return
        }

        val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

        ServerLifecycleEvents.SERVER_STARTED.register(ServerLifecycleEvents.ServerStarted { server: MinecraftServer ->
            val timeInMillis = measureTimeMillis {
                for (ext in immediateExtractors) {
                    runExtractor(ext, outputDirectory, gson, server)
                }
            }
            logger.info("Immediate extractors done, took ${timeInMillis}ms")


            if (!ENABLE_CHUNK_EXTRACTION) {
                logger.info("All extractors complete! (chunk extraction skipped)")
            }
        })

        if (!ENABLE_CHUNK_EXTRACTION) return

        // Build the full list of chunk positions to generate
        val chunkQueue = ArrayDeque<ChunkPos>()
        for (x in -HALF_SIZE until HALF_SIZE) {
            for (z in -HALF_SIZE until HALF_SIZE) {
                chunkQueue.add(ChunkPos(x, z))
            }
        }
        val totalChunks = chunkQueue.size
        val chunksPerTick = 64

        var generationStarted = false
        var generationDone = false
        var chunkExtractorDone = false
        var manuallyMarked = 0

        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (chunkExtractorDone) return@register

            // Start generation on first tick after server is ready
            if (!generationStarted) {
                generationStarted = true
                logger.info("Forcing generation of $totalChunks chunks ($chunksPerTick per tick)...")
            }

            // Generate a batch of chunks per tick
            if (!generationDone) {
                val overworld = server.overworld()
                var generated = 0
                while (chunkQueue.isNotEmpty() && generated < chunksPerTick) {
                    val pos = chunkQueue.removeFirst()
                    overworld.getChunk(pos.x, pos.z, ChunkStatus.FULL, true)
                    generated++
                }

                val progress = totalChunks - chunkQueue.size
                if (progress % (chunksPerTick * 10) == 0 || chunkQueue.isEmpty()) {
                    logger.info("Chunk generation progress: $progress/$totalChunks")
                }

                if (chunkQueue.isEmpty()) {
                    // Mark any chunks loaded from disk as ready
                    for (x in -HALF_SIZE until HALF_SIZE) {
                        for (z in -HALF_SIZE until HALF_SIZE) {
                            val pos = ChunkPos(x, z)
                            if (ChunkStageHashStorage.markReady(pos)) {
                                manuallyMarked++
                            }
                        }
                    }
                    if (manuallyMarked > 0) {
                        logger.warn("$manuallyMarked chunks were loaded from disk (no intermediate stage hashes). Delete the world folder for full tracking.")
                    }
                    generationDone = true
                    logger.info("Chunk generation complete, waiting for all stages...")
                }

                return@register
            }

            // Wait for all chunks to finish all stages
            if (ChunkStageHashStorage.getReadyCount() >= ChunkStageHashStorage.getTrackedCount()) {
                chunkExtractorDone = true
                try {
                    val out = outputDirectory.resolve(chunkStageExtractor.fileName())
                    Files.createDirectories(out.parent)
                    val fileWriter = FileWriter(out.toFile(), StandardCharsets.UTF_8)
                    gson.toJson(chunkStageExtractor.extract(server), fileWriter)
                    fileWriter.close()
                    logger.info("Wrote " + out.toAbsolutePath())
                } catch (e: java.lang.Exception) {
                    logger.error("Extractor for \"${chunkStageExtractor.fileName()}\" failed.", e)
                }
                try {
                    chunkStageExtractor.writeBinaryBlockData(outputDirectory)
                } catch (e: java.lang.Exception) {
                    logger.error("Binary block data extraction failed.", e)
                }
                logger.info("All extractors complete!")
            }
        }
    }

    private fun runExtractor(
        ext: Extractor,
        outputDirectory: Path,
        gson: com.google.gson.Gson,
        server: MinecraftServer
    ) {
        try {
            val out = outputDirectory.resolve(ext.fileName())
            Files.createDirectories(out.parent)
            val fileWriter = FileWriter(out.toFile(), StandardCharsets.UTF_8)
            gson.toJson(ext.extract(server), fileWriter)
            fileWriter.close()
            logger.info("Wrote " + out.toAbsolutePath())
        } catch (e: java.lang.Exception) {
            logger.error("Extractor for \"${ext.fileName()}\" failed.", e)
        }
    }

    interface Extractor {
        fun fileName(): String

        @Throws(Exception::class)
        fun extract(server: MinecraftServer): JsonElement
    }
}
