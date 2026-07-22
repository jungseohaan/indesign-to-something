# SPEC-054: 개발용 콘텐츠 추출 모드 (graphic-only / text-only) + 96dpi 기본

## 문제

개발 반복(추출→변환→육안)에서 InDesign 렌더링이 지배 비용이다. 텍스트/레이아웃
이슈를 다룰 때도 페이지 평면·인라인 PNG 렌더를 전부 기다려야 하고, 그래픽
이슈를 다룰 때도 전체 변환을 돌린다. 또 개발 중엔 220dpi 품질이 불필요하다.

## 목표

`--content-mode` 로 추출·변환 범위를 제한해 반복 시간을 줄인다.

| 모드 | 추출 (ExtendScript) | 변환 (Java) |
|------|--------------------|-------------|
| `full` (기본) | 현행 동일 | 현행 동일 |
| `text-only` | **PNG 렌더 5단계 전부 생략** (page_backgrounds, inline_objects, decoration_groups, image_placed_frames, textless page planes). 추출 플랜·object-plans·resolved 수집은 유지 | 글상자·테이블·텍스트만 배치. 시각물 배치는 자연 생략 (renderedFloatingItems 비어 있음) |
| `graphic-only` | 현행 동일 (렌더는 그래픽 산출물이므로) | 페이지 배경·플로팅/인라인 PNG 등 시각물만 배치. Phase 3 스토리 텍스트·Phase 4 테이블·Phase 4.5 불릿 생략 |

개발 루프(issue.py) 기본 이미지 해상도는 **96dpi** (별도 지시 없을 때).
프로덕션/데스크탑 기본(220dpi, conversion-config.json)은 불변.

## 해결 방안

### 1. 모드 플러밍 (추출)

- `scripts/dev/issue.py`
  - `--content-mode full|text-only|graphic-only` (기본 full) 추가, AppleScript
    인자 **args[18]** 로 전달 (args[0..17] 사용 중).
  - `--dpi N` (기본 **96**) 추가: `conversion-config.json` 을 읽어
    `rendering.pngExportResolution` 만 치환한 파생 config 를 issue 디렉토리에
    쓰고 `--extract-config` 로 전달. `--dpi 220` 지정 시 프로덕션 화질 재현.
  - **페이지 평면 캐시 키에 dpi 반영**: `page_plane_cache_dir()` 경로에
    `-{dpi}dpi` suffix 추가 (dpi 다른 캐시 재사용 방지). content-mode 는
    text-only 일 때 페이지 평면을 만들지 않으므로 캐시 무관.
- `scripts/indd/input_prepare.jsx` `_parseArgs`
  - `contentMode: (args[18] || "full").toLowerCase()` 추가.
- `scripts/indd/extraction_orchestrator.jsx` `_runRenderPhases`
  - `ctx.contentMode === "text-only"` 이면 5개 렌더 호출을 생략하고 빈 결과로
    대체 (`renderedFloatingItems = []`), `_marker` 에 skip 사유 기록.
    extraction-plan/object-plans/extraction-results(빈 items) 기록은 유지 —
    Java 텍스트 소유권 판정이 object-plans 를 소비하기 때문.
  - `_exportPdf` 는 dev 루프에서 이미 skip (기본 skip_pdf=True).
- `scripts/indd/resolved_collectors.jsx`
  - `documentInfo.contentMode` 필드 기록 (full 이 아닐 때만).

### 2. 변환기 (Java)

- `resolved/ResolvedData.java`: `documentInfo.contentMode` 읽기 + `contentMode()`
  접근자.
- `IDMLToHwpxConverter.java`: 파이프라인 분기 보정 —
  `isNewPipeline = resolvedData != null && (!resolvedData.allRenderedFloatingItems().isEmpty() || resolvedData.hasContentMode())`.
  **text-only 에서 renderedFloatingItems 가 비어도 레거시로 떨어지면 안 됨**
  (IDMLToHwpxConverter.java:134, :507 두 곳).
- `ConvertOptions`/`ConverterCLI`: `--content-mode` CLI 오버라이드 (선택,
  resolved 마커가 1차 소스).
- `normalizer/ResolvedToASTBuilder.java`
  - `graphic-only`: Phase 3 (StoryConverter)·Phase 4 (TableBuilder)·
    Phase 4.5 (BulletInserter) 호출 생략. Phase 0~2 (인프라/페이지/프레임 배치)
    와 Stage 2.5/3 (시각 배치) 유지. 빈 편집 글상자 블록은 방출하지 않음.
  - `text-only`: 별도 게이트 불필요 예상 (시각 배치가 rendered 항목 부재로
    자연 no-op). 누락 PNG 파일 경고가 나오는 경로가 있으면 contentMode 조건으로
    침묵 처리.

### 3. 알려진 제한 (v1, 문서화만)

