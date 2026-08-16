#!/usr/bin/env bash
#
# Fetches a portable Temurin JDK 21 into bench/.jdk, for a machine that has no
# JDK on the path. bench/run.sh calls this on its own when it needs to.
#
# Nothing is installed and nothing outside bench/.jdk is touched, so removing
# that directory undoes it completely. bench/.jdk is gitignored.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
target="$here/.jdk"

if [ -x "$target/bin/javac" ] || [ -x "$target/bin/javac.exe" ]; then
  echo "already present: $target"
  exit 0
fi

version="21.0.12+8"
encoded="jdk-21.0.12%2B8"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) os=windows; ext=zip ;;
  Linux)                os=linux;   ext=tar.gz ;;
  Darwin)               os=mac;     ext=tar.gz ;;
  *) echo "unsupported platform: $(uname -s)" >&2; exit 1 ;;
esac

case "$(uname -m)" in
  x86_64|amd64) arch=x64 ;;
  arm64|aarch64) arch=aarch64 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

file="OpenJDK21U-jdk_${arch}_${os}_hotspot_21.0.12_8.${ext}"
url="https://github.com/adoptium/temurin21-binaries/releases/download/${encoded}/${file}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "downloading Temurin ${version} for ${os}/${arch}"
echo "  $url"
curl -fSL --retry 3 -o "$work/$file" "$url"

echo "extracting"
if [ "$ext" = "zip" ]; then
  unzip -q "$work/$file" -d "$work/x"
else
  mkdir -p "$work/x" && tar -xzf "$work/$file" -C "$work/x"
fi

# The archive holds one top level directory, whose name carries the version.
inner="$(find "$work/x" -mindepth 1 -maxdepth 1 -type d | head -n1)"
# On mac the JDK sits under Contents/Home.
if [ -d "$inner/Contents/Home" ]; then
  inner="$inner/Contents/Home"
fi

mkdir -p "$(dirname "$target")"
mv "$inner" "$target"

"$target/bin/java" -version
echo "ready: $target"
