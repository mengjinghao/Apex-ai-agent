package com.apex.agent.database.entity

import androidx.room.*

@Entity(
    tableName = "user_roles",
    primaryKeys = ["userId", "roleId"],
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Role::class,
            parentColumns = ["id"],
            childColumns = ["roleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["userId"]), Index(value = ["roleId"])]
)
data class UserRole(
    val userId: Long,
    val roleId: Long,
    val grantedBy: String? = null,
    val grantedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null
)
