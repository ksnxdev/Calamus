#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
exec ./gradlew :TMessagesProj_App:assembleAfatDebug --max-workers="${CALAMUS_GRADLE_WORKERS:-2}" "$@"
