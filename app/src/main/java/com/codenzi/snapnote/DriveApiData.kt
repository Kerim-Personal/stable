package com.codenzi.snapnote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DriveFile(
    val id: String,
    val name: String
)

@Serializable
data class DriveFileList(
    val files: List<DriveFile>
)