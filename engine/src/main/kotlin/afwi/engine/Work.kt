package afwi.engine

import kotlin.math.max
import kotlin.math.min

/**
 * apply の中で状態を書き換える作業台。GameState をほどいて可変の形で持ち、最後に toState で戻す。
 * 割り込み（応答カード・ミサイル防衛など）が起きたら pending に判断待ちを置いて止まり、
 * Respond を受け取ったら attack や lossQueue から続きを解決する。
 */
internal class Work(private val e: Engine, s: GameState) {
    private val content = e.content
    private val rules = e.rules
    private val campaignId = s.campaignId
    private val campaign = content.campaign(s.campaignId)
    private var ato = s.ato
    private val atoCount = s.atoCount
    private var phase = s.phase
    private val players = s.players.toMutableMap()
    private val tokens = LinkedHashMap(s.tokens)
    private val squadrons = LinkedHashMap(s.squadrons)
    private var turn = s.turn
    private var initiativeWinner = s.initiativeWinner
    private var initiativeHistory = s.initiativeHistory
    private var winner = s.winner
    private var gameOverReason = s.gameOverReason
    private var pending = s.pending
    private var attack = s.attack
    private val lossQueue = s.lossQueue.toMutableList()
    private val rng = Rng(s.rngState)
    private val log = s.log.toMutableList()

    fun toState() = GameState(
        campaignId = campaignId, ato = ato, atoCount = atoCount, phase = phase,
        players = players.toMap(), tokens = LinkedHashMap(tokens), squadrons = LinkedHashMap(squadrons),
        turn = turn, initiativeWinner = initiativeWinner, initiativeHistory = initiativeHistory,
        pending = pending, attack = attack, lossQueue = lossQueue.toList(),
        winner = winner, gameOverReason = gameOverReason, rngState = rng.state, log = log.toList(),
    )

    fun handle(a: Action) {
        when (a) {
            is Action.ChooseMission -> chooseMission(a)
            is Action.ChoosePosture -> choosePosture(a)
            is Action.Draft -> draft(a)
            is Action.ChooseEnablers -> chooseEnablers(a)
            is Action.Deploy -> deploy(a)
            is Action.SubmitBid -> bid(a)
            is Action.ChooseFirst -> chooseFirst(a)
            is Action.Activate -> activate(a)
            is Action.Move -> move(a)
            is Action.Acquire -> acquire(a)
            is Action.ShootToken -> shootToken(a)
            is Action.StrikeSquadron -> strikeSquadron(a)
            is Action.StrikeAirbaseBlock -> strikeBlock(a)
            is Action.PlayEnabler -> playEnabler(a)
            is Action.Respond -> respond(a)
            is Action.EndTurn -> endTurn(passed = false)
            is Action.Pass -> endTurn(passed = true)
        }
        drain()
    }

    // ---- 小道具 ----

    private fun p(side: Side) = players.getValue(side)
    private fun updatePlayer(side: Side, f: (PlayerState) -> PlayerState) { players[side] = f(p(side)) }
    private fun type(t: TokenState) = e.tokenType(t)
    private fun card(id: String) = content.enabler(id)
    private fun coord(t: TokenState) = e.coord(t)

    /** 陣営ごとに文を作る。null を返した陣営には見せない。 */
    private fun event(textFor: (Side) -> String?) {
        val texts = Side.entries.mapNotNull { v -> textFor(v)?.let { v to it } }.toMap()
        if (texts.isNotEmpty()) log += GameEvent(texts)
    }

    private fun publicEvent(text: String) = event { text }
    private fun privateEvent(side: Side, text: String) = event { v -> if (v == side) text else null }

    /** 見る側に正体が分かるときは機種名、分からないときは「未確認機」。 */
    private fun label(t: TokenState, viewer: Side): String =
        if (t.owner == viewer || t.acquired || phase == Phase.GAME_OVER) {
            "${Ja.side(t.owner)}${type(t).nameJa}"
        } else {
            "${Ja.side(t.owner)}の未確認機"
        }

    private fun <T> pickRandom(items: List<T>, n: Int): List<T> {
        val pool = items.toMutableList()
        val out = mutableListOf<T>()
        repeat(min(n, pool.size)) { out += pool.removeAt(rng.nextInt(pool.size)) }
        return out
    }

    // ---- キャンペーンと ATO の準備 ----

    fun beginCampaign() {
        if (Side.entries.all { p(it).mission != null }) {
            beginAtoSetup()
        } else {
            phase = Phase.MISSION
            publicEvent("ミッションの選択へ(相手には非公開)")
        }
    }

    private fun chooseMission(a: Action.ChooseMission) {
        updatePlayer(a.side) { it.copy(mission = a.mission) }
        event { v -> if (v == a.side) "ミッション「${Ja.mission(a.mission)}」を選んだ" else "${Ja.side(a.side)}がミッションを選んだ" }
        if (Side.entries.all { p(it).mission != null }) beginAtoSetup()
    }

    /** ATO の準備を始める。選択肢が1つしかない手順は自動で済ませる。 */
    fun beginAtoSetup() {
        phase = Phase.POSTURE
        for (side in Side.entries) {
            val choices = e.postureChoices(campaign, p(side))
            if (choices.size == 1) updatePlayer(side) { it.copy(posture = choices.single().id) }
        }
        if (Side.entries.all { p(it).posture != null }) revealPostures() else publicEvent("態勢の選択へ")
    }

    private fun choosePosture(a: Action.ChoosePosture) {
        updatePlayer(a.side) { it.copy(posture = a.postureId) }
        event { v -> if (v == a.side) "態勢「${content.posture(a.postureId).nameJa}」を選んだ" else "${Ja.side(a.side)}が態勢を選んだ" }
        if (Side.entries.all { p(it).posture != null }) revealPostures()
    }

