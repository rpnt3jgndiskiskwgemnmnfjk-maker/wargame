package afwi.app

import afwi.engine.RuleSettings
import android.content.Context

/** 画面で変えられる設定。ルールの未確認部分（仕様書 §14）と CPU の速さ。 */
data class AppSettings(
    /** 艦艇の耐久（公式は未確認。既定 1、ファン版互換なら 3）。 */
    val shipHp: Int = 1,
    /** 基地施設の損害ブロック1つの VP（公式は未確認。既定 1）。 */
    val airbaseBlockVp: Int = 1,
    /** 弾切れで帰投した機体を同じ ATO 中に再発進できる（解釈）。 */
    val rtbRearm: Boolean = true,
    /** CPU が1手ごとに待つ時間（ミリ秒）。 */
    val cpuDelayMs: Long = 600,
) {
    fun toRules(): RuleSettings = RuleSettings(shipHp = shipHp, airbaseBlockVp = airbaseBlockVp, rtbRearm = rtbRearm)
}

/** 端末内（SharedPreferences）に設定を保存する。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("afwi_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            shipHp = prefs.getInt("shipHp", d.shipHp),
            airbaseBlockVp = prefs.getInt("airbaseBlockVp", d.airbaseBlockVp),
            rtbRearm = prefs.getBoolean("rtbRearm", d.rtbRearm),
            cpuDelayMs = prefs.getLong("cpuDelayMs", d.cpuDelayMs),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putInt("shipHp", s.shipHp)
            .putInt("airbaseBlockVp", s.airbaseBlockVp)
            .putBoolean("rtbRearm", s.rtbRearm)
            .putLong("cpuDelayMs", s.cpuDelayMs)
            .apply()
    }
}
