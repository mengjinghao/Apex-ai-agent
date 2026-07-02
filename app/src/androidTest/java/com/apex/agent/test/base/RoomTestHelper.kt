package com.apex.agent.test.base

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 数据库测试辅助类
 *
 * 提供创建内存数据库的便捷方法，用于 Android Instrumentation 测试。
 *
 * 注意：此类需要在 Android 环境下运行（Instrumented Test 或 Robolectric），
 * 因为 Room.databaseBuilder 和 Room.inMemoryDatabaseBuilder 需要 Android Context。
 */
object RoomTestHelper {

    /**
     * 创建内存数据库
     *
     * @param context Android Context
     * @param databaseClass 数据库类的 Class 对象
     * @param enableMainThreadQueries 是否允许主线程查询，默认 true
     * @return 数据库实例
     */
    fun <T : RoomDatabase> createInMemoryDatabase(
        context: Context,
        databaseClass: Class<T>,
        enableMainThreadQueries: Boolean = true
    ): T {
        val builder = Room.inMemoryDatabaseBuilder(context, databaseClass)
        if (enableMainThreadQueries) {
            builder.allowMainThreadQueries()
        }
        return builder.build()
    }

    /**
     * 创建内存数据库并应用迁移
     *
     * @param context Android Context
     * @param databaseClass 数据库类的 Class 对象
     * @param migrations 要应用的 Migration 数组
     * @return 数据库实例
     */
    fun <T : RoomDatabase> createWithMigrations(
        context: Context,
        databaseClass: Class<T>,
        vararg migrations: androidx.room.migration.Migration
    ): T {
        return Room.inMemoryDatabaseBuilder(context, databaseClass)
            .addMigrations(*migrations)
            .allowMainThreadQueries()
            .build()
    }

    /**
     * 创建指定版本的数据库（用于迁移测试）
     *
     * @param context Android Context
     * @param databaseClass 数据库类的 Class 对象
     * @param version 目标数据库版本
     * @return SupportSQLiteDatabase 实例
     */
    fun createDatabaseAtVersion(
        context: Context,
        databaseClass: Class<out RoomDatabase>,
        version: Int
    ): SupportSQLiteDatabase {
        val db = Room.inMemoryDatabaseBuilder(context, databaseClass)
            .allowMainThreadQueries()
            .build()

        // 通过 MigrationTestHelper 创建特定版本
        val dbName = "${databaseClass.simpleName}_v$version"
        val supportDb = Room.databaseBuilder(context, databaseClass, dbName)
            .allowMainThreadQueries()
            .build()

        return supportDb.openHelper.writableDatabase
    }

    /**
     * 清空数据库所有数据（用于测试间清理）
     *
     * @param database RoomDatabase 实例
     * @param tableNames 要清空的表名列表
     */
    fun clearTables(database: RoomDatabase, vararg tableNames: String) {
        database.clearAllTables()
    }
}
