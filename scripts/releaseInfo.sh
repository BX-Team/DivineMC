#!/usr/bin/env bash

sha256() {
  sha256sum "$1" | awk '{print $1}'
}

sha1() {
  sha1sum "$1" | awk '{print $1}'
}

md5() {
  md5sum "$1" | awk '{print $1}'
}

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
changelog="changelog.md"
make_latest=$([ "$experimental" = "true" ] && echo "false" || echo "true")

rm -f $changelog

mv divinemc-server/build/libs/divinemc-shuttle-"$version"-mojmap.jar "$jarName"
{
  echo "name=$divinemcid"
  echo "tag=$tagid"
  echo "jar=$jarName"
  echo "info=$changelog"
  echo "experimental=$experimental"
  echo "make_latest=$make_latest"
} >> "$GITHUB_ENV"

{
  echo "### 📜 Commits"
  if [ "$experimental" = "true" ]; then
    echo "> [!WARNING]"
    echo "> This is an experimental build, it may contain bugs and issues. Use at your own risk."
    echo "> **Backups are mandatory!**"
    echo ""
  fi
} >> $changelog

number=$(git log --oneline ver/1.21.8 ^"$(git describe --tags --abbrev=0)" | wc -l)
git log --pretty='- [`%h`](https://github.com/BX-Team/DivineMC/commit/%H) %s' "-$number" >> $changelog

{
  echo ""
  echo "### 🔒 Checksums"
  echo "| File | $jarName |"
  echo "| ---- | ---- |"
  echo "| MD5 | $(md5 "$jarName") |"
  echo "| SHA1 | $(sha1 "$jarName") |"
  echo "| SHA256 | $(sha256 "$jarName") |"
} >> $changelog
