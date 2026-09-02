package dev.mindmax.v4.data.db

import androidx.room.TypeConverter

/**
 * Additional Room TypeConverters. Lists are stored as JSON strings so we don't
 * pull in a JSON converter at the DB layer; the calling code constructs the
 * comma-joined representation.
 */
class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>?): String? =
        value?.joinToString(SEP)

    @TypeConverter
    fun toStringList(value: String?): List<String>? =
        value?.takeIf { it.isNotBlank() }?.split(SEP)?.filter { it.isNotEmpty() }

    private companion object {
        const val SEP = "|"
    }
}
