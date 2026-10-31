package afwi.engine

import kotlinx.serialization.Serializable

@Serializable
enum class RollMode { NORMAL, ADVANTAGE, DISADVANTAGE }

/** 有利と不利は相殺し、どちらも重ならない（仕様書 §6.1）。 */
fun resolveMode(advantage: Boolean, disadvantage: Boolean): RollMode = when {
    advantage && !disadvantage -> RollMode.ADVANTAGE
    disadvantage && !advantage -> RollMode.DISADVANTAGE
    else -> RollMode.NORMAL
}

@Serializable
data class Roll(val dice: List<Int>, val natural: Int, val bonus: Int, val mode: RollMode) {
    val total: Int get() = natural + bonus

    fun describe(): String {
        val modeText = when (mode) {
            RollMode.NORMAL -> ""
            RollMode.ADVANTAGE -> "有利 ${dice.joinToString("・")} → "
            RollMode.DISADVANTAGE -> "不利 ${dice.joinToString("・")} → "
        }
        val bonusText = if (bonus != 0) " ${if (bonus > 0) "+" else ""}$bonus = $total" else ""
        return "出目 $modeText$natural$bonusText"
    }
}

/**
 * 種を指定できる擬似乱数（SplitMix64）。状態は GameState.rngState に保存し、
 * 同じ種と同じ行動列からは同じ対局が再現される。
 */
class Rng(var state: Long) {
    private fun next(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((next() ushr 1) % bound).toInt()
    }

    fun d4(): Int = nextInt(4) + 1

    fun roll(mode: RollMode = RollMode.NORMAL, bonus: Int = 0): Roll = when (mode) {
        RollMode.NORMAL -> d4().let { Roll(listOf(it), it, bonus, mode) }
        RollMode.ADVANTAGE -> listOf(d4(), d4()).let { Roll(it, it.max(), bonus, mode) }
        RollMode.DISADVANTAGE -> listOf(d4(), d4()).let { Roll(it, it.min(), bonus, mode) }
    }

    private companion object {
        val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
        val MIX1 = 0xBF58476D1CE4E5B9uL.toLong()
        val MIX2 = 0x94D049BB133111EBuL.toLong()
    }
}
