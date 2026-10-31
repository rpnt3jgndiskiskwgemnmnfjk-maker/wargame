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
| `app/` | Android アプリ（Jetpack Compose）。ホットシートで遊ぶ画面 |

現在は M1（遭遇戦だけを遊べる版）です。M1 で遊べる範囲は次のとおりです。

- 配置（基地か CL）
- 主導権の入札と先攻の決定
- 飛行隊の起動
- 移動・捕捉・射撃
- 弾切れと帰投
- 飛行隊と基地施設への攻撃
- 消耗（Attrition）の採点

## ビルド

ルールエンジンのテストは JDK 17 以上があれば実行できます（Android SDK は不要）。

```bash
./gradlew :engine:test
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
