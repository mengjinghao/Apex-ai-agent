package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import org.junit.Assert.*
import org.junit.Test

/**
 * API 响应 DTO 测试
 *
 * 验证响应创建、成功/失败辅助方法、分页和序列化。
 */
class ApiResponseTest : BaseUnitTest {

    @Test
    fun `success response should contain data`() {
        val response = ApiResponse.success("test_data")
        assertTrue(response.success)
        assertEquals("test_data", response.data)
        assertNull(response.error)
    }

    @Test
    fun `failure response should contain error message`() {
        val response = ApiResponse.failure<String>("not_found")
        assertFalse(response.success)
        assertNull(response.data)
        assertEquals("not_found", response.error)
    }

    @Test
    fun `pagination should track page state`() {
        val pagination = ApiPagination(page = 1, pageSize = 20, total = 100)
        assertEquals(1, pagination.page)
        assertEquals(20, pagination.pageSize)
        assertEquals(100, pagination.total)
        assertEquals(5, pagination.totalPages)
    }

    @Test
    fun `paginated response should include metadata`() {
        val paginated = ApiResponse.paginated(
            data = listOf("a", "b"),
            page = 1, pageSize = 10, total = 2
        )
        assertTrue(paginated.success)
        assertEquals(2, paginated.data?.size)
        assertNotNull(paginated.pagination)
        assertEquals(1, paginated.pagination!!.totalPages)
    }

    @Test
    fun `empty pagination should have zero pages`() {
        val pagination = ApiPagination(page = 0, pageSize = 10, total = 0)
        assertEquals(0, pagination.totalPages)
    }

    @Test
    fun `response should have correct status code mapping`() {
        val ok = ApiResponse.success("ok")
        assertEquals(200, ok.statusCode)
        val notFound = ApiResponse.failure<String>("not found")
        assertEquals(404, notFound.statusCode)
    }

    @Test
    fun `map should transform response data`() {
        val original = ApiResponse.success(42)
        val mapped = original.map { it.toString() }
        assertEquals("42", mapped.data)
    }

    @Test
    fun `should support list response`() {
        val list = ApiResponse.listOf(listOf(1, 2, 3))
        assertTrue(list.success)
        assertEquals(3, list.data?.size)
    }
}

data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: String?,
    val statusCode: Int,
    val pagination: ApiPagination? = null
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(true, data, null, 200)
        fun <T> failure(error: String, code: Int = 404): ApiResponse<T> = ApiResponse(false, null, error, code)
        fun <T> listOf(data: List<T>): ApiResponse<List<T>> = success(data)
        fun <T> paginated(data: List<T>, page: Int, pageSize: Int, total: Int): ApiResponse<List<T>> {
            return ApiResponse(true, data, null, 200, ApiPagination(page, pageSize, total))
        }
    }

    fun <R> map(transform: (T) -> R): ApiResponse<R> = ApiResponse(success, data?.let { transform(it) }, error, statusCode, pagination)
}

data class ApiPagination(val page: Int, val pageSize: Int, val total: Int) {
    val totalPages: Int get() = if (total == 0 || pageSize == 0) 0 else (total + pageSize - 1) / pageSize
}
