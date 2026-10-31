package afwi.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineTest {
    private val engine = Engine()

    private fun GameState.tokensOf(side: Side) = tokens.values.filter { it.owner == side }
    private fun GameState.squadronOf(side: Side) = squadrons.values.single { it.owner == side }

    /** 遭遇戦を、両軍とも基地に配置して US 先攻の手番開始まで進める。 */
    private fun startC1(seed: Long = 1, usBase: BaseKind = BaseKind.AIRBASE): GameState {
        var s = engine.newGame("C1", seed)
        val usSq = s.squadronOf(Side.US).id
        val prcSq = s.squadronOf(Side.PRC).id
        s = engine.apply(s, Action.Deploy(Side.US, mapOf(usSq to usBase)))
        s = engine.apply(s, Action.Deploy(Side.PRC, mapOf(prcSq to BaseKind.AIRBASE)))
        s = engine.apply(s, Action.SubmitBid(Side.US))
        s = engine.apply(s, Action.SubmitBid(Side.PRC))
        if (s.phase == Phase.GAME_OVER) return s
        s = engine.apply(s, Action.ChooseFirst(s.initiativeWinner!!, Side.US))
        return s
    }

    /** テスト用に駒の位置や状態を直接書き換える。 */
    private fun GameState.withToken(id: String, f: (TokenState) -> TokenState) =
        copy(tokens = LinkedHashMap(tokens).also { it[id] = f(it.getValue(id)) })

    @Test
    fun `bundled content loads all token types and squadrons`() {
        val c = engine.content
        assertEquals(26, c.tokenTypes.size)
        assertEquals(20, c.squadrons.size)
        assertEquals(10, c.squadrons.count { it.side == Side.US })
        // 探知値（確定ルール）
        assertEquals(1, c.tokenType("F-16C").acqValue)
        assertEquals(2, c.tokenType("F-35A").acqValue)
        assertEquals(3, c.tokenType("E-3").acqValue)
        assertEquals(3, c.tokenType("B-52H").acqValue)
        // F-15C は対空専用、攻撃型 UAS の命中は 3+
        assertNull(c.tokenType("F-15C").surface)
        assertEquals(3, c.tokenType("MQ-9").surface!!.hit)
    }

    @Test
    fun `new campaign 1 has one F-16C and one J-10 squadron with four tokens each`() {
        val s = engine.newGame("C1", 1)
        assertEquals(Phase.DEPLOY, s.phase)
        assertEquals(setOf(Side.US, Side.PRC), engine.actors(s))
        assertEquals(4, s.tokensOf(Side.US).count { it.typeId == "F-16C" })
        assertEquals(4, s.tokensOf(Side.PRC).count { it.typeId == "J-10" })
        assertTrue(s.tokens.values.all { it.zone == Zone.Grounded })
        // ID から機種が分からないこと
        assertTrue(s.tokens.keys.none { "F-16" in it || "J-10" in it })
    }

    @Test
    fun `only the US may deploy to the contingency location`() {
        val s = engine.newGame("C1", 1)
        assertEquals(2, engine.legalActions(s, Side.US).size)
        assertEquals(1, engine.legalActions(s, Side.PRC).size)
    }

    @Test
    fun `bid winner chooses who goes first`() {
        var s = engine.newGame("C1", 7)
        s = engine.apply(s, engine.legalActions(s, Side.US).first())
        s = engine.apply(s, engine.legalActions(s, Side.PRC).first())
        s = engine.apply(s, Action.SubmitBid(Side.US))
        s = engine.apply(s, Action.SubmitBid(Side.PRC))
        val w = assertNotNull(s.initiativeWinner)
        assertEquals(Phase.CHOOSE_ORDER, s.phase)
        assertEquals(setOf(w), engine.actors(s))
        assertEquals(IntelLevel.ADVANTAGE, s.player(w).intel)
        s = engine.apply(s, Action.ChooseFirst(w, w.opponent))
        assertEquals(Phase.TURNS, s.phase)
        assertEquals(w.opponent, s.turn.active)
    }

    @Test
    fun `activating a fighter squadron generates its tokens in the owner's band 1`() {
        var s = startC1()
        val sq = s.squadronOf(Side.US)
        s = engine.apply(s, Action.Activate(Side.US, sq.id, Zone.Band(1)))
        assertTrue(s.tokensOf(Side.US).all { it.zone == Zone.Band(1) && !it.acquired })
        assertTrue(s.squadrons.getValue(sq.id).faceUp)
        // 起動した手番には MAS ができない
        assertEquals(listOf<Action>(Action.EndTurn(Side.US)), engine.legalActions(s, Side.US))
        s = engine.apply(s, Action.EndTurn(Side.US))
        val prcSq = s.squadronOf(Side.PRC)
        // 中の 1 帯は米側基準の 5
        assertEquals(listOf<Action>(Action.Activate(Side.PRC, prcSq.id, Zone.Band(5))),
            engine.legalActions(s, Side.PRC).filterIsInstance<Action.Activate>())
    }

    @Test
    fun `generation from the contingency location is limited by a d4 roll`() {
        repeat(20) { seed ->
            var s = startC1(seed.toLong(), usBase = BaseKind.CL)
            if (s.phase == Phase.GAME_OVER) return@repeat
            val sq = s.squadronOf(Side.US)
            s = engine.apply(s, Action.Activate(Side.US, sq.id, Zone.Band(1)))
            val airborne = s.tokensOf(Side.US).count { it.zone == Zone.Band(1) }
            val unavailable = s.tokensOf(Side.US).count { it.unavailable }
            assertTrue(airborne in 1..4)
            assertEquals(4, airborne + unavailable)
        }
    }

    @Test
    fun `fighters move up to two bands and cannot enter the standoff`() {
        var s = startC1()
        s = engine.apply(s, Action.Activate(Side.US, s.squadronOf(Side.US).id, Zone.Band(1)))
        s = engine.apply(s, Action.EndTurn(Side.US))
        s = engine.apply(s, Action.Pass(Side.PRC))
        val tok = s.tokensOf(Side.US).first()
        val moves = engine.legalActions(s, Side.US).filterIsInstance<Action.Move>().filter { it.tokenId == tok.id }
        assertEquals(setOf<Zone>(Zone.Band(2), Zone.Band(3)), moves.map { it.to }.toSet())
    }

    @Test
    fun `targets must be acquired before they can be shot`() {
        var s = startC1()
        val us = s.tokensOf(Side.US).first().id
        val prc = s.tokensOf(Side.PRC).first().id
        s = s.withToken(us) { it.copy(zone = Zone.Band(3)) }.withToken(prc) { it.copy(zone = Zone.Band(4)) }
        val acts = engine.legalActions(s, Side.US)
        assertTrue(acts.none { it is Action.ShootToken })
        assertTrue(Action.Acquire(Side.US, us, listOf(prc)) in acts)
        // J-10 の探知値は 1 なので必ず成功する
        s = engine.apply(s, Action.Acquire(Side.US, us, listOf(prc)))
        assertTrue(s.tokens.getValue(prc).acquired)
        assertTrue(Action.ShootToken(Side.US, us, prc) in engine.legalActions(s, Side.US))
        // 捕捉は1手番に1回
        assertTrue(engine.legalActions(s, Side.US).none { it is Action.Acquire })
    }

    @Test
    fun `shooting below 4 makes a fighter winchester and it returns to base at the start of its next turn`() {
        var found = false
        for (seed in 1L..200L) {
            var s = startC1(seed)
            if (s.phase != Phase.TURNS) continue
            val us = s.tokensOf(Side.US).first().id
            val prc = s.tokensOf(Side.PRC).first().id
            s = s.withToken(us) { it.copy(zone = Zone.Band(3)) }
                .withToken(prc) { it.copy(zone = Zone.Band(4), acquired = true) }
            s = engine.apply(s, Action.ShootToken(Side.US, us, prc))
            val shooter = s.tokens.getValue(us)
            val prcAlive = !s.tokens.getValue(prc).isDestroyed
            if (!shooter.winchester) continue
            found = true
            // 命中は 3+ なので、弾切れでも命中していることがある
            s = engine.apply(s, Action.EndTurn(Side.US))
            s = engine.apply(s, Action.Pass(Side.PRC))
            val back = s.tokens.getValue(us)
            assertEquals(Zone.Grounded, back.zone)
            assertFalse(back.winchester)
            // 再補給して再発進できる（rtbRearm）
            assertTrue(engine.legalActions(s, Side.US).any { it is Action.Activate })
            assertTrue(prcAlive || s.player(Side.US).captured.isNotEmpty())
            break
        }
        assertTrue(found)
    }

    @Test
    fun `two hits destroy a squadron and every token still on its card`() {
        var s = startC1()
        val us = s.tokensOf(Side.US).first().id
        val prcSq = s.squadronOf(Side.PRC).id
        s = s.withToken(us) { it.copy(zone = Zone.Band(5)) }
        assertTrue(Action.StrikeSquadron(Side.US, us, prcSq) in engine.legalActions(s, Side.US))
        // 2命中させるまで、手番を入れ替えながら攻撃を繰り返す
        var guard = 0
        while (!s.squadrons.getValue(prcSq).isDestroyed && guard++ < 200) {
            s = s.withToken(us) { it.copy(zone = Zone.Band(5), winchester = false) }
                .copy(turn = TurnState(Side.US))
            s = engine.apply(s, Action.StrikeSquadron(Side.US, us, prcSq))
        }
        assertTrue(s.squadrons.getValue(prcSq).isDestroyed)
        assertTrue(s.tokensOf(Side.PRC).all { it.isDestroyed })
        val captured = s.player(Side.US).captured
        assertEquals(1, captured.count { it.kind == CaptureKind.SQUADRON })
        assertEquals(4, captured.count { it.kind == CaptureKind.TOKEN && it.onGround })
    }

    @Test
    fun `two consecutive passes end the campaign and attrition is scored`() {
        var s = startC1()
        val us = s.tokensOf(Side.US).first().id
        val prc = s.tokensOf(Side.PRC).first().id
        s = s.withToken(prc) { it.copy(zone = Zone.Destroyed) }
            .copy(players = s.players + (Side.US to s.player(Side.US).copy(
                captured = listOf(CaptureRecord(CaptureKind.TOKEN, ato = 1, typeId = "J-10", vpClass = VpClass.OTHER, inAir = true)),
            )))
        s = engine.apply(s, Action.Pass(Side.US))
        assertEquals(Phase.TURNS, s.phase)
        s = engine.apply(s, Action.Pass(Side.PRC))
        assertEquals(Phase.GAME_OVER, s.phase)
        assertEquals(1, s.player(Side.US).vp)
        assertEquals(0, s.player(Side.PRC).vp)
        assertEquals(Side.US, s.winner)
        assertNotNull(us)
    }

    @Test
    fun `attrition and interdiction values follow the player guide`() {
        fun rec(kind: CaptureKind, vp: VpClass? = null) = CaptureRecord(kind, ato = 1, vpClass = vp)
        assertEquals(3, engine.recordVp(MissionId.ATTRITION, rec(CaptureKind.TOKEN, VpClass.AEW)))
        assertEquals(3, engine.recordVp(MissionId.ATTRITION, rec(CaptureKind.TOKEN, VpClass.ADA)))
        assertEquals(1, engine.recordVp(MissionId.ATTRITION, rec(CaptureKind.TOKEN, VpClass.OTHER)))
        assertEquals(2, engine.recordVp(MissionId.ATTRITION, rec(CaptureKind.SQUADRON)))
        assertEquals(4, engine.recordVp(MissionId.INTERDICTION, rec(CaptureKind.SQUADRON)))
        assertEquals(2, engine.recordVp(MissionId.INTERDICTION, rec(CaptureKind.TOKEN, VpClass.OTHER)))
    }

    @Test
    fun `the opponent sees unacquired tokens only as masked tracks`() {
        var s = startC1()
        s = engine.apply(s, Action.Activate(Side.US, s.squadronOf(Side.US).id, Zone.Band(1)))
        val prcView = engine.viewFor(s, Side.PRC)
        val usTracks = prcView.tokens.filter { it.owner == Side.US }
        assertEquals(4, usTracks.size)
        assertTrue(usTracks.all { it.masked && it.type == null && it.acqValue == 1 })
        // 伏せたままの中の飛行隊は、米からは正体が見えない
        val usView = engine.viewFor(s, Side.US)
        assertNull(usView.squadrons.single { it.owner == Side.PRC }.def)
        assertTrue(usView.tokens.none { it.owner == Side.PRC })
        assertNull(usView.opponent.vp)
        assertNull(usView.opponent.mission)
    }

    @Test
    fun `the same seed and actions reproduce the same game`() {
        fun play(seed: Long): GameState {
            val rnd = Random(99)
            var s = engine.newGame("C1", seed)
            var steps = 0
            while (s.phase != Phase.GAME_OVER && steps++ < 3000) {
                val side = engine.actors(s).first()
                val acts = engine.legalActions(s, side)
                s = engine.apply(s, acts[rnd.nextInt(acts.size)])
            }
            return s
        }
        assertEquals(play(42), play(42))
    }

    @Test
    fun `random games always finish and keep the invariants`() {
        repeat(300) { seed ->
            val rnd = Random(seed)
            var s = engine.newGame("C1", seed.toLong())
            val tokenCount = s.tokens.size
            var steps = 0
            while (s.phase != Phase.GAME_OVER && steps++ < 5000) {
                val side = engine.actors(s).first()
                val acts = engine.legalActions(s, side)
                assertTrue(acts.isNotEmpty(), "no legal action in ${s.phase}")
                val end = acts.filter { it is Action.Pass || it is Action.EndTurn }
                val pick = if (end.isNotEmpty() && rnd.nextInt(4) == 0) end.first() else acts[rnd.nextInt(acts.size)]
                s = engine.apply(s, pick)
                assertEquals(tokenCount, s.tokens.size)
                assertTrue(s.players.values.all { it.cyber in 0..4 })
                // 相手から見た視点に、未捕捉の駒の機種が漏れない
                for (viewer in Side.entries) {
                    val v = engine.viewFor(s, viewer)
                    if (s.phase != Phase.GAME_OVER) {
                        assertTrue(v.tokens.filter { it.owner != viewer && it.masked }.all { it.type == null })
                    }
                }
            }
            assertEquals(Phase.GAME_OVER, s.phase, "seed $seed did not finish")
        }
    }

    @Test
    fun `cyber rate rises on a roll of current plus one`() {
        // 初期値 1 → 2 には 2 以上が必要なので、成功率は 75%
        var success = 0
        val n = 4000
        repeat(n) { seed ->
            var s = engine.newGame("C1", seed.toLong())
            s = engine.apply(s, engine.legalActions(s, Side.US).first())
            s = engine.apply(s, engine.legalActions(s, Side.PRC).first())
            s = engine.apply(s, Action.SubmitBid(Side.US))
            s = engine.apply(s, Action.SubmitBid(Side.PRC))
            if (s.player(s.initiativeWinner!!).cyber == 2) success++
        }
        val rate = success.toDouble() / n
        assertTrue(rate in 0.72..0.78, "rate=$rate")
    }
}
