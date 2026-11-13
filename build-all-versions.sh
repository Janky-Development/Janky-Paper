#!/usr/bin/env bash
# Janky Paper - builds the server jar for every supported Minecraft version (or the ones given as arguments)
# into dist/. A shortcut for the Gradle tasks in the "janky versions" group (build.gradle.kts):
#
#   ./build-all-versions.sh                 # ./gradlew buildAllJars
#   ./build-all-versions.sh 26.2 1.21.11    # ./gradlew buildJar-26.2 buildJar-1.21.11
#
# ./gradlew listVersions shows every version, its branch and where it is checked out.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
if [ $# -eq 0 ]; then
    exec ./gradlew buildAllJars
fi
tasks=()
for version in "$@"; do tasks+=("buildJar-$version"); done
exec ./gradlew "${tasks[@]}"
