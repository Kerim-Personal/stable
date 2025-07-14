package com.codenzi.snapnote

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters

// DÜZELTME: Widget sorgusunu hızlandırmak için indeks eklendi.
@Entity(tableName = "notes", indices = [Index(value = ["isDeleted", "showOnWidget"])])
@TypeConverters(Converters::class)
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val content: String,
    val createdAt: Long,
    val modifiedAt: List<Long> = emptyList(),
    val color: String = "#FFECEFF1",
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val showOnWidget: Boolean = false
)