package afwi.ai

import afwi.engine.Engine
import afwi.engine.Side
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * 重みの候補を、既定の重みの AI と両陣営入れ替えで対戦させる（調整用。結果を表示するだけ）。
 * 時間がかかるため通常のテストからは外している。実行: ./gradlew :ai:tuning
 */
@Tag("tuning")
class TuningTest {
    @Test
    fun `compare weight candidates`() {
        val engine = Engine()
        val base = Weights()
        // 試したい候補をここに並べる
        val candidates = mapOf(
            "cautious" to base.copy(riskAcquired = 0.6, riskUnseen = 0.25, winchesterScale = 1.0),
            "lowEnd" to base.copy(endThreshold = 0.0, moveCost = 0.01),
        )
        for ((name, cand) in candidates) {
            var wins = 0; var losses = 0; var games = 0
            for (campaign in listOf("C1", "C2", "C3")) {
                repeat(80) { i ->
                    for (candSide in Side.entries) {
                        val agents = mapOf(
                            candSide to HeuristicAgent(engine, seed = i.toLong(), w = cand),
                            candSide.opponent to HeuristicAgent(engine, seed = i + 1000L, w = base),
                        )
                        val r = Match.play(engine, campaign, 90_000L + i, agents, 20000)
                        games++
                        if (r.winner == candSide) wins++ else if (r.winner == candSide.opponent) losses++
                    }
                }
            }
            println("TUNE $name wins=$wins losses=$losses games=$games rate=${"%.3f".format(wins.toDouble() / (wins + losses))}")
        }
    }
}
