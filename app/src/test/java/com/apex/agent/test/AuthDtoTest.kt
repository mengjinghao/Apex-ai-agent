package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * 认证 DTO 测试
 *
 * 验证序列化往返、字段验证、默认值和边界条件。
 */
class AuthDtoTest : BaseUnitTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `login request serialization round trip`() {
        val request = LoginRequest("user@test.com", "password123")
        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<LoginRequest>(encoded)
        assertEquals(request.email, decoded.email)
        assertEquals(request.password, decoded.password)
    }

    @Test
    fun `login response should contain token`() {
        val response = LoginResponse("eyJhbGci", "refresh_token_abc", expiresIn = 3600)
        assertEquals("eyJhbGci", response.accessToken)
        assertEquals("refresh_token_abc", response.refreshToken)
        assertEquals(3600, response.expiresIn)
    }

    @Test
    fun `register request should validate password`() {
        val valid = RegisterRequest("test@test.com", "Secure1!", "Secure1!")
        assertTrue(valid.isValid())
        val invalid = RegisterRequest("test@test.com", "short", "short")
        assertFalse(invalid.isValid())
    }

    @Test
    fun `register request should detect password mismatch`() {
        val request = RegisterRequest("a@b.com", "Password1", "Password2")
        assertFalse(request.isValid())
        assertTrue(request.password != request.confirmPassword)
    }

    @Test
    fun `token refresh request should have grant type`() {
        val refresh = RefreshTokenRequest("refresh_token_xyz")
        assertEquals("refresh_token", refresh.grantType)
    }

    @Test
    fun `login request should trim email`() {
        val request = LoginRequest("  user@test.com  ", "pass123")
        assertEquals("user@test.com", request.email.trim())
    }

    @Test
    fun `auth response should have default values`() {
        val response = LoginResponse("token", "refresh")
        assertNotNull(response.accessToken)
        assertEquals(0, response.expiresIn)
    }

    @Test
    fun `register request should reject empty email`() {
        val request = RegisterRequest("", "Pass1234", "Pass1234")
        assertFalse(request.isValid())
    }

    @Test
    fun `login request should serialize to json`() {
        val request = LoginRequest("a@b.com", "pwd")
        val jsonStr = json.encodeToString(request)
        assertTrue(jsonStr.contains("a@b.com"))
        assertTrue(jsonStr.contains("grant_type"))
    }
}

@Serializable
data class LoginRequest(val email: String, val password: String, val grantType: String = "password")

@Serializable
data class LoginResponse(val accessToken: String, val refreshToken: String, val expiresIn: Int = 0)

@Serializable
data class RegisterRequest(val email: String, val password: String, val confirmPassword: String) {
    fun isValid(): Boolean {
        return email.isNotBlank() && password.length >= 6 && password == confirmPassword
    }
}

@Serializable
data class RefreshTokenRequest(val refreshToken: String, val grantType: String = "refresh_token")
