# wargame

AFWI（Air Force Wargame: Indo-Pacific）の調査資料と、そのルールで遊べる Android アプリ（非公式）。

## 文書

| 文書 | 内容 |
|---|---|
| [docs/afwi-rules-spec.md](docs/afwi-rules-spec.md) | 遊び方の仕様書。記述ごとに情報源の信頼度と、未確認事項の一覧を付けている |
| [docs/afwi-implementation-design.md](docs/afwi-implementation-design.md) | プログラムで再現するための設計。データモデル、隠し情報、状態機械、カード効果、AI、テスト |

## アプリの構成

| モジュール | 内容 |
|---|---|
| `engine/` | ルールエンジン（純粋な Kotlin、Android に依存しない）。カード・機種データは `engine/src/main/resources/afwi/content.json` |
| `ai/` | CPU の対戦相手（純粋な Kotlin）。自分の視点だけを見て合法手から選ぶ。初級・中級の2段階 |
| `app/` | Android アプリ（Jetpack Compose）。CPU 対戦とホットシート |

現在は M2 の版です。遭遇戦と大会を、CPU 対戦（米・中どちらでも、初級・中級）かホットシートで遊べます。

遊べる範囲は次のとおりです。

- 態勢の選択（標準以外は再使用不可）
- 全20飛行隊からのドラフト
- 配置（基地か CL）
- 主導権の入札と先攻の決定（大会では第2 ATO で主導権が交代）
- 飛行隊の起動、移動・捕捉・射撃、弾切れと帰投
- 早期警戒機、爆撃機（スタンドオフから射程6・爆発ダイス）、防空（基地への攻撃を不利にする）、無人機
- 飛行隊と基地施設への攻撃（爆発ダイスの損害は、撃破を優先して基地内に自動で割り振る）
- 消耗（Attrition）の採点と、ATO をまたぐ損耗の持ち越し

## ビルド

ルールエンジンと AI のテストは JDK 17 以上があれば実行できます（Android SDK は不要）。

```bash
./gradlew :engine:test :ai:test
```

APK のビルドには Android SDK が必要です。`ANDROID_HOME` を設定するか `local.properties` に `sdk.dir` を書くと、`:app` モジュールが有効になります。

```bash
./gradlew :app:assembleDebug
```

GitHub Actions（`.github/workflows/android.yml`）が、push のたびにテストとデバッグ APK のビルドを行います。APK は実行結果の Artifacts（`afwi-debug-apk`）からダウンロードできます。

## インストール

1. Artifacts からダウンロードした zip を展開し、`app-debug.apk` を端末に移す。
2. 端末の設定で、ファイルを開くアプリに「不明なアプリのインストール」を許可する。
3. APK を開いてインストールする。
