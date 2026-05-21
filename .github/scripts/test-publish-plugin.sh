#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
publish_script="$script_dir/publish-plugin.sh"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

make_gradle_stub() {
  local stub="$tmpdir/gradlew"
  cat >"$stub" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

repo=""
version=""
for arg in "$@"; do
  case "$arg" in
    -Dmaven.repo.local=*) repo="${arg#-Dmaven.repo.local=}" ;;
    -PversionOverride=*) version="${arg#-PversionOverride=}" ;;
  esac
done

if [[ -z "$repo" || -z "$version" ]]; then
  echo "missing staging repo or version" >&2
  exit 1
fi

main_dir="$repo/gg/grounds/grounds-push-plugin/$version"
marker_dir="$repo/gg/grounds/push/gg.grounds.push.gradle.plugin/$version"
mkdir -p "$main_dir" "$marker_dir"
printf 'jar' >"$main_dir/grounds-push-plugin-$version.jar"
printf 'sources' >"$main_dir/grounds-push-plugin-$version-sources.jar"
printf 'module' >"$main_dir/grounds-push-plugin-$version.module"
printf 'pom' >"$main_dir/grounds-push-plugin-$version.pom"
printf 'main metadata' >"$repo/gg/grounds/grounds-push-plugin/maven-metadata-local.xml"
printf 'marker pom' >"$marker_dir/gg.grounds.push.gradle.plugin-$version.pom"
printf 'marker metadata' >"$repo/gg/grounds/push/gg.grounds.push.gradle.plugin/maven-metadata-local.xml"
STUB
  chmod +x "$stub"
  echo "$stub"
}

make_curl_stub() {
  local mode="$1"
  local stub="$tmpdir/curl-$mode"
  cat >"$stub" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

mode="${CURL_STUB_MODE:?}"
log="${CURL_STUB_LOG:?}"
method="GET"
upload=""
url=""
while (($#)); do
  case "$1" in
    -I) method="HEAD" ;;
    -X) method="$2"; shift ;;
    --upload-file) upload="$2"; shift ;;
    http*) url="$1" ;;
  esac
  shift
done

printf '%s %s %s\n' "$method" "${upload:+UPLOAD}" "$url" >>"$log"

case "$mode:$method" in
  missing:HEAD) printf '404' ;;
  missing:PUT) printf '201' ;;
  existing:HEAD) printf '200' ;;
  existing:PUT) printf '409' ;;
  *) printf '500' ;;
esac
STUB
  chmod +x "$stub"
  echo "$stub"
}

assert_equals() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  if [[ "$expected" != "$actual" ]]; then
    echo "$message: expected '$expected', got '$actual'" >&2
    exit 1
  fi
}

test_uploads_staged_artifacts_without_checksums() {
  local log="$tmpdir/missing.log"
  : >"$log"

  CURL_STUB_MODE=missing \
  CURL_STUB_LOG="$log" \
  GITHUB_ACTOR=actor \
  GITHUB_TOKEN=token \
  GITHUB_REF=refs/tags/v1.2.3 \
  RUNNER_TEMP="$tmpdir" \
  GRADLE_CMD="$(make_gradle_stub)" \
  CURL_CMD="$(make_curl_stub missing)" \
    "$publish_script"

  assert_equals "7" "$(grep -c '^PUT UPLOAD ' "$log")" "upload count"
  if grep -qE '\.(md5|sha1|sha256|sha512)$' "$log"; then
    echo "checksum sidecar was uploaded" >&2
    exit 1
  fi
  grep -q 'gg/grounds/grounds-push-plugin/1.2.3/grounds-push-plugin-1.2.3.jar' "$log"
  grep -q 'gg/grounds/push/gg.grounds.push.gradle.plugin/1.2.3/gg.grounds.push.gradle.plugin-1.2.3.pom' "$log"
}

test_skips_existing_artifacts() {
  local log="$tmpdir/existing.log"
  : >"$log"

  CURL_STUB_MODE=existing \
  CURL_STUB_LOG="$log" \
  GITHUB_ACTOR=actor \
  GITHUB_TOKEN=token \
  VERSION_INPUT=1.2.3 \
  RUNNER_TEMP="$tmpdir" \
  GRADLE_CMD="$(make_gradle_stub)" \
  CURL_CMD="$(make_curl_stub existing)" \
    "$publish_script"

  assert_equals "7" "$(grep -c '^HEAD  ' "$log")" "head count"
  assert_equals "0" "$(grep -c '^PUT UPLOAD ' "$log" || true)" "upload count"
}

test_uploads_staged_artifacts_without_checksums
test_skips_existing_artifacts
