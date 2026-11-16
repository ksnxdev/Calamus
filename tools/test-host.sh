#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
test_dir="$(mktemp -d)"
trap 'rm -rf -- "$test_dir"' EXIT
kotlinc TMessagesProj/src/main/java/uz/ksnxdev/calamus/LegacyDataCleanup.kt tests/LegacyDataCleanupTest.kt -include-runtime -d "$test_dir/migration.jar"
java -jar "$test_dir/migration.jar"
