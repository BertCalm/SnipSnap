#!/bin/bash
# SessionStart hook for Claude Code on the web: warm the build so a cloud
# session can run what CI runs before it pushes, instead of learning in CI.
#
# Two things a fresh container lacks, and why each is here:
#
# 1. Gradle's caches. The wrapper, every plugin and every dependency of the
#    nine JVM modules resolve on first use, and compiling the modules and
#    their tests once means the first `./gradlew test` a session runs is
#    the tests, not ten minutes of downloads. The container state is cached
#    after this hook completes, so the work is paid once per environment.
#    :app is not in the build here - settings.gradle.kts leaves it out when
#    no Android SDK is found - so nothing Android is fetched or needed.
#
# 2. Oboe's headers for the native harness (app/src/main/cpp/test). Its
#    CMake fetches the 1.9.0 tarball from github.com's archive URL, which
#    the session proxy refuses; git over the same proxy is fine, so the
#    hook clones the tag once into the home cache and configures the
#    harness's build tree with SNIPSNAP_OBOE_INCLUDE pointing at it. That
#    setting is a CMake cache variable, so the documented
#    `cmake -S app/src/main/cpp/test -B build/native-tests` keeps it on
#    every re-run and never tries the fetch.
#
# Idempotent: Gradle is incremental, the clone is skipped when present,
# and CMake's configure is a no-op on a configured tree. Synchronous on
# purpose: the session starts when this has finished, so nothing races it.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

echo "snipsnap session-start: warming Gradle (wrapper, dependencies, compiled tests)"
./gradlew --no-daemon --quiet testClasses

OBOE_TAG="1.9.0"
OBOE_DIR="${HOME}/.cache/snipsnap/oboe-${OBOE_TAG}"
if [ ! -f "${OBOE_DIR}/include/oboe/Oboe.h" ]; then
  echo "snipsnap session-start: fetching Oboe ${OBOE_TAG} headers for the native harness"
  rm -rf "${OBOE_DIR}"
  git -c advice.detachedHead=false clone --quiet --depth 1 --branch "${OBOE_TAG}" https://github.com/google/oboe.git "${OBOE_DIR}"
fi

# Persist the location for any cmake run a session does by hand.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export SNIPSNAP_OBOE_INCLUDE=\"${OBOE_DIR}/include\"" >> "$CLAUDE_ENV_FILE"
fi

echo "snipsnap session-start: configuring and building the native harness"
cmake -S app/src/main/cpp/test -B build/native-tests -DSNIPSNAP_OBOE_INCLUDE="${OBOE_DIR}/include" > /dev/null
cmake --build build/native-tests > /dev/null

echo "snipsnap session-start: ready - ./gradlew --no-daemon test, and ctest --test-dir build/native-tests"
