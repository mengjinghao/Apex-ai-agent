package com.apex.agent.database.entity

import androidx.room.*

@Entity(
    tableName = "permissions",
    indices = [Index(value = ["name"], unique = true)]
)
data class Permission(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val category: String = "system",
    val createdAt: Long = System.currentTimeMillis()
)
