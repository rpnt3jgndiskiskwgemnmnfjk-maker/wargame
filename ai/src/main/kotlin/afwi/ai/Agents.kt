package afwi.ai

import afwi.engine.Action
import afwi.engine.BaseKind
import afwi.engine.CaptureKind
import afwi.engine.CaptureRecord
import afwi.engine.Engine
import afwi.engine.Effect
import afwi.engine.EnablerDef
import afwi.engine.MissionId
import afwi.engine.ModKind
import afwi.engine.Pending
import afwi.engine.ResponseEffect
import afwi.engine.UseType
import afwi.engine.Phase
import afwi.engine.PostureKind
import afwi.engine.PlayerView
import afwi.engine.TokenClass
import afwi.engine.TokenType
import afwi.engine.TokenView
import afwi.engine.VpClass
import afwi.engine.WinchesterType
import afwi.engine.Zone
import kotlin.math.abs
import kotlin.random.Random

/**
 * CPU の対戦相手。渡されるのは自分の視点（PlayerView）だけで、相手の隠し情報は見えない。
 * view.legalActions の中から1つを返す。
 */
interface Agent {
    fun choose(view: PlayerView): Action
}

/** 合法手から一様に選ぶ。テストと耐久確認用。 */
class RandomAgent(seed: Long) : Agent {
    private val rnd = Random(seed)
    override fun choose(view: PlayerView): Action = view.legalActions.random(rnd)
}

enum class Difficulty(val labelJa: String) {
    /** 時々見当違いの手を選ぶ。 */
    EASY("初級"),

    /** 成功確率と VP の期待値で手を選ぶ（1手読み）。 */
    NORMAL("中級"),
}

/**
 * 期待値に基づく AI。各合法手に点数を付け、最も高い手を選ぶ。
 * 一番高い点数でもしきい値以下なら手番を終える（何もしていなければパス）。
 * 確率は D4 の出目から正確に計算する。
 */
