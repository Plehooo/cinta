package com.plehooo.cinta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CoupleMember(
    @SerialName("couple_id") val coupleId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("left_at") val leftAt: String? = null
)

@Serializable
data class MemberPhoto(
    @SerialName("photo_id") val photoId: String,
    @SerialName("couple_id") val coupleId: String,
    @SerialName("owner_uid") val ownerUid: String,
    @SerialName("object_path") val objectPath: String,
    val caption: String? = null,
    val version: Int,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class CodeHashParams(@SerialName("p_code_hash") val codeHash: String)

@Serializable
data class RedeemCodeParams(@SerialName("p_code") val code: String)

@Serializable
data class PublishPhotoParams(
    @SerialName("p_couple_id") val coupleId: String,
    @SerialName("p_object_path") val objectPath: String,
    @SerialName("p_caption") val caption: String?,
    @SerialName("p_content_hash") val contentHash: String,
    @SerialName("p_expected_version") val expectedVersion: Int
)

@Serializable
data class PendingPhoto(
    val coupleId: String,
    val ownerUid: String,
    val caption: String,
    val expectedVersion: Int,
    val createdAtEpochMillis: Long
)
