#!/usr/bin/env bash

set -e

prop() {
  grep "${1}" gradle.properties | cut -d'=' -f2 | sed 's/\r//'
}

commitid=$(git log --pretty='%h' -1)
mcversion=$(prop mcVersion)
version=$(prop version)
experimental=$(prop experimental)
tagid="$mcversion-$BUILD_NUMBER-$commitid"
jarName="divinemc-$mcversion-$BUILD_NUMBER.jar"
divinemcid="DivineMC-$tagid"

channel=$([ "$experimental" = "true" ] && echo "BETA" || echo "STABLE")

mv divinemc-server/build/libs/divinemc-paperclip-"$version"-mojmap.jar "$jarName"

echo "📦 Collecting commits..."
last_tag=$(git describe --tags --abbrev=0)
number=$(git log --oneline "$last_tag"..HEAD | wc -l)

commits_json="["
first=true

while IFS= read -r line; do
  commit_sha=$(echo "$line" | awk '{print $1}')
  commit_message=$(echo "$line" | cut -d' ' -f2-)
  commit_time=$(git show -s --format=%cI "$commit_sha")

  if [ "$first" = true ]; then
    first=false
  else
    commits_json+=","
  fi

  escaped_message=$(echo "$commit_message" | sed 's/"/\\"/g')

  commits_json+="{\"sha\":\"$commit_sha\",\"message\":\"$escaped_message\",\"time\":\"$commit_time\"}"
done < <(git log --pretty='%h %s' "-$number")

commits_json+="]"

metadata_json="{\"buildNumber\":$BUILD_NUMBER,\"channel\":\"$channel\",\"commits\":$commits_json}"

echo "$metadata_json" | jq . > metadata.json 2>/dev/null || echo "$metadata_json" > metadata.json

API_URL="https://api.bxteam.org/v2/projects/divinemc/versions/$mcversion/builds/upload"
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
echo "   Channel: $channel"
echo "   Commits: $number"

response=$(curl -X POST "$API_URL" \
  -H "x-api-key: $API_KEY" \
  -F "file=@$jarName" \
  -F "metadata=$metadata_json" \
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
