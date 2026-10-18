#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Pass an immutable release candidate version}"
[[ "$VERSION" =~ ^[0-9][0-9A-Za-z._-]*$ ]] || { echo 'Invalid release version' >&2; exit 1; }
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export GROUP=com.github.gycrosskit.dynamic-page
mkdir -p build
# 每次使用空 staging；保留旧候选和失败证据，不混入其他版本。
export DYNAMIC_PAGE_STAGING_DIR="$(mktemp -d "$PWD/build/staging.XXXXXX")"
bash gradlew --no-daemon --max-workers=1 publishAllPublicationsToStagingRepository
python3 jitpack-metadata.py "$DYNAMIC_PAGE_STAGING_DIR/com/github/gycrosskit/dynamic-page"
python3 scripts/check-maven.py "$DYNAMIC_PAGE_STAGING_DIR" "$GROUP" "$VERSION" dynamic-page-core,dynamic-page-kuikly ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64 --max-jvm-major 55
archive_dir="build/prebuilt/$VERSION"
mkdir -p build/prebuilt
mkdir "$archive_dir"
COPYFILE_DISABLE=1 tar --no-xattrs -czf "$archive_dir/dynamic-page-maven.tar.gz" -C "$DYNAMIC_PAGE_STAGING_DIR" com
(cd "$archive_dir" && shasum -a 256 dynamic-page-maven.tar.gz > SHA256SUMS)
cat "$archive_dir/SHA256SUMS"
