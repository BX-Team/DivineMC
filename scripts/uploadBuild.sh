#!/usr/bin/env bash

set -e

MAX_COMMITS=100
FALLBACK_COMMITS=10

prop() {
  grep "${1}" gradle.properties | cut -d'=' -f2 | sed 's/\r//'
}

commitid=$(git rev-parse HEAD)
mcversion=$(prop mcVersion)
channel=$(prop channel)
api_channel=$([ "$channel" = "EXPERIMENTAL" ] && echo "beta" || echo "${channel,,}")
version="$mcversion.build.$BUILD_NUMBER-${channel,,}"
jarName="divinemc-$mcversion-$BUILD_NUMBER.jar"

mv divinemc-server/build/libs/divinemc-paperclip-"$version".jar "$jarName"

echo "📦 Collecting commits..."

latest=$(curl -fsS -H "Cache-Control: no-cache" \
  "https://api.bxteam.org/v1/builds/divinemc/$mcversion/latest" 2>/dev/null || echo '{}')
last_commit=$(echo "$latest" | jq -r '.commit // empty')

range=""
if [ -n "$last_commit" ] && git cat-file -e "$last_commit^{commit}" 2>/dev/null; then
  range="$last_commit..HEAD"
  echo "   Since build $(echo "$latest" | jq -r '.build') ($range)"
else
  last_tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
  if [ -n "$last_tag" ]; then
    range="$last_tag..HEAD"
    echo "   No commit on the previous build; falling back to tag $last_tag"
  else
    echo "   Nothing to compare against; taking the last $FALLBACK_COMMITS commits"
  fi
fi

range_args=()
if [ -n "$range" ]; then
  total=$(git rev-list --count "$range")
  range_args=("$range")
else
  total=$FALLBACK_COMMITS
fi

number=$total
if [ "$number" -gt "$MAX_COMMITS" ]; then
  number=$MAX_COMMITS
  echo "   $total commits in range, sending the newest $MAX_COMMITS"
fi

commits_json=$(git log --pretty=format:'%H%x1f%s%x1f%cI' -n "$number" "${range_args[@]}" | jq -R -s '
  split("\n") | map(select(length > 0) | split("\u001f")) | map({sha: .[0], summary: .[1], at: .[2]})')

metadata_json=$(jq -n --argjson build "$BUILD_NUMBER" --arg ch "$api_channel" --arg commit "$commitid" --argjson commits "$commits_json" \
  '{"build": $build, "channel": $ch, "commit": $commit, "commits": $commits}')

echo "$metadata_json" | jq . > metadata.json 2>/dev/null || echo "$metadata_json" > metadata.json

API_URL="https://api.bxteam.org/v1/publish/builds/divinemc/$mcversion"
API_KEY="${API_KEY:-}"

if [ -z "$API_KEY" ]; then
  echo "❌ Error: API_KEY environment variable is not set"
  exit 1
fi

echo ""
echo "🚀 Uploading build to API..."
echo "   URL: $API_URL"
echo "   File: $jarName"
echo "   Build: $BUILD_NUMBER"
echo "   Channel: $channel (API: $api_channel)"
echo "   Commits: $number"

response=$(curl -X POST "$API_URL" \
  -H "Authorization: Bearer $API_KEY" \
  -F "file=@$jarName" \
  -F "metadata=<metadata.json;type=application/json" \
  -w "\n%{http_code}" \
  -s)

http_code=$(echo "$response" | tail -n1)
response_body=$(echo "$response" | sed '$d')

echo ""
echo "📡 Response:"
echo "$response_body" | jq . 2>/dev/null || echo "$response_body"

if [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
  echo ""
  echo "✅ Build uploaded successfully!"
  echo "   Build Number: $BUILD_NUMBER"
  echo "   Version: $mcversion"
  echo "   Channel: $channel"
else
  echo ""
  echo "❌ Upload failed with HTTP status: $http_code"
  exit 1
fi
