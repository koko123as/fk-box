#!/usr/bin/env bash
# Builds the official, unmodified kernels of version.properties for Android
# (docs/kernel-isolation.md, "Building the kernels"): each source tree is a
# fresh clone of the upstream tag, nothing is copied into it or patched, and
# `git status --porcelain` must stay empty after the build.
#
# usage: build-kernels.sh OUT_DIR ABI...   (ABI: arm64 | amd64)
# needs: go (GO_VERSION), ANDROID_NDK_HOME (NDK r28), git
set -euo pipefail
out=$(realpath -m "$1"); shift
root=$(cd "$(dirname "$0")/../.." && pwd)
prop() { sed -n "s/^$1=//p" "$root/version.properties" | tr -d '\r'; }
xray_version=$(prop XRAY_VERSION)
mihomo_version=$(prop MIHOMO_VERSION)
src=${KERNEL_SRC:-$out/src}
mkdir -p "$out" "$src"
export GOTOOLCHAIN=local
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

clone() { # repo tag dir
  if [[ ! -d "$3/.git" ]]; then
    git clone --quiet --depth 1 --branch "$2" "https://github.com/$1.git" "$3"
  fi
  git -C "$3" describe --tags --exact-match HEAD >/dev/null
}
clean() { # dir
  local status
  status=$(git -C "$1" status --porcelain --ignored=no)
  if [[ -n "$status" ]]; then
    echo "::error::$1 was modified by the build:"; echo "$status"; exit 1
  fi
  echo "$1: git status --porcelain is empty"
}

clone XTLS/Xray-core "v$xray_version" "$src/xray"
clone MetaCubeX/mihomo "v$mihomo_version" "$src/mihomo"

for abi in "$@"; do
  case "$abi" in
    arm64) cc="$toolchain/aarch64-linux-android24-clang" ;;
    amd64) cc="$toolchain/x86_64-linux-android24-clang" ;;
    *) echo "unknown ABI $abi" >&2; exit 2 ;;
  esac
  mkdir -p "$out/$abi"
  # Xray: .github/workflows/release.yml (v26.11.9) android build, verbatim
  # flags; NDK r28 android24 clang as upstream.
  (cd "$src/xray" && CC="$cc" CGO_ENABLED=1 GOOS=android GOARCH="$abi" go build \
    -o "$out/$abi/libxray.so" -trimpath -buildvcs=false -gcflags="all=-l=4" \
    -ldflags="-X github.com/xtls/xray-core/core.build=$(git describe --always --dirty) -s -w -buildid= -checklinkname=0 -extldflags=-Wl,--pack-dyn-relocs=android" \
    ./main)
  # mihomo: .github/workflows/build.yml static linux job (the android build
  # reads /data/system/packages.xml before opening a TUN; see
  # docs/kernel-isolation.md D3).
  (cd "$src/mihomo" && CGO_ENABLED=0 GOOS=linux GOARCH="$abi" GOAMD64=v1 go build \
    -o "$out/$abi/libmihomo.so" -tags "with_gvisor" -trimpath \
    -ldflags "-extldflags --static -X 'github.com/metacubex/mihomo/constant.Version=v$mihomo_version' -X 'github.com/metacubex/mihomo/constant.BuildTime=fk-box' -w -s -buildid=")
done
clean "$src/xray"
clean "$src/mihomo"
ls -l "$out"/*/
