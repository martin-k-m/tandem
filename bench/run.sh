#!/usr/bin/env bash
#
# Regenerates every number in docs/BENCHMARKS.md.
#
# It prints the environment first, then the results, so a figure is never
# separated from the machine that produced it. Redirect the whole thing into a
# file and the file is the evidence:
#
#   bench/run.sh > bench/results.txt
#
# Maven is not required. If a JDK is not on the path, bench/bootstrap-jdk.sh
# fetches a portable Temurin 21 into bench/.jdk and this script uses it.
#
# Pass a single benchmark name to run one of them: append, throughput, recovery.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(dirname "$here")"
out="$here/.classes"
which="${1:-all}"

# ---------------------------------------------------------------- toolchain

if [ -x "$here/.jdk/bin/javac" ] || [ -x "$here/.jdk/bin/javac.exe" ]; then
  JAVA_HOME="$here/.jdk"
elif [ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME" ]; then
  :
elif command -v javac >/dev/null 2>&1; then
  JAVA_HOME="$(dirname "$(dirname "$(command -v javac)")")"
else
  echo "no JDK found; running bench/bootstrap-jdk.sh" >&2
  "$here/bootstrap-jdk.sh"
  JAVA_HOME="$here/.jdk"
fi
export JAVA_HOME
javac="$JAVA_HOME/bin/javac"
java="$JAVA_HOME/bin/java"

# Windows shells need ; between classpath entries and native paths in them.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) sep=';'; native() { cygpath -w "$1"; } ;;
  *)                    sep=':'; native() { printf '%s' "$1"; } ;;
esac

# ------------------------------------------------------------- environment

echo "# Tandem benchmark run"
echo
echo "date:        $(date -u '+%Y-%m-%dT%H:%M:%SZ') (UTC)"
echo "git commit:  $(cd "$root" && git rev-parse --short HEAD 2>/dev/null || echo 'not a git checkout')"
echo "git branch:  $(cd "$root" && git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '-')"
echo "uname:       $(uname -s -r -m)"
echo
echo "## JDK"
"$java" -version 2>&1 | sed 's/^/  /'
echo
echo "## Machine"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    powershell -NoProfile -Command '
      $c = Get-CimInstance Win32_Processor | Select-Object -First 1
      $o = Get-CimInstance Win32_OperatingSystem
      "  CPU:     $($c.Name)"
      "  Cores:   $($c.NumberOfCores) physical, $($c.NumberOfLogicalProcessors) logical, $($c.MaxClockSpeed) MHz nominal"
      "  OS:      $($o.Caption) build $($o.BuildNumber)"
      "  RAM:     {0:N2} GB total, {1:N2} GB free" -f ($o.TotalVisibleMemorySize/1MB), ($o.FreePhysicalMemory/1MB)
      Get-PhysicalDisk | ForEach-Object { "  Disk:    $($_.FriendlyName) ($($_.MediaType), $($_.BusType))" }
      "  Load:    $((Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average)% CPU at start"
    ' 2>/dev/null || echo "  (could not query)"
    ;;
  Linux)
    echo "  CPU:     $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ //')"
    echo "  Cores:   $(nproc) logical"
    echo "  RAM:     $(awk '/MemTotal/ {printf "%.2f GB total", $2/1048576}' /proc/meminfo), $(awk '/MemAvailable/ {printf "%.2f GB available", $2/1048576}' /proc/meminfo)"
    echo "  Load:    $(cut -d' ' -f1-3 /proc/loadavg) (1/5/15 min)"
    ;;
  Darwin)
    echo "  CPU:     $(sysctl -n machdep.cpu.brand_string)"
    echo "  Cores:   $(sysctl -n hw.ncpu) logical"
    echo "  RAM:     $(( $(sysctl -n hw.memsize) / 1073741824 )) GB"
    echo "  Load:    $(uptime | sed 's/.*load averages*: //')"
    ;;
esac

echo
echo "## Storage under test"
echo "  The benchmark writes to a directory from java.io.tmpdir:"
echo "  ${TMPDIR:-${TMP:-/tmp}}"
echo "  Whether that is the SSD above or a RAM disk decides the file rows entirely."

# --------------------------------------------------------------- build, run

rm -rf "$out"
mkdir -p "$out/main" "$out/bench"

# Sources are listed relative to the project root and compiled from there, so
# the argument files hold paths javac resolves the same way on every platform.
cd "$root"

find src/main/java -name '*.java' > "$out/main.txt"
"$javac" -encoding UTF-8 -d "$(native "$out/main")" "@$(native "$out/main.txt")"

find bench/src -name '*.java' > "$out/bench.txt"
"$javac" -encoding UTF-8 -cp "$(native "$out/main")" -d "$(native "$out/bench")" \
  "@$(native "$out/bench.txt")"

echo
echo "## Results"
"$java" -cp "$(native "$out/main")$sep$(native "$out/bench")" \
  io.github.martinkm.tandem.bench.Bench "$which"

echo
echo "(regenerate with: bench/run.sh)"
