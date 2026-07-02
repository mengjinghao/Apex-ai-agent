package com.apex.agent.database

import androidx.room.TypeConverter
import com.apex.agent.database.entity.TaskPriority
import com.apex.agent.database.entity.TaskStatus

class Converters {
    @TypeConverter
    fun fromTaskStatus(status: TaskStatus): String {
        return status.name
    }

    @TypeConverter
    fun toTaskStatus(status: String): TaskStatus {
        return TaskStatus.valueOf(status)
    }

    @TypeConverter
    fun fromTaskPriority(priority: TaskPriority): String {
        return priority.name
    }

    @TypeConverter
    fun toTaskPriority(priority: String): TaskPriority {
        return TaskPriority.valueOf(priority)
    }
}
