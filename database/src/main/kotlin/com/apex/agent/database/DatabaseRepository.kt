package com.apex.agent.database

import com.apex.agent.database.dao.*
import com.apex.agent.database.entity.*
import kotlinx.coroutines.flow.Flow

class DatabaseRepository(
    private val userDao: UserDao,
    private val taskDao: TaskDao,
    private val userWithTasksDao: UserWithTasksDao,
    private val permissionDao: PermissionDao,
    private val roleDao: RoleDao,
    private val userRoleDao: UserRoleDao,
    private val rolePermissionDao: RolePermissionDao
) {
    // ========== User ==========
    fun getAllUsers(): Flow<List<User>> = userDao.getAllUsers()
    fun getUserById(userId: Long): Flow<User?> = userDao.getUserById(userId)
    suspend fun getUserByEmail(email: String): User? = userDao.getUserByEmail(email)
    suspend fun insertUser(user: User): Long = userDao.insertUser(user)
    suspend fun insertUsers(users: List<User>) = userDao.insertUsers(users)
    suspend fun updateUser(user: User) = userDao.updateUser(user)
    suspend fun deleteUser(user: User) = userDao.deleteUser(user)
    suspend fun deleteUserById(userId: Long) = userDao.deleteUserById(userId)
    suspend fun deleteAllUsers() = userDao.deleteAllUsers()

    // ========== Task ==========
    fun getAllTasks(): Flow<List<Task>> = taskDao.getAllTasks()
    fun getTaskById(taskId: Long): Flow<Task?> = taskDao.getTaskById(taskId)
    fun getTasksByUserId(userId: Long): Flow<List<Task>> = taskDao.getTasksByUserId(userId)
    suspend fun insertTask(task: Task): Long = taskDao.insertTask(task)
    suspend fun insertTasks(tasks: List<Task>) = taskDao.insertTasks(tasks)
    suspend fun updateTask(task: Task) = taskDao.updateTask(task)
    suspend fun deleteTask(task: Task) = taskDao.deleteTask(task)
    suspend fun deleteTaskById(taskId: Long) = taskDao.deleteTaskById(taskId)
    suspend fun deleteTasksByUserId(userId: Long) = taskDao.deleteTasksByUserId(userId)
    suspend fun deleteAllTasks() = taskDao.deleteAllTasks()

    // ========== UserWithTasks ==========
    fun getUserWithTasks(userId: Long): Flow<UserWithTasks?> = userWithTasksDao.getUserWithTasks(userId)
    fun getAllUsersWithTasks(): Flow<List<UserWithTasks>> = userWithTasksDao.getAllUsersWithTasks()

    // ========== Permission ==========
    fun getAllPermissions(): Flow<List<Permission>> = permissionDao.getAllPermissions()
    fun getPermissionById(id: Long): Flow<Permission?> = permissionDao.getPermissionById(id)
    suspend fun getPermissionByName(name: String): Permission? = permissionDao.getPermissionByName(name)
    fun getPermissionsByCategory(category: String): Flow<List<Permission>> = permissionDao.getPermissionsByCategory(category)
    suspend fun insertPermission(permission: Permission): Long = permissionDao.insertPermission(permission)
    suspend fun insertPermissions(permissions: List<Permission>) = permissionDao.insertPermissions(permissions)
    suspend fun updatePermission(permission: Permission) = permissionDao.updatePermission(permission)
    suspend fun deletePermission(permission: Permission) = permissionDao.deletePermission(permission)
    suspend fun deletePermissionById(id: Long) = permissionDao.deletePermissionById(id)
    suspend fun permissionCount(): Int = permissionDao.count()

    // ========== Role ==========
    fun getAllRoles(): Flow<List<Role>> = roleDao.getAllRoles()
    fun getRoleById(id: Long): Flow<Role?> = roleDao.getRoleById(id)
    suspend fun getRoleByName(name: String): Role? = roleDao.getRoleByName(name)
    fun getRolesByMaxLevel(maxLevel: Int): Flow<List<Role>> = roleDao.getRolesByMaxLevel(maxLevel)
    suspend fun insertRole(role: Role): Long = roleDao.insertRole(role)
    suspend fun insertRoles(roles: List<Role>) = roleDao.insertRoles(roles)
    suspend fun updateRole(role: Role) = roleDao.updateRole(role)
    suspend fun deleteRole(role: Role) = roleDao.deleteRole(role)
    suspend fun deleteNonSystemRoleById(id: Long) = roleDao.deleteNonSystemRoleById(id)
    suspend fun roleCount(): Int = roleDao.count()

    // ========== UserRole ==========
    fun getRolesForUser(userId: Long): Flow<List<Role>> = userRoleDao.getRolesForUser(userId)
    suspend fun getRolesForUserSync(userId: Long): List<Role> = userRoleDao.getRolesForUserSync(userId)
    suspend fun getMaxRoleLevelForUser(userId: Long): Int? = userRoleDao.getMaxRoleLevelForUser(userId)
    fun getUserRoles(userId: Long): Flow<List<UserRole>> = userRoleDao.getUserRoles(userId)
    fun getPermissionsForUser(userId: Long): Flow<List<Permission>> = userRoleDao.getPermissionsForUser(userId)
    suspend fun getPermissionNamesForUser(userId: Long): List<String> = userRoleDao.getPermissionNamesForUser(userId)
    suspend fun hasPermission(userId: Long, permissionName: String): Boolean = userRoleDao.hasPermission(userId, permissionName)
    suspend fun assignRole(userId: Long, roleId: Long, grantedBy: String? = null): Long {
        return userRoleDao.insertUserRole(UserRole(userId = userId, roleId = roleId, grantedBy = grantedBy))
    }
    suspend fun revokeRole(userId: Long, roleId: Long) = userRoleDao.deleteUserRole(userId, roleId)
    suspend fun revokeAllRolesForUser(userId: Long) = userRoleDao.deleteUserRolesForUser(userId)

    // ========== RolePermission ==========
    suspend fun getPermissionsForRole(roleId: Long): List<Permission> = rolePermissionDao.getPermissionsForRole(roleId)
    suspend fun roleHasPermission(roleId: Long, permissionId: Long): Boolean = rolePermissionDao.hasPermission(roleId, permissionId)
    suspend fun assignPermissionToRole(roleId: Long, permissionId: Long) {
        rolePermissionDao.insertRolePermission(RolePermission(roleId = roleId, permissionId = permissionId))
    }
    suspend fun revokePermissionFromRole(roleId: Long, permissionId: Long) {
        rolePermissionDao.deleteRolePermission(roleId, permissionId)
    }
    suspend fun clearPermissionsForRole(roleId: Long) = rolePermissionDao.deletePermissionsForRole(roleId)
}
