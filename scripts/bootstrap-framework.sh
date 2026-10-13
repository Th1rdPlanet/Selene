#!/usr/bin/env bash
set -euo pipefail

repository='https://github.com/MoonL1ghtProject/MoonLightBridge.git'
revision='20f746fbc9782e356b98494122a8c9b1cafa1d5a'
destination='.deps/moonlightbridge'

if [[ -d "$destination/.git" ]]; then
  actual=$(git -C "$destination" rev-parse HEAD)
  if [[ "$actual" != "$revision" ]]; then
    printf 'MoonLightBridge revision mismatch: expected %s, found %s\n' "$revision" "$actual" >&2
    exit 1
  fi
  exit 0
fi

mkdir -p .deps
git clone --filter=blob:none --no-checkout "$repository" "$destination"
git -C "$destination" fetch --depth=1 origin "$revision"
git -C "$destination" checkout --detach "$revision"
actual=$(git -C "$destination" rev-parse HEAD)
[[ "$actual" == "$revision" ]]
