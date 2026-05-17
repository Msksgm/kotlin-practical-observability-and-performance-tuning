# private-isu Kotlin 実装

[![bench](https://github.com/Msksgm/private-isu-kotlin/actions/workflows/bench.yml/badge.svg?branch=main)](https://github.com/Msksgm/private-isu-kotlin/actions/workflows/bench.yml)
[![hadolint](https://github.com/Msksgm/private-isu-kotlin/actions/workflows/hadolint.yml/badge.svg?branch=main)](https://github.com/Msksgm/private-isu-kotlin/actions/workflows/hadolint.yml)
[![osv-scanner](https://github.com/Msksgm/private-isu-kotlin/actions/workflows/osv-scanner-scheduled.yml/badge.svg?branch=main)](https://github.com/Msksgm/private-isu-kotlin/actions/workflows/osv-scanner-scheduled.yml)

[private-isu](https://github.com/catatsuy/private-isu) に Kotlin 実装を追加するためのリポジトリです。
Ktor + JDBI + FreeMarker で、Go リファレンス実装（`webapp/golang/app.go`）を移植しています。
現状、Docker Compose と `./gradlew run` でのローカル起動に対応しています。

## このリポジトリについて

このリポジトリ `Msksgm/private-isu-kotlin` が **真の master** です。
upstream フォーク [Msksgm/private-isu](https://github.com/Msksgm/private-isu/tree/kotlin-impl) の `kotlin-impl` ブランチは、本リポジトリ `main` への push をトリガに `.github/workflows/sync-to-fork.yml` で `webapp/kotlin/` 配下へ自動同期される **読み取り専用ミラー** です。`kotlin-impl` を `master` へマージすることは想定していません。

- 編集はすべて本リポジトリ (`Msksgm/private-isu-kotlin`) で行ってください。
- `Msksgm/private-isu` の `kotlin-impl` ブランチの `webapp/kotlin/` 配下に直接 commit しても、次回 sync で **上書きされます**。
- `webapp/public/`, `webapp/golang/`, `webapp/etc/` 等、`webapp/kotlin/` 以外は同期対象外で無傷です。

## ローカルでの動作確認

本家 `catatsuy/private-isu` を clone し、その `webapp/` 配下に本リポジトリを `kotlin/` として追加する形で動作確認します。

1. `catatsuy/private-isu` を clone する。
    ```sh
    git clone https://github.com/catatsuy/private-isu.git
    cd private-isu
    ```
2. `webapp/` 配下に本リポジトリを `kotlin/` として clone する。
    ```sh
    cd webapp
    git clone https://github.com/Msksgm/private-isu-kotlin.git kotlin
    ```
3. `webapp/docker-compose.yml` の `app.build` を `kotlin` に変更する。
4. 起動する。
    ```sh
    docker compose up
    ```
5. (Option) Gradle で直接起動する場合は、MySQL と Memcached を別途用意したうえで以下を実行する（カッコ内はデフォルト値）。
    ```sh
    export ISUCONP_DB_HOST=localhost   # default: localhost
    export ISUCONP_DB_PORT=3306        # default: 3306
    export ISUCONP_DB_NAME=isuconp     # default: isuconp
    export ISUCONP_DB_USER=root        # default: root
    export ISUCONP_DB_PASSWORD=root    # default: root
    export ISUCONP_MEMCACHED_ADDRESS=localhost  # default: localhost

    ./gradlew run
    ```
    JDK は Gradle Toolchain で JDK 21 が自動解決されます。起動に成功すると以下のように表示されます。
    ```
    2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
    2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
    ```

## EC2 での動作確認

`catatsuy/private-isu` が提供する AMI から EC2 インスタンスを起動し、本リポジトリの Ansible playbook でデプロイします。詳細な手順は [`provisioning/README.md`](provisioning/README.md) を参照してください。

```sh
# hosts.tmpl から hosts を作成し、PUBLIC_IP と鍵パスを書き換える
cp provisioning/hosts{.tmpl,}
# $EDITOR provisioning/hosts

# playbook を実行する
ansible-playbook -i provisioning/hosts provisioning/image/ansible/playbooks.yml
```

## Bench

ローカルで benchmarker を走らせる手順です。CI（`.github/workflows/bench.yml`）と同等のフローを手元で再現できます。

### 必要なもの

- Docker（compose v2）と `unzip`, `jq`
- 数 GB の空き容量（`img.zip` が数百 MB、展開後はさらに使う）

### 手順

1. seed データ（`dump.sql.bz2`）を取得する。MySQL の `/docker-entrypoint-initdb.d` が起動時に自動展開して投入する。
    ```sh
    mkdir -p sql
    curl -L --fail -o sql/dump.sql.bz2 \
      https://github.com/catatsuy/private-isu/releases/download/img/dump.sql.bz2
    ```

2. アプリ stack（nginx + app + mysql + memcached）を起動する。
    ```sh
    docker compose up --build -d
    ```

    MySQL の seed 投入完了は `docker compose logs mysql` で確認できる。`http://localhost/` にアクセスして 200 が返れば準備完了。

3. upstream から benchmarker のソースと userdata（bench 用画像）を取得し、image を build する。`userdata/img.zip` の取得・展開を忘れずに（しないと `panic: invalid argument to IntN` になる）。
    ```sh
    git clone --depth=1 https://github.com/catatsuy/private-isu /tmp/upstream
    cd /tmp/upstream/benchmarker/userdata
    curl -L --fail -O \
      https://github.com/catatsuy/private-isu/releases/download/img/img.zip
    unzip -qq -o img.zip
    cd -
    docker build -t private-isu-benchmarker /tmp/upstream/benchmarker
    ```

4. compose ネットワークに join して bench を実行する。
    ```sh
    NET=$(docker network ls --format '{{.Name}}' | grep private-isu-kotlin)
    docker run --network "$NET" -i private-isu-benchmarker \
      /bin/benchmarker -t http://nginx -u /opt/userdata \
      | tee benchmark_output.json
    ```

5. 結果を確認する。`pass: true` であれば bench を通過。
    ```sh
    jq . benchmark_output.json
    # {"pass":true,"score":1738,"success":1652,"fail":1,"messages":[...]}
    ```

### 後片付け

```sh
docker compose down -v
```
