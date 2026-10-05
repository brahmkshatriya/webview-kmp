#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
    echo "Usage: run-linux-ci.sh GRADLE_TASK..." >&2
    exit 2
fi

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="${WEBVIEW_KMP_LINUX_CI_IMAGE:-debian:forky}"
tasks="$*"

docker run --rm \
    -e "ORG_GRADLE_PROJECT_VERSION_NAME=${ORG_GRADLE_PROJECT_VERSION_NAME:-}" \
    -e "ORG_GRADLE_PROJECT_webviewMetadataOnly=${ORG_GRADLE_PROJECT_webviewMetadataOnly:-}" \
    -e "TARGET_TASKS=$tasks" \
    -v "$project_root:/workspace" \
    -w /workspace \
    "$image" \
    bash -lc '
        set -euo pipefail
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y -qq --no-install-recommends \
            ca-certificates curl git unzip xz-utils \
            openjdk-21-jdk-headless \
            build-essential pkg-config \
            libwpewebkit-2.0-dev libwpe-1.0-dev \
            libsdl3-dev libegl-dev libgl-dev libxkbcommon-dev \
            libfontconfig1-dev libfreetype-dev libpng-dev
        export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
        export PATH="$JAVA_HOME/bin:$PATH"
        ./gradlew --no-daemon --stacktrace --no-configuration-cache $TARGET_TASKS
    '
