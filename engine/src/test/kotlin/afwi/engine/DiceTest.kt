package afwi.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiceTest {
    @Test
    fun `advantage and disadvantage cancel and do not stack`() {
        assertEquals(RollMode.ADVANTAGE, resolveMode(advantage = true, disadvantage = false))
        assertEquals(RollMode.DISADVANTAGE, resolveMode(advantage = false, disadvantage = true))
        assertEquals(RollMode.NORMAL, resolveMode(advantage = true, disadvantage = true))
        assertEquals(RollMode.NORMAL, resolveMode(advantage = false, disadvantage = false))
    }

    @Test
    fun `d4 rolls stay between 1 and 4`() {
        val rng = Rng(123)
        repeat(10_000) { assertTrue(rng.d4() in 1..4) }
    }

    @Test
    fun `success rates match the theoretical values for 2 or more`() {
        val n = 40_000
        fun rate(mode: RollMode): Double {
            val rng = Rng(2026)
            return (1..n).count { rng.roll(mode).total >= 2 }.toDouble() / n
        }
        assertEquals(0.75, rate(RollMode.NORMAL), 0.01)
        assertEquals(0.9375, rate(RollMode.ADVANTAGE), 0.01)
        assertEquals(0.5625, rate(RollMode.DISADVANTAGE), 0.01)
    }

    @Test
    fun `combinations with repetition cover single and paired acquisition attempts`() {
        assertEquals(listOf(listOf("a"), listOf("b")), combinationsWithRepetition(listOf("a", "b"), 1))
        assertEquals(
            setOf(listOf("a"), listOf("a", "a"), listOf("a", "b"), listOf("b"), listOf("b", "b")),
            combinationsWithRepetition(listOf("a", "b"), 2).toSet(),
        )
    }
}
