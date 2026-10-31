package afwi.engine

import kotlin.math.abs
import kotlin.math.min

/**
 * ルールエンジン。状態は変更せず、apply が新しい状態を返す。
 * 画面も AI も legalActions の中から行動を選んで apply に渡すだけで、ルールはここにしかない。
 */
class Engine(
    val content: Content = Content.loadBundled(),
    val rules: RuleSettings = RuleSettings(),
) {

    fun newGame(campaignId: String, seed: Long): GameState {
        val campaign = content.campaign(campaignId)
        val tokens = LinkedHashMap<String, TokenState>()
        val squadrons = LinkedHashMap<String, SquadronState>()
        for (side in Side.entries) {
            // ID は正体が分からない連番にする（相手に見える合法手や視点に ID が載るため）
            campaign.fixedSquadrons[side].orEmpty().forEachIndexed { n, defId ->
                val def = content.squadron(defId)
                require(def.side == side) { "$defId is not a ${side.name} squadron" }
                val sqId = "${side.name}-S${n + 1}"
                squadrons[sqId] = SquadronState(id = sqId, defId = defId, owner = side)
                repeat(def.count) { i ->
                    val id = "$sqId-T${i + 1}"
                    tokens[id] = TokenState(id = id, typeId = def.tokenType, owner = side, squadronId = sqId)
                }
            }
        }
        val start = "キャンペーン「${campaign.nameJa}」開始(ATO ${1}/${campaign.atoCount})"
        return GameState(
            campaignId = campaignId,
            ato = 1,
            atoCount = campaign.atoCount,
            phase = Phase.DEPLOY,
            players = Side.entries.associateWith { PlayerState(side = it, mission = campaign.mission) },
            tokens = tokens,
            squadrons = squadrons,
            turn = TurnState(active = Side.US),
            rngState = seed,
            log = listOf(GameEvent(Side.entries.associateWith { start })),
        )
    }

    /** いま行動を求められている陣営。秘密裏に同時に決める場面では両方になる。 */
    fun actors(s: GameState): Set<Side> = when (s.phase) {
        Phase.DEPLOY -> Side.entries.filter { !s.player(it).deployed }.toSet()
        Phase.BID -> Side.entries.filter { s.player(it).bid == null }.toSet()
        Phase.CHOOSE_ORDER -> setOfNotNull(s.initiativeWinner)
        Phase.TURNS -> setOf(s.turn.active)
        Phase.GAME_OVER -> emptySet()
    }

    fun legalActions(s: GameState, side: Side): List<Action> {
        if (side !in actors(s)) return emptyList()
        return when (s.phase) {
            Phase.DEPLOY -> deployOptions(s, side)
            // M1 は支援カードがないため、差し出す枚数は 0 だけ
            Phase.BID -> listOf(Action.SubmitBid(side, 0))
            Phase.CHOOSE_ORDER -> Side.entries.map { Action.ChooseFirst(side, it) }
            Phase.TURNS -> turnActions(s, side)
            Phase.GAME_OVER -> emptyList()
        }
    }

    fun apply(s: GameState, action: Action): GameState {
        require(action in legalActions(s, action.side)) { "illegal action: $action" }
        val w = Work(s)
        when (action) {
            is Action.Deploy -> w.deploy(action)
            is Action.SubmitBid -> w.bid(action)
            is Action.ChooseFirst -> w.chooseFirst(action)
            is Action.Activate -> w.activate(action)
            is Action.Move -> w.move(action)
            is Action.Acquire -> w.acquire(action)
            is Action.ShootToken -> w.shootToken(action)
            is Action.StrikeSquadron -> w.strikeSquadron(action)
            is Action.StrikeAirbaseBlock -> w.strikeBlock(action)
            is Action.EndTurn -> w.endTurn(passed = false)
            is Action.Pass -> w.endTurn(passed = true)
        }
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

    private fun readyOnCard(s: GameState, squadronId: String): List<TokenState> =
        s.tokens.values.filter { it.squadronId == squadronId && it.zone == Zone.Grounded && !it.unavailable }

    // ---- 合法手 ----

    private fun deployOptions(s: GameState, side: Side): List<Action> {
        val ids = s.squadrons.values.filter { it.owner == side }.map { it.id }
        val choices = if (side == Side.US) listOf(BaseKind.AIRBASE, BaseKind.CL) else listOf(BaseKind.AIRBASE)
        var combos = listOf(emptyMap<String, BaseKind>())
        for (id in ids) combos = combos.flatMap { m -> choices.map { m + (id to it) } }
        return combos.map { Action.Deploy(side, it) }
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
        out += if (t.didSomething) Action.EndTurn(side) else Action.Pass(side)
        return out
    }

    private fun activationOptions(s: GameState, side: Side): List<Action> =
        s.squadrons.values.filter { it.owner == side && !it.isDestroyed }.flatMap { sq ->
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
            type.air?.let { w ->
                s.tokens.values
                    .filter { it.owner == enemy && it.isAirborne && it.acquired && tokenType(it).cls.isAircraft }
                    .filter { distance(from, coord(it)) <= w.range }
                    .forEach { out += Action.ShootToken(side, tok.id, it.id) }
            }
            type.surface?.let { w ->
                s.tokens.values
                    .filter { it.owner == enemy && it.isAirborne && it.acquired && tokenType(it).cls == TokenClass.SHIP }
                    .filter { distance(from, coord(it)) <= w.range }
                    .forEach { out += Action.ShootToken(side, tok.id, it.id) }
                // 基地と CL 上のものは捕捉なしで攻撃できる
                if (distance(from, enemy.baseCoord) <= w.range) {
                    s.squadrons.values.filter { it.owner == enemy && !it.isDestroyed }
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

    // ---- 採点 ----

    fun recordVp(mission: MissionId, rec: CaptureRecord): Int {
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
            // 残りのミッションは M4 で実装する
            else -> 0
        }
    }

    // ---- 状態の書き換え（apply の中だけで使う） ----

    private inner class Work(s: GameState) {
        private val campaignId = s.campaignId
        private var ato = s.ato
        private val atoCount = s.atoCount
        private var phase = s.phase
        private val players = s.players.toMutableMap()
        private val tokens = LinkedHashMap(s.tokens)
        private val squadrons = LinkedHashMap(s.squadrons)
        private var turn = s.turn
        private var initiativeWinner = s.initiativeWinner
        private var winner = s.winner
        private var gameOverReason = s.gameOverReason
        private val rng = Rng(s.rngState)
        private val log = s.log.toMutableList()

        fun toState() = GameState(
            campaignId = campaignId, ato = ato, atoCount = atoCount, phase = phase,
            players = players.toMap(), tokens = LinkedHashMap(tokens), squadrons = LinkedHashMap(squadrons),
            turn = turn, initiativeWinner = initiativeWinner, winner = winner,
            gameOverReason = gameOverReason, rngState = rng.state, log = log.toList(),
        )

        private fun p(side: Side) = players.getValue(side)
        private fun updatePlayer(side: Side, f: (PlayerState) -> PlayerState) { players[side] = f(p(side)) }
        private fun type(t: TokenState) = tokenType(t)

        /** 陣営ごとに文を作る。null を返した陣営には見せない。 */
        private fun event(textFor: (Side) -> String?) {
            val texts = Side.entries.mapNotNull { v -> textFor(v)?.let { v to it } }.toMap()
            if (texts.isNotEmpty()) log += GameEvent(texts)
        }

        private fun publicEvent(text: String) = event { text }

        /** 見る側に正体が分かるときは機種名、分からないときは「未確認機」。 */
        private fun label(t: TokenState, viewer: Side): String =
            if (t.owner == viewer || t.acquired || phase == Phase.GAME_OVER) {
                "${Ja.side(t.owner)}${type(t).nameJa}"
            } else {
                "${Ja.side(t.owner)}の未確認機"
            }

        // ---- 準備 ----

        fun deploy(a: Action.Deploy) {
            for ((id, base) in a.placements) squadrons[id] = squadrons.getValue(id).copy(base = base)
            updatePlayer(a.side) { it.copy(deployed = true) }
            event { v ->
                if (v == a.side) {
                    "配置を確定: " + a.placements.entries.joinToString("、") { (id, base) ->
                        "${content.squadron(squadrons.getValue(id).defId).nameJa}→${Ja.base(base)}"
                    }
                } else {
                    val cl = a.placements.values.count { it == BaseKind.CL }
                    "${Ja.side(a.side)}が飛行隊カード${a.placements.size}枚を伏せて配置(うちCL ${cl}枚)"
                }
            }
            if (Side.entries.all { p(it).deployed }) {
                phase = Phase.BID
                publicEvent("主導権の入札へ")
            }
        }

        fun bid(a: Action.SubmitBid) {
            updatePlayer(a.side) { it.copy(bid = a.sacrifice) }
            if (Side.entries.all { p(it).bid != null }) resolveBid()
        }

        private fun resolveBid() {
            val bu = p(Side.US).bid!!
            val bp = p(Side.PRC).bid!!
            val won: Side
            while (true) {
                val ru = rng.d4()
                val rp = rng.d4()
                publicEvent("入札: 米 出目$ru+${bu}枚=${ru + bu} / 中 出目$rp+${bp}枚=${rp + bp}")
                if (ru + bu != rp + bp) {
                    won = if (ru + bu > rp + bp) Side.US else Side.PRC
                    break
                }
                publicEvent("同点のため振り直し")
            }
            initiativeWinner = won
            publicEvent("${Ja.side(won)}が主導権を得た")
            updatePlayer(won) { it.copy(intel = IntelLevel.ADVANTAGE) }
            updatePlayer(won.opponent) { it.copy(intel = IntelLevel.NORMAL) }
            cyberAttempt(won, "主導権の獲得")
            if (phase == Phase.GAME_OVER) return
            for (side in Side.entries) {
                val mode = if (p(side).intel == IntelLevel.ADVANTAGE) RollMode.ADVANTAGE else RollMode.NORMAL
                val r = rng.roll(mode)
                // M1 は支援カードがないため、見られるカードはない
                publicEvent("${Ja.side(side)}の情報判定: ${r.describe()}(相手の支援カードは0枚)")
            }
            phase = Phase.CHOOSE_ORDER
        }

        private fun cyberAttempt(side: Side, reason: String) {
            val current = p(side).cyber
            if (current >= 4) return
            val need = current + 1
            val r = rng.roll()
            if (r.total >= need) {
                updatePlayer(side) { it.copy(cyber = current + 1) }
                publicEvent("${Ja.side(side)}のサイバー判定($reason): ${r.describe()}、必要${need}以上 → 成功。サイバー値 $current→${current + 1}")
                if (current + 1 >= 4) gameOver(side, "${Ja.side(side)}のサイバー値が4に達した")
            } else {
                publicEvent("${Ja.side(side)}のサイバー判定($reason): ${r.describe()}、必要${need}以上 → 失敗")
            }
        }

        fun chooseFirst(a: Action.ChooseFirst) {
            publicEvent("${Ja.side(a.side)}は${Ja.side(a.first)}を先攻にした")
            phase = Phase.TURNS
            turn = TurnState(active = a.first)
            startTurn(a.first)
        }

        // ---- 手番 ----

        /** 手番の開始時：弾切れの航空機は帰投、弾切れの艦艇は盤外へ（移動に数えない）。 */
        private fun startTurn(side: Side) {
            publicEvent("── ${Ja.side(side)}の手番 ──")
            val spent = tokens.values.filter { it.owner == side && it.winchester && it.isAirborne }
            for (tok in spent) {
                if (type(tok).cls.isAircraft) {
                    val sq = tok.squadronId?.let { squadrons[it] }
                    if (sq == null || sq.isDestroyed) {
                        event { v -> "${label(tok, v)}は帰投先の飛行隊が撃破されていたため失われた" }
                        destroyToken(tok.id, by = side.opponent, onGround = false, inAir = false)
                    } else {
                        event { v -> "${label(tok, v)}が弾切れのため帰投" }
                        tokens[tok.id] = tok.copy(zone = Zone.Grounded, winchester = false)
                    }
                } else {
                    event { v -> "${label(tok, v)}が弾切れのため盤外へ" }
                    tokens[tok.id] = tok.copy(zone = Zone.Offboard)
                }
            }
        }

        fun endTurn(passed: Boolean) {
            val side = turn.active
            if (passed) {
                val passes = turn.consecutivePasses + 1
                publicEvent("${Ja.side(side)}はパス")
                if (passes >= 2) {
                    endAto()
                    return
                }
                turn = TurnState(active = side.opponent, consecutivePasses = passes)
            } else {
                turn = TurnState(active = side.opponent, consecutivePasses = 0)
            }
            startTurn(side.opponent)
        }

        fun activate(a: Action.Activate) {
            turn = turn.copy(activated = true)
            val sq = squadrons.getValue(a.squadronId)
            val def = content.squadron(sq.defId)
            val tname = content.tokenType(def.tokenType).nameJa
            var ready = tokens.values.filter { it.squadronId == sq.id && it.zone == Zone.Grounded && !it.unavailable }
            if (!sq.faceUp) {
                squadrons[sq.id] = sq.copy(faceUp = true)
                publicEvent("${Ja.side(a.side)}が${def.nameJa}($tname)を起動(${Ja.base(sq.base)})")
                if (sq.base == BaseKind.CL) {
                    val r = rng.roll()
                    val n = min(r.total, ready.size)
                    publicEvent("CLからの発進判定: ${r.describe()} → ${n}機が発進、${ready.size - n}機はこのATOでは発進できない")
                    ready.drop(n).forEach { tokens[it.id] = it.copy(unavailable = true) }
                    ready = ready.take(n)
                }
            } else {
                publicEvent("${Ja.side(a.side)}の${def.nameJa}が再発進")
            }
            ready.forEach { tokens[it.id] = it.copy(zone = a.to) }
            event { v -> "${ready.size}機を${Ja.zone(a.to, v)}に配置" }
        }

        fun move(a: Action.Move) {
            turn = turn.copy(moved = true)
            val tok = tokens.getValue(a.tokenId)
            tokens[tok.id] = tok.copy(zone = a.to)
            event { v -> "${label(tok, v)}が${Ja.zone(tok.zone, v)}→${Ja.zone(a.to, v)}へ移動" }
        }

        fun acquire(a: Action.Acquire) {
            turn = turn.copy(acquired = true)
            val seeker = tokens.getValue(a.tokenId)
            val seekerType = type(seeker)
            for (targetId in a.targetIds) {
                val target = tokens.getValue(targetId)
                if (target.acquired) {
                    event { v -> "${label(target, v)}は捕捉済みのため判定なし" }
                    continue
                }
                val need = type(target).acqValue
                val r = rng.roll(bonus = seekerType.acqBonus)
                val ok = r.total >= need
                event { v ->
                    "${label(seeker, v)}が${label(target, v)}(${Ja.zone(target.zone, v)})の捕捉を試みた: " +
                        "${r.describe()}、必要${need}以上 → ${if (ok) "成功。正体は${type(target).nameJa}" else "失敗"}"
                }
                if (ok) tokens[targetId] = target.copy(acquired = true)
            }
        }

        fun shootToken(a: Action.ShootToken) {
            turn = turn.copy(shot = true)
            val shooter = tokens.getValue(a.tokenId)
            val target = tokens.getValue(a.targetId)
            val st = type(shooter)
            val weapon = (if (type(target).cls.isAircraft) st.air else st.surface)
                ?: error("no weapon for target")
            val r = rng.roll(attackMode())
            val hit = r.total >= weapon.hit
            val onGround = target.zone == Zone.AtBase
            event { v ->
                "${label(shooter, v)}が${label(target, v)}を射撃: ${r.describe()}、必要${weapon.hit}以上 → ${if (hit) "命中" else "外れ"}"
            }
            if (hit) {
                val damage = if (weapon.explosive) damageRoll() else 1
                damageToken(target.id, damage, by = a.side, onGround = onGround)
            }
            applyWinchester(shooter.id, r.natural)
        }

        fun strikeSquadron(a: Action.StrikeSquadron) {
            turn = turn.copy(shot = true)
            val shooter = tokens.getValue(a.tokenId)
            val weapon = type(shooter).surface ?: error("no surface weapon")
            val sq = squadrons.getValue(a.squadronId)
            val r = rng.roll(attackMode())
            val hit = r.total >= weapon.hit
            event { v ->
                val name = if (sq.faceUp || v == sq.owner) content.squadron(sq.defId).nameJa else "伏せた飛行隊"
                "${label(shooter, v)}が${Ja.side(sq.owner)}の${Ja.base(sq.base)}の${name}を攻撃: " +
                    "${r.describe()}、必要${weapon.hit}以上 → ${if (hit) "命中" else "外れ"}"
            }
            if (hit) {
                val damage = if (weapon.explosive) damageRoll() else 1
                damageSquadron(sq.id, damage, by = a.side)
            }
            applyWinchester(shooter.id, r.natural)
        }

        fun strikeBlock(a: Action.StrikeAirbaseBlock) {
            turn = turn.copy(shot = true)
            val shooter = tokens.getValue(a.tokenId)
            val weapon = type(shooter).surface ?: error("no surface weapon")
            val enemy = a.side.opponent
            val r = rng.roll(attackMode())
            val hit = r.total >= weapon.hit
            event { v ->
                "${label(shooter, v)}が${Ja.side(enemy)}の基地施設を攻撃: ${r.describe()}、必要${weapon.hit}以上 → ${if (hit) "命中" else "外れ"}"
            }
            if (hit) hitAirbaseBlocks(enemy, if (weapon.explosive) damageRoll() else 1, by = a.side)
            applyWinchester(shooter.id, r.natural)
        }

        /** 有利・不利の要因を集めて1回だけ決める。M1 には要因がない（態勢・ADA・カードは M2 以降）。 */
        private fun attackMode(): RollMode = resolveMode(advantage = false, disadvantage = false)

        private fun damageRoll(): Int {
            val r = rng.roll()
            publicEvent("損害判定(爆発ダイス): ${r.describe()} → ${r.total}点")
            return r.total
        }

        private fun applyWinchester(tokenId: String, natural: Int) {
            val tok = tokens.getValue(tokenId)
            if (tok.isDestroyed) return
            val t = type(tok)
            val updated = when (t.winchester) {
                WinchesterType.STANDARD -> if (natural < 4) tok.copy(winchester = true) else tok
                WinchesterType.INFINITE -> if (natural <= 2) tok.copy(winchester = true) else tok
                WinchesterType.NONE -> tok
                WinchesterType.SALVO -> if (natural < 4) {
                    val used = tok.salvosUsed + 1
                    tok.copy(salvosUsed = used, winchester = used >= (t.salvos ?: 1))
                } else tok
            }
            tokens[tokenId] = updated
            if (updated.winchester && !tok.winchester) {
                event { v -> "${label(updated, v)}は弾切れ(Winchester)" }
            } else if (natural == 4 && t.winchester == WinchesterType.STANDARD) {
                event { v -> "出目4のため${label(updated, v)}は弾を残した" }
            }
        }

        private fun damageToken(id: String, amount: Int, by: Side, onGround: Boolean) {
            val tok = tokens.getValue(id)
            val hp = if (type(tok).cls == TokenClass.SHIP) rules.shipHp else 1
            val dmg = tok.damage + amount
            if (dmg >= hp) {
                destroyToken(id, by, onGround = onGround, inAir = tok.isAirborne && type(tok).cls.isAircraft)
            } else {
                tokens[id] = tok.copy(damage = dmg)
                event { v -> "${label(tok, v)}に損害$amount(残り耐久${hp - dmg})" }
            }
        }

        private fun destroyToken(id: String, by: Side, onGround: Boolean, inAir: Boolean) {
            val tok = tokens.getValue(id)
            if (tok.isDestroyed) return
            val t = type(tok)
            tokens[id] = tok.copy(zone = Zone.Destroyed, acquired = true)
            updatePlayer(by) {
                it.copy(captured = it.captured + CaptureRecord(
                    kind = CaptureKind.TOKEN, ato = ato, typeId = t.id, vpClass = t.vpClass,
                    onGround = onGround, inAir = inAir,
                ))
            }
            publicEvent("${Ja.side(tok.owner)}${t.nameJa}を撃破")
        }

        private fun damageSquadron(id: String, amount: Int, by: Side) {
            val sq = squadrons.getValue(id)
            val remaining = SquadronState.SQUADRON_HP - sq.damage
            val applied = min(amount, remaining)
            val updated = sq.copy(damage = sq.damage + applied)
            squadrons[id] = updated
            val name = if (sq.faceUp) content.squadron(sq.defId).nameJa else "伏せた飛行隊"
            if (updated.isDestroyed) {
                publicEvent("${Ja.side(sq.owner)}の${name}を撃破")
                updatePlayer(by) {
                    it.copy(captured = it.captured + CaptureRecord(kind = CaptureKind.SQUADRON, ato = ato))
                }
                // 地上にいる所属機（未発進・帰投済み・発進できなかった機体）と基地上の ADA も撃破
                tokens.values
                    .filter { it.squadronId == id && (it.zone == Zone.Grounded || it.zone == Zone.AtBase) }
                    .forEach { destroyToken(it.id, by, onGround = true, inAir = false) }
            } else {
                publicEvent("${Ja.side(sq.owner)}の${name}に損害$applied(あと${SquadronState.SQUADRON_HP - updated.damage}命中で撃破)")
            }
            // 余った損害は基地施設へ（攻撃側が割り振る正式な手順は M2 で実装する）
            val leftover = amount - applied
            if (leftover > 0 && sq.base == BaseKind.AIRBASE) hitAirbaseBlocks(sq.owner, leftover, by)
        }

        private fun hitAirbaseBlocks(owner: Side, amount: Int, by: Side) {
            val room = rules.airbaseBlocks - p(owner).airbaseBlocksHit
            val n = min(amount, room)
            if (n <= 0) return
            updatePlayer(owner) { it.copy(airbaseBlocksHit = it.airbaseBlocksHit + n) }
            updatePlayer(by) {
                it.copy(captured = it.captured + List(n) { CaptureRecord(kind = CaptureKind.AIRBASE_BLOCK, ato = ato) })
            }
            publicEvent("${Ja.side(owner)}の基地施設に損害$n(累計${p(owner).airbaseBlocksHit}/${rules.airbaseBlocks})")
        }

        // ---- ATO の終了とゲーム終了 ----

        private fun endAto() {
            publicEvent("両者が続けてパスしたため ATO $ato 終了")
            for (tok in tokens.values.filter { it.isAirborne }) {
                if (!type(tok).cls.isAircraft) {
                    tokens[tok.id] = tok.copy(zone = Zone.Offboard)
                    continue
                }
                val sq = tok.squadronId?.let { squadrons[it] }
                if (sq == null || sq.isDestroyed) {
                    event { v -> "${label(tok, v)}は帰投先の飛行隊が撃破されていたため失われた" }
                    destroyToken(tok.id, by = tok.owner.opponent, onGround = false, inAir = false)
                } else {
                    tokens[tok.id] = tokens.getValue(tok.id).copy(zone = Zone.Grounded, winchester = false)
                }
            }
            for (side in Side.entries) {
                val records = p(side).captured.filter { it.ato == ato }
                val gained = records.sumOf { recordVp(p(side).mission, it) }
                updatePlayer(side) { it.copy(vp = it.vp + gained) }
                event { v -> if (v == side) "ATO $ato の獲得VP: $gained(累計${p(side).vp})" else null }
            }
            if (ato < atoCount) startNextAto() else finishGame()
        }

        /** 次の ATO へ（態勢とドラフトは M2 で実装する）。損耗は持ち越す。 */
        private fun startNextAto() {
            ato += 1
            for (tok in tokens.values.filter { !it.isDestroyed }) {
                tokens[tok.id] = tok.copy(zone = Zone.Grounded, acquired = false, winchester = false, unavailable = false, salvosUsed = 0)
            }
            for (sq in squadrons.values) squadrons[sq.id] = sq.copy(faceUp = false)
            for (side in Side.entries) updatePlayer(side) { it.copy(deployed = false, bid = null) }
            initiativeWinner = null
            phase = Phase.DEPLOY
            publicEvent("ATO $ato/$atoCount 開始")
        }

        private fun finishGame() {
            val vu = p(Side.US).vp
            val vp = p(Side.PRC).vp
            val w = when {
                vu > vp -> Side.US
                vp > vu -> Side.PRC
                else -> null
            }
            gameOver(w, "全ATO終了。VP 米 $vu / 中 $vp")
        }

        private fun gameOver(w: Side?, reason: String) {
            phase = Phase.GAME_OVER
            winner = w
            gameOverReason = reason
            publicEvent("ゲーム終了: $reason → ${if (w == null) "引き分け" else "${Ja.side(w)}の勝ち"}")
        }
    }

    private companion object {
        val HIGH_VALUE = setOf(VpClass.SHIP, VpClass.BOMBER, VpClass.ADA, VpClass.AEW)
    }
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
