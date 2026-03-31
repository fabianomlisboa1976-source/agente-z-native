package com.agente.autonomo.utils

import androidx.room.TypeConverter
import java.util.Date

/**
 * Conversor de Date para Room Database
 */
class DateConverter {
    
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }
    
    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}