    private fun revealPostures() {
        for (side in Side.entries) {
            val posture = content.posture(p(side).posture!!)
            updatePlayer(side) { it.copy(usedPostures = it.usedPostures + posture.id) }
            publicEvent("${Ja.side(side)}の態勢: ${posture.nameJa}(飛行隊${posture.squadronLimit}・支援カード${posture.enablerLimit})")
            if (PostureEffect.GENERATE_ADA in posture.effects && posture.adaTokenType != null) {
                val id = "${side.name}-P$ato"
                tokens[id] = TokenState(id = id, typeId = posture.adaTokenType, owner = side, squadronId = null, zone = Zone.AtBase)
                publicEvent("${Ja.side(side)}の基地に防空部隊が展開した")
            }
        }
        if (campaign.fixedSquadrons.isNotEmpty()) {
            for (side in Side.entries) updatePlayer(side) { it.copy(drafted = true) }
            afterDraft()
        } else {
            phase = Phase.DRAFT
            publicEvent("飛行隊の選択へ")
        }
    }

    private fun draft(a: Action.Draft) {
        for (id in a.squadronIds) squadrons[id] = squadrons.getValue(id).copy(drafted = true, base = BaseKind.AIRBASE)
        updatePlayer(a.side) { it.copy(drafted = true) }
        event { v ->
            if (v == a.side) {
                "参加させる飛行隊: " + a.squadronIds.joinToString("、") { content.squadron(squadrons.getValue(it).defId).nameJa }
            } else {
                "${Ja.side(a.side)}が飛行隊${a.squadronIds.size}枚を選んだ"
            }
        }
        if (Side.entries.all { p(it).drafted }) afterDraft()
    }

    private fun afterDraft() {
        if (campaign.enablers) {
            phase = Phase.ENABLERS
            publicEvent("支援カードの選択へ")
        } else {
            phase = Phase.DEPLOY
            publicEvent("飛行隊の配置へ")
        }
    }

    private fun chooseEnablers(a: Action.ChooseEnablers) {
        updatePlayer(a.side) { it.copy(hand = a.cardIds.toList(), enablersChosen = true) }
        event { v ->
            if (v == a.side) {
                "支援カード: " + a.cardIds.joinToString("、") { card(it).nameJa }.ifEmpty { "なし" }
            } else {
                "${Ja.side(a.side)}が支援カード${a.cardIds.size}枚を選んだ"
            }
        }
        if (Side.entries.all { p(it).enablersChosen }) {
            phase = Phase.DEPLOY
            publicEvent("飛行隊の配置へ")
        }
    }

    private fun deploy(a: Action.Deploy) {
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
        if (Side.entries.all { p(it).deployed }) enterInitiative()
    }

    private fun enterInitiative() {
        val kills = Side.entries.associateWith { side -> p(side).captured.count { it.kind == CaptureKind.TOKEN } }
        if (campaign.bidRule == BidRule.FIRST_ATO_ONLY_ALTERNATE && initiativeHistory.isNotEmpty()) {
            // 大会: 第2 ATO 以降は、前の ATO で主導権を持たなかった側が主導権を持つ
            grantInitiative(initiativeHistory.last().opponent, "大会のルールで主導権が移った")
        } else if (campaign.bidRule == BidRule.LEADER_AFTER_FIRST && ato > 1 && kills.getValue(Side.US) != kills.getValue(Side.PRC)) {
            // 世界が見ている: 撃破数で上回る側に国際世論が傾き、主導権を得る
            val leader = if (kills.getValue(Side.US) > kills.getValue(Side.PRC)) Side.US else Side.PRC
            grantInitiative(leader, "撃破数 米${kills.getValue(Side.US)}・中${kills.getValue(Side.PRC)} で国際世論が傾いた")
        } else {
            phase = Phase.BID
            publicEvent("主導権の入札へ")
        }
    }

    private fun bid(a: Action.SubmitBid) {
        // 差し出したカードは山札に戻り、この ATO では使えない
        updatePlayer(a.side) { it.copy(bid = a.sacrifice.size, hand = it.hand - a.sacrifice) }
        if (a.sacrifice.isNotEmpty()) {
            privateEvent(a.side, "入札に差し出した: " + a.sacrifice.joinToString("、") { card(it).nameJa })
        }
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
        grantInitiative(won, "入札に勝った")
    }

    /** 主導権を与える：先攻の決定権、サイバー判定1回、情報優位。続いて両者が情報判定。 */
    private fun grantInitiative(won: Side, reason: String) {
        initiativeWinner = won
        initiativeHistory = initiativeHistory + won
        publicEvent("${Ja.side(won)}が主導権を得た($reason)")
        updatePlayer(won) { it.copy(intel = IntelLevel.ADVANTAGE) }
        updatePlayer(won.opponent) { it.copy(intel = IntelLevel.NORMAL) }
        cyberAttempt(won, "主導権の獲得")
        if (phase == Phase.GAME_OVER) return
        for (side in Side.entries) {
            val mode = if (p(side).intel == IntelLevel.ADVANTAGE) RollMode.ADVANTAGE else RollMode.NORMAL
            val r = rng.roll(mode)
            val enemyHand = p(side.opponent).hand
            val seen = pickRandom(enemyHand, r.total)
            updatePlayer(side) { it.copy(knownEnemyCards = seen) }
            event { v ->
                when {
                    v == side && seen.isNotEmpty() ->
                        "情報判定: ${r.describe()} → 相手の支援カード${seen.size}枚を見た: ${seen.joinToString("、") { card(it).nameJa }}"
                    v == side -> "情報判定: ${r.describe()}(相手の支援カードは${enemyHand.size}枚)"
                    else -> "${Ja.side(side)}の情報判定: ${r.describe()} → 支援カード${seen.size}枚を見られた"
                }
            }
        }
        phase = Phase.CHOOSE_ORDER
    }

    private fun cyberAttempt(side: Side, reason: String) {
        val current = p(side).cyber
        if (current >= 4) return
        val need = current + 1
        val r = rng.roll()
        if (r.total >= need) {
            publicEvent("${Ja.side(side)}のサイバー判定($reason): ${r.describe()}、必要${need}以上 → 成功")
            changeCyber(side, +1)
        } else {
            publicEvent("${Ja.side(side)}のサイバー判定($reason): ${r.describe()}、必要${need}以上 → 失敗")
        }
    }

