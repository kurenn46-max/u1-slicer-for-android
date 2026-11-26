#!/usr/bin/env bash
# Restore the OCCT archive produced by the successful NDK r26b/API 26 build.
set -euo pipefail

repository=kurenn46-max/u1-slicer-for-android
repository_id=1390875009
run_id=45590018351
artifact_id=15011966722
build_source=106acdd7b2a1e1cfb98cacf4ab807f5f2e0c2076
artifact_name=nightprint-arm64-stage3-occt
archive_sha256=055fdaaf277ff45c7fc59f35a2b7c20a309f37eb949b125065db1b8e258896b5
occt_source=bd2a789f15235755ce4d1a3b07379a2e062fdc2e
ndk_revision=26.1.10909125

destination="${1:?supply a new artifact directory}"
evidence="${2:?supply an existing diagnostics directory}"
ndk="${ANDROID_NDK_HOME:?select the Android NDK first}"
tools="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
grep -Fxq "Pkg.Revision = $ndk_revision" "$ndk/source.properties"
"$tools/clang" --version > "$evidence/occt-consumer-compiler.txt"

endpoint="repos/$repository/actions/artifacts/$artifact_id"
gh api "$endpoint" > "$evidence/occt-artifact.json"
jq -e --arg name "$artifact_name" --arg sha "sha256:$archive_sha256" \
  --arg source "$build_source" --argjson repo "$repository_id" \
  --argjson run "$run_id" --argjson artifact "$artifact_id" '
    .id == $artifact and .name == $name and .digest == $sha and .expired == false
    and .workflow_run.id == $run and .workflow_run.head_sha == $source
    and .workflow_run.repository_id == $repo and .workflow_run.head_repository_id == $repo
  ' "$evidence/occt-artifact.json" > /dev/null

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
if ! gh api "$endpoint/zip" > "$tmp/occt.zip" 2>/dev/null; then
  echo "Cannot download pinned OCCT artifact $artifact_id from run $run_id." >&2
  exit 1
fi
echo "$archive_sha256  $tmp/occt.zip" | sha256sum --check
mkdir "$destination"
unzip -q "$tmp/occt.zip" -d "$destination"

manifest="$destination/deps-stage3"
test "$(cat "$manifest/occt.commit")" = "$occt_source"
sha256sum -c "$manifest/build-script.sha256"
grep -Fq 'clang version 17.0.2' "$manifest/compiler.txt"
diff -u <(sed "/^InstalledDir:/d" "$manifest/compiler.txt") \
  <(sed "/^InstalledDir:/d" "$evidence/occt-consumer-compiler.txt")

(
  cd "$destination"
  root=app/src/main/cpp/extern/occt
  grep -Eq '^#define[[:space:]]+OCC_VERSION_COMPLETE[[:space:]]+"7\.8\.1"' "$root/include/Standard_Version.hxx"
  test -s "$root/include/Standard.hxx"
  sha256sum -c deps-stage3/sha256.txt
  sha256sum -c deps-stage3/header-manifest.sha256
  shopt -s nullglob
  archives=("$root/lib/arm64-v8a/"*.a)
  test "${#archives[@]}" -gt 0
  sha256sum "${archives[@]}" | diff - deps-stage3/sha256.txt
  find "$root/include" -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > "$tmp/headers.sha256"
  LC_ALL=C sort deps-stage3/header-manifest.sha256 > "$tmp/expected-headers.sha256"
  LC_ALL=C sort "$tmp/headers.sha256" | diff - "$tmp/expected-headers.sha256"
  for archive in "${archives[@]}"; do
    "$tools/llvm-ar" t "$archive" > "$tmp/members"
    "$tools/llvm-readelf" -h "$archive" > "$tmp/elf"
    awk -v members="$(wc -l < "$tmp/members")" '
      /Machine:/ { count++; if ($NF != "AArch64") exit 1 }
      END { if (!count || count != members) exit 1 }
    ' "$tmp/elf"
  done
) > "$evidence/occt-verify.log" 2>&1 || { tail -30 "$evidence/occt-verify.log"; exit 1; }

cat > "$evidence/occt-provenance.txt" <<EOF_PROVENANCE
repository=$repository
producer_run=$run_id
producer_head=$build_source
artifact_id=$artifact_id
archive_sha256=$archive_sha256
upstream=https://github.com/Open-Cascade-SAS/OCCT
upstream_tag=V7_8_1
upstream_commit=$occt_source
ndk_revision=$ndk_revision
android_api=26
abi=arm64-v8a
EOF_PROVENANCE
echo "Verified OCCT V7_8_1 for Android API 26 / arm64-v8a (NDK $ndk_revision)"
