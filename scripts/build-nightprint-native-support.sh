#!/usr/bin/env bash
# Use the source archives and hashes from the pinned OrcaSlicer dependencies.
set -euo pipefail

prefix="$(realpath -m "${1:?supply an install directory}")"
extern="$(realpath "${2:?supply the verified Android dependency directory}")"
ndk="${ANDROID_NDK_HOME:?select the Android NDK first}"
toolchain="$ndk/build/cmake/android.toolchain.cmake"
readelf="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
grep -Fxq "Pkg.Revision = 26.1.10909125" "$ndk/source.properties"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

build_dependency() {
  local name="$1" url="$2" sha="$3"
  shift 3
  local source="$tmp/$name"
  mkdir "$source"
  curl --fail --location --silent --show-error --retry 2 "$url" -o "$tmp/archive"
  echo "$sha  $tmp/archive" | sha256sum --check
  echo "$name $sha $url"
  case "$url" in
    *.zip) unzip -q "$tmp/archive" -d "$source" ;;
    *.tar.gz) tar -xzf "$tmp/archive" -C "$source" ;;
  esac
  local roots=("$source"/*)
  test "${#roots[@]}" -eq 1
  cmake -S "${roots[0]}" -B "$source/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain" -DANDROID_PLATFORM=android-26 \
    -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_INSTALL_PREFIX="$prefix" -DCMAKE_INSTALL_LIBDIR="$prefix/lib" "$@"
  cmake --build "$source/build" --parallel 2
  cmake --install "$source/build"
}

build_dependency libpng https://github.com/glennrp/libpng/archive/refs/tags/v1.6.35.zip \
  3d22d46c566b1761a0e15ea397589b3a5f36ac09b7c785382e6470156c04247f \
  -DPNG_SHARED=OFF -DPNG_STATIC=ON -DPNG_TESTS=OFF -DPNG_ARM_NEON=off \
  -DZLIB_INCLUDE_DIR="$extern/zlib/include" \
  -DZLIB_LIBRARY="$extern/zlib/lib/arm64-v8a/libz.a"

build_dependency libjpeg-turbo https://github.com/libjpeg-turbo/libjpeg-turbo/archive/refs/tags/3.0.1.zip \
  d6d99e693366bc03897677650e8b2dfa76b5d6c54e2c9e70c03f0af821b0a52f \
  -DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_SIMD=OFF -DWITH_JPEG8=ON

build_dependency nlopt https://github.com/stevengj/nlopt/archive/v2.5.0.tar.gz \
  c6dd7a5701fff8ad5ebb45a3dc8e757e61d52658de3918e38bab233e7fd3b4ae \
  -DBUILD_SHARED_LIBS=OFF -DNLOPT_PYTHON=OFF -DNLOPT_OCTAVE=OFF \
  -DNLOPT_MATLAB=OFF -DNLOPT_GUILE=OFF -DNLOPT_SWIG=OFF -DNLOPT_TESTS=OFF

for library in "$prefix/lib/"{libpng16,libjpeg,libnlopt}.a; do
  "$readelf" -h "$library" > "$tmp/elf"
  awk '/Machine:/ { n++; if ($NF != "AArch64") exit 1 } END { if (!n) exit 1 }' "$tmp/elf"
  sha256sum "$library"
done
