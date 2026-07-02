package com.apex.agent.database.entity

import androidx.room.*

@Entity(
    tableName = "role_permissions",
    primaryKeys = ["roleId", "permissionId"],
    foreignKeys = [
        ForeignKey(
            entity = Role::class,
            parentColumns = ["id"],
            childColumns = ["roleId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Permission::class,
            parentColumns = ["id"],
            childColumns = ["permissionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["roleId"]), Index(value = ["permissionId"])]
)
data class RolePermission(
    val roleId: Long,
    val permissionId: Long
)
