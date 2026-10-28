#!/bin/bash
# 生成 README 截图（中文 + 英文）。需要一台已连接的模拟器或设备；全部数据是本机虚构的测试账号，不联网。
# 用法：tools/readme_screenshots.sh [输出目录，默认 docs/screenshots]
# 个别页面在进程刚启动时偶发组合竞争，没生成出来的会自动单独重跑。
set -uo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-docs/screenshots}"
ADB="${ADB:-adb}"
SERIAL_ARG=()
[ -n "${ANDROID_SERIAL:-}" ] && SERIAL_ARG=(-s "$ANDROID_SERIAL")
# macOS 自带的 bash 3.2 在 set -u 下展开空数组会报错，所以用 ${arr[@]+…} 的写法
A() { "$ADB" ${SERIAL_ARG[@]+"${SERIAL_ARG[@]}"} "$@"; }

# 文件名 → 测试方法
declare -a SHOTS=(
  "01-home:home" "02-repos:repos" "03-repo:repo" "04-issue:issue" "05-diff-split:diff"
  "06-compare:compareScreen" "07-notifications:notifications" "08-insights:insights"
  "09-star-list:starLists" "10-settings:settings" "11-login:login" "12-star-overview:starListsOverview"
)

# 手机比例的屏幕：1080x2340、约 393dp 宽
A shell wm size 1080x2340
A shell wm density 440
trap 'A shell wm size reset; A shell wm density reset' EXIT

run_tests() { # $1 语言  $2 类过滤（整个类，或 类#方法）
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.class="$2" \
    -Pandroid.testInstrumentationRunnerArguments.readmeShots=true \
    -Pandroid.testInstrumentationRunnerArguments.lang="$1" > /dev/null 2>&1 || true
}

pull() { # $1 语言
  mkdir -p "$OUT/$1"
  for f in $(A shell run-as com.mobilegh ls "cache/readme/$1" 2>/dev/null | tr -d '\r'); do
    A shell run-as com.mobilegh cat "cache/readme/$1/$f" > "$OUT/$1/$f"
  done
}

CLASS=com.mobilegh.ui.ReadmeScreenshotsTest
for LANGUAGE in zh en; do
  mkdir -p "$OUT/$LANGUAGE"
  rm -f "$OUT/$LANGUAGE"/*.png
  A shell run-as com.mobilegh rm -r "cache/readme/$LANGUAGE" 2>/dev/null
  run_tests "$LANGUAGE" "$CLASS"
  pull "$LANGUAGE"
  for entry in "${SHOTS[@]}"; do
    name="${entry%%:*}"; method="${entry##*:}"
    for attempt in 1 2 3; do
      [ -s "$OUT/$LANGUAGE/$name.png" ] && break
      # 变量名后面紧跟中文全角标点时必须加花括号，否则 bash 会把标点的首字节当成变量名的一部分
      echo "重跑 ${LANGUAGE}/${name} (第 ${attempt} 次)"
      run_tests "$LANGUAGE" "$CLASS#$method"
      pull "$LANGUAGE"
    done
  done
done
echo "截图已写入 ${OUT}"
