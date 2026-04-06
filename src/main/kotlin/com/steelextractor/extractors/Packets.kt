package com.steelextractor.extractors

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.steelextractor.SteelExtractor
import net.minecraft.SharedConstants
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketType
import net.minecraft.network.protocol.configuration.ConfigurationProtocols
import net.minecraft.network.protocol.game.GameProtocols
import net.minecraft.network.protocol.handshake.HandshakeProtocols
import net.minecraft.network.protocol.login.LoginProtocols
import net.minecraft.network.protocol.status.StatusProtocols
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

class Packets : SteelExtractor.Extractor {
    private val logger = LoggerFactory.getLogger("steel-extractor-packets")

    private val packetClasses: Map<PacketType<*>, Class<*>> by lazy {
        scanPacketClasses()
    }

    override fun fileName(): String {
        return "steel-registry/build_assets/packets.json"
    }

    override fun extract(server: MinecraftServer): JsonElement {
        val packetsJson = JsonObject()

        val clientBound = arrayOf(
            StatusProtocols.CLIENTBOUND_TEMPLATE.details(),
            LoginProtocols.CLIENTBOUND_TEMPLATE.details(),
            ConfigurationProtocols.CLIENTBOUND_TEMPLATE.details(),
            GameProtocols.CLIENTBOUND_TEMPLATE.details()
        )

        val serverBound = arrayOf(
            HandshakeProtocols.SERVERBOUND_TEMPLATE.details(),
            StatusProtocols.SERVERBOUND_TEMPLATE.details(),
            LoginProtocols.SERVERBOUND_TEMPLATE.details(),
            ConfigurationProtocols.SERVERBOUND_TEMPLATE.details(),
            GameProtocols.SERVERBOUND_TEMPLATE.details()
        )
        val serverBoundJson = serializeServerBound(serverBound)
        val clientBoundJson = serializeClientBound(clientBound)
        packetsJson.addProperty("version", SharedConstants.getProtocolVersion())
        packetsJson.add("serverbound", serverBoundJson)
        packetsJson.add("clientbound", clientBoundJson)
        return packetsJson
    }

    private fun serializeServerBound(
        packets: Array<ProtocolInfo.Details>
    ): JsonObject {
        val handshake = JsonObject()
        val status = JsonObject()
        val login = JsonObject()
        val config = JsonObject()
        val play = JsonObject()

        for (factory in packets) {
            factory.listPackets { type: PacketType<*>, id: Int ->
                val packetObj = createPacketObject(type, id, "Serverbound", factory.id())
                when (factory.id()!!) {
                    ConnectionProtocol.HANDSHAKING -> handshake.add(type.id().path, packetObj)
                    ConnectionProtocol.PLAY -> play.add(type.id().path, packetObj)
                    ConnectionProtocol.STATUS -> status.add(type.id().path, packetObj)
                    ConnectionProtocol.LOGIN -> login.add(type.id().path, packetObj)
                    ConnectionProtocol.CONFIGURATION -> config.add(type.id().path, packetObj)
                }
            }
        }

        val finalJson = JsonObject()
        finalJson.add("handshake", handshake)
        finalJson.add("status", status)
        finalJson.add("login", login)
        finalJson.add("config", config)
        finalJson.add("play", play)
        return finalJson
    }

    private fun serializeClientBound(
        packets: Array<ProtocolInfo.Details>
    ): JsonObject {
        val status = JsonObject()
        val login = JsonObject()
        val config = JsonObject()
        val play = JsonObject()

        for (factory in packets) {
            factory.listPackets { type: PacketType<*>, id: Int ->
                val packetObj = createPacketObject(type, id, "Clientbound", factory.id())
                when (factory.id()!!) {
                    ConnectionProtocol.HANDSHAKING -> error("Client bound Packet should have no handshake")
                    ConnectionProtocol.PLAY -> play.add(type.id().path, packetObj)
                    ConnectionProtocol.STATUS -> status.add(type.id().path, packetObj)
                    ConnectionProtocol.LOGIN -> login.add(type.id().path, packetObj)
                    ConnectionProtocol.CONFIGURATION -> config.add(type.id().path, packetObj)
                }
            }
        }
        val finalJson = JsonObject()
        finalJson.add("status", status)
        finalJson.add("login", login)
        finalJson.add("config", config)
        finalJson.add("play", play)
        return finalJson
    }

    private fun createPacketObject(type: PacketType<*>, id: Int, direction: String, protocol: ConnectionProtocol?): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", String.format("0x%02X", id))
        
        var clazz = packetClasses[type]
        if (clazz == null) {
            clazz = guessPacketClass(type, direction, protocol)
        }

        if (clazz != null) {
            obj.add("fields", extractFields(clazz))
        } else {
            logger.warn("Could not find class for packet ${type.id()} ($direction, $protocol)")
        }
        
