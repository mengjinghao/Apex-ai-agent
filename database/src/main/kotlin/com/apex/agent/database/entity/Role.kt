package com.apex.agent.database.entity

import androidx.room.*

@Entity(
    tableName = "roles",
    indices = [Index(value = ["name"], unique = true)]
)
data class Role(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val level: Int = 0,
    val isSystem: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
