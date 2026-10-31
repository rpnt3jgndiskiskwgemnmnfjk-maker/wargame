package afwi.ai

import afwi.engine.Engine
import afwi.engine.GameState
import afwi.engine.Phase
import afwi.engine.Side
import afwi.engine.viewFor

/** AI 同士の自動対戦。テストとバランス確認に使う。 */
object Match {
    data class Result(val final: GameState, val steps: Int) {
        val winner: Side? get() = final.winner
        val finished: Boolean get() = final.phase == Phase.GAME_OVER
    }

    fun play(
        engine: Engine,
        campaignId: String,
        seed: Long,
        agents: Map<Side, Agent>,
        maxSteps: Int = 5000,
    ): Result {
        var s = engine.newGame(campaignId, seed)
        var steps = 0
        while (s.phase != Phase.GAME_OVER && steps < maxSteps) {
            val side = engine.actors(s).first()
            // AI には自分の視点だけを渡す
            val action = agents.getValue(side).choose(engine.viewFor(s, side))
            s = engine.apply(s, action)
            steps++
        }
        return Result(s, steps)
    }
}
