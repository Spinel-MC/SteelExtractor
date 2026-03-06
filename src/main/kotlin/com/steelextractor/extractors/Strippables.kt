import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.fabricmc.fabric.mixin.content.registry.AxeItemAccessor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory


class StrippablesExtractor : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-strippables")

    override fun fileName(): String {
        return "strippables.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val topLevelJson = JsonArray()

        for ((normal, stripped) in AxeItemAccessor.getStrippedBlocks()) {
            val jsonObject = JsonObject()
            jsonObject.addProperty("from", BuiltInRegistries.BLOCK.getKey(normal).path)
            jsonObject.addProperty("to", BuiltInRegistries.BLOCK.getKey(stripped).path)
            topLevelJson.add(jsonObject)
        }

        return topLevelJson
    }
}