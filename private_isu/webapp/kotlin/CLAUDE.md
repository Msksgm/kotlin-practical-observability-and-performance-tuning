# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## このプロジェクトについて

[private-isu](https://github.com/catatsuy/private-isu)（ISUCON 練習用アプリ、オリジナルは Go 実装）の Kotlin/Ktor 移植版。ハンドラ層は Go リファレンス実装をほぼ 1 行ずつ写経した構造になっている。バグ修正や挙動追加の際は、Kotlin 的な抽象化を導入するより先に Go 側の実装と突き合わせること。Go と挙動が乖離するとベンチマーク用途として意味が無くなる。

## 由来とコンテキスト

- このリポジトリは [catatsuy/private-isu](https://github.com/catatsuy/private-isu) の Kotlin 実装ポート。
- 元の Go 実装（`webapp/golang/app.go`）を参照実装として、Ktor + JDBI + FreeMarker で移植している。ローカルでは `/Users/sugimotomasaki/workspace/github.com/Msksgm/private-isu/webapp/kotlin` に元の実装（フォーク内に同梱されていた Kotlin 実装）が存在する。
- 開発の歴史的経緯と詳細な計画は upstream フォーク [Msksgm/private-isu](https://github.com/Msksgm/private-isu) の `kotlin-impl` ブランチの CLAUDE.md を参照: <https://github.com/Msksgm/private-isu/blob/kotlin-impl/CLAUDE.md>

## 開発フェーズ

- **フェーズ 1**（フォーク内実装 + ベンチマーカー pass）は **2026-05-05** に完了。
- 現在は **フェーズ 2**: 独立リポジトリでの運用確立段階。
  - 2-1 ✅ `git filter-repo` で抽出
  - 2-2 ✅ 独立 CI 構築
  - 2-3 ✅ Renovate 設定
  - 2-4 ✅ `Msksgm/private-isu:kotlin-impl` への同期メカニズム
  - 2-5 ⏳ AMI プロビジョニング

## エンドポイント完成の定義 (DoD)

- 動作確認まで含めて初めて完成とする（curl で HTTP レイヤー検証 + DB の状態確認 + HTML 表示が責務のものはブラウザ確認）。
- 詳細は upstream フォークの CLAUDE.md DoD セクションを参照。

## AI への期待

- ペアプロモード（いきなり答えを書かない、ヒント先行）。
- 詳細は upstream フォークの CLAUDE.md を参照。

## よく使うコマンド

```bash
# サーバ起動（デフォルトで :8080）
./gradlew run

# build/install/private-isu-kotlin/ 配下に実行可能なディストリビューションを生成
./gradlew installDist

# テスト
./gradlew test
./gradlew test --tests "io.github.msksgm.ServerTest"

# Docker
docker build -t private-isu-kotlin .
```

JDK は `kotlin { jvmToolchain(21) }` で **JDK 21** に固定されている。Foojay が自動解決するので `JAVA_HOME` を別途設定する必要はない。

アプリは MySQL と Memcached を必要とする。接続情報は環境変数で渡す（カッコ内はデフォルト値）。

- `ISUCONP_DB_HOST` (`localhost`), `ISUCONP_DB_PORT` (`3306`), `ISUCONP_DB_NAME` (`isuconp`), `ISUCONP_DB_USER` (`root`), `ISUCONP_DB_PASSWORD` (`root`)
- `ISUCONP_MEMCACHED_ADDRESS` (`localhost`)

## アーキテクチャ

### モジュール配線はコードではなく YAML 駆動

`Application.module()` は **存在しない**。Ktor が起動時に呼び出すモジュールは `src/main/resources/application.yaml` に列挙されている。

```
io.github.msksgm.SerializationKt.configureSerialization
io.github.msksgm.SecurityKt.configureSecurity
io.github.msksgm.FreemarkerKt.configureFreemarker
io.github.msksgm.RoutingKt.configureRouting
```

新しいトップレベルの `Application.configureX()` を追加した場合、`application.yaml` にも登録しないと **何も言われずに実行されない** ので注意。順序にも意味があり、`configureSecurity` が `Sessions` をインストールしてから `configureRouting` がそれを読む。

### Routing.kt はモノリス

`src/main/kotlin/Routing.kt` に **全部入っている** — ハンドラ、ドメインデータクラス（`User` / `Post` / `Comment`）、JDBI/HikariCP のシングルトン、Memcached バックエンドの `SessionStorage` 実装、パスワードハッシュ、SQL すべて。サービス層やリポジトリ層は無い。理由なくこの構造を変えないこと。Go リファレンスのフラットな構造を意図的に踏襲している。

ルート登録はファイル末尾の `configureRouting()` にまとまっている。2 つのルートは path パラメータではなく **名前付きキャプチャ付き `Regex`** で定義されている: `/image/{id}.{ext}` と `/@{accountName}`。緩いマッチャに変えると過去発生した 404 不整合を再発させるので、編集時にもこの形を維持すること（経緯は `git log` 参照）。

### DB アクセス（JDBI + HikariCP）

- `Jdbi.create(dataSource).installPlugin(KotlinPlugin())` — Kotlin プラグインがあるおかげで `mapTo<User>()` が snake_case カラム（`account_name` / `created_at` / `del_flg` 等）を camelCase の Kotlin プロパティへマップしてくれる。`KotlinPlugin()` を外さないこと。
- HikariCP のプールサイズは **10**。`dataSource` と `jdbi` はファイルスコープの `by lazy` シングルトン。リクエストごとに作り直さない。
- リスト系の bind は `bindList("post_ids", postIds)` と `<post_ids>` テンプレート構文を使う。JDBC の `IN (?)` は使えない。
- クエリは全てインライン文字列。Go 実装との対応関係を保つため、`.sql` ファイルへの抽出はしないこと。

### セッションは Memcached に格納

`MemcachedSessionStorage`（`Routing.kt` 内）はブロッキングな `xmemcached` 呼び出しを `withContext(Dispatchers.IO)` で包んでいる。セッションキーは `isuconp-kotlin.session:<id>`、TTL は 1 時間。Cookie 名は `isuconp-kotlin.session`、`SameSite=lax` を明示的に設定している。`UserSession`（`UserSession.kt`）は `@Serializable` で全フィールドにデフォルト値が必要 — Ktor のセッションストアは引数なしコンストラクタを要求する。

### パスワードハッシュは意図的に外部コマンドを呼ぶ

`Routing.kt` の `digest()` は `ProcessBuilder("/bin/bash", "-c", ...)` で `printf "%s" '<arg>' | openssl dgst -sha512 | sed 's/^.*= //'` を実行している。これは **バグではない**。Go リファレンス実装の `exec.Command` の形を維持し、シードされた DB のハッシュ値とバイト単位で一致させるため。`escapeshellarg()` は PHP の同名関数を手で移植したもの。`java.security.MessageDigest` に置き換えるとハッシュ値が変わり、既存データへのログインが壊れる。

### FreeMarker テンプレート

テンプレートは `src/main/resources/templates/` 配下、`ClassTemplateLoader` で読み込まれる。`configureFreemarker()` で `numberFormat = "computer"` を設定している — これが無いと post ID が `1,234` のようにフォーマットされ、URL が壊れる。テンプレートには `TemplateHelpers` が `h` として渡される（例: `${h.imageUrl(post)}`）。

### 静的ファイル

`staticFiles("/", File("/home/public"))` はホスト上に存在することを前提とした絶対パスを参照する（典型的には private-isu の `public/` ディレクトリをコンテナにマウントする）。`Dockerfile` ではコピーしていないので、デプロイ時には外部からマウントするか別途配置する必要がある。

## ビルドシステムの注意

バージョンカタログは 2 つある。

- `libs` — `gradle/libs.versions.toml` で定義（Kotlin, logback, HikariCP, MySQL, JDBI, xmemcached）。
- `ktorLibs` — `settings.gradle.kts` で `io.ktor:ktor-version-catalog` Maven アーティファクトから読み込み。Ktor 関連の座標はすべてこちらから来る。`libs.versions.toml` ではない。

Ktor のバージョンを上げる時は、toml ではなく **`settings.gradle.kts`** のバージョンを書き換える。
