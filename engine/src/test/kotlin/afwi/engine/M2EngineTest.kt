package afwi.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M2EngineTest {
    private val engine = Engine()

    /** 態勢を自由に選べる試験用キャンペーン（2 ATO、毎回入札）。 */
    private val openEngine = Engine(
        engine.content.copy(
            campaigns = engine.content.campaigns + CampaignDef(
                id = "T", nameJa = "試験", descriptionJa = "", atoCount = 2, mission = MissionId.ATTRITION,
            ),
        ),
    )

    private fun GameState.sqByDef(defId: String) = squadrons.values.single { it.defId == defId }
    private fun GameState.withToken(id: String, f: (TokenState) -> TokenState) =
        copy(tokens = LinkedHashMap(tokens).also { it[id] = f(it.getValue(id)) })

    private fun draftOf(s: GameState, side: Side, vararg defIds: String) =
        Action.Draft(side, defIds.map { s.sqByDef(it).id }.toSet())

    /** 大会を、指定した飛行隊で第1 ATO の手番開始まで進める。 */
    private fun startC2(seed: Long, us: List<String>, prc: List<String>, e: Engine = engine, campaign: String = "C2"): GameState {
        var s = e.newGame(campaign, seed)
        if (s.phase == Phase.POSTURE) {
            s = e.apply(s, Action.ChoosePosture(Side.US, "US-STANDARD"))
            s = e.apply(s, Action.ChoosePosture(Side.PRC, "PRC-STANDARD"))
        }
        s = e.apply(s, draftOf(s, Side.US, *us.toTypedArray()))
        s = e.apply(s, draftOf(s, Side.PRC, *prc.toTypedArray()))
        s = e.apply(s, Action.Deploy(Side.US, s.squadrons.values.filter { it.owner == Side.US && it.drafted }.associate { it.id to BaseKind.AIRBASE }))
        s = e.apply(s, Action.Deploy(Side.PRC, s.squadrons.values.filter { it.owner == Side.PRC && it.drafted }.associate { it.id to BaseKind.AIRBASE }))
        s = e.apply(s, Action.SubmitBid(Side.US))
        s = e.apply(s, Action.SubmitBid(Side.PRC))
        if (s.phase == Phase.GAME_OVER) return s
        return e.apply(s, Action.ChooseFirst(s.initiativeWinner!!, Side.US))
    }

    @Test
    fun `tournament starts at the draft with the standard posture already chosen`() {
        val s = engine.newGame("C2", 3)
        assertEquals(Phase.DRAFT, s.phase)
        assertEquals("US-STANDARD", s.player(Side.US).posture)
        assertEquals(20, s.squadrons.size)
        // 10 枚から 0〜4 枚を選ぶ組み合わせ = 1 + 10 + 45 + 120 + 210
        assertEquals(386, engine.legalActions(s, Side.US).size)
    }

    @Test
    fun `squadron ids do not reveal which unit they are`() {
        val a = engine.newGame("C2", 1)
        val b = engine.newGame("C2", 2)
        val differs = a.squadrons.keys.any { a.squadrons.getValue(it).defId != b.squadrons.getValue(it).defId }
        assertTrue(differs)
    }

    @Test
    fun `only drafted squadrons are deployed and the opponent sees only how many`() {
        var s = engine.newGame("C2", 5)
        s = engine.apply(s, draftOf(s, Side.US, "US-SQ-90FS", "US-SQ-960AACS"))
        s = engine.apply(s, draftOf(s, Side.PRC, "PRC-SQ-J20", "PRC-SQ-J16", "PRC-SQ-HQ9"))
        assertEquals(Phase.DEPLOY, s.phase)
        val deploy = engine.legalActions(s, Side.US).filterIsInstance<Action.Deploy>()
        assertTrue(deploy.all { it.placements.size == 2 })
        val usView = engine.viewFor(s, Side.US)
        val prcSquadrons = usView.squadrons.filter { it.owner == Side.PRC }
        assertEquals(3, prcSquadrons.size)
        assertTrue(prcSquadrons.all { it.def == null })
    }

    @Test
    fun `in the tournament the second ATO initiative goes to the other side without a bid`() {
        var s = startC2(11, listOf("US-SQ-480FS"), listOf("PRC-SQ-J10"))
        val first = s.initiativeHistory.single()
        s = engine.apply(s, Action.Pass(Side.US))
        s = engine.apply(s, Action.Pass(Side.PRC))
        assertEquals(2, s.ato)
        assertEquals(Phase.DRAFT, s.phase)
        s = engine.apply(s, draftOf(s, Side.US, "US-SQ-480FS"))
        s = engine.apply(s, draftOf(s, Side.PRC, "PRC-SQ-J10"))
        s = engine.apply(s, engine.legalActions(s, Side.US).first())
        s = engine.apply(s, engine.legalActions(s, Side.PRC).first())
        if (s.phase == Phase.GAME_OVER) return
        assertEquals(Phase.CHOOSE_ORDER, s.phase)
        assertEquals(first.opponent, s.initiativeWinner)
    }

    @Test
    fun `a destroyed squadron cannot be drafted in the next ATO`() {
        var s = startC2(4, listOf("US-SQ-480FS"), listOf("PRC-SQ-J10", "PRC-SQ-J16"))
        val j10 = s.sqByDef("PRC-SQ-J10")
        s = s.copy(squadrons = LinkedHashMap(s.squadrons).also { it[j10.id] = j10.copy(damage = 2) })
        s = engine.apply(s, Action.Pass(Side.US))
        s = engine.apply(s, Action.Pass(Side.PRC))
        val drafts = engine.legalActions(s, Side.PRC).filterIsInstance<Action.Draft>()
        assertTrue(drafts.none { j10.id in it.squadronIds })
        assertTrue(drafts.any { s.sqByDef("PRC-SQ-J16").id in it.squadronIds })
    }

    @Test
    fun `a bomber in the standoff can strike the enemy base six bands away`() {
        var s = startC2(8, listOf("US-SQ-69BS"), listOf("PRC-SQ-J10"))
        val b52 = s.sqByDef("US-SQ-69BS")
        val zones = engine.legalActions(s, Side.US).filterIsInstance<Action.Activate>().map { it.to }.toSet()
        assertEquals(setOf(Zone.Band(1), Zone.Standoff(Side.US)), zones)
        s = engine.apply(s, Action.Activate(Side.US, b52.id, Zone.Standoff(Side.US)))
        s = engine.apply(s, Action.EndTurn(Side.US))
        s = engine.apply(s, Action.Pass(Side.PRC))
        val strikes = engine.legalActions(s, Side.US).filterIsInstance<Action.StrikeSquadron>()
        assertEquals(setOf(s.sqByDef("PRC-SQ-J10").id), strikes.map { it.squadronId }.toSet())
    }

    @Test
    fun `explosive damage spills over to other squadrons at the airbase and then to the facilities`() {
        var checked = 0
        for (seed in 1L..400L) {
            var s = startC2(seed, listOf("US-SQ-69BS"), listOf("PRC-SQ-J10", "PRC-SQ-J16"))
            if (s.phase != Phase.TURNS) continue
            val bomber = s.tokens.values.single { it.squadronId == s.sqByDef("US-SQ-69BS").id }.id
            s = s.withToken(bomber) { it.copy(zone = Zone.Standoff(Side.US)) }
            val target = s.sqByDef("PRC-SQ-J10").id
            val before = s.log.size
            s = engine.apply(s, Action.StrikeSquadron(Side.US, bomber, target))
            val damageLine = s.log.drop(before).mapNotNull { it.textFor(Side.US) }.firstOrNull { "損害判定" in it } ?: continue
            val rolled = Regex("→ (\\d)点").find(damageLine)!!.groupValues[1].toInt()
            val dealt = s.squadrons.values.filter { it.owner == Side.PRC }.sumOf { it.damage } + s.player(Side.PRC).airbaseBlocksHit
            assertEquals(rolled, dealt, "seed $seed")
            if (rolled >= 3) {
                assertTrue(s.squadrons.getValue(target).isDestroyed)
                assertTrue(s.sqByDef("PRC-SQ-J16").damage >= 1)
            }
            checked++
        }
        assertTrue(checked > 20)
    }

    @Test
    fun `postures already used cannot be chosen again except standard`() {
        var s = openEngine.newGame("T", 2)
        assertEquals(Phase.POSTURE, s.phase)
        assertEquals(5, openEngine.legalActions(s, Side.US).size)
        s = openEngine.apply(s, Action.ChoosePosture(Side.US, "US-HEDGEHOG"))
        // 相手が選び終えるまで、選んだ態勢は相手に見えない
        assertNull(openEngine.viewFor(s, Side.PRC).opponent.posture)
        s = openEngine.apply(s, Action.ChoosePosture(Side.PRC, "PRC-ADA"))
        assertEquals("US-HEDGEHOG", openEngine.viewFor(s, Side.PRC).opponent.posture)
        // HEDGEHOG で米の基地に防空が展開している
        val ada = s.tokens.values.single { it.owner == Side.US && it.zone == Zone.AtBase }
        assertEquals("US-ADA", ada.typeId)
        assertEquals(listOf("防空の交戦域内"), openEngine.viewFor(s, Side.PRC).baseDefense.getValue(Side.US).getValue(BaseKind.AIRBASE))
        // PRC の ADA 態勢では防空の飛行隊2枚が上限外
        val drafts = openEngine.legalActions(s, Side.PRC).filterIsInstance<Action.Draft>()
        assertEquals(6, drafts.maxOf { it.squadronIds.size })
        // 次の ATO では HEDGEHOG を選べず、防空の駒は片付けられている
        s = openEngine.apply(s, draftOf(s, Side.US, "US-SQ-480FS"))
        s = openEngine.apply(s, draftOf(s, Side.PRC, "PRC-SQ-J10"))
        s = openEngine.apply(s, openEngine.legalActions(s, Side.US).first())
        s = openEngine.apply(s, openEngine.legalActions(s, Side.PRC).first())
        s = openEngine.apply(s, Action.SubmitBid(Side.US))
        s = openEngine.apply(s, Action.SubmitBid(Side.PRC))
        if (s.phase == Phase.GAME_OVER) return
        s = openEngine.apply(s, Action.ChooseFirst(s.initiativeWinner!!, Side.US))
        s = openEngine.apply(s, Action.Pass(Side.US))
        s = openEngine.apply(s, Action.Pass(Side.PRC))
        assertEquals(2, s.ato)
        val choices = openEngine.legalActions(s, Side.US).map { (it as Action.ChoosePosture).postureId }
        assertFalse("US-HEDGEHOG" in choices)
        assertTrue("US-STANDARD" in choices)
        assertEquals(Zone.Offboard, s.tokens.getValue(ada.id).zone)
    }

    @Test
    fun `surge forbids the contingency location and ace generates every aircraft from it`() {
        var s = openEngine.newGame("T", 9)
        s = openEngine.apply(s, Action.ChoosePosture(Side.US, "US-SURGE"))
        s = openEngine.apply(s, Action.ChoosePosture(Side.PRC, "PRC-STANDARD"))
        s = openEngine.apply(s, draftOf(s, Side.US, "US-SQ-480FS"))
        s = openEngine.apply(s, draftOf(s, Side.PRC, "PRC-SQ-J10"))
        assertTrue(openEngine.legalActions(s, Side.US).filterIsInstance<Action.Deploy>().all { BaseKind.CL !in it.placements.values })

        var t = openEngine.newGame("T", 9)
        t = openEngine.apply(t, Action.ChoosePosture(Side.US, "US-ACE"))
        t = openEngine.apply(t, Action.ChoosePosture(Side.PRC, "PRC-STANDARD"))
        t = openEngine.apply(t, draftOf(t, Side.US, "US-SQ-480FS"))
        t = openEngine.apply(t, draftOf(t, Side.PRC, "PRC-SQ-J10"))
        val f16 = t.sqByDef("US-SQ-480FS").id
        t = openEngine.apply(t, Action.Deploy(Side.US, mapOf(f16 to BaseKind.CL)))
        t = openEngine.apply(t, openEngine.legalActions(t, Side.PRC).first())
        t = openEngine.apply(t, Action.SubmitBid(Side.US))
        t = openEngine.apply(t, Action.SubmitBid(Side.PRC))
        if (t.phase == Phase.GAME_OVER) return
        t = openEngine.apply(t, Action.ChooseFirst(t.initiativeWinner!!, Side.US))
        t = openEngine.apply(t, Action.Activate(Side.US, f16, Zone.Band(1)))
        assertEquals(4, t.tokens.values.count { it.squadronId == f16 && it.zone == Zone.Band(1) })
        // ACE の米 Airbase への攻撃は不利
        assertEquals(listOf("米の態勢(ACE)"), openEngine.viewFor(t, Side.PRC).baseDefense.getValue(Side.US).getValue(BaseKind.AIRBASE))
        assertEquals(emptyList(), openEngine.viewFor(t, Side.PRC).baseDefense.getValue(Side.US).getValue(BaseKind.CL))
    }

    @Test
    fun `random tournament games finish and keep the invariants`() {
        repeat(150) { seed ->
            val rnd = Random(seed)
            var s = engine.newGame("C2", seed.toLong())
            val tokenCount = s.tokens.size
            var steps = 0
            while (s.phase != Phase.GAME_OVER && steps++ < 8000) {
                val side = engine.actors(s).first()
                val acts = engine.legalActions(s, side)
                assertTrue(acts.isNotEmpty(), "no legal action in ${s.phase}")
                val end = acts.filter { it is Action.Pass || it is Action.EndTurn }
                val pick = if (end.isNotEmpty() && rnd.nextInt(4) == 0) end.first() else acts[rnd.nextInt(acts.size)]
                s = engine.apply(s, pick)
                assertEquals(tokenCount, s.tokens.size)
                for (viewer in Side.entries) {
                    val v = engine.viewFor(s, viewer)
                    if (s.phase != Phase.GAME_OVER) {
                        assertTrue(v.tokens.filter { it.owner != viewer && it.masked }.all { it.type == null })
                        assertTrue(v.squadrons.filter { it.owner != viewer && !it.faceUp }.all { it.def == null })
                    }
                }
            }
            assertEquals(Phase.GAME_OVER, s.phase, "seed $seed did not finish")
            assertNotNull(s.gameOverReason)
        }
    }
}
