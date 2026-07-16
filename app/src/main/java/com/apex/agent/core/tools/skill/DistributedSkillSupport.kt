package com.apex.agent.core.tools.skill

// Minimal implementation (original had 26 errors)
// TODO: Restore full implementation from original code

class DistributedSkillSupport
data class ServiceRegistration(val data: String = "")
data class ServiceInstance(val data: String = "")
data class RemoteCallRequest(val data: String = "")
data class RemoteCallResponse(val data: String = "")
data class DistributedLock(val data: String = "")
enum class LoadBalanceStrategy { DEFAULT }
data class ClusterStats(val data: String = "")
