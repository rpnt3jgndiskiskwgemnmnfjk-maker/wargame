package afwi.engine

/** 盤上の駒を、ある陣営から見た姿。masked のときは機種が分からない。 */
data class TokenView(
    val id: String,
    val owner: Side,
    val zone: Zone,
    val masked: Boolean,
    val type: TokenType?,
    /** 裏面に書かれた探知値。正体が分からなくても見える。 */
    val acqValue: Int,
    val acquired: Boolean,
    val winchester: Boolean,
    val unavailable: Boolean,
    val squadronId: String?,
)

data class SquadronView(
    val id: String,
    val owner: Side,
    val base: BaseKind,
    val faceUp: Boolean,
    /** 伏せた相手の飛行隊は null。 */
    val def: SquadronDef?,
    val damage: Int,
    val destroyed: Boolean,
)

data class OpponentView(
    val side: Side,
    val cyber: Int,
    val intel: IntelLevel,
    val airbaseBlocksHit: Int,
    /** ゲーム終了までは null（ミッションが分かってしまうため）。 */
    val mission: MissionId?,
    val vp: Int?,
)

/** 画面と AI に渡すのはこれだけ。真の状態（GameState）は渡さない。 */
data class PlayerView(
    val viewer: Side,
    val phase: Phase,
    val ato: Int,
    val atoCount: Int,
    val actors: Set<Side>,
    val activeSide: Side?,
    val turn: TurnState?,
    val initiativeWinner: Side?,
    val me: PlayerState,
    val opponent: OpponentView,
    val tokens: List<TokenView>,
    val squadrons: List<SquadronView>,
    val log: List<String>,
    val winner: Side?,
    val gameOverReason: String?,
    val legalActions: List<Action>,
)

fun Engine.viewFor(s: GameState, viewer: Side): PlayerView {
    val over = s.phase == Phase.GAME_OVER
    val enemy = viewer.opponent
    val tokens = s.tokens.values.mapNotNull { t ->
        val type = content.tokenType(t.typeId)
        val known = over || t.owner == viewer || t.acquired || t.isDestroyed
        val sq = t.squadronId?.let { s.squadrons[it] }
        // 伏せたままの相手の飛行隊カードに載っている駒は見えない
        if (!known && t.zone == Zone.Grounded && sq?.faceUp != true) return@mapNotNull null
        TokenView(
            id = t.id, owner = t.owner, zone = t.zone, masked = !known,
            type = if (known) type else null, acqValue = type.acqValue,
            acquired = t.acquired, winchester = if (known) t.winchester else false,
            unavailable = if (known) t.unavailable else false,
            squadronId = t.squadronId,
        )
    }
    val squadrons = s.squadrons.values.map { sq ->
        val known = over || sq.owner == viewer || sq.faceUp
        SquadronView(
            id = sq.id,
            owner = sq.owner, base = sq.base, faceUp = sq.faceUp,
            def = if (known) content.squadron(sq.defId) else null,
            damage = sq.damage, destroyed = sq.isDestroyed,
        )
    }
    val op = s.player(enemy)
    return PlayerView(
        viewer = viewer,
        phase = s.phase,
        ato = s.ato,
        atoCount = s.atoCount,
        actors = actors(s),
        activeSide = if (s.phase == Phase.TURNS) s.turn.active else null,
        turn = if (s.phase == Phase.TURNS) s.turn else null,
        initiativeWinner = s.initiativeWinner,
        me = s.player(viewer),
        opponent = OpponentView(
            side = enemy, cyber = op.cyber, intel = op.intel, airbaseBlocksHit = op.airbaseBlocksHit,
            mission = if (over) op.mission else null, vp = if (over) op.vp else null,
        ),
        tokens = tokens,
        squadrons = squadrons,
        log = s.log.mapNotNull { it.textFor(viewer) },
        winner = s.winner,
        gameOverReason = s.gameOverReason,
        legalActions = legalActions(s, viewer),
    )
}
