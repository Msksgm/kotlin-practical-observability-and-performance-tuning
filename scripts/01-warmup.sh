#!/usr/bin/env bash
set -Eeuo pipefail
#set -x
# -E: 関数やサブシェルでエラーが起きた時トラップ発動
# -e: エラーが発生した時点でスクリプトを終了
# -u: 未定義の変数を使用した場合にエラーを発生
# -x: スクリプトの実行内容を表示(debugで利用)
# -o pipefail: パイプライン内のエラーを検出

source "$(dirname "$0")/99-util.sh"

usage() {
  cat >&2 <<EOF
$0
概要:
  - 引数(target_host)に対してJVM暖機運転を実行する
実行方法:
  - $0 <target_host>
実行例:
  - $0 web
EOF
  exit 2
}

start_timer "$@"
(($# == 1)) || (echo '引数は1つだけ必要です' >&2 && usage)
readonly TARGET_HOST="$1"
ssh -F "$SSH_CONFIG_FILE" "$TARGET_HOST" 'touch ~/.hushlogin' 2>&1 || {
  log_info "${TARGET_HOST}へのssh失敗($0 $*): "
  exit 0
}

ssh -F "$SSH_CONFIG_FILE" "$TARGET_HOST" <<'EOF'
# アプリ起動完了まで待機（タイムアウト 60 秒）
for i in $(seq 1 60); do
  if curl -sf -o /dev/null http://localhost:8080/; then
    break
  fi
  sleep 1
done

# 暖機運転: 並列 50 で 10,000 リクエスト
seq 1 10000 | xargs -P 50 -I{} curl -sf -o /dev/null http://localhost:8080/ || true
EOF

end_timer "$@"
