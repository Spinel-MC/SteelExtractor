package com.steelextractor

import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ChunkStageHashStorage {
    private val hashes = ConcurrentHashMap<Pair<ChunkPos, String>, String>()
    private val trackedChunks = ConcurrentHashMap.newKeySet<ChunkPos>()
    private val readyChunks = ConcurrentHashMap.newKeySet<ChunkPos>()

    @Volatile
    private var readyLatch: CountDownLatch? = null

    fun startTracking(chunks: Set<ChunkPos>) {
        trackedChunks.addAll(chunks)
        readyLatch = CountDownLatch(chunks.size)
    }

    fun isTracking(pos: ChunkPos): Boolean {
        return trackedChunks.contains(pos)
    }

    fun markReady(pos: ChunkPos): Boolean {
        if (trackedChunks.contains(pos) && readyChunks.add(pos)) {
            readyLatch?.countDown()
            return true
        }
        return false
    }

    fun waitForAllReady(timeoutSeconds: Long): Boolean {
        return readyLatch?.await(timeoutSeconds, TimeUnit.SECONDS) ?: true
    }

    fun storeHash(pos: ChunkPos, stageName: String, hash: String) {
        hashes[Pair(pos, stageName)] = hash
    }

    fun getAllHashes(): Map<Pair<ChunkPos, String>, String> {
        return hashes.toMap()
    }

    fun getReadyCount(): Int = readyChunks.size
    fun getTrackedCount(): Int = trackedChunks.size

    fun clear() {
        hashes.clear()
        trackedChunks.clear()
        readyChunks.clear()
        readyLatch = null
    }

    fun computeBlockHash(sections: Iterable<net.minecraft.world.level.chunk.LevelChunkSection>): String {
        val md = MessageDigest.getInstance("MD5")

        for (section in sections) {
            if (section.hasOnlyAir()) {
                md.update(0.toByte())
            } else {
                val states = section.states
                for (y in 0 until 16) {
                    for (z in 0 until 16) {
                        for (x in 0 until 16) {
                            val state = states.get(x, y, z)
                            val stateId = net.minecraft.world.level.block.Block.getId(state)
                            md.update((stateId shr 24).toByte())
                            md.update((stateId shr 16).toByte())
                            md.update((stateId shr 8).toByte())
                            md.update(stateId.toByte())
                        }
                    }
                }
            }
        }

        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
