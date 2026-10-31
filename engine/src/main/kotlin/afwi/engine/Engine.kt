package afwi.engine

import kotlin.math.abs
import kotlin.random.Random

/**
 * ルールエンジン。状態は変更せず、apply が新しい状態を返す。
 * 画面も AI も legalActions の中から行動を選んで apply に渡すだけで、ルールはここにしかない。
 * 状態の書き換えそのものは Work（Work.kt）が行う。
 */
class Engine(
    val content: Content = Content.loadBundled(),
    val rules: RuleSettings = RuleSettings(),
) {

    fun newGame(campaignId: String, seed: Long): GameState {
        val campaign = content.campaign(campaignId)
        val tokens = LinkedHashMap<String, TokenState>()
        val squadrons = LinkedHashMap<String, SquadronState>()
        val fixed = campaign.fixedSquadrons.isNotEmpty()
        val shuffle = Random(seed)
        for (side in Side.entries) {
            val defIds = if (fixed) {
                campaign.fixedSquadrons[side].orEmpty()
            } else {
                content.squadrons.filter { it.side == side }.map { it.id }
            }
            // ID から正体が分からないよう、対局ごとに並びを混ぜてから連番を振る
            // （相手に見える合法手や視点に ID が載るため）
            defIds.shuffled(shuffle).forEachIndexed { n, defId ->
                val def = content.squadron(defId)
                require(def.side == side) { "$defId is not a ${side.name} squadron" }
                val sqId = "${side.name}-S${n + 1}"
                squadrons[sqId] = SquadronState(id = sqId, defId = defId, owner = side, drafted = fixed)
                repeat(def.count) { i ->
                    val id = "$sqId-T${i + 1}"
                    tokens[id] = TokenState(id = id, typeId = def.tokenType, owner = side, squadronId = sqId)
                }
            }
        }
        val start = "キャンペーン「${campaign.nameJa}」開始(ATO 1/${campaign.atoCount})"
        val initial = GameState(
            campaignId = campaignId,
            ato = 1,
            atoCount = campaign.atoCount,
            phase = Phase.MISSION,
            players = Side.entries.associateWith { PlayerState(side = it, mission = campaign.mission) },
            tokens = tokens,
            squadrons = squadrons,
            turn = TurnState(active = Side.US),
            rngState = seed,
            log = listOf(GameEvent(Side.entries.associateWith { start })),
        )
        // 選択肢が1つしかない手順（ミッションや態勢が固定など）は自動で進める
        return Work(this, initial).also { it.beginCampaign() }.toState()
    }

    /** その陣営がこの ATO で選べる態勢。標準以外は一度使うと選べない。 */
    fun postureChoices(campaign: CampaignDef, player: PlayerState): List<PostureDef> =
        content.postures.filter { p ->
            p.side == player.side &&
                (campaign.allowedPostures == null || p.kind in campaign.allowedPostures) &&
                (p.kind == PostureKind.STANDARD || p.id !in player.usedPostures)
        }

    /** その飛行隊にまだ使える（撃破されていない）機体が残っているか。 */
    internal fun hasLiveTokens(s: GameState, squadronId: String): Boolean =
        s.tokens.values.any { it.squadronId == squadronId && !it.isDestroyed }

    /** この ATO で選べる支援カード（使用済みの単発カードを除く。PLAAF 態勢なら空軍のカードだけ）。 */
    fun enablerPool(s: GameState, side: Side): List<EnablerDef> {
        val p = s.player(side)
        val posture = p.posture?.let { content.posture(it) }
        return content.enablers.filter { it.side == side && it.id !in p.spent }
            .filter { posture == null || PostureEffect.PLAAF_ENABLERS_ONLY !in posture.effects || it.family == "PLAAF" }
    }

    fun enablerLimit(s: GameState, side: Side): Int =
        s.player(side).posture?.let { content.posture(it).enablerLimit } ?: 0

    /** いま行動を求められている陣営。秘密裏に同時に決める場面では両方になる。 */
    fun actors(s: GameState): Set<Side> {
        s.pending?.let { return setOf(it.decider) }
        return when (s.phase) {
            Phase.MISSION -> Side.entries.filter { s.player(it).mission == null }.toSet()
            Phase.POSTURE -> Side.entries.filter { s.player(it).posture == null }.toSet()
            Phase.DRAFT -> Side.entries.filter { !s.player(it).drafted }.toSet()
            Phase.ENABLERS -> Side.entries.filter { !s.player(it).enablersChosen }.toSet()
            Phase.DEPLOY -> Side.entries.filter { !s.player(it).deployed }.toSet()
            Phase.BID -> Side.entries.filter { s.player(it).bid == null }.toSet()
            Phase.CHOOSE_ORDER -> setOfNotNull(s.initiativeWinner)
            Phase.TURNS -> setOf(s.turn.active)
            Phase.GAME_OVER -> emptySet()
        }
    }

    /**
     * 合法手の一覧。支援カードの選択だけは組み合わせが多すぎるため、代表的な候補だけを返す
     * （実際の選択は isLegal で判定する。画面と AI は enablerPool から自分で組み立てる）。
     */
    fun legalActions(s: GameState, side: Side): List<Action> {
        if (side !in actors(s)) return emptyList()
        s.pending?.let { p ->
            return listOf<Action>(Action.Respond(side)) +
                p.cardOptions.map { Action.Respond(side, cardId = it) } +
                p.shipOptions.map { Action.Respond(side, shipId = it) }
        }
        return when (s.phase) {
            Phase.MISSION -> content.missionsFor(side).map { Action.ChooseMission(side, it) }
            Phase.POSTURE -> postureChoices(content.campaign(s.campaignId), s.player(side))
                .map { Action.ChoosePosture(side, it.id) }
            Phase.DRAFT -> draftOptions(s, side)
            Phase.ENABLERS -> {
                val pool = enablerPool(s, side).map { it.id }
                listOf(
                    Action.ChooseEnablers(side, emptySet()),
                    Action.ChooseEnablers(side, pool.take(enablerLimit(s, side)).toSet()),
                ).distinct()
            }
            Phase.DEPLOY -> deployOptions(s, side)
            Phase.BID -> bidOptions(s, side)
            Phase.CHOOSE_ORDER -> Side.entries.map { Action.ChooseFirst(side, it) }
            Phase.TURNS -> turnActions(s, side)
            Phase.GAME_OVER -> emptyList()
        }
    }

    fun isLegal(s: GameState, action: Action): Boolean = when (action) {
        is Action.ChooseEnablers -> action.side in actors(s) && s.pending == null && s.phase == Phase.ENABLERS &&
            action.cardIds.size <= enablerLimit(s, action.side) &&
            enablerPool(s, action.side).map { it.id }.containsAll(action.cardIds)
        else -> action in legalActions(s, action.side)
    }

    fun apply(s: GameState, action: Action): GameState {
        require(isLegal(s, action)) { "illegal action: $action" }
        val w = Work(this, s)
        w.handle(action)
        return w.toState()
    }

    // ---- 位置と距離 ----

    fun tokenType(t: TokenState): TokenType = content.tokenType(t.typeId)

    /** 米側基準の座標。地上の駒は所属陣営の基地エリア（0 か 6）。 */
    fun coord(t: TokenState): Int = when (val z = t.zone) {
        is Zone.Band -> z.pos
        is Zone.Standoff -> z.side.baseCoord
        else -> t.owner.baseCoord
    }

    fun coord(zone: Zone, owner: Side): Int = when (zone) {
        is Zone.Band -> zone.pos
        is Zone.Standoff -> zone.side.baseCoord
        else -> owner.baseCoord
    }

    fun distance(a: Int, b: Int): Int = abs(a - b)

    fun generationZones(def: SquadronDef): List<Zone> {
        val band1 = Zone.Band(def.side.bandToUsFrame(1))
        return when (def.generate) {
            Generate.BAND1 -> listOf(band1)
            Generate.ANY_BAND -> (1..5).map { Zone.Band(def.side.bandToUsFrame(it)) }
            Generate.BAND1_OR_STANDOFF -> listOf(band1, Zone.Standoff(def.side))
            Generate.AIRBASE -> listOf(Zone.AtBase)
        }
    }

    /** 弾薬改良を反映した空対空射程。 */
    fun airRange(s: GameState, t: TokenState): Int? {
        val base = tokenType(t).air?.range ?: return null
        val upgrade = t.squadronId?.let { s.player(t.owner).airRange[it] }
        return maxOf(base, upgrade ?: 0)
    }

    private fun readyOnCard(s: GameState, squadronId: String): List<TokenState> =
        s.tokens.values.filter { it.squadronId == squadronId && it.zone == Zone.Grounded && !it.unavailable }

    // ---- 合法手 ----

    private fun draftOptions(s: GameState, side: Side): List<Action> {
        val posture = content.posture(s.player(side).posture ?: return emptyList())
        val available = s.squadrons.values
            .filter { it.owner == side && !it.isDestroyed && hasLiveTokens(s, it.id) }
            .filter { PostureEffect.FLYING_ONLY !in posture.effects || content.squadron(it.defId).flying }
            .map { it.id }
        val (free, normal) = available.partition { id -> s.squadrons.getValue(id).defId in posture.freeSquadrons }
        val normalSets = combinations(normal, posture.squadronLimit)
        val freeSets = combinations(free, free.size)
        return normalSets.flatMap { a -> freeSets.map { b -> Action.Draft(side, (a + b).toSet()) } }
    }

    fun clAllowed(s: GameState, side: Side): Boolean {
        if (side != Side.US) return false
        val posture = s.player(side).posture?.let { content.posture(it) }
        return posture == null || PostureEffect.NO_CL !in posture.effects
    }

    private fun deployOptions(s: GameState, side: Side): List<Action> {
        val ids = s.squadrons.values.filter { it.owner == side && it.drafted }.map { it.id }
        val choices = if (clAllowed(s, side)) listOf(BaseKind.AIRBASE, BaseKind.CL) else listOf(BaseKind.AIRBASE)
        var combos = listOf(emptyMap<String, BaseKind>())
        for (id in ids) combos = combos.flatMap { m -> choices.map { m + (id to it) } }
        return combos.map { Action.Deploy(side, it) }
    }

    private fun bidOptions(s: GameState, side: Side): List<Action> {
        val campaign = content.campaign(s.campaignId)
        if (campaign.bidRule != BidRule.EVERY_ATO) return listOf(Action.SubmitBid(side))
        val hand = s.player(side).hand
        return combinations(hand, hand.size).map { Action.SubmitBid(side, it.toSet()) }
    }

    private fun turnActions(s: GameState, side: Side): List<Action> {
        val t = s.turn
        val out = mutableListOf<Action>()
        if (!t.primaryUsed) out += activationOptions(s, side)
        if (!t.activated) {
            if (!t.moved) out += moveOptions(s, side)
            if (!t.acquired) out += acquireOptions(s, side)
            if (!t.shot) out += shootOptions(s, side)
        }
        if (!t.enablerUsed) out += enablerOptions(s, side)
        out += if (t.didSomething) Action.EndTurn(side) else Action.Pass(side)
        return out
    }

    private fun activationOptions(s: GameState, side: Side): List<Action> =
        s.squadrons.values.filter { it.owner == side && it.drafted && !it.isDestroyed }.flatMap { sq ->
            val def = content.squadron(sq.defId)
            val ready = readyOnCard(s, sq.id)
            val canActivate = when {
                ready.isEmpty() -> false
                !sq.faceUp -> true
                else -> rules.rtbRearm && def.generate != Generate.AIRBASE
            }
            if (canActivate) generationZones(def).map { Action.Activate(side, sq.id, it) } else emptyList()
        }

    private fun moveOptions(s: GameState, side: Side): List<Action> =
        s.tokens.values.filter { it.owner == side && it.isAirborne }.flatMap { tok ->
            val type = tokenType(tok)
            if (type.move <= 0) return@flatMap emptyList()
            val candidates = (1..5).map<Int, Zone> { Zone.Band(it) } +
                if (type.cls.canUseStandoff) listOf(Zone.Standoff(side)) else emptyList()
            candidates
                .filter { it != tok.zone && distance(coord(tok), coord(it, side)) <= type.move }
                .map { Action.Move(side, tok.id, it) }
        }

    private fun acquireOptions(s: GameState, side: Side): List<Action> =
        s.tokens.values.filter { it.owner == side && (it.isAirborne || it.zone == Zone.AtBase) }.flatMap { tok ->
            val type = tokenType(tok)
            val targets = s.tokens.values
                .filter { it.owner != side && it.isAirborne && !it.acquired }
                .filter { distance(coord(tok), coord(it)) <= type.acqRange }
                .map { it.id }
            combinationsWithRepetition(targets, type.acqTries).map { Action.Acquire(side, tok.id, it) }
        }

    private fun shootOptions(s: GameState, side: Side): List<Action> {
        val enemy = side.opponent
        val out = mutableListOf<Action>()
        val shooters = s.tokens.values.filter {
            it.owner == side && !it.winchester && (it.isAirborne || it.zone == Zone.AtBase)
        }
        for (tok in shooters) {
            val type = tokenType(tok)
            val from = coord(tok)
            airRange(s, tok)?.let { range ->
                s.tokens.values
                    .filter { it.owner == enemy && it.isAirborne && it.acquired && tokenType(it).cls.isAircraft }
                    .filter { distance(from, coord(it)) <= range }
                    .forEach { out += Action.ShootToken(side, tok.id, it.id) }
            }
            type.surface?.let { w ->
                s.tokens.values
                    .filter { it.owner == enemy && it.isAirborne && it.acquired && tokenType(it).cls == TokenClass.SHIP }
                    .filter { distance(from, coord(it)) <= w.range }
                    .forEach { out += Action.ShootToken(side, tok.id, it.id) }
                // 基地と CL 上のものは捕捉なしで攻撃できる
                if (distance(from, enemy.baseCoord) <= w.range) {
                    s.squadrons.values.filter { it.owner == enemy && it.drafted && !it.isDestroyed }
                        .forEach { out += Action.StrikeSquadron(side, tok.id, it.id) }
                    s.tokens.values.filter { it.owner == enemy && it.zone == Zone.AtBase }
                        .forEach { out += Action.ShootToken(side, tok.id, it.id) }
                    if (s.player(enemy).airbaseBlocksHit < rules.airbaseBlocks) {
                        out += Action.StrikeAirbaseBlock(side, tok.id)
                    }
                }
            }
        }
        return out
    }

    /** 手番中に使える支援カードと、その対象の組み合わせ。 */
    private fun enablerOptions(s: GameState, side: Side): List<Action> =
        s.player(side).hand.flatMap { id ->
            val effect = content.enabler(id).action ?: return@flatMap emptyList()
            effectOptions(s, side, effect).map { (option, target, zone) ->
                Action.PlayEnabler(side, id, option, target, zone)
            }
        }

    private data class EffectParams(val option: Int, val target: String?, val zone: Zone?)

    private fun effectOptions(s: GameState, side: Side, effect: Effect, option: Int = 0): List<EffectParams> = when (effect) {
        is Effect.Choice -> effect.options.flatMapIndexed { i, e -> effectOptions(s, side, e, i) }
        is Effect.Sequence -> effect.steps.fold(listOf(EffectParams(option, null, null))) { acc, step ->
            val stepOpts = effectOptions(s, side, step, option)
            acc.flatMap { a ->
                stepOpts.map { b -> EffectParams(option, b.target ?: a.target, b.zone ?: a.zone) }
            }
        }
        is Effect.Generate -> if (effect.atBase) {
            listOf(EffectParams(option, null, null))
        } else {
            (1..5).map { EffectParams(option, null, Zone.Band(it)) }
        }
        is Effect.DestroyShip -> s.tokens.values
            .filter { it.owner == side.opponent && it.isAirborne && tokenType(it).cls == TokenClass.SHIP }
            .map { EffectParams(option, it.id, null) }
        is Effect.ExtraSquadron -> s.squadrons.values
            .filter { it.owner == side && !it.drafted && !it.isDestroyed && hasLiveTokens(s, it.id) }
            .map { EffectParams(option, it.id, null) }
        is Effect.ReturnSpent -> s.player(side).spent
            .filter { effect.tag == null || effect.tag in content.enabler(it).tags }
            .map { EffectParams(option, it, null) }
        is Effect.AirRangeUpgrade -> s.squadrons.values
            .filter { it.owner == side && it.drafted && !it.isDestroyed }
            .filter { content.tokenType(content.squadron(it.defId).tokenType).let { t -> t.air != null && t.cls.isAircraft } }
            .map { EffectParams(option, it.id, null) }
        else -> listOf(EffectParams(option, null, null))
    }

    // ---- 採点 ----

    /** 撃破記録1件の VP（撃破で点が入るミッションのみ。他は scoreAto で数える）。 */
    fun recordVp(mission: MissionId?, rec: CaptureRecord): Int {
        if (rec.kind == CaptureKind.AIRBASE_BLOCK) return rules.airbaseBlockVp
        return when (mission) {
            MissionId.ATTRITION -> when (rec.kind) {
                CaptureKind.SQUADRON -> 2
                else -> if (rec.vpClass in HIGH_VALUE) 3 else 1
            }
            MissionId.INTERDICTION -> when (rec.kind) {
                CaptureKind.SQUADRON -> 4
                else -> when (rec.vpClass) {
                    VpClass.BOMBER, VpClass.AEW -> 4
                    VpClass.SHIP, VpClass.ADA -> 3
                    else -> 2
                }
            }
            // 地上で撃破した航空機は1機につき +3（介入阻止）
            MissionId.COUNTER_INTERVENTION ->
                if (rec.kind == CaptureKind.TOKEN && rec.onGround && rec.vpClass != VpClass.ADA && rec.vpClass != VpClass.SHIP) 3 else 0
            else -> 0
        }
    }

    internal companion object {
        val HIGH_VALUE = setOf(VpClass.SHIP, VpClass.BOMBER, VpClass.ADA, VpClass.AEW)
    }
}