    private fun changeCyber(side: Side, delta: Int) {
        val before = p(side).cyber
        val after = (before + delta).coerceIn(0, 4)
        if (after == before) return
        updatePlayer(side) { it.copy(cyber = after) }
        publicEvent("${Ja.side(side)}のサイバー値 $before→$after")
        if (after >= 4) gameOver(side, "${Ja.side(side)}のサイバー値が4に達した")
    }

    private fun chooseFirst(a: Action.ChooseFirst) {
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
            val sq = tok.squadronId?.let { squadrons[it] }
            when {
                !type(tok).cls.isAircraft || tok.squadronId == null -> {
                    event { v -> "${label(tok, v)}が弾切れのため盤外へ" }
                    tokens[tok.id] = tok.copy(zone = Zone.Offboard)
                }
                sq == null || sq.isDestroyed -> {
                    event { v -> "${label(tok, v)}は帰投先の飛行隊が撃破されていたため失われた" }
                    destroyToken(tok.id, by = side.opponent, onGround = false, inAir = false)
                }
                else -> {
                    event { v -> "${label(tok, v)}が弾切れのため帰投" }
                    tokens[tok.id] = tok.copy(zone = Zone.Grounded, winchester = false)
                }
            }
        }
    }

    private fun endTurn(passed: Boolean) {
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

    private fun activate(a: Action.Activate) {
        turn = turn.copy(activated = true)
        val sq = squadrons.getValue(a.squadronId)
        val def = content.squadron(sq.defId)
        val tname = content.tokenType(def.tokenType).nameJa
        var ready = tokens.values.filter { it.squadronId == sq.id && it.zone == Zone.Grounded && !it.unavailable }
        if (!sq.faceUp) {
            squadrons[sq.id] = sq.copy(faceUp = true)
            updatePlayer(a.side) { it.copy(activations = it.activations + ato) }
            publicEvent("${Ja.side(a.side)}が${def.nameJa}($tname)を起動(${Ja.base(sq.base)})")
            val postureMax = p(a.side).posture
                ?.let { PostureEffect.CL_MAX_GENERATION in content.posture(it).effects } == true
            val cardMax = ModKind.CL_MAX_GENERATION in p(a.side).modifiers
            if (sq.base == BaseKind.CL && (postureMax || cardMax)) {
                if (!postureMax) updatePlayer(a.side) { it.copy(modifiers = it.modifiers - ModKind.CL_MAX_GENERATION) }
                publicEvent("${if (postureMax) "態勢" else "支援カード"}の効果で CL から全機が発進")
            } else if (sq.base == BaseKind.CL) {
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

    private fun move(a: Action.Move) {
        turn = turn.copy(moved = true)
        val tok = tokens.getValue(a.tokenId)
        tokens[tok.id] = tok.copy(zone = a.to)
        event { v -> "${label(tok, v)}が${Ja.zone(tok.zone, v)}→${Ja.zone(a.to, v)}へ移動" }
    }

    private fun acquireMode(seeker: Side): RollMode {
        val dis = ModKind.ENEMY_ACQUIRE_DIS in p(seeker.opponent).modifiers
        if (dis) publicEvent("不利の要因: ${Ja.side(seeker.opponent)}の電子戦")
        return resolveMode(advantage = false, disadvantage = dis)
    }

    /** 捕捉を1回試みる。成功したら true。 */
    private fun tryAcquire(seeker: TokenState, targetId: String): Boolean {
        val target = tokens.getValue(targetId)
        if (target.acquired) {
            event { v -> "${label(target, v)}は捕捉済みのため判定なし" }
            return true
        }
        val need = type(target).acqValue
        val r = rng.roll(acquireMode(seeker.owner), bonus = type(seeker).acqBonus)
        val ok = r.total >= need
        event { v ->
            "${label(seeker, v)}が${label(target, v)}(${Ja.zone(target.zone, v)})の捕捉を試みた: " +
                "${r.describe()}、必要${need}以上 → ${if (ok) "成功。正体は${type(target).nameJa}" else "失敗"}"
        }
        if (ok) tokens[targetId] = target.copy(acquired = true)
        return ok
    }

    private fun acquire(a: Action.Acquire) {
        turn = turn.copy(acquired = true)
        val seeker = tokens.getValue(a.tokenId)
        val failed = a.targetIds.filterNot { tryAcquire(seeker, it) }
        val seekerCls = type(seeker).cls
        if (failed.isNotEmpty() && (seekerCls == TokenClass.UAS_ATTACK || seekerCls == TokenClass.UAS_RECON)) {
            val cards = responseCards(a.side, Trigger.OWN_UAS_ACQUIRE_FAILED)
            if (cards.isNotEmpty()) {
                pending = Pending(
                    kind = WindowKind.UAS_REROLL, decider = a.side, cardOptions = cards,
                    tokenId = failed.first(), seekerId = seeker.id,
                    promptJa = "無人機の捕捉が失敗した。振り直すか?",
                )
            }
        }
    }

    private fun shootToken(a: Action.ShootToken) {
        turn = turn.copy(shot = true)
        val shooter = tokens.getValue(a.tokenId)
        val target = tokens.getValue(a.targetId)
        val st = type(shooter)
        val air = type(target).cls.isAircraft
        val weapon = (if (air) st.air else st.surface) ?: error("no weapon for target")
        attack = AttackCtx(
            attacker = a.side, kind = AttackKind.TOKEN, shooterId = shooter.id, targetTokenId = target.id,
            hit = weapon.hit, explosive = weapon.explosive, surface = !air,
        )
        event { v -> "${label(shooter, v)}が${label(target, v)}を射撃" }
    }

    private fun strikeSquadron(a: Action.StrikeSquadron) {
        turn = turn.copy(shot = true)
        val shooter = tokens.getValue(a.tokenId)
        val weapon = type(shooter).surface ?: error("no surface weapon")
        val sq = squadrons.getValue(a.squadronId)
        attack = AttackCtx(
            attacker = a.side, kind = AttackKind.SQUADRON, shooterId = shooter.id, targetSquadronId = sq.id,
            hit = weapon.hit, explosive = weapon.explosive, surface = true,
        )
        event { v ->
            val name = if (sq.faceUp || v == sq.owner) content.squadron(sq.defId).nameJa else "伏せた飛行隊"
            "${label(shooter, v)}が${Ja.side(sq.owner)}の${Ja.base(sq.base)}の${name}を攻撃"
        }
    }

    private fun strikeBlock(a: Action.StrikeAirbaseBlock) {
        turn = turn.copy(shot = true)
        val shooter = tokens.getValue(a.tokenId)
        val weapon = type(shooter).surface ?: error("no surface weapon")
        attack = AttackCtx(
            attacker = a.side, kind = AttackKind.BLOCK, shooterId = shooter.id,
            hit = weapon.hit, explosive = weapon.explosive, surface = true,
        )
        event { v -> "${label(shooter, v)}が${Ja.side(a.side.opponent)}の基地施設を攻撃" }
    }

    // ---- 支援カード ----

    /** 手札から使えるようになった応答カード。 */
    private fun responseCards(side: Side, trigger: Trigger, playedTags: List<String> = emptyList()): List<String> =
        p(side).hand.filter { id ->
            val r = card(id).response ?: return@filter false
            r.trigger == trigger && (r.tag == null || r.tag in playedTags)
        }

    /** カードを手札から出す。単発カードは使用済みになる。 */
    private fun useCard(side: Side, id: String) {
        updatePlayer(side) {
            it.copy(hand = it.hand - id, spent = if (card(id).use == UseType.SINGLE) it.spent + id else it.spent)
        }
    }

    private fun recordPlay(side: Side, id: String) {
        updatePlayer(side) { it.copy(cardPlays = it.cardPlays + CardPlay(ato, id)) }
    }

    private fun playEnabler(a: Action.PlayEnabler) {
        turn = turn.copy(enablerUsed = true)
        val c = card(a.cardId)
        useCard(a.side, a.cardId)
        publicEvent("${Ja.side(a.side)}が支援カード「${c.nameJa}」を使った")
        val play = CardPlayCtx(a.side, a.cardId, a.option, a.target, a.zone)
        val cancels = responseCards(a.side.opponent, Trigger.ENEMY_CARD_TAG, c.tags)
        if (cancels.isNotEmpty()) {
            pending = Pending(
                kind = WindowKind.CARD_CANCEL, decider = a.side.opponent, cardOptions = cancels, play = play,
                promptJa = "${Ja.side(a.side)}が「${c.nameJa}」を使った。打ち消すか?",
            )
        } else {
            executePlay(play)
        }
    }

    private fun executePlay(play: CardPlayCtx) {
        recordPlay(play.side, play.cardId)
        runEffect(play, card(play.cardId).action ?: return)
    }

    private fun runEffect(play: CardPlayCtx, effect: Effect) {
        if (phase == Phase.GAME_OVER) return
        val side = play.side
        val enemy = side.opponent
        when (effect) {
            is Effect.Choice -> runEffect(play, effect.options[play.option])
            is Effect.Sequence -> effect.steps.forEach { runEffect(play, it) }
            is Effect.AcquireDice -> {
                val r = rng.roll()
                publicEvent("捕捉の数: ${r.describe()}")
                acquireRandom(side, r.total)
                if (effect.cyberOn4 && r.natural == 4) changeCyber(side, +1)
            }
            is Effect.AcquireCyber -> acquireRandom(side, p(side).cyber * effect.multiplier)
            Effect.CyberAttempt -> cyberAttempt(side, card(play.cardId).nameJa)
            is Effect.CyberDegrade -> changeCyber(enemy, -effect.amount)
            is Effect.RemoveTokens -> {
                val n = p(side).cyber * effect.multiplier
                val removed = pickRandom(tokens.values.filter { it.owner == enemy && it.isAirborne }, n)
                for (t in removed) {
                    tokens[t.id] = t.copy(zone = if (t.squadronId == null) Zone.Offboard else Zone.Grounded, winchester = false)
                    event { v -> "${label(t, v)}を盤上から除いた" }
                }
                if (removed.isEmpty()) publicEvent("除ける駒がなかった")
            }
            Effect.ForceDiscard -> {
                val n = p(side).cyber
                val discarded = pickRandom(p(enemy).hand, n)
                for (id in discarded) useCard(enemy, id)
                updatePlayer(side) { it.copy(knownEnemyCards = it.knownEnemyCards - discarded.toSet()) }
                publicEvent("${Ja.side(enemy)}は支援カード${discarded.size}枚を捨てた" +
                    if (discarded.isEmpty()) "" else ": " + discarded.joinToString("、") { card(it).nameJa })
            }
            Effect.DestroyUas -> {
                val n = p(side).cyber
                val uas = tokens.values.filter {
                    it.owner == enemy && it.isAirborne &&
                        (type(it).cls == TokenClass.UAS_ATTACK || type(it).cls == TokenClass.UAS_RECON)
                }
                val hits = pickRandom(uas, n)
                if (hits.isEmpty()) publicEvent("撃破できる無人機がいなかった")
                hits.forEach { destroyToken(it.id, by = side, onGround = false, inAir = true) }
            }
            is Effect.Modifier -> {
                updatePlayer(side) { it.copy(modifiers = it.modifiers + effect.mod) }
                publicEvent("${Ja.side(side)}: ${modifierText(effect.mod)}")
            }
            is Effect.Generate -> {
                val zone = if (effect.atBase) Zone.AtBase else play.zone ?: Zone.Band(side.bandToUsFrame(1))
                val t = content.tokenType(effect.tokenType)
                repeat(effect.count) {
                    val id = "${side.name}-E${tokens.size + 1}"
                    tokens[id] = TokenState(id = id, typeId = t.id, owner = side, squadronId = null, zone = zone)
                }
                updatePlayer(side) { it.copy(enablerTokens = it.enablerTokens + List(effect.count) { ato }) }
                event { v -> "${Ja.side(side)}が${t.nameJa}を${effect.count}個、${Ja.zone(zone, v)}に発生させた" }
            }
            is Effect.BaseStrike -> attack = AttackCtx(
                attacker = side, kind = AttackKind.CARD_BASE, cardId = play.cardId, hit = effect.hit,
                explosive = true, surface = true, unblockable = effect.unblockable, sof = effect.sof,
            )
            Effect.DestroyShip -> {
                val target = play.target?.let { tokens[it] }
                if (target == null || !target.isAirborne) {
                    publicEvent("目標の艦艇はもういなかった")
                } else {
                    destroyToken(target.id, by = side, onGround = false, inAir = false)
                }
            }
            Effect.ExtraSquadron -> {
                val id = play.target ?: return
                squadrons[id] = squadrons.getValue(id).copy(drafted = true, base = BaseKind.AIRBASE, faceUp = false)
                event { v ->
                    if (v == side) "${content.squadron(squadrons.getValue(id).defId).nameJa}を基地に追加した"
                    else "${Ja.side(side)}が飛行隊カード1枚を基地に追加した"
                }
            }
            is Effect.ReturnSpent -> {
                val id = play.target ?: return
                updatePlayer(side) { it.copy(spent = it.spent - id, hand = it.hand + id) }
                publicEvent("${Ja.side(side)}が「${card(id).nameJa}」を手札に戻した")
            }
            is Effect.AirRangeUpgrade -> {
                val id = play.target ?: return
                updatePlayer(side) { it.copy(airRange = it.airRange + (id to effect.range)) }
                publicEvent("${Ja.side(side)}の${content.squadron(squadrons.getValue(id).defId).nameJa}の空対空射程が${effect.range}になった")
            }
        }
    }

    private fun acquireRandom(side: Side, n: Int) {
        val candidates = tokens.values.filter { it.owner == side.opponent && it.isAirborne && !it.acquired }
        val found = pickRandom(candidates, n)
        if (found.isEmpty()) publicEvent("捕捉できる駒がなかった")
        for (t in found) {
            tokens[t.id] = t.copy(acquired = true)
            event { v -> "${label(t.copy(acquired = true), v)}(${Ja.zone(t.zone, v)})を捕捉した" }
        }
    }

    private fun modifierText(mod: ModKind): String = when (mod) {
        ModKind.OWN_AIR_TO_AIR_ADV -> "この ATO の空対空判定は有利"
        ModKind.ENEMY_ACQUIRE_DIS -> "この ATO の相手の捕捉判定は不利"
        ModKind.ENEMY_ATTACK_DIS -> "この ATO の相手の攻撃判定は不利"
        ModKind.OWN_ATTACK_ADV -> "この ATO の攻撃判定は有利"
        ModKind.ENEMY_BASE_ATTACK_DIS -> "この ATO の相手の基地攻撃は不利"
        ModKind.H6K_ADV -> "この ATO の H-6K の判定は有利"
        ModKind.NEXT_AIR_TO_AIR_ADV -> "次の空対空攻撃は有利"
        ModKind.CL_MAX_GENERATION -> "CL からの次の発進は全機"
        ModKind.SOF_IMMUNE -> "この ATO は基地が特殊作戦の攻撃を受けない"
    }

    // ---- 割り込みへの返答 ----

    private fun respond(a: Action.Respond) {
        val w = pending ?: return
        pending = null
        val used = a.cardId
        if (used != null) {
            useCard(a.side, used)
            recordPlay(a.side, used)
            publicEvent("${Ja.side(a.side)}が応答カード「${card(used).nameJa}」を使った")
        }
        when (w.kind) {
            WindowKind.CARD_CANCEL -> {
                val play = w.play!!
                if (used != null) {
                    publicEvent("「${card(play.cardId).nameJa}」は打ち消された")
                } else {
                    executePlay(play)
                }
            }
            WindowKind.AUTO_HIT -> attack = attack!!.copy(autoHit = used != null)
            WindowKind.MISSILE_DEFENSE -> a.shipId?.let { shipId ->
                val ship = tokens.getValue(shipId)
                val t = type(ship)
                val usedSalvo = ship.salvosUsed + 1
                tokens[shipId] = ship.copy(salvosUsed = usedSalvo, winchester = usedSalvo >= (t.salvos ?: 1))
                attack = attack!!.copy(md = true)
                event { v -> "${label(ship, v)}がミサイル防衛を宣言(斉射ブロックを1つ使用)" }
            }
            WindowKind.MD_COUNTER -> if (used != null) {
                attack = attack!!.copy(md = false)
                publicEvent("ミサイル防衛は打ち消された")
            }
            WindowKind.HIT_CANCEL -> if (used != null) {
                attack = attack!!.copy(hitResult = false)
                publicEvent("命中は打ち消された")
            }
            WindowKind.DAMAGE_CANCEL -> if (used != null) {
                attack = attack!!.copy(damage = 0)
                publicEvent("基地への損害はすべて打ち消された")
            }
            WindowKind.AIRCRAFT_LOST -> if (used != null) recoverAircraft(w.tokenId!!)
            WindowKind.SQUADRON_DEPLETED -> if (used != null) regenerateSquadron(w.squadronId!!)
            WindowKind.UAS_REROLL -> if (used != null) {
                val seeker = tokens.getValue(w.seekerId!!)
                publicEvent("捕捉を振り直す")
                tryAcquire(seeker, w.tokenId!!)
            }
        }
    }

    private fun recoverAircraft(tokenId: String) {
        val tok = tokens.getValue(tokenId)
        if (!tok.isDestroyed) return
        tokens[tokenId] = tok.copy(zone = Zone.Band(tok.owner.bandToUsFrame(1)), acquired = false, winchester = false, damage = 0)
        removeCaptureRecords(tok.owner.opponent, setOf(tokenId))
        publicEvent("${Ja.side(tok.owner)}${type(tok).nameJa}は救出され、撃破に数えない")
    }

    private fun regenerateSquadron(squadronId: String) {
        val sq = squadrons.getValue(squadronId)
        if (sq.isDestroyed) return
        val revived = tokens.values.filter { it.squadronId == squadronId && it.isDestroyed }
        revived.forEach {
            tokens[it.id] = it.copy(zone = Zone.Grounded, acquired = false, winchester = false, unavailable = false, damage = 0)
        }
        removeCaptureRecords(sq.owner.opponent, revived.map { it.id }.toSet())
        publicEvent("${Ja.side(sq.owner)}の${content.squadron(sq.defId).nameJa}が完全に再生した(${revived.size}機)")
    }

    private fun removeCaptureRecords(holder: Side, tokenIds: Set<String>) {
        updatePlayer(holder) { pl -> pl.copy(captured = pl.captured.filterNot { it.tokenId in tokenIds }) }
    }

    // ---- 攻撃の解決 ----

    /** 判断待ちがなくなるまで、進行中の攻撃と損失への応答を解決する。 */
    private fun drain() {
        while (pending == null && phase != Phase.GAME_OVER) {
            if (attack != null) {
                proceedAttack()
                continue
            }
            val loss = lossQueue.removeFirstOrNull() ?: return
            openLossWindow(loss)
        }
    }

    private fun openLossWindow(loss: LossEvent) {
        when (loss.kind) {
            WindowKind.AIRCRAFT_LOST -> {
                val tok = tokens[loss.tokenId] ?: return
                if (!tok.isDestroyed) return
                val cards = responseCards(loss.owner, Trigger.OWN_AIRCRAFT_LOST)
                if (cards.isNotEmpty()) {
                    pending = Pending(
                        kind = loss.kind, decider = loss.owner, cardOptions = cards, tokenId = tok.id,
                        promptJa = "${type(tok).nameJa}が撃破された。救出するか?",
                    )
                }
            }
            WindowKind.SQUADRON_DEPLETED -> {
                val sq = squadrons[loss.squadronId] ?: return
                if (sq.isDestroyed) return
                val cards = responseCards(loss.owner, Trigger.OWN_SQUADRON_DEPLETED)
                if (cards.isNotEmpty()) {
                    pending = Pending(
                        kind = loss.kind, decider = loss.owner, cardOptions = cards, squadronId = sq.id,
                        promptJa = "${content.squadron(sq.defId).nameJa}が機体をすべて失った。再生するか?",
                    )
                }
            }
            else -> Unit
        }
    }

    private fun isBaseTarget(a: AttackCtx): Boolean = when (a.kind) {
        AttackKind.SQUADRON, AttackKind.BLOCK, AttackKind.CARD_BASE -> true
        AttackKind.TOKEN -> tokens[a.targetTokenId]?.zone == Zone.AtBase
    }

    private fun isAirbaseTarget(a: AttackCtx): Boolean = when (a.kind) {
        AttackKind.SQUADRON -> squadrons.getValue(a.targetSquadronId!!).base == BaseKind.AIRBASE
        AttackKind.BLOCK, AttackKind.CARD_BASE -> true
        AttackKind.TOKEN -> tokens[a.targetTokenId]?.zone == Zone.AtBase
    }

    /** 攻撃の発射地点と目標地点（米側基準の座標）。ミサイル防衛の交戦域との交差を見るのに使う。 */
    private fun attackPath(a: AttackCtx): Pair<Int, Int> {
        val from = a.shooterId?.let { coord(tokens.getValue(it)) } ?: a.attacker.baseCoord
        val to = if (a.kind == AttackKind.TOKEN) coord(tokens.getValue(a.targetTokenId!!)) else a.attacker.opponent.baseCoord
        return min(from, to) to max(from, to)
    }

    private fun missileDefenseShips(a: AttackCtx): List<String> {
        if (!a.surface || a.unblockable || a.sof) return emptyList()
        val (lo, hi) = attackPath(a)
        return tokens.values.filter { t ->
            val tt = type(t)
            t.owner == a.attacker.opponent && t.isAirborne && tt.cls == TokenClass.SHIP && tt.wez != null &&
                t.salvosUsed < (tt.salvos ?: 0) && coord(t) - tt.wez <= hi && coord(t) + tt.wez >= lo
        }.map { it.id }
    }

    /** 有利・不利の要因を集めて1回だけ決める（仕様書 §6.1）。 */
    private fun attackMode(a: AttackCtx): RollMode {
        val defender = a.attacker.opponent
        val shooter = a.shooterId?.let { tokens.getValue(it) }
        val target = a.targetTokenId?.let { tokens[it] }
        val airToAir = a.kind == AttackKind.TOKEN && target != null && type(target).cls.isAircraft
        val adv = mutableListOf<String>()
        val dis = mutableListOf<String>()
        val mine = p(a.attacker).modifiers
        val theirs = p(defender).modifiers
        if (ModKind.OWN_ATTACK_ADV in mine) adv += "攻撃電子戦"
        if (airToAir && ModKind.OWN_AIR_TO_AIR_ADV in mine) adv += "弾薬改良"
        if (airToAir && ModKind.NEXT_AIR_TO_AIR_ADV in mine) {
            adv += "精鋭パイロット"
            updatePlayer(a.attacker) { it.copy(modifiers = it.modifiers - ModKind.NEXT_AIR_TO_AIR_ADV) }
        }
        if (shooter != null && shooter.typeId == "H-6K" && ModKind.H6K_ADV in mine) adv += "H-6K の集中運用"
        if (ModKind.ENEMY_ATTACK_DIS in theirs) dis += "${Ja.side(defender)}の電子戦"
        if (isBaseTarget(a)) {
            if (ModKind.ENEMY_BASE_ATTACK_DIS in theirs) dis += "${Ja.side(defender)}の宇宙配備電子戦"
            val reasons = e.baseDefenseReasons(defender, isAirbaseTarget(a), tokens.values, p(defender).posture)
            // 防げない攻撃と特殊作戦は、防空(ミサイル防衛)の影響を受けない
            dis += if (a.unblockable || a.sof) reasons.filter { it != "防空の交戦域内" } else reasons
        }
        if (a.md) dis += "ミサイル防衛"
        val shooterBand = (shooter?.zone as? Zone.Band)?.pos
        if (shooterBand != null && tokens.values.any {
                it.owner == defender && type(it).cls == TokenClass.EW && it.zone == Zone.Band(shooterBand)
            }
        ) dis += "EC-130 の妨害"
        val mode = resolveMode(adv.isNotEmpty(), dis.isNotEmpty())
        if (adv.isNotEmpty() || dis.isNotEmpty()) {
            publicEvent("判定の修正: " + listOfNotNull(
                adv.takeIf { it.isNotEmpty() }?.let { "有利(${it.joinToString("・")})" },
                dis.takeIf { it.isNotEmpty() }?.let { "不利(${it.joinToString("・")})" },
            ).joinToString(" ") + " → ${when (mode) { RollMode.ADVANTAGE -> "有利"; RollMode.DISADVANTAGE -> "不利"; else -> "相殺" }}")
        }
        return mode
    }

    /** 攻撃を1段ずつ進める。割り込みが必要なら pending を置いて戻る。 */
    private fun proceedAttack() {
        var a = attack ?: return
        val defender = a.attacker.opponent

        // 1. 前方基地（自軍の対地攻撃を自動命中にする応答）
        if (!a.autoHitChecked) {
            a = a.copy(autoHitChecked = true); attack = a
            if (a.shooterId != null && a.surface && a.hit != null) {
                val cards = responseCards(a.attacker, Trigger.OWN_SURFACE_STRIKE)
                if (cards.isNotEmpty()) {
                    pending = Pending(WindowKind.AUTO_HIT, a.attacker, cards, promptJa = "この対地攻撃を自動で命中させるか?")
                    return
                }
            }
        }
        // 2. ミサイル防衛（艦艇）と、それへの対抗
        if (!a.mdChecked) {
            a = a.copy(mdChecked = true); attack = a
            val ships = missileDefenseShips(a)
            if (ships.isNotEmpty()) {
                pending = Pending(WindowKind.MISSILE_DEFENSE, defender, shipOptions = ships, promptJa = "ミサイル防衛を宣言するか?(攻撃側の判定が不利になる)")
                return
            }
        }
        if (a.md && !a.counterChecked) {
            a = a.copy(counterChecked = true); attack = a
            val cards = (responseCards(a.attacker, Trigger.ENEMY_MD) + responseCards(a.attacker, Trigger.ENEMY_HIT_OR_MD)).distinct()
            if (cards.isNotEmpty()) {
                pending = Pending(WindowKind.MD_COUNTER, a.attacker, cards, promptJa = "相手がミサイル防衛を宣言した。打ち消すか?")
                return
            }
        }
        // 3. 命中判定
        if (!a.rolled) {
            a = if (a.hit == null || a.autoHit) {
                if (a.autoHit) publicEvent("攻撃は自動で命中")
                a.copy(rolled = true, hitResult = true, natural = null)
            } else {
                val r = rng.roll(attackMode(a))
                val hit = r.total >= a.hit
                publicEvent("命中判定: ${r.describe()}、必要${a.hit}以上 → ${if (hit) "命中" else "外れ"}")
                a.copy(rolled = true, hitResult = hit, natural = r.natural)
            }
            attack = a
        }
        if (a.hitResult && !a.hitCancelChecked) {
            a = a.copy(hitCancelChecked = true); attack = a
            val cards = responseCards(defender, Trigger.ENEMY_HIT_OR_MD)
            if (cards.isNotEmpty()) {
                pending = Pending(WindowKind.HIT_CANCEL, defender, cards, promptJa = "相手の攻撃が命中した。打ち消すか?")
                return
            }
        }
        // 4. 損害
        if (a.hitResult && a.damage == null) {
            val dmg = if (a.explosive) {
                val r = rng.roll(if (a.hit == null) RollMode.NORMAL else attackMode(a))
                publicEvent("損害判定: ${r.describe()} → ${r.total}点")
                r.total
            } else 1
            a = a.copy(damage = dmg); attack = a
        }
        if (a.hitResult && (a.damage ?: 0) > 0 && isBaseTarget(a) && !a.damageCancelChecked) {
            a = a.copy(damageCancelChecked = true); attack = a
            val cards = responseCards(defender, Trigger.ENEMY_BASE_DAMAGE)
            if (cards.isNotEmpty()) {
                pending = Pending(WindowKind.DAMAGE_CANCEL, defender, cards, promptJa = "基地に${a.damage}点の損害が出る。打ち消すか?")
                return
            }
        }
        // 5. 適用
        attack = null
        val dmg = if (a.hitResult) a.damage ?: 0 else 0
        if (dmg > 0) applyAttackDamage(a, dmg)
        a.shooterId?.let { applyWinchester(it, a.natural ?: 1) }
    }

    private fun applyAttackDamage(a: AttackCtx, dmg: Int) {
        val defender = a.attacker.opponent
        when (a.kind) {
            AttackKind.TOKEN -> {
                val target = tokens.getValue(a.targetTokenId!!)
                damageToken(target.id, dmg, by = a.attacker, onGround = target.zone == Zone.AtBase)
            }
            AttackKind.SQUADRON -> distributeBaseDamage(a.targetSquadronId!!, dmg, by = a.attacker)
            AttackKind.BLOCK -> hitAirbaseBlocks(defender, dmg, by = a.attacker)
            AttackKind.CARD_BASE -> {
                if (a.sof && ModKind.SOF_IMMUNE in p(defender).modifiers) {
                    publicEvent("歩兵大隊の警備で特殊作戦の攻撃は失敗した")
                    return
                }
                val target = squadrons.values
                    .filter { it.owner == defender && it.drafted && !it.isDestroyed && it.base == BaseKind.AIRBASE }
                    .maxByOrNull { it.damage }
                if (target != null) distributeBaseDamage(target.id, dmg, by = a.attacker) else hitAirbaseBlocks(defender, dmg, by = a.attacker)
            }
        }
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

    // ---- 損害と撃破 ----

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
                kind = CaptureKind.TOKEN, ato = ato, tokenId = id, typeId = t.id, vpClass = t.vpClass,
                onGround = onGround, inAir = inAir,
            ))
        }
        publicEvent("${Ja.side(tok.owner)}${t.nameJa}を撃破")
        if (inAir && t.cls.isAircraft) lossQueue += LossEvent(WindowKind.AIRCRAFT_LOST, tok.owner, tokenId = id)
        val sqId = tok.squadronId
        if (sqId != null && tokens.values.none { it.squadronId == sqId && !it.isDestroyed }) {
            lossQueue += LossEvent(WindowKind.SQUADRON_DEPLETED, tok.owner, squadronId = sqId)
        }
    }

    /**
     * 基地・CL への損害を割り振る。目標の飛行隊を先に、余りは同じ Airbase の他の飛行隊（損害の多い順）、
     * さらに余れば基地施設へ。CL は目標の飛行隊1枚だけ（余りは失われる）。
     * 本来は攻撃側が割り振るが、撃破を優先する割り振りを自動で行う。
     */
    private fun distributeBaseDamage(targetId: String, amount: Int, by: Side) {
        val target = squadrons.getValue(targetId)
        var left = damageSquadron(targetId, amount, by)
        if (target.base != BaseKind.AIRBASE) return
        val others = squadrons.values
            .filter { it.owner == target.owner && it.drafted && !it.isDestroyed && it.base == BaseKind.AIRBASE && it.id != targetId }
            .sortedByDescending { it.damage }
        for (other in others) {
            if (left <= 0) break
            left = damageSquadron(other.id, left, by)
        }
        if (left > 0) hitAirbaseBlocks(target.owner, left, by)
    }

    /** 飛行隊に損害を与え、使い切れなかった損害を返す。 */
    private fun damageSquadron(id: String, amount: Int, by: Side): Int {
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
        return amount - applied
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
        attack = null
        lossQueue.clear()
        // 三つの優勢は ATO の終わりに盤上にいる艦艇を数える（片付ける前に数える）
        val shipsOnBoard = Side.entries.associateWith { side ->
            tokens.values.count { it.owner == side && it.isAirborne && type(it).cls == TokenClass.SHIP }
        }
        for (tok in tokens.values.filter { it.isAirborne }) {
            val sq = tok.squadronId?.let { squadrons[it] }
            when {
                !type(tok).cls.isAircraft || tok.squadronId == null -> tokens[tok.id] = tok.copy(zone = Zone.Offboard)
                sq == null || sq.isDestroyed -> {
                    event { v -> "${label(tok, v)}は帰投先の飛行隊が撃破されていたため失われた" }
                    destroyToken(tok.id, by = tok.owner.opponent, onGround = false, inAir = false)
                }
                else -> tokens[tok.id] = tokens.getValue(tok.id).copy(zone = Zone.Grounded, winchester = false)
            }
        }
        lossQueue.clear()
        for (side in Side.entries) {
            val gained = scoreAto(side, shipsOnBoard.getValue(side))
            updatePlayer(side) { it.copy(vp = it.vp + gained) }
            event { v -> if (v == side) "ATO $ato の獲得VP: $gained(累計${p(side).vp})" else null }
        }
        if (ato < atoCount) startNextAto() else finishGame()
    }

    /** その ATO のミッションの得点（仕様書 §9）。 */
    private fun scoreAto(side: Side, shipsOnBoard: Int): Int {
        val pl = p(side)
        val mission = pl.mission
        var vp = pl.captured.filter { it.ato == ato }.sumOf { e.recordVp(mission, it) }
        if (campaign.airKillBonusPerAto > 0) {
            // 世界が見ている: 空中での撃破1機につき +1（ATO ごとに上限あり）
            vp += min(campaign.airKillBonusPerAto, pl.captured.count { it.ato == ato && it.kind == CaptureKind.TOKEN && it.inAir })
        }
        val plays = pl.cardPlays.filter { it.ato == ato }.map { card(it.cardId) }
        val drafted = squadrons.values.filter { it.owner == side && it.drafted }
        vp += when (mission) {
            MissionId.ECONOMY_OF_FORCE ->
                2 * drafted.count { !it.faceUp && !it.isDestroyed } + 2 * pl.hand.count { "UNIT" in card(it).tags }
            MissionId.ENFORCE_RULE_OF_LAW -> 2 * pl.activations.count { it == ato } + pl.enablerTokens.count { it == ato }
            MissionId.NON_KINETIC_DOMINANCE ->
                3 * pl.cyber + plays.count { c -> c.tags.any { it in setOf("EW", "CYBER", "SPACE", "SOF") } }
            MissionId.COUNTER_INTERVENTION -> plays.count { "PLARF" in it.tags }
            MissionId.THREE_DOMINANCES -> 2 * pl.cyber + 3 * shipsOnBoard + plays.count { "NAVAL" in it.tags } +
                drafted.count { sq -> tokens.values.none { it.squadronId == sq.id && it.isDestroyed } }
            else -> 0
        }
        return vp
    }

    /** 次の ATO へ。盤上を片付けるが、撃破された飛行隊と機体（損耗）は持ち越す。 */
    private fun startNextAto() {
        ato += 1
        for (tok in tokens.values.filter { !it.isDestroyed && it.zone != Zone.Offboard }) {
            // 態勢や支援カードで発生させた駒など、飛行隊に属さない駒は片付ける
            val zone = if (tok.squadronId == null) Zone.Offboard else Zone.Grounded
            tokens[tok.id] = tok.copy(zone = zone, acquired = false, winchester = false, unavailable = false, salvosUsed = 0)
        }
        val fixed = campaign.fixedSquadrons.isNotEmpty()
        for (sq in squadrons.values) {
            squadrons[sq.id] = sq.copy(faceUp = false, drafted = fixed && !sq.isDestroyed, base = BaseKind.AIRBASE)
        }
        for (side in Side.entries) {
            updatePlayer(side) {
                it.copy(
                    posture = null, drafted = false, enablersChosen = false, deployed = false, bid = null,
                    hand = emptyList(), knownEnemyCards = emptyList(), modifiers = emptyList(), airRange = emptyMap(),
                )
            }
        }
        initiativeWinner = null
        publicEvent("ATO $ato/$atoCount 開始")
        beginAtoSetup()
    }

    private fun finishGame() {
        if (campaign.intactSquadronBonus) {
            // 予備戦力: 無傷の飛行隊カード（損害なし）1枚につき +1。使えない飛行隊は数えない
            for (side in Side.entries) {
                val intact = squadrons.values.count {
                    it.owner == side && it.damage == 0 && it.defId !in campaign.bannedSquadrons
                }
                updatePlayer(side) { it.copy(vp = it.vp + intact) }
                privateEvent(side, "無傷の飛行隊カード${intact}枚で +$intact VP(累計${p(side).vp})")
            }
        }
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
        pending = null
        attack = null
        winner = w
        gameOverReason = reason
        publicEvent("ゲーム終了: $reason → ${if (w == null) "引き分け" else "${Ja.side(w)}の勝ち"}")
    }
}
