package io.github.msksgm

import kotlinx.serialization.Serializable

@Serializable
data class UserSession(
    val userId: Int = 0,
    val csrfToken: String = "",
    val notice: String = "",
)
