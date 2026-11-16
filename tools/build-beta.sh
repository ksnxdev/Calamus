#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-temurin}"
exec ./gradlew :TMessagesProj_App:assembleAfatStandalone --max-workers="${CALAMUS_GRADLE_WORKERS:-2}" -Pcalamus.nativeJobs=2 -Pkotlin.compiler.execution.strategy=in-process '-Dorg.gradle.jvmargs=-Xmx5g -XX:MaxMetaspaceSize=1g' "$@"
