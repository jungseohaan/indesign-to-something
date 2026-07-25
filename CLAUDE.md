# CLAUDE.md — indesign-to-something

> **새 에이전트/계정이라면**: 먼저 [ONBOARDING.md](ONBOARDING.md)를 10분 안에 훑어 진입점을 잡고, 이 문서로 돌아오세요.

## 프로젝트 개요

IDML(Adobe InDesign) → HWPX(한글) 변환기. Java 백엔드 + Tauri(Rust) 데스크탑 앱 + React 프론트엔드.

## 활성 작업 (2026-07-25 기준)

- **브랜치**: `open-indd` — 영어 u1 시각/텍스트 스윕 머지 완료 (#140~#149)
- **최근 완료 (2026-07-25)**:
  - [SPEC-074](docs/specs/SPEC-074-image-textless-group-render-missing.md) (#147) — Group 배치 이미지 유실(p13 동영상 사진).
    plan 이 참조하는 `pass.image_textless_groups` 렌더 미등록 → pass 등록. 20p 회귀 PASS(+7 이미지)
  - [SPEC-075](docs/specs/SPEC-075-graphic-short-text-button-group-complete-png.md) (#149) — YES/NO 버튼 글자 안 보임·줄바꿈(p16).
    편집 셸 대신 **그래픽+짧은텍스트 그룹 통PNG**(짧은TF≥2+그래픽+긴TF<3 게이트). 회귀 PASS(10개 정확 전환), 한글 육안 확인 완료
- **최근 완료 (2026-07-24~25)**: 영어 u1 p10 한 페이지에서 연쇄적으로 드러난 4건
  - [SPEC-068](docs/specs/SPEC-068-page-plane-foreign-visual-owner-hide.md) (#140) — 배경 평면에 도형이 중복.
    **소유권 게이트와 숨김 게이트를 분리** (`hiddenForeignVisualOwnerSourceObjectIds`). 20p 에서 1,751건 숨김
  - [SPEC-069](docs/specs/SPEC-069-bleeding-shape-page-plane-absorb.md) (#142) — 도련(bleed) 도형이 작게·빈틈 있게 배치.
    **개별 PNG 로는 불가**(한글이 음수 오프셋 미지원) → textless 도련 도형을 배경 평면에 흡수. 20p 에서 9페이지 13건
  - [SPEC-070](docs/specs/SPEC-070-table-frame-hastext.md) (#142) — 표에 담긴 본문이 셸 PNG 로 구워짐.
    `hasText` 가 표 셀 텍스트 미인식 + `contentOpacity` 가 표에 미적용
  - [SPEC-072](docs/specs/SPEC-072-cell-char-attr-investigation.md) (#144) — 표 셀 텍스트가 IDML 색·폰트 유실.
    파서에 **명시/상속 구분 플래그** 추가 후 명시값만 주입. 선행 실패(SPEC-071)의 원인 분석 포함
- **진행 중 / 대기**:
  - [SPEC-041](docs/specs/SPEC-041-anchored-edge-label-floating.md) + SPEC-044~047 (화학식 계열) — 구현 완료, **한글 육안 확인 대기**
  - SPEC-067 잔여: 수식 그룹 경계 문맥 기반 재설계(수학 조각화) — stash@{0}/{1} 에 WIP.
    남은 블로커는 연산자·쉼표 연속 조각 24건(`1²=1,2²=4`)
  - 별건 관찰: 최종 HWPX 에 `Time to Shine` 이 두 번 나오는 중복 (영어 u1 p10, 미조사)
- **기타 Active SPEC**: SPEC-012 (속성 우선순위), SPEC-014 (폰트 자동 매핑), SPEC-015 (AST 디버깅), SPEC-018 (시멘틱 M3), SPEC-055 (화학식 전면 수식화)
- **전체 SPEC 인덱스**: [docs/specs/INDEX.md](docs/specs/INDEX.md)

## 빌드 & 실행

```bash
# Java (Maven) — JAR 빌드
mvn clean package -q -DskipTests
# 결과: converter/target/idml-to-something-1.0.9-cli.jar (fat JAR)

# CLI 변환 (macOS; resolved.json은 input.idml 옆에서 자동 탐지)
/opt/homebrew/opt/openjdk/bin/java -jar converter/target/idml-to-something-1.0.9-cli.jar \
     --convert input.idml output.hwpx --links-directory /path/to/Links
# 옵션: --progress (진행률 JSON lines), --debug-ast (블록별 createdAt phase 메타)

# Desktop 앱: cd desktop && npm run tauri build   (개발 모드: npm run tauri dev)

# 페이지 단위 이슈 루프 (추출+변환, InDesign 필요)
python3 scripts/dev/issue.py --case 중3과학교과서/u1-local --page 17 --content-mode text-only
# 그래픽 검증 시에만 --content-mode full
```

## 아키텍처 (요약 — 상세는 [docs/architecture.md](docs/architecture.md))

`.indd → extract_indd.jsx (output.idml + resolved.json + PNG들) → IDMLLoader/ResolvedDataReader → normalizeToPoints() → AST 빌드 → ASTToHwpxConverter → .hwpx`

- **파이프라인 분기**: `IDMLToHwpxConverter.convert()`가 `resolvedData.allRenderedFloatingItems()` 유무로 신/레거시 분기. 데스크탑 앱 추출은 항상 **신 파이프라인** = `normalizer/ResolvedToASTBuilder`(Phase 0 Infra → 1 PageLayout → 2 FramePlacer → 3 StoryConverter → 4 Table → 4.5 Bullet → 5 Wrap → Stage 2.5 VisualRefine → Stage 3 VisualBuilder). 레거시 = `normalizer/legacy/IDMLNormalizer`(Stage 1~3)
- **주요 진입점**:
  - Phase 3 텍스트: `normalizer/resolved/phase3/` — StoryConverter(오케스트레이터), StoryLoader, RunBuilder, InlineFrameHandler, MathProcessor(BT/EH/NP 수식)
  - Stage 3 시각: `normalizer/resolved/stage3/VisualBuilder` (실행 본체는 `phase6/BackgroundInjector.inject`)
  - HWPX 출력: `converter/ASTToHwpxConverter` + Hwpx{Paragraph,TextBox,Table,Image}Builder (+ CharPrFactory/InlineItemDispatcher/InlineFrameBuilder 등 분리 모듈)
  - 추출기: `scripts/extract_indd.jsx` + `scripts/indd/*.jsx` (eval 모듈), 소유권 계획은 object_plans.jsx
  - 데스크탑: `desktop/src/`(React) + `desktop/src-tauri/src/`(Rust, indesign.rs가 osascript 호출)
- 클래스별 위치/LOC/디렉토리 트리는 [docs/architecture.md](docs/architecture.md) 참조

## 코딩 컨벤션

- **Java 8** 호환 (람다 OK, var 불가)
- **접근자 패턴**: `field()` getter, `field(value)` setter (JavaBean 스타일 아님)
- **단위**: HWPUNIT (1pt = 100 hwpunit, 1inch = 7200 hwpunit)
- **ID 형식**: IDML `u` + hex (`u1735`), InDesign DOM decimal (`5941`), 변환: `parseInt("1735", 16) = 5941`
- **커밋 메시지**: 한글, 기능 설명 중심 (예: "resolved.json 보강 파이프라인, 다단 N개 글상자 변환")
- **브랜치**: `main` (릴리스), 기능 브랜치에서 개발

## SPEC 기반 개발 워크플로우

### 새 기능/수정 시

1. **SPEC 작성** → `docs/specs/<feature-name>.md`
   - 문제 정의, 영향 범위, 해결 방안, 수정 파일 목록
2. **리뷰** → SPEC 검토 후 구현 시작
3. **구현** → SPEC의 수정 파일 목록 순서대로
4. **검증** → `mvn clean package -q -DskipTests` 빌드, CLI 테스트
5. **골든 게이트 (PR 전 필수)** → 기준 추출물로 전체 변환 후
   `python3 scripts/dev/verify_hwpx.py <out.hwpx> --golden test-data/golden/과학u1-p008-049.json`
   — diff 가 의도한 변화뿐인지 확인하고, 의도한 변화면 같은 PR 에서 골든 갱신
   (수식 목록·표 스타일·이미지·유출 패턴 구조 비교, [SPEC-061](docs/specs/SPEC-061-hwpx-structural-golden-gate.md))
6. **결과** → SPEC에 완료 상태 기록

### SPEC 템플릿

```markdown
# [기능명]

## 문제
현재 상황과 문제점

## 목표
기대하는 결과

## 해결 방안
기술적 접근 방법

## 수정 파일
1. `path/to/File.java` — 변경 내용
2. `path/to/File2.java` — 변경 내용

## 검증
- [ ] 빌드 성공
- [ ] 테스트 케이스
```

## 알려진 이슈 & 주의사항

### 데이터 파이프라인
- **ExtendScript JSON**: 제어 문자를 이스케이프하지 못함 → Gson lenient 모드 필수. 그리고 **`JSON.parse` 자체가 없다** — raw `JSON.parse` 호출은 조용히 예외→defaults 폴백된다 (conversion-config.json 이 추출기에서 무시되던 잠복 버그, SPEC-054 에서 `_parseJsonText` eval 폴백으로 수정). jsx 에서 JSON 읽기는 `readJson`/`_parseJsonText` 를 쓸 것
- **dev 루프 실효 dpi 는 150 이었다**: perf-mode=fast 가 `pngExportResolution` 을 150 으로 강제 override (SPEC-030). SPEC-054 부터 issue.py 가 `--dpi`(기본 96) + `pngExportResolutionLocked` 로 명시 dpi 를 우선시킴. 페이지 평면 캐시는 dpi 별 디렉토리로 분리
- **resolved 좌표 단위 불일치**: InDesign DOM은 문서 측정 단위(mm/in/pt)로 반환, IDML은 항상 pt → `ResolvedData.normalizeToPoints(idmlPageWidthPt)`로 scaleFactor 자동 계산. ExtendScript `viewPreferences` 변경은 효과 없음
- **resolved Group bounds 과대**: DOM의 Group `geometricBounds`는 비가시 자식까지 포함 → 벡터 그룹은 IDML 폴백, 개별 도형만 resolved 사용
- **TextPath(곡선 위 텍스트)는 수집기 미추출 — textless 숨김 금지**: text_collectors/resolved 는 TextFrame 만 추출하므로 TextPath 를 textless 렌더에서 숨기면 편집 텍스트 재배치 없이 그대로 유실된다 (p17 리본 배너: 흰 외곽선 벡터만 남음). 곡선 조판은 HWPX 재현 불가 → PNG 에 구운 채 유지 (`render_inline_objects.jsx` `isTextItem`)
- **문단 인덱스 불일치**: IDML(AST)과 InDesign DOM(resolved)의 문단 수가 다름 → 텍스트 기반 매핑 사용
- **HWPX 연결 글상자**: 한글은 연결 글상자 체인에서 후속 프레임의 명시적 콘텐츠를 무시 → distributed 프레임은 `linkListIDRef=0`으로 해제

### 신 파이프라인 (Phase 0~6 + Stage 3) 구현 트랩
- **lazy IDMLDocument 초기화 순서**: `ctx.idmlDocumentSupplier.get()` 호출 전 반드시 `ctx.ensureIdmlInfra.run()` 먼저 (idempotent). 누락 시 `IDMLDocument=null` → ParagraphStyle/CharacterStyle 정의 조회 실패 (SPEC-024 회귀 사례)
- **Phase 3 텍스트 매칭**: `lastMatchResult[0]` 인덱스 캐시로 O(n) 가속. 인라인 수식으로 텍스트 길이 차이 시 next() 재탐색 → O(n²) 위험
- **수식 폰트 한국어 오적용 방지**: BT/EH/NP 폰트 필터가 한국어 텍스트 보호. 단, 단일 라틴 문자는 통과 → 혼합 텍스트 경계 케이스 잔존
- **text-range-shell 이 화학식 첫 글자 오분리**: `OwnershipPlanner.leadingStyledStoryRunRanges` 는 Group(편집TF+빈셸)에서 선두 런을 다음 런과 스타일 경계가 있으면 셸로 뗀다(배지/라벨용). 화학식 H₂O 는 H→2(첨자) 경계 때문에 첫 글자 H 가 58pt rect 로 오분리되고 원자리엔 빈 charPr=0 H 가 남아 레이아웃이 세로로 깨진다 (SPEC-046, p17 파랑 박스). 증상이 "글상자 세로 깨짐"이어도 lineWrap 이 아니라 sourceId=`text_range_shell_*` 인 rect 분리를 의심할 것 → `isChemicalFormulaParagraph` 가드로 화학식 문단 제외. **판정 원천은 추출기**(`source_index.jsx` `_sourceIndexLeadingStyledStoryRunRanges`)에도 있어 Java 가드만으론 배경 PNG 분리(말풍선 배경 누락)를 못 막는다 — 문장 중 화학식 진입(한글→BT/EH/NP 폰트) 경계는 추출기에서 range 생성 자체를 막아야 함 (SPEC-047, 재추출 필요). Java(SPEC-046)와 추출기(SPEC-047) 양쪽에 같은 가드가 필요
- **resolved 첨자 위치 흘림 → 계수 오첨자**: resolved DOM 이 화학식 "2H₂O"의 첨자 위치를 계수·hair space(U+200A)까지 SUBSCRIPT 로 흘리면, splitChemical 로 분리된 계수 2 가 `findResolvedRun`(텍스트 contains 매칭)에서 첨자 2 에 오매칭돼 계수가 아래첨자로 깨진다 (SPEC-045, pg25/26/28). IDML 원본은 Position=None + charStyle 로만 첨자 표기 → `RunBuilder.applyPositionStyle`/`resolvedCharacterStyleRef` 가 "IDML 비첨자 증거(position=null + 비첨자 charStyle)"면 resolved 첨자를 버린다. 진짜 첨자(H₂)는 IDML charStyle=하부자라 무영향
- **CharPr 캐시 키는 스타일 인자 전부 포함**: `CharPrFactory` 계열 캐시 키에 CharPrBuilder.build 가 소비하는 인자(특히 subscript/superscript/fontStyle)가 하나라도 빠지면, 다른 문단이 만든 CharPr 을 물려받아 첨자가 이웃 글자로 전이된다 (SPEC-042 p47 사례: H↔2 첨자 스왑). AST 계측이 침묵인데 HWPX 에 속성이 있으면 CharPr 캐시/공유 층을 의심할 것
- **resolved DOM 의 EH 폰트 과대 보고**: InDesign DOM 이 √ 글리프 뒤 한국어 문장까지 EH상부자 폰트로 보고할 수 있다 (IDML 은 [No character style]). resolved 셀 경로가 폰트만 보고 수식 그룹에 넣으면 lexSubSup 미매핑 스킵으로 한국어가 통째로 유실 → `MathProcessor.splitEHKoreanMixedTextRuns` 가 첫 한국어 문자에서 분리 (p20 사례, 36c2d24c)
- **표 전용 TF 는 hasText=false — 인라인 그룹의 표가 통PNG 로 구워짐**: 콘텐츠가 표 앵커 제어문자(U+0016)뿐인 TextFrame 은 `_textFrameHasContent`/`hasText` 판정이 거짓 → 인라인 앵커 그룹(표 TF + 배경 rect)이 편집 텍스트 없음으로 오분류돼 표 텍스트째 PNG 렌더 + Java 편집 테이블과 중복. 표 소유 여부는 `storyHasVisibleTableCellText` 로 판정할 것. 배경 rect 셀 fill 흡수는 `PLACE_TABLE_STYLE` plan 의 styleSourceObjectIds 경유인데, style 소스 수집의 `isInline` 무조건 배제가 같은 앵커 그룹 형제 rect 를 누락시킴 → 표 소유 TF 가 인라인이면 허용 (`_isTableAttributeStyleSource` allowInlineFlow, p47 사례, PR #85)
- **캐리어 셀 흡수 테이블의 fixedOuterBounds 리셋 금지**: `normalizeNestedInlineTable` 이 `fixedOuterBounds(false)` 로 되돌리면 `HwpxTableBuilder.inlineFlowChunks` 가 다행 인라인 테이블을 행별 1×N 조각으로 분리한다 (p47 2×5 → 1×5 두 개 사례). resolved 가 외곽 경계를 아는 테이블은 흡수 후에도 플래그 보존

- **표 셀 인라인 앵커는 `StoryLoader.buildResolvedCellParagraphs`(resolved 셀 런) 경로**: IDML 스토리 경로(ASTStoryConverter/StoryLoader 본문 루프)에 계측을 넣어도 셀 문단은 안 잡힌다. 경로 확정은 `InlineFrameHandler.loadPlannedInlineAnchorItems` 스택트레이스로. 셀 텍스트에 앵커 마커(￼)가 없으면 추출기 `text_collectors.jsx` fallback이 앵커를 수집하는데, SPEC-056 부터 문자 위치 기반 interleave 삽입 — 끝에 몰아넣으면 답란 순서가 깨진다. 관련 3중 함정(스페이서 오판·□ 삼킴)은 [SPEC-056](docs/specs/SPEC-056-cell-answer-blank-inline-loss.md)
- **줄간격이 이유 없이 벌어지면 폭 0.1pt 유령 인라인 테이블 의심**: `layout_only_inline_slot` plan 의 `createLayoutOnlyInlineSpacer` 가 원본 rect 높이를 인라인 슬롯에 예약한다("하단간격" 스타일은 폭 0.1pt+원본 높이). **FLOATING_ANCHORED 소스는 박스가 줄 밖(페이지 좌표)이라 예약하면 안 됨** — 가드 있음 (p19 답안영역 rect 6mm→17pt 유령 테이블 사례). storyTextInlineSlot=true + FLOATING_ANCHORED 오분류 계열(SPEC-048/056)의 layout-only 변종
- **□(U+25A1) 텍스트 출현 = 앵커→답란상자 치환 의심**: `ASTMathGrouper.formulaAnswerBoxRun` 이 수식 이웃의 orcOnly 앵커 런을 □ 로 삼키고 인라인 그래픽을 소비한다. 실체 시각물(콘텐츠 인라인 PNG plan) 앵커는 가드로 제외해야 하며, 가드는 StoryLoader 와 ASTStoryConverter **양쪽**에 있어야 한다 (SPEC-056). PUA(E285류)→□ 치환과는 별개 경로
- **plan bounds(mm)와 resolved TF geometricBounds(pt)를 섞지 말 것**: resolved TF 좌표는 `normalizeToPoints` 로 pt 지만 object-plans.json 의 plan bounds 는 추출기 원단위(mm)다. 혼합 산술은 조용히 유령 오프셋을 만든다 (p47 라벨 62mm cellMargin 사례, SPEC-057). 차분 계산은 같은 단위로 환산 후에
- **도련(bleed) 도형은 개별 PNG 로 두면 반드시 깨진다 — 배경 평면에 흡수할 것**: 재단선 밖까지 그린 도형(예: bounds `[-3.5, -8.0, ...]`)을 개별 PNG 로 배치하면 잘리거나 찌그러진다. 추출기 클램프와 Java `BackgroundInjector` 가 **각각 독립적으로** 자르므로 한쪽만 고치면 최종 HWPX 가 바이트 단위로 동일하고, 억지로 원본 크기를 넘기면 `horzOffset="-2268"` 같은 음수가 나오는데 **한글이 음수 오프셋을 0 으로 밀어 넣어** 오히려 더 어긋난다(XML 은 정상, 렌더러 미지원). 해법은 크롭·좌표 보정이 아니라 배경 평면 흡수 — 페이지 통째 export 라 도련이 자연히 처리된다. 단 **textless 가드 필수**(텍스트 든 도련 프레임을 흡수하면 본문이 그림이 됨). 레이어 인접성("바탕 바로 위")은 기준으로 못 쓴다 — 미사용 작업 레이어가 사이에 낀다 ([SPEC-069](docs/specs/SPEC-069-bleeding-shape-page-plane-absorb.md))
- **프레임 contentOpacity 는 안쪽 표에 닿지 않는다**: `hideOneTextFrameContent` 가 `tf.contentTransparencySettings.opacity=0` 으로 텍스트를 숨기지만 표는 자체 콘텐츠로 독립 렌더돼 셀 텍스트가 그대로 남는다. 게다가 `hasText` 가 표 셀 텍스트를 못 세면(`item.contents` 는 표 앵커 문자뿐) 그 프레임은 애초에 `hiddenTextFrameIds` 에 들어가지도 않는다 — export 가 참조하는 건 `hiddenVisualSourceObjectIds` 가 아니라 **`hiddenTextFrameIds`** 다. 영어 u1 p10 Look Ahead 본문이 통째로 PNG 로 구워진 사례 ([SPEC-070](docs/specs/SPEC-070-table-frame-hastext.md))
- **text wrap 은 값이 도달해도 floating 경로에서 무력화된다**: 라벨 셸이 본문과 겹치도록 배치된 뒤 감싸기로 밀어내는 조판(영어 u1 p12 "Listen & Number": 셸 x23.3~52.6, 본문 프레임 x45 시작 — 7.6mm 겹침이 정상)에서, 감싸기를 잃으면 실제로 겹쳐 렌더된다. 유실은 두 겹이다 — (1) plan 생성이 감싸기를 `mixed_source_bundle_placed_visual_branch` 한 경로에서만 실어 `decoration_groups` 후보는 값을 잃었고(실측 plan 1,013개 중 0개), (2) 고쳐서 `outMargin right=850` 이 HWPX 까지 도달해도 `HwpxImageBuilder` floating 경로가 `allowOverlap(true)` 를 하드코딩하고 감싸기 모드를 `BEHIND_TEXT`/`IN_FRONT_OF_TEXT` 로 고정해 여백이 생기지 않는다. **`outMargin` 값만 보고 통과로 판단하지 말 것** — 한글에서 실제 여백을 확인해야 한다 ([SPEC-073](docs/specs/SPEC-073-text-wrap-shell-gap.md))
- **plan 이 참조하는 pass 이름과 렌더 레지스트리가 어긋나면 시각물이 통째로 유실된다**: object-plan 은 정상 생성되는데 `file=null` 이고 PNG 가 없으면, 그 plan 의 `passId` 가 `extraction_passes.jsx` 레지스트리에 등록됐는지부터 확인할 것. `pass.image_textless_groups`(Group 으로 묶인 배치 이미지)는 plan 빌더·소유권 로직 등 13곳이 참조하는데 렌더 실행기가 등록된 적이 없어 동영상 미리보기 사진이 유실됐다(p13, SPEC-074). **pass 이름을 바꾸지 말 것** — 소유권/번들/스코어 로직이 이름에 의존한다. 렌더가 빠졌으면 이름 변경이 아니라 렌더를 등록해야 한다
- **텍스트 중복 강등이 시각 소유까지 지울 수 있음**: `_resolveObjectPlanDuplicateTextOwners` 가 텍스트를 전부 뺏긴 plan 을 DROP_VISUAL 로 강등한다 — 그 plan 만 아는 시각 소스(라벨 달린 삽화의 placed Image)가 있으면 소유자 없는 시각이 되어 유실된다. 인라인이면 그룹 통짜 PNG 승격으로 해결 ([SPEC-057](docs/specs/SPEC-057-labeled-figure-inline-complete-png.md)). export 는 그룹 루트 id 하나만 — 자식 여러 개를 주면 `_duplicateNestedCompletePngForExport` 가 개별 복제·재그룹해 캔버스가 부푼다
- **jsx eval 모듈 로드는 함수만 공유, 전역 `var` 는 공유 안 됨**: `extract_indd.jsx` 가 `scripts/indd/*.jsx` 를 eval 로드할 때 파일 간 공유되는 것은 **함수 선언뿐**이다. 한 파일의 top-level `var` 상수를 다른 파일에서 참조하면 런타임 ReferenceError 로 추출 전체가 실패한다 → 공유 상수는 `function _foo() { return v; }` 로 정의 (SPEC-048 사례)
- **인라인 시각물 seed 는 3곳 — 하나만 고치면 안 바뀐다**: `pass.inline_objects` candidate 는 `_appendInlineObjectExtractionCandidates`(34소스), `_appendSourceDeclaredInlineShellCandidates`(2소스), `_appendInlineFlowVisualRootCandidates`(root 기반, `_pushExtractionCandidate` **우회** 직접 push) 3경로에서 중복 생성된다. 소유권 분류를 바꿀 땐 3곳 모두 같은 판정을 넣어야 한다. 또한 `_pushExtractionCandidate` 는 attrs 중 **정해진 필드만 복사** — `placement`/`ownershipSlot`/`textAction`/`materialization` 등은 조용히 버려지고 bundle/object_plans 가 재계산한다. placement 를 바꾸려면 `_objectPlanPlacement` 의 입력(예: `pagePositionedAnchoredSource`)을 바꿀 것 (SPEC-048 사례)

### 환경
- **Java 경로**: macOS Homebrew `/opt/homebrew/opt/openjdk/bin/java`
- **데스크탑 추출 경로**: `/var/folders/.../T/indd-extract-{timestamp}/`
- **추출 캐시 (SPEC-011)**: `~/Library/Caches/idml-to-hwpx/extracts/<sha256>/`
- 데스크탑 앱 추출 결과로 테스트 시: 최신 `indd-extract-*/` 디렉토리에서 `output.idml` + `resolved.json` + 원본 INDD 옆 `Links/` 사용

## 변경 시 문서 동기화 규칙

코드 변경이 다음에 해당하면 반드시 함께 갱신:

| 변경 종류 | 갱신 대상 |
|----------|----------|
| Phase 추가/제거/이름 변경 | [README.md](README.md), [docs/architecture.md](docs/architecture.md), 본 문서의 아키텍처 요약 |
| 모듈 LOC가 두 배 이상 변동 또는 sub-module 분리 | [docs/architecture.md](docs/architecture.md) Phase 클래스 LOC 표 |
| SPEC 시작 | [docs/specs/INDEX.md](docs/specs/INDEX.md)에 Active로 등록 |
| SPEC 완료 | [docs/specs/INDEX.md](docs/specs/INDEX.md)에서 Done으로 이동, 본 문서 "활성 작업" 섹션 갱신 |
| 새로운 트랩/주의사항 발견 | "알려진 이슈" 섹션 추가 (재발 방지) |
| 브랜치 머지/생성 | "활성 작업" 섹션의 브랜치명 갱신 |

> SPEC 본문이 "구현 완료" 상태로 머무는 동안 문서가 옛 상태로 남으면, 새 에이전트는 그 SPEC을 다시 작업하려고 시도할 수 있다. 종료 시점에 INDEX.md를 갱신하는 게 가장 비용이 낮다.
