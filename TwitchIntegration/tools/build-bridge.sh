#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../bridge"

PROFILE_FLAG="--release"
if [[ "${1:-}" == "--debug" ]]; then
    PROFILE_FLAG=""
    shift
fi

cargo build $PROFILE_FLAG "$@"

echo "Bridge built. Gradle's copyBridge task packages it into the mod jar."
