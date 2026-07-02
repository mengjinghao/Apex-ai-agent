package com.apex.agent.test.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before

/**
 * DAO 测试的抽象基类
 *
 * 提供内存数据库的创建和清理，子类只需实现 [getDatabaseClass] 方法。
 *
 * 使用示例：
 * ```
 * class CostRecordDaoTest : BaseDaoTest<ApexAgentDatabase>() {
 *     override fun getDatabaseClass(): Class<ApexAgentDatabase> = ApexAgentDatabase::class.java
 *
 *     private lateinit var dao: CostRecordDao
 *
 *     @Before
 *     fun initDao() {
 *         dao = database.costRecordDao()
 *     }
 *
 *     @Test
 *     fun insertAndQuery() = runTest {
 *         val record = CostRecordEntity("1", "gpt-4", "openai", 100, 50, 0.01f, 1000L, null, null, null)
 *         dao.insert(record)
 *         val result = dao.getRecordsByTimeRange(0L, 2000L)
 *         assertEquals(1, result.size)
 *     }
 * }
 * ```
 *
 * @param T RoomDatabase 子类类型
 */
abstract class BaseDaoTest<T : RoomDatabase> {

    /** 数据库实例，在 [setUp] 中初始化 */
    protected lateinit var database: T

    @Before
    open fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, getDatabaseClass())
            .allowMainThreadQueries()
            .build()
    }

    @After
    open fun tearDown() {
        if (::database.isInitialized && database.isOpen) {
            database.close()
        }
    }

    /**
     * 返回待测试的数据库类
     *
     * @return RoomDatabase 的 Class 对象
     */
    protected abstract fun getDatabaseClass(): Class<T>
}
