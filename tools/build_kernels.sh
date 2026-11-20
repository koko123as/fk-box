#!/usr/bin/env bash
# Builds the official, unmodified kernels pinned in version.properties and
# fk-box's own executables for Android (docs/kernel-isolation.md, "构建"):
#
#   OUT/jni/<abi>/libxray.so       Xray-core  ./main   (release.yml flags)
#   OUT/jni/<abi>/libmihomo.so     mihomo     .        (build.yml linux job, with_gvisor, static)
#   OUT/jni/<abi>/libfkcore.so     fk-box     core/cmd/fk-core
#   OUT/jni/<abi>/libxrayshare.so  fk-box     core/tools/xrayshare (official libXray)
#   OUT/libbox-official.aar        sing-box   cmd/internal/build_libbox (arm64 only)
#
# Each kernel tree is a fresh clone of the upstream tag: nothing is copied into
# it, no patch script runs, and `git status --porcelain` must be empty after
# the build.
#
# usage: tools/build_kernels.sh OUT ABI... [--no-libbox]   (ABI: arm64 | amd64)
# needs: go ($GO_VERSION), ANDROID_NDK_HOME (NDK r28), ANDROID_HOME and
#        OpenJDK 17 for libbox, git
set -euo pipefail
out=$(realpath -m "$1"); shift
abis=(); libbox=1
for arg in "$@"; do
  case "$arg" in
    --no-libbox) libbox=0 ;;
    arm64|amd64) abis+=("$arg") ;;
    *) echo "unknown argument $arg" >&2; exit 2 ;;
  esac
done
root=$(cd "$(dirname "$0")/.." && pwd)
prop() { sed -n "s/^$1=//p" "$root/version.properties" | tr -d '\r'; }
singbox_version=$(prop CORE_VERSION)
xray_version=$(prop XRAY_VERSION)
mihomo_version=$(prop MIHOMO_VERSION)
libxray_version=$(prop LIBXRAY_MODULE_VERSION)
app_version=$(prop VERSION_NAME)
src=${KERNEL_SRC:-$out/src}
mkdir -p "$out" "$src"
export GOTOOLCHAIN=local
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

# xrayshare's go.mod must pin the libXray of version.properties.
test "$(cd "$root/core/tools/xrayshare" && go list -m -f '{{.Version}}' github.com/xtls/libxray)" = "$libxray_version" || {
  echo "::error::core/tools/xrayshare/go.mod does not require libXray $libxray_version (tools/bump_core.py updates it)"; exit 1; }

clone() { # repo tag dir
  if [[ ! -d "$3/.git" ]]; then
    git clone --quiet --depth 1 --branch "$2" "https://github.com/$1.git" "$3"
  fi
  test "$(git -C "$3" describe --tags --exact-match HEAD)" = "$2"
}
unmodified() { # dir
  local status
  status=$(git -C "$1" status --porcelain --ignored=no)
  if [[ -n "$status" ]]; then
    echo "::error::$1 was modified by the build:"; echo "$status"; exit 1
  fi
  echo "$(basename "$1") $(git -C "$1" describe --tags): git status --porcelain is empty"
}

clone XTLS/Xray-core "v$xray_version" "$src/xray"
clone MetaCubeX/mihomo "v$mihomo_version" "$src/mihomo"

for abi in "${abis[@]}"; do
  case "$abi" in
    arm64) cc="$toolchain/aarch64-linux-android24-clang"; dir=arm64-v8a ;;
    amd64) cc="$toolchain/x86_64-linux-android24-clang"; dir=x86_64 ;;
  esac
  jni="$out/jni/$dir"
  mkdir -p "$jni"
  # Xray-core .github/workflows/release.yml: android build, verbatim flags,
  # NDK r28 android24 clang as upstream.
  (cd "$src/xray" && CC="$cc" CGO_ENABLED=1 GOOS=android GOARCH="$abi" go build \
    -o "$jni/libxray.so" -trimpath -buildvcs=false -gcflags="all=-l=4" \
    -ldflags="-X github.com/xtls/xray-core/core.build=$(git describe --always --dirty) -s -w -buildid= -checklinkname=0 -extldflags=-Wl,--pack-dyn-relocs=android" \
    ./main)
  # mihomo .github/workflows/build.yml: the static linux-<arch> job, verbatim
  # tags and flags (CGO_ENABLED=0, -extldflags --static). Its android build
  # (android && !cmfa) reads /data/system/packages.xml before it opens any
  # TUN, which an app cannot (P0, docs/kernel-isolation.md); the cmfa build
  # expects the embedding app to call into it. The static linux binary runs
  # on Android and takes the TUN as fd 3 without netlink.
  (cd "$src/mihomo" && CGO_ENABLED=0 GOOS=linux GOARCH="$abi" GOAMD64=v1 go build \
    -o "$jni/libmihomo.so" -tags "with_gvisor" -trimpath \
    -ldflags "-extldflags --static -X 'github.com/metacubex/mihomo/constant.Version=v$mihomo_version' -X 'github.com/metacubex/mihomo/constant.BuildTime=fk-box' -w -s -buildid=")
  (cd "$root/core" && CC="$cc" CGO_ENABLED=1 GOOS=android GOARCH="$abi" go build \
    -o "$jni/libfkcore.so" -trimpath -buildvcs=false \
    -ldflags "-X main.Version=$app_version -s -w -buildid=" ./cmd/fk-core)
  (cd "$root/core/tools/xrayshare" && CC="$cc" CGO_ENABLED=1 GOOS=android GOARCH="$abi" go build \
    -o "$jni/libxrayshare.so" -trimpath -buildvcs=false -ldflags "-s -w -buildid= -checklinkname=0" .)
done
unmodified "$src/xray"
unmodified "$src/mihomo"

if [[ $libbox == 1 ]]; then
  clone SagerNet/sing-box "v$singbox_version" "$src/sing-box"
  # sing-box Makefile lib_install + cmd/internal/build_libbox, unchanged.
  (cd "$src/sing-box" && make lib_install \
    && PATH="$PATH:$(go env GOPATH)/bin" go run ./cmd/internal/build_libbox -target android -platform android/arm64)
  mv "$src/sing-box/libbox.aar" "$out/libbox-official.aar"
  rm -f "$src/sing-box/libbox-legacy.aar"
  unmodified "$src/sing-box"
fi
ls -l "$out" "$out"/jni/*/
