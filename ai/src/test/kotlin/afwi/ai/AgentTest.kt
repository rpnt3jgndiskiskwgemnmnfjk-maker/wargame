package afwi.ai

import afwi.engine.Engine
import afwi.engine.Side
import kotlin.test.Test
import kotlin.test.assertTrue

class AgentTest {
    private val engine = Engine()

    private fun winRate(make: (Long) -> Map<Side, Agent>, side: Side, games: Int = 200): Double {
        var wins = 0
        repeat(games) { i ->
            val r = Match.play(engine, "C1", seed = 10_000L + i, agents = make(i.toLong()))
            assertTrue(r.finished, "game $i did not finish in ${r.steps} steps")
            if (r.winner == side) wins++
        }
        return wins.toDouble() / games
    }

    @Test
    fun `normal AI games against itself always finish`() {
        repeat(200) { i ->
            val r = Match.play(
                engine, "C1", seed = i.toLong(),
                agents = mapOf(Side.US to HeuristicAgent(engine), Side.PRC to HeuristicAgent(engine)),
            )
            assertTrue(r.finished, "seed $i did not finish in ${r.steps} steps")
        }
    }

    @Test
    fun `normal AI beats a random player as either side`() {
        val usRate = winRate({ mapOf(Side.US to HeuristicAgent(engine, seed = it), Side.PRC to RandomAgent(it)) }, Side.US)
        val prcRate = winRate({ mapOf(Side.US to RandomAgent(it), Side.PRC to HeuristicAgent(engine, seed = it)) }, Side.PRC)
        println("normal vs random: US $usRate / PRC $prcRate")
        assertTrue(usRate >= 0.7, "US win rate $usRate")
        assertTrue(prcRate >= 0.7, "PRC win rate $prcRate")
    }

    @Test
    fun `normal AI is stronger than easy AI`() {
        val rate = winRate(
            { mapOf(Side.US to HeuristicAgent(engine, Difficulty.NORMAL, it), Side.PRC to HeuristicAgent(engine, Difficulty.EASY, it)) },
            Side.US,
        )
        val rate2 = winRate(
            { mapOf(Side.US to HeuristicAgent(engine, Difficulty.EASY, it), Side.PRC to HeuristicAgent(engine, Difficulty.NORMAL, it)) },
            Side.PRC,
        )
        println("normal vs easy: as US $rate / as PRC $rate2")
        assertTrue((rate + rate2) / 2 > 0.55, "normal vs easy ${(rate + rate2) / 2}")
    }
}