- text-only 에서 `textOwner=indesign_png` (COMPLETE_PNG 소유) 텍스트는 PNG 가
  없으므로 화면에서 사라진다. COMPLETE_PNG 는 애초에 편집/가독성을 포기한
  소유 형태이므로 손실로 보지 않는다 (사용자 확인). 전체 텍스트 검수가
  필요하면 full 모드 사용.
- graphic-only 에서 **인라인(story-flow) 시각물은 배치되지 않는다** — 앵커가
  텍스트 흐름 안에 있는데 텍스트 phase 를 생략하기 때문. 페이지 평면(대부분의
  페이지 시각물이 여기 구움)과 플로팅 시각물만 배치됨. graphic-only 는 추출
  시간 단축이 아니라 **변환 단순화 + 산출물 격리**가 목적.
- 96dpi 산출물은 개발 전용. 품질 기준(220dpi)은 프로덕션 경로에서 유지.

### 4. 구현 중 발견 (중요)

- **추출기 config 로드 잠복 버그**: InDesign ExtendScript 에는 `JSON.parse` 가
  없어 `loadConversionConfig` 가 항상 예외 → defaults 로 폴백하고 있었다.
  즉 **conversion-config.json 이 추출기에서 지금까지 무시**되고 있었음
  (`readJson` 은 eval 폴백이 있어 정상). `_parseJsonText` 폴백으로 수정.
  현재 리포 config 값이 jsx defaults 와 동일해 기존 동작 변화는 없음.
- **perfMode 가 dpi 를 무조건 override**: dev 루프 기본 perf-mode=fast 는
  `pngExportResolution` 을 150 으로 강제한다 (SPEC-030). 즉 기존 dev 산출물은
  220 이 아니라 **150dpi** 였다. `pngExportResolutionLocked` 플래그로 명시
  dpi(파생 config)가 perfMode 보다 우선하게 수정.
- **파생 config 경로**: 한글 케이스명이 든 issue 디렉토리 대신 ASCII 경로
  `output/cache/extract-config-{dpi}dpi.json` 에 기록.

## 결과 (2026-07-22, p47 검증)

| 모드 | 결과 |
|------|------|
| text-only | 렌더 5단계 생략 (render-phase 740ms→118ms, 페이지 평면 캐시 웜 기준). HWPX 이미지 0, 텍스트·테이블·셀 fill 흡수 유지, 신 파이프라인 유지 |
| graphic-only | 텍스트 phase 생략 로그 확인, HWPX 텍스트 런 0, 페이지 평면 1건 배치 |
| 96dpi | 인라인 PNG 625→382 bytes, 페이지 평면 1299px→831px (=96/150 배율). 캐시 `-96dpi` 네임스페이스 분리 |

단일 페이지 총 시간은 문서 open(~43s)이 지배해 이득이 작다. 렌더 비중이 큰
다페이지/콜드캐시 실행에서 이득이 커진다 (콜드 페이지평면 4.2s/페이지 관측).

## 수정 파일

1. `scripts/dev/issue.py` — `--content-mode`, `--dpi`(기본 96), 파생 config 생성, args[18] 전달, 페이지 평면 캐시 키에 dpi
2. `scripts/indd/input_prepare.jsx` — args[18] → `ctx.contentMode`
3. `scripts/indd/extraction_orchestrator.jsx` — text-only 렌더 생략 분기
4. `scripts/indd/resolved_collectors.jsx` — `documentInfo.contentMode` 기록
5. `converter/.../resolved/ResolvedData.java` — contentMode 파싱/접근자
6. `converter/.../IDMLToHwpxConverter.java` — 신 파이프라인 분기 보정 (2곳)
7. `converter/.../ConvertOptions.java`, `ConverterCLI.java` — CLI 오버라이드 (선택)
8. `converter/.../normalizer/ResolvedToASTBuilder.java` — graphic-only 텍스트 phase 생략

## 검증

- [x] 빌드 성공 (`mvn clean package -q -DskipTests`)
- [x] `issue.py --case 중3과학교과서/u1 --page 47 --content-mode text-only`:
      렌더 phase marker 생략 확인, HWPX 에 텍스트·테이블만 존재 (BinData 이미지 0),
      신 파이프라인 유지 (`[ResolvedToASTBuilder]` 로그), p47 2×5 테이블 + fill 흡수 유지
- [x] `--content-mode graphic-only`: HWPX 에 시각물만 존재 (편집 텍스트 런 0)
- [x] text-only 추출 시간 vs full 추출 시간 기록 (_phase_timing.log, 결과 표 참조)
- [x] dpi 별 페이지 평면 캐시 분리 (`single-textless-plane-{mode}-{dpi}dpi`).
      주의: 이 브랜치 이전에 만들어진 `-96dpi` 캐시는 lock 미적용 150dpi 산출물이
      섞여 있을 수 있어 1회 purge 필요 (검증 중 수행)
- [x] full 모드 회귀: `--dpi 150` 실행이 브랜치 이전 p47 산출물과 동일
      (extraction-results 카운트·파일 목록·인라인 PNG 바이트 일치, 변환
      frames=14/images=8/warnings=0 동일)
