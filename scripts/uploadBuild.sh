#!/usr/bin/env bash

set -e

prop() {
  grep "${1}" gradle.properties | cut -d'=' -f2 | sed 's/\r//'
}

commitid=$(git log --pretty='%h' -1)
mcversion=$(prop mcVersion)
channel=$(prop channel)
api_channel=$([ "$channel" = "EXPERIMENTAL" ] && echo "BETA" || echo "$channel")
version="$mcversion.build.$BUILD_NUMBER-${channel,,}"
tagid="$mcversion-$BUILD_NUMBER-$commitid"
jarName="divinemc-$mcversion-$BUILD_NUMBER.jar"
divinemcid="DivineMC-$tagid"

mv divinemc-server/build/libs/divinemc-paperclip-"$version".jar "$jarName"

echo "📦 Collecting commits..."
last_tag=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
if [ -n "$last_tag" ]; then
  number=$(git log --oneline "$last_tag"..HEAD | wc -l | tr -d ' ')
else
  number=10
fi

commits_json="[]"
if [ "$number" -gt 0 ]; then
  while IFS= read -r line; do
    commit_sha=$(echo "$line" | awk '{print $1}')
    commit_message=$(echo "$line" | cut -d' ' -f2-)
    commit_time=$(git show -s --format=%cI "$commit_sha")
    commits_json=$(echo "$commits_json" | jq --arg sha "$commit_sha" --arg msg "$commit_message" --arg time "$commit_time" \
      '. + [{"sha": $sha, "message": $msg, "time": $time}]')
  done < <(git log --pretty='%h %s' "-$number")
fi

metadata_json=$(jq -n --argjson bn "$BUILD_NUMBER" --arg ch "$api_channel" --argjson commits "$commits_json" \
  '{"buildNumber": $bn, "channel": $ch, "commits": $commits}')

echo "$metadata_json" | jq . > metadata.json 2>/dev/null || echo "$metadata_json" > metadata.json

API_URL="https://api.bxteam.org/atlas/projects/divinemc/versions/$mcversion/builds/upload"
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
