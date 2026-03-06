#!/bin/bash
# test.sh — 테스트 문서 빌드+변환+진단 원커맨드
#
# 사용법:
#   ./test.sh <과목/단원>              빌드 + 변환 + 진단
#   ./test.sh <과목/단원> convert      빌드 + 변환만
#   ./test.sh <과목/단원> diagnose     빌드 + 진단만
#   ./test.sh <과목/단원> extract      추출만 (InDesign 필요)
#   ./test.sh list                     등록된 케이스 목록
#
# 케이스 등록: test-data/cases.json 편집
#   {
#     "cases": {
#       "과목": {
#         "desc": "과목 설명",
#         "units": {
#           "단원": {
#             "indd": "/path/to/file.indd",
#             "pages": "8-10",       ← 선택, 생략 시 전체 페이지
#             "desc": "설명"
#           }
#         }
#       }
#     }
#   }
#
# 예시:
#   ./test.sh eng/u1 convert     → eng 과목의 u1 단원 변환
#
# pages 형식:
#   "5"      → 5페이지만
#   "8-10"   → 8~10페이지
#   생략     → 전체 페이지
#
# 추출 파일 탐색 순서:
#   1) .indd 파일과 같은 디렉토리
#   2) /tmp/indd-extract-* 중 가장 최근
#   없으면 InDesign을 실행해서 자동 추출

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CASES_FILE="$SCRIPT_DIR/test-data/cases.json"
JAR="$SCRIPT_DIR/target/idml-to-something-1.0.9-cli.jar"
JAVA="/opt/homebrew/opt/openjdk/bin/java"
JSX="$SCRIPT_DIR/scripts/extract_indd.jsx"

if [ ! -f "$CASES_FILE" ]; then
    echo "Error: $CASES_FILE not found"
    exit 1
fi

# list 커맨드
if [ "$1" = "list" ]; then
    echo "=== Registered test cases ==="
    python3 -c "
import json
d = json.load(open('$CASES_FILE'))
for subj_key, subj in d.get('cases', {}).items():
    print(f'  [{subj_key}] {subj.get(\"desc\", \"\")}')
    for unit_key, unit in subj.get('units', {}).items():
        pages = unit.get('pages', 'all')
        print(f'    {subj_key}/{unit_key:12s} {unit.get(\"desc\", \"\")}  pages: {pages}')
" 2>/dev/null || echo "  (no cases registered)"
    exit 0
fi

if [ -z "$1" ]; then
    echo "Usage: ./test.sh <과목/단원> [convert|diagnose|both|extract]"
    echo "       ./test.sh list"
    exit 1
fi

ALIAS="$1"
ACTION="${2:-both}"

