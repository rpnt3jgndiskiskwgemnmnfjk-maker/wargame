package afwi.engine

/** 画面と履歴に出す日本語表記。 */
object Ja {
    fun side(side: Side): String = if (side == Side.US) "米" else "中"

    fun base(base: BaseKind): String = if (base == BaseKind.AIRBASE) "基地" else "CL"

    /** 帯は見る側の陣営から数えた番号で書く。 */
    fun zone(zone: Zone, viewer: Side): String = when (zone) {
        is Zone.Band -> "${viewer.bandFromUsFrame(zone.pos)}帯"
        is Zone.Standoff -> "${side(zone.side)}のスタンドオフ"
        Zone.Grounded -> "飛行隊カード上"
        Zone.AtBase -> "基地上"
        Zone.Offboard -> "盤外"
        Zone.Destroyed -> "撃破済み"
    }

    fun mission(mission: MissionId): String = when (mission) {
        MissionId.ATTRITION -> "消耗(Attrition)"
        MissionId.INTERDICTION -> "阻止(Interdiction)"
        MissionId.ECONOMY_OF_FORCE -> "兵力節約(Economy of Force)"
        MissionId.ENFORCE_RULE_OF_LAW -> "法の執行(Enforce Rule of Law)"
        MissionId.NON_KINETIC_DOMINANCE -> "非物理的優勢(Non-Kinetic Dominance)"
        MissionId.COUNTER_INTERVENTION -> "介入阻止(Counter-Intervention)"
        MissionId.THREE_DOMINANCES -> "三つの優勢(Three Dominances)"
    }
}
