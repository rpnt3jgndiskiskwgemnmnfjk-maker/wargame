package afwi.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3EngineTest {
    private val engine = Engine()

    private fun GameState.sqByDef(defId: String) = squadrons.values.single { it.defId == defId }
    private fun GameState.withToken(id: String, f: (TokenState) -> TokenState) =
        copy(tokens = LinkedHashMap(tokens).also { it[id] = f(it.getValue(id)) })
    private fun GameState.withPlayer(side: Side, f: (PlayerState) -> PlayerState) =
        copy(players = players + (side to f(player(side))))
    private fun GameState.textsFor(side: Side, from: Int) = log.drop(from).mapNotNull { it.textFor(side) }

    /** 長期戦を、指定した飛行隊・支援カードで第1 ATO の米の手番まで進める。 */
    private fun startC3(
        seed: Long,
        us: List<String> = listOf("US-SQ-480FS"),
        prc: List<String> = listOf("PRC-SQ-J10"),
        usCards: Set<String> = emptySet(),
        prcCards: Set<String> = emptySet(),
        usMission: MissionId = MissionId.ATTRITION,
        prcMission: MissionId = MissionId.ATTRITION,
    ): GameState {
        var s = engine.newGame("C3", seed)
        s = engine.apply(s, Action.ChooseMission(Side.US, usMission))
        s = engine.apply(s, Action.ChooseMission(Side.PRC, prcMission))
        s = engine.apply(s, Action.ChoosePosture(Side.US, "US-STANDARD"))
        s = engine.apply(s, Action.ChoosePosture(Side.PRC, "PRC-STANDARD"))
        s = engine.apply(s, Action.Draft(Side.US, us.map { s.sqByDef(it).id }.toSet()))
        s = engine.apply(s, Action.Draft(Side.PRC, prc.map { s.sqByDef(it).id }.toSet()))
        s = engine.apply(s, Action.ChooseEnablers(Side.US, usCards))
        s = engine.apply(s, Action.ChooseEnablers(Side.PRC, prcCards))
        s = engine.apply(s, Action.Deploy(Side.US, s.squadrons.values.filter { it.owner == Side.US && it.drafted }.associate { it.id to BaseKind.AIRBASE }))
        s = engine.apply(s, Action.Deploy(Side.PRC, s.squadrons.values.filter { it.owner == Side.PRC && it.drafted }.associate { it.id to BaseKind.AIRBASE }))
        s = engine.apply(s, Action.SubmitBid(Side.US))
        s = engine.apply(s, Action.SubmitBid(Side.PRC))
        if (s.phase == Phase.GAME_OVER) return s
        return engine.apply(s, Action.ChooseFirst(s.initiativeWinner!!, Side.US))
    }

    @Test
    fun `prolonged combat starts with a secret mission choice`() {
        var s = engine.newGame("C3", 1)
        assertEquals(Phase.MISSION, s.phase)
        assertEquals(5, engine.legalActions(s, Side.US).size)
        s = engine.apply(s, Action.ChooseMission(Side.US, MissionId.NON_KINETIC_DOMINANCE))
        assertNull(engine.viewFor(s, Side.PRC).opponent.mission)
        s = engine.apply(s, Action.ChooseMission(Side.PRC, MissionId.THREE_DOMINANCES))
        assertEquals(Phase.POSTURE, s.phase)
        assertEquals(5, engine.legalActions(s, Side.PRC).size)
    }

    @Test
    fun `enabler selection respects the posture limit and the PLAAF restriction`() {
        var s = engine.newGame("C3", 2)
        s = engine.apply(s, Action.ChooseMission(Side.US, MissionId.ATTRITION))
        s = engine.apply(s, Action.ChooseMission(Side.PRC, MissionId.ATTRITION))
        s = engine.apply(s, Action.ChoosePosture(Side.US, "US-STANDARD"))
        s = engine.apply(s, Action.ChoosePosture(Side.PRC, "PRC-PLAAF"))
        s = engine.apply(s, Action.Draft(Side.US, emptySet()))
        s = engine.apply(s, Action.Draft(Side.PRC, emptySet()))
        assertEquals(Phase.ENABLERS, s.phase)
        assertEquals(34, engine.enablerPool(s, Side.US).size)
        assertTrue(engine.enablerPool(s, Side.PRC).all { it.family == "PLAAF" })
        val seven = engine.enablerPool(s, Side.US).take(7).map { it.id }.toSet()
        assertFalse(engine.isLegal(s, Action.ChooseEnablers(Side.US, seven)))
        assertTrue(engine.isLegal(s, Action.ChooseEnablers(Side.US, seven.take(6).toSet())))
        assertFalse(engine.isLegal(s, Action.ChooseEnablers(Side.PRC, setOf("PRC-EN-11"))))
        // 相手の手札の中身は視点に出ない
        s = engine.apply(s, Action.ChooseEnablers(Side.US, seven.take(6).toSet()))
        assertEquals(6, engine.viewFor(s, Side.PRC).opponent.handSize)
    }

    @Test
    fun `sacrificed cards add to the bid and leave the hand`() {
        var s = engine.newGame("C3", 3)
        s = engine.apply(s, Action.ChooseMission(Side.US, MissionId.ATTRITION))
        s = engine.apply(s, Action.ChooseMission(Side.PRC, MissionId.ATTRITION))
        s = engine.apply(s, Action.ChoosePosture(Side.US, "US-STANDARD"))
        s = engine.apply(s, Action.ChoosePosture(Side.PRC, "PRC-STANDARD"))
        s = engine.apply(s, Action.Draft(Side.US, emptySet()))
        s = engine.apply(s, Action.Draft(Side.PRC, emptySet()))
        s = engine.apply(s, Action.ChooseEnablers(Side.US, setOf("US-EN-21", "US-EN-12", "US-EN-04")))
        s = engine.apply(s, Action.ChooseEnablers(Side.PRC, emptySet()))
        s = engine.apply(s, engine.legalActions(s, Side.US).first())
        s = engine.apply(s, engine.legalActions(s, Side.PRC).first())
        assertEquals(8, engine.legalActions(s, Side.US).size)
        s = engine.apply(s, Action.SubmitBid(Side.US, setOf("US-EN-21", "US-EN-12")))
        s = engine.apply(s, Action.SubmitBid(Side.PRC))
        assertEquals(listOf("US-EN-04"), s.player(Side.US).hand)
        assertTrue(s.log.any { it.textFor(Side.US)?.contains("+2枚") == true })
        // 差し出したカードは使用済みにならない
        assertTrue(s.player(Side.US).spent.isEmpty())
    }

    @Test
    fun `playing a card spends single use cards and modifiers last for the ATO`() {
        var s = startC3(4, usCards = setOf("US-EN-17", "US-EN-27"))
        if (s.phase != Phase.TURNS) return
        s = engine.apply(s, Action.PlayEnabler(Side.US, "US-EN-17"))
        assertTrue(ModKind.ENEMY_ATTACK_DIS in s.player(Side.US).modifiers)
        assertTrue("US-EN-17" in s.player(Side.US).spent)
        // 支援カードは1手番に1枚
        assertTrue(engine.legalActions(s, Side.US).none { it is Action.PlayEnabler })
        s = engine.apply(s, Action.EndTurn(Side.US))
        s = engine.apply(s, Action.Pass(Side.PRC))
        val ship = engine.legalActions(s, Side.US).filterIsInstance<Action.PlayEnabler>()
        assertEquals(5, ship.size)
        s = engine.apply(s, ship.first { it.zone == Zone.Band(2) })
        val ddg = s.tokens.values.single { it.typeId == "US-DDG" }
        assertEquals(Zone.Band(2), ddg.zone)
        assertEquals(listOf(1), s.player(Side.US).enablerTokens)
    }

    @Test
    fun `a space card can be cancelled by counter space`() {
        var s = startC3(5, usCards = setOf("US-EN-21"), prcCards = setOf("PRC-EN-23"))
        if (s.phase != Phase.TURNS) return
        s = engine.apply(s, Action.PlayEnabler(Side.US, "US-EN-21"))
        val p = assertNotNull(s.pending)
        assertEquals(WindowKind.CARD_CANCEL, p.kind)
        assertEquals(setOf(Side.PRC), engine.actors(s))
        s = engine.apply(s, Action.Respond(Side.PRC, cardId = "PRC-EN-23"))
        assertNull(s.pending)
        assertTrue(s.log.any { it.textFor(Side.US)?.contains("打ち消された") == true })
        assertTrue(s.player(Side.US).cardPlays.isEmpty())
        assertEquals(Side.US, s.turn.active)
    }

    @Test
    fun `ships can declare missile defense and make the strike roll at disadvantage`() {
        var s = startC3(6, us = listOf("US-SQ-391FS"), prc = listOf("PRC-SQ-J10"))
        if (s.phase != Phase.TURNS) return
        val f15e = s.tokens.values.first { it.squadronId == s.sqByDef("US-SQ-391FS").id }.id
        s = s.withToken(f15e) { it.copy(zone = Zone.Band(5)) }
            .copy(tokens = LinkedHashMap(s.tokens).also {
                it[f15e] = it.getValue(f15e).copy(zone = Zone.Band(5))
                it["PRC-E99"] = TokenState("PRC-E99", "PRC-DDG", Side.PRC, null, zone = Zone.Band(4))
            })
        s = engine.apply(s, Action.StrikeSquadron(Side.US, f15e, s.sqByDef("PRC-SQ-J10").id))
        val p = assertNotNull(s.pending)
        assertEquals(WindowKind.MISSILE_DEFENSE, p.kind)
        assertEquals(listOf("PRC-E99"), p.shipOptions)
        val before = s.log.size
        s = engine.apply(s, Action.Respond(Side.PRC, shipId = "PRC-E99"))
        assertEquals(1, s.tokens.getValue("PRC-E99").salvosUsed)
        assertTrue(s.textsFor(Side.US, before).any { "ミサイル防衛" in it && "不利" in it })
        assertNull(s.attack)
    }

    @Test
    fun `red horse cancels the damage of a ballistic missile`() {
        var checked = false
        for (seed in 1L..60L) {
            var s = startC3(seed, us = listOf("US-SQ-480FS"), usCards = setOf("US-EN-28"), prcCards = setOf("PRC-EN-11"))
            if (s.phase != Phase.TURNS) continue
            s = engine.apply(s, Action.Pass(Side.US))
            s = engine.apply(s, Action.PlayEnabler(Side.PRC, "PRC-EN-11"))
            val p = s.pending ?: continue
            assertEquals(WindowKind.DAMAGE_CANCEL, p.kind)
            s = engine.apply(s, Action.Respond(Side.US, cardId = "US-EN-28"))
            assertEquals(0, s.sqByDef("US-SQ-480FS").damage)
            assertEquals(0, s.player(Side.US).airbaseBlocksHit)
            checked = true
            break
        }
        assertTrue(checked)
    }

    @Test
    fun `personnel recovery returns a shot down aircraft and removes the kill`() {
        var checked = false
        for (seed in 1L..200L) {
            var s = startC3(seed, usCards = setOf("US-EN-02"))
            if (s.phase != Phase.TURNS) continue
            s = engine.apply(s, Action.Pass(Side.US))
            val j10 = s.tokens.values.first { it.squadronId == s.sqByDef("PRC-SQ-J10").id }.id
            val f16 = s.tokens.values.first { it.squadronId == s.sqByDef("US-SQ-480FS").id }.id
            s = s.withToken(j10) { it.copy(zone = Zone.Band(3)) }.withToken(f16) { it.copy(zone = Zone.Band(3), acquired = true) }
            s = engine.apply(s, Action.ShootToken(Side.PRC, j10, f16))
            val p = s.pending ?: continue
            assertEquals(WindowKind.AIRCRAFT_LOST, p.kind)
            assertEquals(1, s.player(Side.PRC).captured.size)
            s = engine.apply(s, Action.Respond(Side.US, cardId = "US-EN-02"))
            assertEquals(Zone.Band(1), s.tokens.getValue(f16).zone)
            assertTrue(s.player(Side.PRC).captured.isEmpty())
            checked = true
            break
        }
        assertTrue(checked)
    }

    @Test
    fun `non kinetic and three dominances score cyber cards and ships at the end of the ATO`() {
        var s = startC3(
            7, usCards = setOf("US-EN-23"), prcCards = setOf("PRC-EN-28"),
            usMission = MissionId.NON_KINETIC_DOMINANCE, prcMission = MissionId.THREE_DOMINANCES,
        )
        if (s.phase != Phase.TURNS) return
        val usCyber = s.player(Side.US).cyber
        s = engine.apply(s, Action.PlayEnabler(Side.US, "US-EN-23"))
        s = engine.apply(s, Action.EndTurn(Side.US))
        s = engine.apply(s, engine.legalActions(s, Side.PRC).filterIsInstance<Action.PlayEnabler>().first())
        val prcCyber = s.player(Side.PRC).cyber
        s = engine.apply(s, Action.EndTurn(Side.PRC))
        s = engine.apply(s, Action.Pass(Side.US))
        s = engine.apply(s, Action.Pass(Side.PRC))
        assertEquals(2, s.ato)
        // 米: サイバー値×3 + サイバーカード1枚
        assertEquals(3 * usCyber + 1, s.player(Side.US).vp)
        // 中: サイバー値×2 + 盤上の艦艇1隻×3 + 海軍カード1枚 + 機体がそろった飛行隊1個
        assertEquals(2 * prcCyber + 3 + 1 + 1, s.player(Side.PRC).vp)
        // 単発カードは次の ATO で選べない
        assertFalse(engine.enablerPool(s.copy(phase = Phase.ENABLERS), Side.US).any { it.id == "US-EN-23" })
    }

    @Test
    fun `random prolonged combat games finish without leaking the opponent's hand`() {
        repeat(25) { seed ->
            val rnd = Random(seed)
            var s = engine.newGame("C3", seed.toLong())
            var steps = 0
            while (s.phase != Phase.GAME_OVER && steps++ < 20000) {
                val side = engine.actors(s).first()
                val acts = engine.legalActions(s, side)
                assertTrue(acts.isNotEmpty(), "no legal action in ${s.phase}")
                val end = acts.filter { it is Action.Pass || it is Action.EndTurn }
                val pick = if (end.isNotEmpty() && rnd.nextInt(3) == 0) end.first() else acts[rnd.nextInt(acts.size)]
                s = engine.apply(s, pick)
                assertTrue(s.players.values.all { it.cyber in 0..4 })
                val v = engine.viewFor(s, Side.US)
                if (s.pending != null && s.pending!!.decider != Side.US) assertNull(v.pending)
            }
            assertEquals(Phase.GAME_OVER, s.phase, "seed $seed did not finish")
        }
    }
}
