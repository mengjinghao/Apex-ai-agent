package com.apex.agent.orchestration.core

import com.apex.agent.orchestration.agent.AgentCapabilityProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CapabilityMatcher @Inject constructor() {

    data class MatchResult(
        val score: Float,
        val breakdown: Map<String, Float> = emptyMap(),
        val matchedCapabilities: List<String> = emptyList(),
        val matchedSpecialties: List<String> = emptyList(),
        val missingSkills: List<String> = emptyList()
    )

    fun computeMatch(
        requiredSkills: List<String>,
        specialties: List<String>,
        capabilityProfile: AgentCapabilityProfile
    ): MatchResult {
        val specialtyMatch = computeSpecialtyOverlap(requiredSkills, specialties)
        val capabilityScores = computeCapabilityScores(requiredSkills, capabilityProfile)
        val matchedCaps = capabilityScores.filter { it.value > 0.3f }.keys.toList()
        val matchedSpecs = requiredSkills.filter { req ->
            specialties.any { spec -> spec.contains(req, ignoreCase = true) || req.contains(spec, ignoreCase = true) }
        }
        val missing = requiredSkills.filter { req ->
            !matchedCaps.any { it.contains(req, ignoreCase = true) } &&
            !matchedSpecs.any { it.contains(req, ignoreCase = true) }
        }
        val totalScore = specialtyMatch * 0.4f + (capabilityScores.values.average().toFloat().coerceAtMost(1f)) * 0.6f
        return MatchResult(
            score = totalScore.coerceIn(0f, 1f),
            breakdown = mapOf(
                "specialty" to specialtyMatch,
                "capability" to capabilityScores.values.average().toFloat().coerceAtMost(1f)
            ),
            matchedCapabilities = matchedCaps,
            matchedSpecialties = matchedSpecs,
            missingSkills = missing
        )
    }

    fun computeSpecialtyOverlap(required: List<String>, specialties: List<String>): Float {
        if (required.isEmpty()) return 0.5f
        if (specialties.isEmpty()) return 0f
        val normalizedRequired = required.map { it.lowercase().trim() }
        val normalizedSpecialties = specialties.map { it.lowercase().trim() }
        var matchCount = 0
        for (req in normalizedRequired) {
            val hasMatch = normalizedSpecialties.any { spec ->
                spec.contains(req) || req.contains(spec) ||
                spec.split(" ", "_", "-").any { it == req } ||
                req.split(" ", "_", "-").any { it == spec }
            }
            if (hasMatch) matchCount++
        }
        return matchCount.toFloat() / required.size
    }

    fun computeCapabilityScores(
        required: List<String>,
        profile: AgentCapabilityProfile
    ): Map<String, Double> {
        return required.associateWith { skill ->
            val direct = profile.getCapability(skill)
            if (direct > 0.0) direct
            else profile.predictCapability(skill, skill)
        }
    }

    fun findBestCategoryMatch(description: String, category: String, profiles: List<Pair<String, AgentCapabilityProfile>>): String? {
        return profiles.maxByOrNull { (_, profile) ->
            profile.predictCapability(description, category)
        }?.first
    }

    fun rankByRelevance(
        query: String,
        candidates: List<Pair<String, String>>,
        topN: Int = 5
    ): List<Pair<String, Float>> {
        val lowerQuery = query.lowercase()
        val queryTerms = lowerQuery.split(" ", "_", "-", "\n").filter { it.length > 2 }.toSet()
        val scored = candidates.map { (id, description) ->
            val lowerDesc = description.lowercase()
            val descTerms = lowerDesc.split(" ", "_", "-", "\n").filter { it.length > 2 }.toSet()
            val intersection = queryTerms.intersect(descTerms).size
            val jaccard = if (queryTerms.union(descTerms).isEmpty()) 0f
            else intersection.toFloat() / queryTerms.union(descTerms).size
            id to jaccard
        }
        return scored.sortedByDescending { it.second }.take(topN)
    }
}
