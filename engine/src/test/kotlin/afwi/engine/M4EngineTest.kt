package afwi.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M4EngineTest {
    private val engine = Engine()

    private fun GameState.sqByDef(defId: String) = squadrons.values.single { it.defId == defId }
    private fun GameState.withPlayer(side: Side, f: (PlayerState) -> PlayerState) =
        copy(players = players + (side to f(player(side))))

    /** 指定キャンペーンを、両軍ともに1個飛行隊で第1 ATO の手番まで進める。 */
    private fun toTurns(campaign: String, seed: Long, us: String = "US-SQ-480FS", prc: String = "PRC-SQ-J10"): GameState {
        var s = engine.newGame(campaign, seed)
        s = engine.apply(s, Action.ChooseMission(Side.US, MissionId.ATTRITION))
        s = engine.apply(s, Action.ChooseMission(Side.PRC, MissionId.ATTRITION))
        s = engine.apply(s, Action.ChoosePosture(Side.US, "US-STANDARD"))
        s = engine.apply(s, Action.ChoosePosture(Side.PRC, "PRC-STANDARD"))
        s = engine.apply(s, Action.Draft(Side.US, setOf(s.sqByDef(us).id)))
        s = engine.apply(s, Action.Draft(Side.PRC, setOf(s.sqByDef(prc).id)))
        s = engine.apply(s, Action.ChooseEnablers(Side.US, emptySet()))
        s = engine.apply(s, Action.ChooseEnablers(Side.PRC, emptySet()))
        s = engine.apply(s, engine.legalActions(s, Side.US).first())
        s = engine.apply(s, engine.legalActions(s, Side.PRC).first())
        s = engine.apply(s, Action.SubmitBid(Side.US))
        s = engine.apply(s, Action.SubmitBid(Side.PRC))
        if (s.phase == Phase.GAME_OVER) return s
        return engine.apply(s, Action.ChooseFirst(s.initiativeWinner!!, Side.US))
    }

    private fun draftableDefs(s: GameState, side: Side): Set<String> =
        engine.legalActions(s, side).filterIsInstance<Action.Draft>()
            .flatMap { it.squadronIds }.map { s.squadrons.getValue(it).defId }.toSet()

    @Test
    fun `the world watches bans bombers and long range missile and SOF cards`() {
        var s = engine.newGame("C4", 1)
        s = engine.apply(s, Action.ChooseMission(Side.US, MissionId.ATTRITION))
        s = engine.apply(s, Action.ChooseMission(Side.PRC, MissionId.ATTRITION))
        s = engine.apply(s, Action.ChoosePosture(Side.US, "US-STANDOFF"))
        s = engine.apply(s, Action.ChoosePosture(Side.PRC, "PRC-STANDARD"))
        val us = draftableDefs(s, Side.US)
        assertTrue("US-SQ-69BS" !in us && "US-SQ-23BS" !in us)
        assertTrue("PRC-SQ-H6K" !in draftableDefs(s, Side.PRC))
        s = engine.apply(s, Action.Draft(Side.US, emptySet()))
        s = engine.apply(s, Action.Draft(Side.PRC, emptySet()))
        val usPool = engine.enablerPool(s, Side.US).map { it.id }
        val prcPool = engine.enablerPool(s, Side.PRC)
        assertTrue("US-EN-31" !in usPool && "US-EN-32" !in usPool && "US-EN-04" !in usPool)
        assertTrue(prcPool.none { "PLARF" in it.tags || "SOF" in it.tags })
    }

    @Test
    fun `air kills give at most two bonus points per ATO in the world watches`() {
        var s = toTurns("C4", 2)
        if (s.phase != Phase.TURNS) return
        val kills = List(3) { CaptureRecord(CaptureKind.TOKEN, ato = 1, typeId = "J-10", vpClass = VpClass.OTHER, inAir = true) }
        s = s.withPlayer(Side.US) { it.copy(captured = kills) }
        s = engine.apply(s, Action.Pass(Side.US))
        s = engine.apply(s, Action.Pass(Side.PRC))
        // 消耗 3機×1 + 空中撃破ボーナス(上限2)
        assertEquals(5, s.player(Side.US).vp)
    }

    @Test
    fun `in the world watches the side with more kills takes the second ATO initiative`() {
        var s = toTurns("C4", 3)
        if (s.phase != Phase.TURNS) return
        val loser = s.initiativeHistory.single().opponent
        s = s.withPlayer(loser) {
            it.copy(captured = listOf(CaptureRecord(CaptureKind.TOKEN, ato = 1, typeId = "F-16C", vpClass = VpClass.OTHER, inAir = true)))
        }
        s = engine.apply(s, Action.Pass(Side.US))
        s = engine.apply(s, Action.Pass(Side.PRC))
        s = engine.apply(s, Action.ChoosePosture(Side.US, "US-STANDARD"))
        s = engine.apply(s, Action.ChoosePosture(Side.PRC, "PRC-STANDARD"))
        s = engine.apply(s, Action.Draft(Side.US, emptySet()))
        s = engine.apply(s, Action.Draft(Side.PRC, emptySet()))
        s = engine.apply(s, Action.ChooseEnablers(Side.US, emptySet()))
        s = engine.apply(s, Action.ChooseEnablers(Side.PRC, emptySet()))
        s = engine.apply(s, Action.Deploy(Side.US, emptyMap()))
        s = engine.apply(s, Action.Deploy(Side.PRC, emptyMap()))
        if (s.phase == Phase.GAME_OVER) return
        assertEquals(Phase.CHOOSE_ORDER, s.phase)
        assertEquals(loser, s.initiativeWinner)
    }

    @Test
    fun `reserves bans fifth generation fighters bombers and the long range SAM`() {
        var s = engine.newGame("C5", 4)
        s = engine.apply(s, Action.ChooseMission(Side.US, MissionId.ATTRITION))
        s = engine.apply(s, Action.ChooseMission(Side.PRC, MissionId.ATTRITION))
        s = engine.apply(s, Action.ChoosePosture(Side.US, "US-STANDARD"))
        s = engine.apply(s, Action.ChoosePosture(Side.PRC, "PRC-ADA"))
        val us = draftableDefs(s, Side.US)
        val prc = draftableDefs(s, Side.PRC)
        assertTrue(listOf("US-SQ-90FS", "US-SQ-34FS", "US-SQ-69BS").none { it in us })
        assertTrue(listOf("PRC-SQ-J20", "PRC-SQ-H6K", "PRC-SQ-SA21").none { it in prc })
        assertTrue("PRC-SQ-HQ9" in prc)
    }

    @Test
    fun `reserves adds one point per undamaged squadron card at the end`() {
        var s = toTurns("C5", 5)
        if (s.phase != Phase.TURNS) return
        val j10 = s.sqByDef("PRC-SQ-J10")
        s = s.copy(squadrons = LinkedHashMap(s.squadrons).also { it[j10.id] = j10.copy(damage = 1) })
        s = engine.apply(s, Action.Pass(Side.US))
        s = engine.apply(s, Action.Pass(Side.PRC))
        // 第2 ATO は何もせずに終える
        s = engine.apply(s, Action.ChoosePosture(Side.US, "US-STANDARD"))
        s = engine.apply(s, Action.ChoosePosture(Side.PRC, "PRC-STANDARD"))
        s = engine.apply(s, Action.Draft(Side.US, emptySet()))
        s = engine.apply(s, Action.Draft(Side.PRC, emptySet()))
        s = engine.apply(s, Action.ChooseEnablers(Side.US, emptySet()))
        s = engine.apply(s, Action.ChooseEnablers(Side.PRC, emptySet()))
        s = engine.apply(s, Action.Deploy(Side.US, emptyMap()))
        s = engine.apply(s, Action.Deploy(Side.PRC, emptyMap()))
        s = engine.apply(s, Action.SubmitBid(Side.US))
        s = engine.apply(s, Action.SubmitBid(Side.PRC))
        if (s.phase == Phase.GAME_OVER) return
        s = engine.apply(s, Action.ChooseFirst(s.initiativeWinner!!, Side.US))
        s = engine.apply(s, Action.Pass(Side.US))
        s = engine.apply(s, Action.Pass(Side.PRC))
        assertEquals(Phase.GAME_OVER, s.phase)
        // 米は使える6枚がすべて無傷、中は使える7枚のうち J-10 が損害あり
        assertEquals(6, s.player(Side.US).vp)
        assertEquals(6, s.player(Side.PRC).vp)
    }

    @Test
    fun `a saved game reloads to the same state and continues identically`() {
        repeat(10) { seed ->
            val rnd = Random(seed)
            var s = engine.newGame(listOf("C1", "C2", "C3", "C4", "C5")[seed % 5], seed.toLong())
            var steps = 0
            while (s.phase != Phase.GAME_OVER && steps++ < 3000) {
                val side = engine.actors(s).first()
                val acts = engine.legalActions(s, side)
                val pick = acts[rnd.nextInt(acts.size)]
                if (steps % 37 == 0) {
                    val text = engine.save(s, mapOf("mode" to "cpu"))
                    val loaded = assertNotNull(SavedGame.decodeOrNull(text, engine.content))
                    assertEquals(s, loaded.state)
                    assertEquals("cpu", loaded.meta["mode"])
                    assertEquals(engine.apply(s, pick), engine.apply(loaded.state, pick))
                }
                s = engine.apply(s, pick)
            }
        }
    }

    @Test
    fun `broken or incompatible save data is rejected`() {
        assertNull(SavedGame.decodeOrNull("not json", engine.content))
        val text = engine.save(engine.newGame("C1", 1)).replace("\"format\":1", "\"format\":99")
        assertNull(SavedGame.decodeOrNull(text, engine.content))
    }
}
