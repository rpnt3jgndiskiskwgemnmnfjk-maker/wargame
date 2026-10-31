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

    fun mission(mission: MissionId?): String = if (mission == null) "未選択" else missionName(mission)

    /** ミッションの採点方法（仕様書 §9）。 */
    fun missionRule(mission: MissionId): String = when (mission) {
        MissionId.ATTRITION -> "艦艇・爆撃機・防空・早期警戒機の撃破 +3、飛行隊カード +2、その他 +1"
        MissionId.INTERDICTION -> "爆撃機・早期警戒機・飛行隊カード +4、艦艇・防空 +3、その他 +2"
        MissionId.ECONOMY_OF_FORCE -> "各 ATO の終わりに、起動しなかった飛行隊と使わなかった部隊カード1つにつき +2"
        MissionId.ENFORCE_RULE_OF_LAW -> "起動した飛行隊1つにつき +2、支援カードで発生させた駒1つにつき +1"
        MissionId.NON_KINETIC_DOMINANCE -> "各 ATO の終わりにサイバー値×3、電子戦・サイバー・宇宙・SOF カード1枚につき +1"
        MissionId.COUNTER_INTERVENTION -> "地上で撃破した航空機1機につき +3、ロケット軍カード1枚につき +1"
        MissionId.THREE_DOMINANCES -> "各 ATO の終わりにサイバー値×2、盤上の艦艇1隻 +3、海軍カード1枚 +1、機体がそろった飛行隊1つ +1"
    }

    private fun missionName(mission: MissionId): String = when (mission) {
        MissionId.ATTRITION -> "消耗(Attrition)"
        MissionId.INTERDICTION -> "阻止(Interdiction)"
        MissionId.ECONOMY_OF_FORCE -> "兵力節約(Economy of Force)"
        MissionId.ENFORCE_RULE_OF_LAW -> "法の執行(Enforce Rule of Law)"
        MissionId.NON_KINETIC_DOMINANCE -> "非物理的優勢(Non-Kinetic Dominance)"
        MissionId.COUNTER_INTERVENTION -> "介入阻止(Counter-Intervention)"
        MissionId.THREE_DOMINANCES -> "三つの優勢(Three Dominances)"
    }
}
