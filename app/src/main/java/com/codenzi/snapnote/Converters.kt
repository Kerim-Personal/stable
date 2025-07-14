package com.codenzi.snapnote

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromTimestampList(timestamps: List<Long>): String {
        return timestamps.joinToString(",")
    }

    @TypeConverter
    fun toTimestampList(data: String): List<Long> {
        return if (data.isEmpty()) emptyList() else data.split(',').map { it.toLong() }
    }
}