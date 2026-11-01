@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.gatekeeper.admin.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A nullable string property that distinguishes an omitted PATCH field from explicit JSON null. */
@Serializable(with = PatchStringFieldSerializer::class)
data class PatchStringField(val value: String? = null, val isSpecified: Boolean = false)

object PatchStringFieldSerializer : KSerializer<PatchStringField> {
    override val descriptor: SerialDescriptor = String.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): PatchStringField =
        if (decoder.decodeNotNullMark()) PatchStringField(decoder.decodeString(), isSpecified = true)
        else {
            decoder.decodeNull()
            PatchStringField(value = null, isSpecified = true)
        }

    override fun serialize(encoder: Encoder, value: PatchStringField) {
        if (value.value == null) encoder.encodeNull() else encoder.encodeString(value.value)
    }
}

/** A nullable numeric property that distinguishes an omitted PATCH field from explicit JSON null. */
@Serializable(with = PatchDoubleFieldSerializer::class)
data class PatchDoubleField(val value: Double? = null, val isSpecified: Boolean = false)

object PatchDoubleFieldSerializer : KSerializer<PatchDoubleField> {
    override val descriptor: SerialDescriptor = Double.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): PatchDoubleField =
        if (decoder.decodeNotNullMark()) PatchDoubleField(decoder.decodeDouble(), isSpecified = true)
        else {
            decoder.decodeNull()
            PatchDoubleField(value = null, isSpecified = true)
        }

    override fun serialize(encoder: Encoder, value: PatchDoubleField) {
        if (value.value == null) encoder.encodeNull() else encoder.encodeDouble(value.value)
    }
}
