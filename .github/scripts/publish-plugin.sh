#!/usr/bin/env bash
set -euo pipefail

version_input="${VERSION_INPUT:-}"
if [[ -n "$version_input" ]]; then
  version_override="$version_input"
elif [[ "${GITHUB_REF:-}" == refs/tags/v* ]]; then
  tag_name="${GITHUB_REF#refs/tags/}"
  version_override="${tag_name#v}"
else
  echo "Manual publish requires a version input when the workflow is not run from a v* tag." >&2
  exit 1
fi

if [[ ! "$version_override" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  echo "Invalid publish version (version=$version_override)" >&2
  exit 1
fi

: "${GITHUB_ACTOR:?GITHUB_ACTOR is required}"
: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"

gradle_cmd="${GRADLE_CMD:-./gradlew}"
curl_cmd="${CURL_CMD:-curl}"
maven_base_url="${MAVEN_BASE_URL:-https://maven.pkg.github.com/groundsgg/grounds-push}"
staging_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/grounds-push-maven-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"

"$gradle_cmd" \
  :plugin:publishToMavenLocal \
  -PversionOverride="$version_override" \
  -Dmaven.repo.local="$staging_root" \
  --no-daemon \
  --stacktrace

declare -a artifacts=(
  "gg/grounds/grounds-push-plugin/$version_override/grounds-push-plugin-$version_override.jar|gg/grounds/grounds-push-plugin/$version_override/grounds-push-plugin-$version_override.jar"
  "gg/grounds/grounds-push-plugin/$version_override/grounds-push-plugin-$version_override-sources.jar|gg/grounds/grounds-push-plugin/$version_override/grounds-push-plugin-$version_override-sources.jar"
  "gg/grounds/grounds-push-plugin/$version_override/grounds-push-plugin-$version_override.module|gg/grounds/grounds-push-plugin/$version_override/grounds-push-plugin-$version_override.module"
  "gg/grounds/grounds-push-plugin/$version_override/grounds-push-plugin-$version_override.pom|gg/grounds/grounds-push-plugin/$version_override/grounds-push-plugin-$version_override.pom"
  "gg/grounds/grounds-push-plugin/maven-metadata-local.xml|gg/grounds/grounds-push-plugin/maven-metadata.xml"
  "gg/grounds/push/gg.grounds.push.gradle.plugin/$version_override/gg.grounds.push.gradle.plugin-$version_override.pom|gg/grounds/push/gg.grounds.push.gradle.plugin/$version_override/gg.grounds.push.gradle.plugin-$version_override.pom"
  "gg/grounds/push/gg.grounds.push.gradle.plugin/maven-metadata-local.xml|gg/grounds/push/gg.grounds.push.gradle.plugin/maven-metadata.xml"
)

http_status() {
  "$curl_cmd" \
    -sS \
    -o /dev/null \
    -w "%{http_code}" \
    -u "$GITHUB_ACTOR:$GITHUB_TOKEN" \
    "$@"
}

upload_artifact() {
  local source_path="$1"
  local target_path="$2"
  local file="$staging_root/$source_path"
  local url="$maven_base_url/$target_path"
  local status

  if [[ ! -f "$file" ]]; then
    echo "Staged Maven artifact missing (path=$source_path)" >&2
    exit 1
  fi

  status="$(http_status -I "$url")"
  case "$status" in
    2??)
      echo "Maven artifact already published (path=$target_path)"
      return
      ;;
    404)
      ;;
    *)
      echo "Failed to check Maven artifact (path=$target_path, status=$status)" >&2
      exit 1
      ;;
  esac

  status="$(http_status -X PUT --upload-file "$file" "$url")"
  case "$status" in
    2??)
      echo "Maven artifact published (path=$target_path)"
      ;;
    409)
      status="$(http_status -I "$url")"
      if [[ "$status" == 2?? ]]; then
        echo "Maven artifact already published after conflict (path=$target_path)"
      else
        echo "Failed to publish Maven artifact after conflict (path=$target_path, status=$status)" >&2
        exit 1
      fi
      ;;
    *)
      echo "Failed to publish Maven artifact (path=$target_path, status=$status)" >&2
      exit 1
      ;;
  esac
}

for artifact in "${artifacts[@]}"; do
  upload_artifact "${artifact%%|*}" "${artifact#*|}"
done