/**
 * 基地・CL 上の目標への攻撃が不利になる理由。エンジンの判定と、画面・AI の見積もりで共用する。
 * tokens には防御側の基地上の駒が含まれていればよい（防空の駒は基地上なら相手からも存在が見える）。
 */
fun Engine.baseDefenseReasons(
    defender: Side,
    airbaseTarget: Boolean,
    tokens: Collection<TokenState>,
    defenderPosture: String?,
): List<String> = buildList {
    if (tokens.any { it.owner == defender && it.zone == Zone.AtBase && tokenType(it).cls == TokenClass.ADA }) {
        add("防空の交戦域内")
    }
    val ace = defenderPosture?.let { PostureEffect.AIRBASE_STRIKES_DISADVANTAGE in content.posture(it).effects } == true
    if (airbaseTarget && ace) add("${Ja.side(defender)}の態勢(ACE)")
}

/** 大きさ 0〜maxSize の組み合わせ（重複なし）。ドラフトの候補で使う。 */
internal fun <T> combinations(items: List<T>, maxSize: Int): List<List<T>> {
    val out = mutableListOf<List<T>>()
    fun go(start: Int, current: List<T>) {
        out += current
        if (current.size == maxSize) return
        for (i in start until items.size) go(i + 1, current + items[i])
    }
    go(0, emptyList())
    return out
}

/** 重複を許す組み合わせ（大きさ 1〜maxSize）。AEW の2回捕捉で使う。 */
internal fun <T> combinationsWithRepetition(items: List<T>, maxSize: Int): List<List<T>> {
    val out = mutableListOf<List<T>>()
    fun go(start: Int, current: List<T>) {
        if (current.isNotEmpty()) out += current
        if (current.size == maxSize) return
        for (i in start until items.size) go(i, current + items[i])
    }
    go(0, emptyList())
    return out
}
