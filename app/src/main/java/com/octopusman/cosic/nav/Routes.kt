package com.octopusman.cosic.nav

import kotlinx.serialization.Serializable

@Serializable
data object Library

@Serializable
data class Editor(val projectId: String)

@Serializable
data object Sounds

@Serializable
data object Settings

@Serializable
data object Credits
