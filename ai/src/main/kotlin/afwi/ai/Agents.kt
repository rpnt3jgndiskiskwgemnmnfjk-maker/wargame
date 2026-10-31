package afwi.ai

import afwi.engine.Action
import afwi.engine.BaseKind
import afwi.engine.CaptureKind
import afwi.engine.CaptureRecord
import afwi.engine.Engine
import afwi.engine.Phase
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
        return when (view.phase) {
            Phase.DEPLOY -> chooseDeploy(acts)
            Phase.BID -> acts.first()
            // 先攻を取って先に飛行隊を発進させる
            Phase.CHOOSE_ORDER -> acts.filterIsInstance<Action.ChooseFirst>().first { it.first == view.viewer }
            Phase.TURNS -> chooseTurnAction(view, acts)
            Phase.GAME_OVER -> acts.first()
        }
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
        else -> 0.0
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
        val ready = ctx.view.tokens.count { it.squadronId == sq.id && it.zone == Zone.Grounded && !it.unavailable }
        val airborne = ctx.mine.count { it.isAirborne() }
        val def = sq.def ?: return 0.0
        // 地上にいる機体は基地攻撃で一緒に失われる危険がある。上空に出しておく価値がある
        val urgency = if (airborne == 0) 3.0 else 1.2
        return urgency * ready / def.count.coerceAtLeast(1)
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
