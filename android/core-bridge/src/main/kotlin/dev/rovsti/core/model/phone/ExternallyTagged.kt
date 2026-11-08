package dev.rovsti.core.model.phone

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Serializer for a Rust enum that serde writes in its default (externally tagged) form: a unit variant is a bare
 * string (`"pause"`), a variant with data is a one-key object (`{"log": 3}`). kotlinx has no format for that mix, so
 * each such type states its two conversions over [JsonElement].
 */
internal abstract class ExternallyTagged<T>(
    private val typeName: String,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    abstract fun fromTag(
        tag: String,
        value: JsonElement?,
    ): T

    abstract fun toJson(value: T): JsonElement

    override fun deserialize(decoder: Decoder): T {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        return when {
            element is JsonPrimitive && element.isString -> {
                fromTag(element.content, null)
            }

            element is JsonObject && element.size == 1 -> {
                element.entries.first().let { fromTag(it.key, it.value) }
            }

            else -> {
                throw SerializationException("$typeName: expected a string or a one-key object, got $element")
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) = encoder.encodeSerializableValue(JsonElement.serializer(), toJson(value))

    protected fun unknown(tag: String): Nothing = throw SerializationException("$typeName: unknown variant \"$tag\"")

    protected fun tagged(
        tag: String,
        value: JsonElement,
    ): JsonElement = JsonObject(mapOf(tag to value))
}
