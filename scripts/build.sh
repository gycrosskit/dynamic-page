#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
operation="${1:-}"; shift || true
[[ "${1:-}" == --platform && $# -ge 2 ]] || { echo 'Usage: scripts/build.sh compile|package --platform android|ios|harmony|maven' >&2; exit 2; }
platform="$2"; shift 2
if [[ "$platform" == harmony && -n "${HARMONY_CLT_HOME:-}" ]]; then
  export DEVECO_SDK_HOME="$HARMONY_CLT_HOME/sdk"
  export DEVECO_NODE_HOME="$HARMONY_CLT_HOME/tool/node"
  export NODE_HOME="$DEVECO_NODE_HOME"
  export PATH="$NODE_HOME/bin:$HARMONY_CLT_HOME/ohpm/bin:$HARMONY_CLT_HOME/bin:$PATH"
fi
if [[ "$operation" == compile ]]; then
  [[ $# == 0 ]] || { echo 'Unexpected arguments' >&2; exit 2; }
  case "$platform" in
    android) tasks=(:dynamic-page-core:compileDebugKotlinAndroid :dynamic-page-kuikly:compileDebugKotlinAndroid) ;;
    ios) tasks=(:dynamic-page-core:compileKotlinIosArm64 :dynamic-page-core:compileKotlinIosSimulatorArm64 :dynamic-page-kuikly:compileKotlinIosArm64 :dynamic-page-kuikly:compileKotlinIosSimulatorArm64) ;;
    harmony) tasks=(:dynamic-page-core:compileKotlinOhosArm64 :dynamic-page-kuikly:compileKotlinOhosArm64) ;;
    *) echo 'Unsupported compile platform' >&2; exit 2 ;;
  esac
  [[ ${#tasks[@]} -gt 0 ]] || { echo 'No production target on this platform' >&2; exit 2; }
  args=(--no-daemon --max-workers=1 -Dorg.gradle.parallel=false)
  [[ ! -f scripts/ci-repositories.gradle ]] || args+=(--init-script "$PWD/scripts/ci-repositories.gradle")
  bash gradlew "${args[@]}" "${tasks[@]}"
  if [[ "$platform" == ios ]]; then
    export GYC_IOS_SOURCE="$PWD"
    for native_pod in GycDynamicPageNative; do
      native_output="$PWD/build/native-ios-compile/$native_pod"
      mkdir -p "$native_output"
      export GYC_IOS_POD="$native_pod"
      cat > "$native_output/Podfile" <<'POD'
install! 'cocoapods', :integrate_targets => false
platform :ios, '15.0'
use_frameworks!
target 'NativeCompile' do
  pod ENV.fetch('GYC_IOS_POD'), :path => ENV.fetch('GYC_IOS_SOURCE')
end
POD
      (cd "$native_output" && pod install)
      xcodebuild -project "$native_output/Pods/Pods.xcodeproj" -scheme "$native_pod" \
        -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' \
        -derivedDataPath "$native_output/DerivedData" CODE_SIGNING_ALLOWED=NO build
    done
  fi
  exit 0
fi
[[ "$operation" == package ]] || { echo 'Unsupported operation' >&2; exit 2; }
if [[ "$platform" == maven ]]; then exec bash scripts/archive-prebuilt.sh "$@"; fi
echo 'No native HAR channel for this component' >&2; exit 2
