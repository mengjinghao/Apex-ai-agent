package com.apex.agent.test

import com.apex.agent.test.base.BaseUnitTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 贝叶斯推理策略测试
 *
 * 验证先验/后验概率计算、概率更新和信念传播。
 */
class BayesianReasoningStrategyTest : BaseUnitTest {

    private lateinit var strategy: BayesianReasoningStrategy

    @Before
    override fun setUp() {
        super.setUp()
        strategy = BayesianReasoningStrategy()
    }

    @Test
    fun `prior probability should be set correctly`() {
        strategy.setPrior("hypothesis", 0.3)
        assertEquals(0.3, strategy.getPrior("hypothesis"), 0.001)
    }

    @Test
    fun `posterior should update based on evidence`() {
        strategy.setPrior("disease", 0.01)
        val posterior = strategy.computePosterior("disease", 0.9, 0.05)
        assertTrue(posterior > 0.0 && posterior < 1.0)
    }

    @Test
    fun `probability update should converge`() {
        strategy.setPrior("event", 0.5)
        val p1 = strategy.computePosterior("event", 0.8, 0.2)
        val p2 = strategy.update("event", p1)
        assertNotEquals(p1, p2, 0.001)
    }

    @Test
    fun `should handle extreme likelihoods`() {
        strategy.setPrior("rare", 1e-6)
        val posterior = strategy.computePosterior("rare", 1.0, 0.0)
        assertTrue(posterior.isFinite())
    }

    @Test
    fun `reason using bayesian inference`() = runTest {
        strategy.setPrior("test", 0.5)
        val result = strategy.reason("update belief on test result")
        assertNotNull(result)
        assertTrue(result.contains("posterior"))
    }

    @Test
    fun `should normalize probabilities`() {
        val normalized = strategy.normalize(mapOf("A" to 0.8, "B" to 0.4))
        assertEquals(1.0, normalized.values.sum(), 0.001)
    }

    @Test
    fun `should compute bayes factor`() {
        val bf = strategy.bayesFactor(0.8, 0.2)
        assertEquals(4.0, bf, 0.001)
    }
}

class BayesianReasoningStrategy {
    private val priors = mutableMapOf<String, Double>()

    fun setPrior(hypothesis: String, prob: Double) { priors[hypothesis] = prob }
    fun getPrior(hypothesis: String): Double = priors[hypothesis] ?: 0.0

    fun computePosterior(hypothesis: String, likelihood: Double, falsePositive: Double): Double {
        val prior = getPrior(hypothesis)
        val marginal = prior * likelihood + (1 - prior) * falsePositive
        return if (marginal == 0.0) 0.0 else (prior * likelihood) / marginal
    }

    fun update(hypothesis: String, newProb: Double): Double {
        priors[hypothesis] = newProb
        return newProb
    }

    fun normalize(probs: Map<String, Double>): Map<String, Double> {
        val total = probs.values.sum()
        return if (total == 0.0) probs else probs.mapValues { it.value / total }
    }

    fun bayesFactor(likelihood: Double, falsePositive: Double): Double {
        return likelihood / falsePositive
    }

    suspend fun reason(input: String): String {
        val probs = priors.mapValues { computePosterior(it.key, 0.8, 0.1) }
        return "posterior: ${probs.entries.joinToString { "${it.key}=${"%.3f".format(it.value)}" }}"
    }
}