        return obj
    }

    private fun guessPacketClass(type: PacketType<*>, direction: String, protocol: ConnectionProtocol?): Class<*>? {
        val path = type.id().path
        val camelName = snakeToCamel(path)
        val className = "$direction${camelName}Packet"
        
        val packageNames = mutableListOf<String>()
        
        val protocolPkg = when(protocol) {
            ConnectionProtocol.HANDSHAKING -> "handshake"
            ConnectionProtocol.PLAY -> "game"
            ConnectionProtocol.STATUS -> "status"
            ConnectionProtocol.LOGIN -> "login"
            ConnectionProtocol.CONFIGURATION -> "configuration"
            else -> "common"
        }
        
        packageNames.add("net.minecraft.network.protocol.$protocolPkg")
        packageNames.add("net.minecraft.network.protocol.common")
        
        for (pkg in packageNames) {
            val fullClassName = "$pkg.$className"
            try {
                return Class.forName(fullClassName)
            } catch (e: ClassNotFoundException) {
            }
        }
        return null
    }

    private fun extractFields(clazz: Class<*>): JsonArray {
        val fields = JsonArray()
        
        if (clazz.isRecord) {
            for (comp in clazz.recordComponents) {
                val fieldObj = JsonObject()
                fieldObj.addProperty("name", camelToSnake(comp.name))
                fieldObj.addProperty("type", mapType(comp.genericType))
                fields.add(fieldObj)
            }
        } else {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || Modifier.isTransient(field.modifiers)) continue
                if (field.isSynthetic) continue
                
                val fieldObj = JsonObject()
                fieldObj.addProperty("name", camelToSnake(field.name))
                fieldObj.addProperty("type", mapType(field.genericType))
                fields.add(fieldObj)
            }
        }
        return fields
    }

    private fun camelToSnake(str: String): String {
        return str.replace(Regex("([a-z])([A-Z]+)"), "$1_$2").lowercase()
    }

    private fun snakeToCamel(str: String): String {
        return str.split("_").joinToString("") { it.replaceFirstChar { char -> char.uppercase() } }
    }

    private fun mapType(type: Type): String {
        if (type is WildcardType) {
            val upperBounds = type.upperBounds
            return if (upperBounds.isNotEmpty()) {
                mapType(upperBounds[0])
            } else {
                "?" 
            }
        }

        if (type is Class<*>) {
            return when {
                type == Int::class.javaPrimitiveType -> "int"
                type == Integer::class.java -> "Integer"
                type == Long::class.javaPrimitiveType -> "long"
                type == Long::class.java -> "Long"
                type == Boolean::class.javaPrimitiveType -> "boolean"
                type == Boolean::class.java -> "Boolean"
                type == Byte::class.javaPrimitiveType -> "byte"
                type == Byte::class.java -> "Byte"
                type == Short::class.javaPrimitiveType -> "short"
                type == Short::class.java -> "Short"
                type == Float::class.javaPrimitiveType -> "float"
                type == Float::class.java -> "Float"
                type == Double::class.javaPrimitiveType -> "double"
                type == Double::class.java -> "Double"
                type == String::class.java -> "String"
                type == java.util.UUID::class.java -> "UUID"
                type.isArray -> "Array<${mapType(type.componentType)}>"
                else -> type.simpleName
            }
        }
        
        if (type is ParameterizedType) {
            val raw = type.rawType as? Class<*> ?: return type.typeName
            val typeArguments = type.actualTypeArguments.joinToString(", ") { mapType(it) }
            return "${raw.simpleName}<$typeArguments>"
        }
        
        return type.typeName
    }

    private fun scanPacketClasses(): Map<PacketType<*>, Class<*>> {
        val map = mutableMapOf<PacketType<*>, Class<*>>()
        val containers = listOf(
            "net.minecraft.network.protocol.handshake.HandshakeProtocols",
            "net.minecraft.network.protocol.status.StatusProtocols",
            "net.minecraft.network.protocol.login.LoginProtocols",
            "net.minecraft.network.protocol.configuration.ConfigurationProtocols",
            "net.minecraft.network.protocol.game.GameProtocols",
            "net.minecraft.network.protocol.common.CommonProtocols",
            "net.minecraft.network.protocol.cookie.CookieProtocols",
            "net.minecraft.network.protocol.ping.PingProtocols"
        )
        
        logger.info("Starting packet class-to-type mapping scan...")
        var foundCount = 0
        
        for (containerName in containers) {
            try {
                val clazz = Class.forName(containerName)
                for (field in clazz.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) && PacketType::class.java.isAssignableFrom(field.type)) {
                        field.isAccessible = true
                        val packetType = field.get(null) as? PacketType<*>
                        if (packetType != null) {
                            val genericType = field.genericType as? ParameterizedType
                            if (genericType != null && genericType.actualTypeArguments.isNotEmpty()) {
                                val packetClass = genericType.actualTypeArguments[0] as? Class<*>
                                if (packetClass != null) {
                                    map[packetType] = packetClass
                                    foundCount++
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to scan packet container $containerName: ${e.message}")
            }
        }
        
        logger.info("Found $foundCount packet mappings from protocol containers.")
        return map
    }
}