# ── 레지스트리에서 .indd 경로 + pages 읽기 (과목/단원 형식) ──
CASE_INFO=$(python3 -c "
import json, sys
d = json.load(open('$CASES_FILE'))
alias = '$ALIAS'
if '/' not in alias:
    print('NOTFOUND: use 과목/단원 format', file=sys.stderr)
    sys.exit(1)
subj_key, unit_key = alias.split('/', 1)
subj = d.get('cases', {}).get(subj_key)
if not subj:
    print(f'NOTFOUND: subject \"{subj_key}\"', file=sys.stderr)
    sys.exit(1)
unit = subj.get('units', {}).get(unit_key)
if not unit:
    print(f'NOTFOUND: unit \"{unit_key}\" in \"{subj_key}\"', file=sys.stderr)
    sys.exit(1)
pages = unit.get('pages', '')
print(unit['indd'] + '|' + str(pages))
" 2>/dev/null)

if [ $? -ne 0 ] || [ -z "$CASE_INFO" ]; then
    echo "Error: Unknown case '$ALIAS'"
    echo "Use 과목/단원 format. Available cases:"
    python3 -c "
import json
d = json.load(open('$CASES_FILE'))
for sk, sv in d.get('cases', {}).items():
    for uk in sv.get('units', {}).keys():
        print(f'  {sk}/{uk}')
" 2>/dev/null
    exit 1
fi

INDD_PATH="${CASE_INFO%%|*}"
PAGES_RAW="${CASE_INFO##*|}"

if [ ! -f "$INDD_PATH" ]; then
    echo "Error: .indd file not found: $INDD_PATH"
    exit 1
fi

INDD_DIR="$(dirname "$INDD_PATH")"

# ── pages 파싱 ("5" → 5-5, "8-10" → 8-10, "" → 0-0 전체) ──
START_PAGE=0
END_PAGE=0

if [ -n "$PAGES_RAW" ]; then
    if echo "$PAGES_RAW" | grep -q '-'; then
        START_PAGE="${PAGES_RAW%%-*}"
        END_PAGE="${PAGES_RAW##*-}"
    else
        START_PAGE="$PAGES_RAW"
        END_PAGE="$PAGES_RAW"
    fi
fi

echo "=== Test: $ALIAS ==="
echo "  INDD: $INDD_PATH"
if [ "$START_PAGE" -gt 0 ] 2>/dev/null; then
    if [ "$START_PAGE" -eq "$END_PAGE" ]; then
        echo "  Pages: $START_PAGE"
    else
        echo "  Pages: $START_PAGE-$END_PAGE"
    fi
else
    echo "  Pages: all"
fi

# ── 추출 파일 자동 탐색 ──
find_extracted_files() {
    IDML=""
    RESOLVED=""
    EXTRACT_DIR=""

    # 1) .indd 파일과 같은 디렉토리에서 찾기
    if [ -f "$INDD_DIR/output.idml" ]; then
        IDML="$INDD_DIR/output.idml"
        EXTRACT_DIR="$INDD_DIR"
        if [ -f "$INDD_DIR/resolved.json" ]; then
            RESOLVED="$INDD_DIR/resolved.json"
        fi
        return 0
    fi

    # 2) /tmp/indd-extract-* 중 같은 .indd에서 추출된 가장 최근 디렉토리
    local tmpdir="${TMPDIR:-/tmp}"
    local latest=""
    local latest_ts=0

    for d in "$tmpdir"/indd-extract-*; do
        [ -d "$d" ] || continue
        [ -f "$d/output.idml" ] || continue

        # .source 파일로 원본 .indd 경로 확인
        if [ -f "$d/.source" ]; then
            local src
            src=$(cat "$d/.source" 2>/dev/null)
            if [ "$src" != "$INDD_PATH" ]; then
                continue  # 다른 문서에서 추출된 것 → 건너뜀
            fi
        else
            continue  # .source 없으면 출처 불명 → 건너뜀
        fi

        local ts
        ts=$(stat -f "%m" "$d" 2>/dev/null || stat -c "%Y" "$d" 2>/dev/null || echo "0")
        if [ "$ts" -gt "$latest_ts" ]; then
            latest_ts="$ts"
            latest="$d"
        fi
    done

    if [ -n "$latest" ]; then
        IDML="$latest/output.idml"
        EXTRACT_DIR="$latest"
        if [ -f "$latest/resolved.json" ]; then
            RESOLVED="$latest/resolved.json"
        fi
        return 0
    fi

    return 1
}

# ── InDesign 추출 실행 ──
run_extraction() {
    echo "--- Extracting via InDesign ---"

    # InDesign 앱 찾기
    local app_name=""
    for year in 2026 2025 2024 2023; do
        local app_path="/Applications/Adobe InDesign ${year}/Adobe InDesign ${year}.app"
        if [ -d "$app_path" ]; then
            app_name="Adobe InDesign ${year}"
            break
        fi
    done

    if [ -z "$app_name" ]; then
        echo "Error: Adobe InDesign not found in /Applications/"
        exit 1
    fi

    if [ ! -f "$JSX" ]; then
        echo "Error: ExtendScript not found: $JSX"
        exit 1
    fi

    # 추출 디렉토리 생성
    local tmpdir="${TMPDIR:-/tmp}"
    local ts=$(date +%s%3N 2>/dev/null || date +%s)
    EXTRACT_DIR="$tmpdir/indd-extract-$ts"
    mkdir -p "$EXTRACT_DIR"

    echo "  App: $app_name"
    echo "  Output: $EXTRACT_DIR"

    # osascript로 InDesign 호출 (startPage, endPage 전달)
    osascript -e "
tell application \"$app_name\"
    activate
    with timeout of 600 seconds
        do script (read POSIX file \"$JSX\") language javascript with arguments {\"$INDD_PATH\", \"$EXTRACT_DIR\", \"$START_PAGE\", \"$END_PAGE\", \"0\"}
    end timeout
end tell
" 2>&1

    # .done 파일 대기 (최대 600초)
    echo "  Waiting for extraction to complete..."
    local wait_count=0
    while [ ! -f "$EXTRACT_DIR/.done" ] && [ $wait_count -lt 600 ]; do
        sleep 1
        wait_count=$((wait_count + 1))
        if [ $((wait_count % 10)) -eq 0 ]; then
            if [ -f "$EXTRACT_DIR/.progress" ]; then
                local step
                step=$(python3 -c "
import json
p = json.load(open('$EXTRACT_DIR/.progress'))
print(p.get('step', '?'))
" 2>/dev/null)
                echo "  [$wait_count s] step: $step"
            else
                echo "  [$wait_count s] waiting..."
            fi
        fi
    done

    if [ ! -f "$EXTRACT_DIR/.done" ]; then
        echo "Error: Extraction timed out (600s)"
        exit 1
    fi

    local status
    status=$(python3 -c "
import json
d = json.load(open('$EXTRACT_DIR/.done'))
print(d.get('status', 'unknown'))
" 2>/dev/null)

    if [ "$status" != "ok" ] && [ "$status" != "success" ]; then
        echo "Error: Extraction failed (status: $status)"
        cat "$EXTRACT_DIR/.done" 2>/dev/null
        exit 1
    fi

    IDML="$EXTRACT_DIR/output.idml"
    RESOLVED=""
    if [ -f "$EXTRACT_DIR/resolved.json" ]; then
        RESOLVED="$EXTRACT_DIR/resolved.json"
    fi

    if [ ! -f "$IDML" ]; then
        echo "Error: output.idml not found after extraction"
        exit 1
    fi

    # 원본 .indd 경로 기록 (다음 탐색에서 매칭용)
    echo "$INDD_PATH" > "$EXTRACT_DIR/.source"

    echo "  Extraction OK"
}

# ── extract 전용 커맨드 ──
if [ "$ACTION" = "extract" ]; then
    run_extraction
    echo "  IDML: $IDML"
    [ -n "$RESOLVED" ] && echo "  Resolved: $RESOLVED"
    exit 0
fi

# ── 추출 파일 탐색, 없으면 자동 추출 ──
find_extracted_files
if [ $? -ne 0 ] || [ -z "$IDML" ]; then
    echo "  No extracted files found, running InDesign extraction..."
    run_extraction
fi

HWPX="$EXTRACT_DIR/output.hwpx"

echo "  IDML: $IDML"
[ -n "$RESOLVED" ] && echo "  Resolved: $RESOLVED"
echo "  HWPX: $HWPX"

# ── 빌드 ──
echo "--- Building ---"
cd "$SCRIPT_DIR"
mvn package -q -DskipTests
if [ $? -ne 0 ]; then
    echo "Build FAILED"
    exit 1
fi
echo "Build OK"

# resolved 옵션
RESOLVED_OPT=""
if [ -n "$RESOLVED" ] && [ -f "$RESOLVED" ]; then
    RESOLVED_OPT="--resolved $RESOLVED"
fi

# 페이지 옵션
PAGE_OPT=""
if [ "$START_PAGE" -gt 0 ] 2>/dev/null; then
    PAGE_OPT="--start-page $START_PAGE --end-page $END_PAGE"
fi

# Links 디렉토리 (이미지 경로 리졸브용)
LINKS_OPT=""
if [ -d "$INDD_DIR/Links" ]; then
    LINKS_OPT="--links-directory $INDD_DIR/Links"
    echo "  Links: $INDD_DIR/Links"
fi

# ── 변환 ──
if [ "$ACTION" = "convert" ] || [ "$ACTION" = "both" ]; then
    echo "--- Converting ---"
    $JAVA -jar "$JAR" --convert "$IDML" "$HWPX" $RESOLVED_OPT $PAGE_OPT $LINKS_OPT --include-images
    echo ""

    # PDF 프리뷰 + HWPX 열기
    PDF_PREVIEW="$EXTRACT_DIR/preview.pdf"
    if [ -f "$PDF_PREVIEW" ]; then
        echo "  Opening preview PDF: $PDF_PREVIEW"
        open "$PDF_PREVIEW"
    fi
    if [ -f "$HWPX" ]; then
        echo "  Opening HWPX: $HWPX"
        open "$HWPX"
    fi
fi

# ── 진단 ──
if [ "$ACTION" = "diagnose" ] || [ "$ACTION" = "both" ]; then
    echo "--- Diagnosing ---"
    if [ -n "$RESOLVED_OPT" ]; then
        $JAVA -jar "$JAR" --diagnose "$IDML" $RESOLVED_OPT $PAGE_OPT 2>/dev/null
    else
        echo "Warning: No resolved.json — diagnose shows IDML only"
        $JAVA -jar "$JAR" --diagnose "$IDML" $PAGE_OPT 2>/dev/null
    fi
fi
