#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
config=${1:-backend/config.toml}
if [[ "$config" != /* ]]; then config="$root/$config"; fi

fail() {
  printf 'Selene backend: %s\n' "$1" >&2
  exit 1
}

[[ -f "$config" ]] || fail "config not found: $config"
command -v cargo >/dev/null 2>&1 || fail "Rust/Cargo is not installed or not available in PATH"
[[ -f "$root/.deps/moonlightbridge/Cargo.toml" ]] || fail "MoonLightBridge is missing; run $root/scripts/bootstrap-framework.sh first"

setting() {
  sed -n -E "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\"([^\"]+)\".*/\1/p" "$config" | head -n 1
}

database_env=$(setting database_url_env)
[[ -n "$database_env" ]] || fail "database_url_env is missing or invalid in $config"
[[ -n "${!database_env:-}" ]] || fail "environment variable $database_env is missing or empty"

plaintext=$(sed -n -E 's/^[[:space:]]*development_plaintext[[:space:]]*=[[:space:]]*(true|false).*/\1/p' "$config" | head -n 1)
[[ -n "$plaintext" ]] || fail "development_plaintext must be true or false in $config"

if [[ "$plaintext" == false ]]; then
  for key in certificate private_key client_ca; do
    value=$(setting "$key")
    [[ -n "$value" ]] || fail "$key is missing or invalid in $config"
    path=$value
    [[ "$path" == /* ]] || path="$root/$path"
    [[ -f "$path" ]] || fail "$key file not found: $path"
  done
fi

printf 'Selene backend: config=%s, database=%s, transport=%s\n' \
  "$config" "$database_env" "$([[ "$plaintext" == true ]] && printf plaintext-loopback || printf mTLS)"
cd "$root"
exec cargo run --manifest-path backend/Cargo.toml --release -- "$config"