class HeuristicAgent(
    private val engine: Engine,
    private val difficulty: Difficulty = Difficulty.NORMAL,
    seed: Long = 0,
) : Agent {
    private val rnd = Random(seed)

    override fun choose(view: PlayerView): Action {
        val acts = view.legalActions
        require(acts.isNotEmpty()) { "no legal action" }
        if (acts.size == 1) return acts.single()
        view.pending?.let { return chooseResponse(it, acts) }
        return when (view.phase) {
            Phase.MISSION -> chooseMission(acts)
            Phase.POSTURE -> choosePosture(acts)
            Phase.DRAFT -> chooseDraft(view, acts)
            Phase.ENABLERS -> chooseEnablers(view)
            Phase.DEPLOY -> chooseDeploy(acts)
            Phase.BID -> chooseBid(view, acts)
            // 先攻を取って先に飛行隊を発進させる
            Phase.CHOOSE_ORDER -> acts.filterIsInstance<Action.ChooseFirst>().first { it.first == view.viewer }
            Phase.TURNS -> chooseTurnAction(view, acts)
            Phase.GAME_OVER -> acts.first()
        }
    }

    private fun chooseMission(acts: List<Action>): Action {
        val missions = acts.filterIsInstance<Action.ChooseMission>()
        if (difficulty == Difficulty.EASY) return missions.random(rnd)
        // 阻止(Interdiction)は、どの撃破でも消耗(Attrition)以上の点になる
        return missions.firstOrNull { it.mission == MissionId.INTERDICTION } ?: missions.first()
    }

    /** 割り込みには、使える応答があれば使う（初級は半々）。 */
    private fun chooseResponse(p: Pending, acts: List<Action>): Action {
        val decline = acts.first { it is Action.Respond && it.cardId == null && it.shipId == null }
        val uses = acts.filter { it !== decline }
        if (uses.isEmpty()) return decline
        if (difficulty == Difficulty.EASY && rnd.nextBoolean()) return decline
        // 艦艇のミサイル防衛は、斉射ブロックの残りが多い艦から使う
        return uses.first()
    }

    /** 支援カードを、効果の強さとミッションとの相性で上限まで選ぶ。 */
    private fun chooseEnablers(view: PlayerView): Action {
        val pool = view.enablerPool
        val k = view.enablerLimit
        val picked = if (difficulty == Difficulty.EASY) {
            pool.shuffled(rnd).take(k)
        } else {
            // 手番に使うカードを中心に、守りの応答カードを1枚混ぜる
            val ranked = pool.sortedByDescending { cardValue(it, view) }
            val reaction = pool.filter { it.action == null && it.response != null }.maxByOrNull { cardValue(it, view) }
            val main = ranked.filter { it != reaction }.take(if (reaction != null && k >= 4) k - 1 else k)
            if (reaction != null && k >= 4) main + reaction else main
        }
        return Action.ChooseEnablers(view.viewer, picked.map { it.id }.toSet())
    }

    private fun chooseBid(view: PlayerView, acts: List<Action>): Action {
        val bids = acts.filterIsInstance<Action.SubmitBid>()
        val none = bids.first { it.sacrifice.isEmpty() }
        if (difficulty == Difficulty.EASY || view.me.hand.size < 5) return none
        // 手札が多ければ、一番弱いカードを1枚差し出して主導権を狙う
        val weakest = view.me.hand.minBy { cardValue(engine.content.enabler(it), view) }
        return bids.firstOrNull { it.sacrifice == setOf(weakest) } ?: none
    }

    /** 支援カードの価値の目安（選択と入札で使う）。 */
    private fun cardValue(c: EnablerDef, view: PlayerView): Double {
        var v = c.action?.let { effectValue(it) } ?: 0.0
        c.response?.let {
            v = maxOf(v, when (it.effect) {
                ResponseEffect.CANCEL_CARD -> 1.0
                ResponseEffect.RECOVER_AIRCRAFT -> 1.3
                ResponseEffect.AUTO_HIT -> 1.4
                ResponseEffect.CANCEL_HIT_OR_MD -> 1.5
                ResponseEffect.CANCEL_MD -> 0.8
                ResponseEffect.CANCEL_DAMAGE -> 1.6
                ResponseEffect.REGENERATE_SQUADRON -> 1.5
                ResponseEffect.REROLL_ACQUIRE -> 0.5
            })
        }
        val mission = view.me.mission
        val tags = c.tags
        if (mission == MissionId.NON_KINETIC_DOMINANCE && tags.any { it in setOf("EW", "CYBER", "SPACE", "SOF") }) v += 1.0
        if (mission == MissionId.THREE_DOMINANCES && "NAVAL" in tags) v += 1.0
        if (mission == MissionId.COUNTER_INTERVENTION && "PLARF" in tags) v += 1.0
        if (mission == MissionId.ENFORCE_RULE_OF_LAW && c.action is Effect.Generate) v += 0.5
        // 何度でも使えるカードは、単発カードより気軽に選べる
        if (c.use == UseType.MULTIPLE) v += 0.2
        return v
    }

    private fun effectValue(e: Effect): Double = when (e) {
        is Effect.AcquireDice -> 1.6
        is Effect.AcquireCyber -> 1.4
        Effect.CyberAttempt -> 1.8
        // サイバー値4は即勝利なので、相手のサイバー値を下げるカードは守りの要
        is Effect.CyberDegrade -> 1.8 * e.amount
        is Effect.RemoveTokens -> 1.2 * e.multiplier
        Effect.ForceDiscard -> 1.2
        Effect.DestroyUas -> 1.0
        is Effect.Modifier -> when (e.mod) {
            ModKind.OWN_ATTACK_ADV, ModKind.ENEMY_ATTACK_DIS -> 2.4
            ModKind.OWN_AIR_TO_AIR_ADV -> 2.0
            ModKind.ENEMY_ACQUIRE_DIS -> 1.6
            ModKind.ENEMY_BASE_ATTACK_DIS -> 1.2
            ModKind.H6K_ADV, ModKind.NEXT_AIR_TO_AIR_ADV -> 0.8
            ModKind.SOF_IMMUNE -> 0.6
            ModKind.CL_MAX_GENERATION -> 0.4
        }
        is Effect.Generate -> when {
            e.count > 1 -> 3.0
            engine.content.tokenType(e.tokenType).cls == TokenClass.SHIP -> 2.4
            engine.content.tokenType(e.tokenType).cls == TokenClass.ADA -> 1.8
            else -> 1.4
        }
        is Effect.BaseStrike -> if (e.hit == null) 2.8 else 2.6
        Effect.DestroyShip -> 1.2
        Effect.ExtraSquadron -> 2.0
        is Effect.ReturnSpent -> 0.8
        is Effect.AirRangeUpgrade -> 1.5
        is Effect.Choice -> e.options.maxOf { effectValue(it) } + 0.2
        is Effect.Sequence -> e.steps.sumOf { effectValue(it) } * 0.7
    }

    private fun choosePosture(acts: List<Action>): Action {
        val postures = acts.filterIsInstance<Action.ChoosePosture>()
        if (difficulty == Difficulty.EASY) return postures.random(rnd)
        // 支援カードが入るまでは、枚数の多い標準を基本にする
        return postures.firstOrNull { engine.content.posture(it.postureId).kind == PostureKind.STANDARD } ?: postures.first()
    }

    /** 参加させる飛行隊の組み合わせを、機種の強さと組み合わせの相性で選ぶ。 */
    private fun chooseDraft(view: PlayerView, acts: List<Action>): Action {
        val drafts = acts.filterIsInstance<Action.Draft>()
        if (difficulty == Difficulty.EASY) {
            // 初級は、なるべく多くの飛行隊をでたらめに選ぶ
            val maxSize = drafts.maxOf { it.squadronIds.size }
            return drafts.filter { it.squadronIds.size == maxSize }.random(rnd)
        }
        val value = view.squadrons.filter { it.owner == view.viewer }.associate { sq ->
            val def = sq.def
            val live = view.tokens.count { it.squadronId == sq.id && it.zone != Zone.Destroyed }
            val type = def?.let { engine.content.tokenType(it.tokenType) }
            sq.id to Pair(type, if (def == null) 0.0 else squadronValue(type!!) * live / def.count)
        }
        return drafts.maxBy { d ->
            val picked = d.squadronIds.mapNotNull { value[it] }
            val classes = picked.mapNotNull { it.first?.cls }
            var total = picked.sumOf { it.second }
            // 早期警戒機は戦闘機と組んで初めて生きる
            if (TokenClass.AEW in classes && classes.none { it == TokenClass.FIGHTER_GEN4 || it == TokenClass.FIGHTER_GEN5 }) total -= 2.0
            total
        }
    }

    private fun squadronValue(type: TokenType): Double = when (type.cls) {
        TokenClass.FIGHTER_GEN5 -> 4.0
        TokenClass.FIGHTER_GEN4 -> when {
            type.air?.hit == 2 && type.surface != null -> 3.3
            type.air?.hit == 2 -> 3.0
            else -> 2.6
        }
        TokenClass.AEW -> 2.6
        TokenClass.BOMBER -> 2.4
        TokenClass.ADA -> 2.2
        TokenClass.UAS_ATTACK -> 2.0
        TokenClass.UAS_RECON -> 1.2
        TokenClass.EW -> 1.5
        TokenClass.SHIP -> 2.0
    }

    private fun chooseDeploy(acts: List<Action>): Action {
        val deploys = acts.filterIsInstance<Action.Deploy>()
        return if (difficulty == Difficulty.EASY) {
            deploys.random(rnd)
        } else {
            // 中級は全部を基地に置く（CL は発進数が D4 で減るため）
            deploys.firstOrNull { d -> d.placements.values.all { it == BaseKind.AIRBASE } } ?: deploys.first()
        }
    }

    private fun chooseTurnAction(view: PlayerView, acts: List<Action>): Action {
        val end = acts.first { it is Action.EndTurn || it is Action.Pass }
        val ctx = Ctx(view)
        val scored = acts.filter { it !== end }.map { it to score(it, ctx) }
        if (scored.isEmpty()) return end
        if (difficulty == Difficulty.EASY && rnd.nextDouble() < EASY_BLUNDER) {
            // 初級はときどき、点数を見ずに手を選ぶ（手番を終えることもある）
            return (scored.map { it.first } + end).random(rnd)
        }
        val best = scored.maxBy { it.second }
        return if (best.second > END_THRESHOLD) best.first else end
    }

    // ---- 点数付け ----

    private inner class Ctx(val view: PlayerView) {
        val me = view.viewer
        val enemy = view.opponent.side
        val byId = view.tokens.associateBy { it.id }
        val mine = view.tokens.filter { it.owner == me }
        val enemyAirborne = view.tokens.filter { it.owner == enemy && it.isAirborne() }
        val turn = view.turn!!

        fun coord(t: TokenView): Int = engine.coord(t.zone, t.owner)
    }

    private fun TokenView.isAirborne() = zone is Zone.Band || zone is Zone.Standoff

    /** D4 で need 以上を出す確率（修正 bonus 込み）。 */
    private fun p(need: Int, bonus: Int = 0): Double = ((5 - (need - bonus)).coerceIn(0, 4)) / 4.0

    /** その駒を撃破したときの自分のミッションでの VP。正体不明なら戦闘機並みとみなす。 */
    private fun killValue(ctx: Ctx, t: TokenView, onGround: Boolean = false): Double {
        val vpClass = t.type?.vpClass ?: VpClass.OTHER
        val rec = CaptureRecord(CaptureKind.TOKEN, ato = ctx.view.ato, typeId = t.type?.id, vpClass = vpClass, onGround = onGround)
        // ミッションで点にならなくても、敵の戦力を減らす価値はある
        return engine.recordVp(ctx.view.me.mission, rec) + THREAT_REMOVAL
    }

    /** 射撃すると弾切れになる見込みの損失。 */
    private fun winchesterCost(type: TokenType): Double = when (type.winchester) {
        WinchesterType.STANDARD -> 0.75 * 0.4
        WinchesterType.INFINITE -> 0.5 * 0.3
        WinchesterType.SALVO -> 0.75 * 0.2
        WinchesterType.NONE -> 0.0
    }

    private fun score(a: Action, ctx: Ctx): Double = when (a) {
        is Action.ShootToken -> scoreShoot(a, ctx)
        is Action.StrikeSquadron -> scoreStrike(a, ctx)
        is Action.StrikeAirbaseBlock -> scoreBlock(a, ctx)
        is Action.Acquire -> scoreAcquire(a, ctx)
        is Action.Activate -> scoreActivate(a, ctx)
        is Action.Move -> scoreMove(a, ctx)
        is Action.PlayEnabler -> scoreEnabler(a, ctx)
        else -> 0.0
    }

    /** 手番中の支援カードの価値を、いまの盤面に合わせて見積もる。 */
    private fun scoreEnabler(a: Action.PlayEnabler, ctx: Ctx): Double {
        val c = engine.content.enabler(a.cardId)
        val effect = c.action ?: return 0.0
        val chosen = if (effect is Effect.Choice) effect.options[a.option] else effect
        return effectNow(chosen, a, ctx)
    }

    private fun effectNow(e: Effect, a: Action.PlayEnabler, ctx: Ctx): Double {
        val view = ctx.view
        val cyber = view.me.cyber
        val unseen = ctx.enemyAirborne.count { !it.acquired }
        val mine = view.me.modifiers
        return when (e) {
            is Effect.AcquireDice -> if (unseen == 0) -1.0 else 0.6 * minOf(2.5, unseen.toDouble())
            is Effect.AcquireCyber -> if (unseen == 0) -1.0 else 0.6 * minOf(cyber * e.multiplier, unseen)
            Effect.CyberAttempt -> p(cyber + 1) * if (cyber >= 3) 20.0 else 1.6
            is Effect.CyberDegrade -> {
                val enemyCyber = view.opponent.cyber
                if (enemyCyber == 0) -1.0 else minOf(e.amount, enemyCyber) * if (enemyCyber >= 3) 3.0 else 1.3
            }
            is Effect.RemoveTokens -> 0.7 * minOf(cyber * e.multiplier, ctx.enemyAirborne.size) - 0.1
            Effect.ForceDiscard -> 0.6 * minOf(cyber, view.opponent.handSize) - 0.1
            Effect.DestroyUas -> {
                val uas = ctx.enemyAirborne.count { it.type?.cls == TokenClass.UAS_ATTACK || it.type?.cls == TokenClass.UAS_RECON || it.masked }
                if (uas == 0) -1.0 else 1.2 * minOf(cyber, uas)
            }
            is Effect.Modifier -> if (e.mod in mine && e.mod != ModKind.CL_MAX_GENERATION) -1.0 else when (e.mod) {
                ModKind.OWN_ATTACK_ADV, ModKind.ENEMY_ATTACK_DIS ->
                    if (ctx.mine.any { it.isAirborne() } || ctx.enemyAirborne.isNotEmpty()) 2.0 else 0.3
                ModKind.OWN_AIR_TO_AIR_ADV -> if (ctx.mine.any { it.isAirborne() && it.type?.air != null }) 1.5 else 0.2
                ModKind.ENEMY_ACQUIRE_DIS -> if (ctx.enemyAirborne.isNotEmpty()) 1.2 else 0.2
                ModKind.ENEMY_BASE_ATTACK_DIS -> if (ctx.enemyAirborne.isNotEmpty()) 0.9 else 0.2
                ModKind.H6K_ADV -> if (ctx.mine.any { it.isAirborne() && it.type?.id == "H-6K" }) 1.2 else -1.0
                ModKind.NEXT_AIR_TO_AIR_ADV -> if (!ctx.turn.shot) 0.8 else 0.2
                ModKind.CL_MAX_GENERATION -> if (view.squadrons.any { it.owner == ctx.me && it.drafted && !it.faceUp && it.base == BaseKind.CL }) 1.0 else -1.0
                ModKind.SOF_IMMUNE -> 0.4
            }
            is Effect.Generate -> {
                val type = engine.content.tokenType(e.tokenType)
                val base = when {
                    e.count > 1 -> 3.0
                    type.cls == TokenClass.SHIP -> 2.4
                    type.cls == TokenClass.ADA -> 1.8
                    else -> 1.4
                }
                val zoneBonus = a.zone?.let { z ->
                    val dummy = TokenView(a.cardId, ctx.me, z, false, type, type.acqValue, false, false, false, null)
                    0.3 * positionValue(ctx, dummy, type, engine.coord(z, ctx.me))
                } ?: 0.0
                base + zoneBonus
            }
            is Effect.BaseStrike -> {
                val dis = view.baseDefense[ctx.enemy]?.get(BaseKind.AIRBASE).orEmpty().isNotEmpty() && !e.unblockable && !e.sof
                val hit = e.hit?.let { val q = p(it); if (dis) q * q else q } ?: 1.0
                val targets = view.squadrons.count { it.owner == ctx.enemy && it.drafted && !it.destroyed && it.base == BaseKind.AIRBASE }
                hit * 2.5 * (if (targets > 0) 1.0 else 0.4)
            }
            Effect.DestroyShip -> a.target?.let { id -> ctx.byId[id]?.let { killValue(ctx, it) + 0.5 } } ?: -1.0
            Effect.ExtraSquadron -> 1.5
            is Effect.ReturnSpent -> a.target?.let { 0.6 * cardValue(engine.content.enabler(it), view) } ?: -1.0
            is Effect.AirRangeUpgrade -> if (ctx.mine.any { it.isAirborne() && it.squadronId == a.target }) 1.5 else 0.6
            is Effect.Choice -> effectNow(e.options[a.option], a, ctx)
            is Effect.Sequence -> e.steps.sumOf { effectNow(it, a, ctx) } * 0.8
        }
    }

    private fun scoreShoot(a: Action.ShootToken, ctx: Ctx): Double {
        val shooter = ctx.byId.getValue(a.tokenId)
        val target = ctx.byId[a.targetId] ?: return 0.0
        val st = shooter.type ?: return 0.0
        val weapon = (if (target.type?.cls?.isAircraft != false) st.air else st.surface) ?: return 0.0
        return p(weapon.hit) * killValue(ctx, target, onGround = target.zone == Zone.AtBase) - winchesterCost(st)
    }

    private fun scoreStrike(a: Action.StrikeSquadron, ctx: Ctx): Double {
        val shooter = ctx.byId.getValue(a.tokenId)
        val st = shooter.type ?: return 0.0
        val weapon = st.surface ?: return 0.0
        val sq = ctx.view.squadrons.firstOrNull { it.id == a.squadronId } ?: return 0.0
        val sqVp = engine.recordVp(ctx.view.me.mission, CaptureRecord(CaptureKind.SQUADRON, ato = ctx.view.ato)).toDouble()
        // 地上に残っている所属機（見えない場合は2機と見積もる）
        val grounded = if (sq.faceUp) {
            ctx.view.tokens.count { it.squadronId == sq.id && it.zone == Zone.Grounded }
        } else 2
        val destroyValue = sqVp + grounded * (1.0 + THREAT_REMOVAL)
        val perHit = if (sq.damage >= 1 || weapon.explosive) destroyValue else destroyValue * 0.5
        return p(weapon.hit) * perHit - winchesterCost(st)
    }

    private fun scoreBlock(a: Action.StrikeAirbaseBlock, ctx: Ctx): Double {
        val st = ctx.byId.getValue(a.tokenId).type ?: return 0.0
        val weapon = st.surface ?: return 0.0
        return p(weapon.hit) * engine.rules.airbaseBlockVp * 0.6 - winchesterCost(st)
    }

    private fun scoreAcquire(a: Action.Acquire, ctx: Ctx): Double {
        val seeker = ctx.byId.getValue(a.tokenId)
        val bonus = seeker.type?.acqBonus ?: 0
        var total = 0.0
        val seen = mutableMapOf<String, Double>()
        for (id in a.targetIds) {
            val target = ctx.byId[id] ?: continue
            val pa = p(target.acqValue, bonus)
            // 同じ目標への2回目は、1回目が失敗したときだけ意味がある
            val prior = seen[id] ?: 0.0
            val gain = (1 - prior) * pa
            seen[id] = prior + gain
            total += gain * followUpValue(ctx, target)
        }
        return total
    }

    /** 捕捉した目標を、この手番かその後に撃てる見込みの価値。 */
    private fun followUpValue(ctx: Ctx, target: TokenView): Double {
        val tc = ctx.coord(target)
        val canShootNow = !ctx.turn.shot && ctx.mine.any { m ->
            val w = m.type?.air
            m.isAirborne() && !m.winchester && w != null && abs(ctx.coord(m) - tc) <= w.range
        }
        val base = killValue(ctx, target)
        return if (canShootNow) 0.9 * base * 0.6 else 0.35 * base
    }

    private fun scoreActivate(a: Action.Activate, ctx: Ctx): Double {
        val sq = ctx.view.squadrons.firstOrNull { it.id == a.squadronId } ?: return 0.0
        val readyTokens = ctx.view.tokens.filter { it.squadronId == sq.id && it.zone == Zone.Grounded && !it.unavailable }
        val airborne = ctx.mine.count { it.isAirborne() }
        val def = sq.def ?: return 0.0
        val type = engine.content.tokenType(def.tokenType)
        // 地上にいる機体は基地攻撃で一緒に失われる危険がある。上空に出しておく価値がある
        val urgency = if (airborne == 0) 3.0 else 1.2
        var v = urgency * readyTokens.size / def.count.coerceAtLeast(1)
        // 防空は展開すると、敵の基地攻撃を不利にする
        if (type.cls == TokenClass.ADA) v += 1.5
        // 発進先（1帯かスタンドオフか、どの帯か）の良し悪しで同点を崩す
        readyTokens.firstOrNull()?.let { v += 0.2 * positionValue(ctx, it, type, engine.coord(a.to, ctx.me)) }
        return v
    }

    private fun scoreMove(a: Action.Move, ctx: Ctx): Double {
        val tok = ctx.byId.getValue(a.tokenId)
        val type = tok.type ?: return 0.0
        val from = ctx.coord(tok)
        val to = engine.coord(a.to, ctx.me)
        return positionValue(ctx, tok, type, to) - positionValue(ctx, tok, type, from) - MOVE_COST
    }

    /** その位置にいるときの価値：攻撃の機会から、撃たれる危険を引く。 */
    private fun positionValue(ctx: Ctx, tok: TokenView, type: TokenType, c: Int): Double {
        var v = 0.0
        val air = type.air
        val surface = type.surface
        for (e in ctx.enemyAirborne) {
            val d = abs(c - ctx.coord(e))
            if (d <= type.acqRange && !e.acquired) v += 0.35
            if (air != null && d <= air.range && e.type?.cls?.isAircraft != false) v += if (e.acquired) 0.7 else 0.3
            // 相手の戦闘機の射程（多くは1）に入ると撃たれる危険がある
            if (d <= 1 && (e.masked || e.type?.air != null)) {
                v -= if (tok.acquired) RISK_ACQUIRED else RISK_UNSEEN
            }
        }
        if (surface != null && !tok.winchester) {
            val dBase = abs(c - ctx.enemy.baseCoord)
            if (dBase <= surface.range) {
                val targets = ctx.view.squadrons.count { it.owner == ctx.enemy && !it.destroyed }
                v += if (targets > 0) 0.9 else 0.2
            } else if (ctx.enemyAirborne.isEmpty()) {
                // 敵機が見えないときは敵基地へ向かう
                v -= 0.08 * dBase
            }
        }
        if (ctx.enemyAirborne.isNotEmpty() && air != null) {
            val nearest = ctx.enemyAirborne.minOf { abs(c - ctx.coord(it)) }
            v -= 0.05 * nearest
        }
        if (type.cls == TokenClass.AEW || type.cls == TokenClass.UAS_RECON) {
            // 支援機は前に出すぎない
            v -= 0.1 * abs(c - ctx.me.bandToUsFrame(1))
        }
        return v
    }

    private companion object {
        const val END_THRESHOLD = 0.05
        const val MOVE_COST = 0.03
        const val THREAT_REMOVAL = 0.5
        const val RISK_ACQUIRED = 0.6
        const val RISK_UNSEEN = 0.25
        const val EASY_BLUNDER = 0.3
    }
}
