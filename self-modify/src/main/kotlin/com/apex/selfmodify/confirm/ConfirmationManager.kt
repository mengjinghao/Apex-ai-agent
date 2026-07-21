package com.apex.selfmodify.confirm

import com.apex.selfmodify.plan.ModificationPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Pending confirmation request for a high-risk modification plan.
 */
data class ConfirmationRequest(
    val id: String,
    val plan: ModificationPlan,
    val reason: String
)

/**
 * Manager for user confirmation of HIGH/CRITICAL risk modifications.
 *
 * Flow:
 * 1. [SelfModifyService.apply] calls [requestConfirmation] (suspend) for HIGH/CRITICAL plans
 * 2. UI collects [pendingRequests] and shows a dialog
 * 3. UI calls [approve] or [reject], which completes the deferred
 * 4. [requestConfirmation] returns the user's decision
 */
class ConfirmationManager {
    private val _pendingRequests = MutableSharedFlow<ConfirmationRequest>(extraBufferCapacity = 16)
    val pendingRequests: SharedFlow<ConfirmationRequest> = _pendingRequests.asSharedFlow()

    private val pending = mutableMapOf<String, CompletableDeferred<Boolean>>()

    /**
     * Request user confirmation for a plan. Suspends until the user responds.
     * @return true if approved, false if rejected
     */
    suspend fun requestConfirmation(plan: ModificationPlan): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val request = ConfirmationRequest(plan.id, plan, plan.reason)
        pending[plan.id] = deferred
        _pendingRequests.tryEmit(request)
        return deferred.await()
    }

    fun approve(planId: String) {
        pending.remove(planId)?.complete(true)
    }

    fun reject(planId: String, reason: String = "rejected by user") {
        pending.remove(planId)?.complete(false)
    }
}
