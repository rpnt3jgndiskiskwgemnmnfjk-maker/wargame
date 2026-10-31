package afwi.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReplayTest {
    private val engine = Engine()

    @Test
    fun `replaying a record reproduces every state of the game`() {
        for (campaign in listOf("C1", "C3", "C4")) {
            val rnd = Random(campaign.hashCode())
            val seed = 77L
            var s = engine.newGame(campaign, seed)
            var record = GameRecord(campaign, seed)
            val states = mutableListOf(s)
            var steps = 0
            while (s.phase != Phase.GAME_OVER && steps++ < 4000) {
                val acts = engine.legalActions(s, engine.actors(s).first())
                val a = acts[rnd.nextInt(acts.size)]
                s = engine.apply(s, a)
                record = record.plus(a)
                states += s
            }
            val replayed = engine.replay(record)
            assertEquals(states, replayed)
            // 記録は保存データに入れても同じ形で読み戻せる
            val loaded = assertNotNull(SavedGame.decodeOrNull(engine.save(s, record = record), engine.content))
            assertEquals(record, loaded.record)
        }
    }

    @Test
    fun `the reveal-all view opens hidden information`() {
        var s = engine.newGame("C3", 9)
        s = engine.apply(s, Action.ChooseMission(Side.US, MissionId.INTERDICTION))
        s = engine.apply(s, Action.ChooseMission(Side.PRC, MissionId.ATTRITION))
        val hidden = engine.viewFor(s, Side.US)
        val open = engine.viewFor(s, Side.US, revealAll = true)
        assertEquals(null, hidden.opponent.mission)
        assertEquals(MissionId.ATTRITION, open.opponent.mission)
        assertTrue(open.legalActions.isEmpty())
        assertTrue(open.squadrons.all { it.def != null })
    }
}
