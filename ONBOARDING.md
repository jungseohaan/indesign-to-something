# ONBOARDING — indesign-to-something

> **새 에이전트/계정용 10분 진입점.** 이 문서를 먼저 훑고 [CLAUDE.md](CLAUDE.md)로 넘어가라.

## 1. 이 프로젝트는 무엇인가 (5분 요약)

Adobe InDesign(`.indd` / `.idml`) 문서를 한글(`.hwpx`)로 변환한다.

| 항목 | 값 |
|------|-----|
| 출력 포맷 | HWPX 단일 |
| 현재 버전 | 1.0.9 |
| 현재 브랜치 | `open-indd` (main 머지 전) |
| Java | 8 (lambda OK, var 불가) |
| 빌드 | Maven, fat JAR `target/idml-to-something-1.0.9-cli.jar` |
| Desktop | Tauri 2.0 (Rust) + React 18 + Zustand + Tailwind |
| 입력 추출 | ExtendScript (`.jsx`, UXP 아님) |
| 라이선스 | Apache-2.0, hwpxlib 기반 |

핵심 설계는 **AST-First**: `ASTDocument`가 단일 진실 공급원, 모든 입력 처리가 AST 채우기로 수렴, HWPX 생성은 AST 소비.

## 2. 읽는 순서

1. **본 문서** (10분) — 큰 그림 + 현재 활성 작업
2. [CLAUDE.md](CLAUDE.md) (15분) — 빌드, 아키텍처 표, 모듈 트리, 코딩 컨벤션, 트랩
3. [docs/architecture.md](docs/architecture.md) (필요 시) — 시스템 구조 심화 (696줄)
4. [docs/specs/INDEX.md](docs/specs/INDEX.md) — SPEC 30개 상태별 인덱스
5. [docs/improvement-roadmap.md](docs/improvement-roadmap.md) — 리팩토링 진척 (W1~W4 완료 분량)
6. 작업하려는 SPEC 본문 — `docs/specs/SPEC-NNN-*.md`

## 3. 변환 파이프라인 한눈에

```
.indd ─[ExtendScript: extract_indd.jsx]─→ idml + resolved.json + page PNG + Links
                                              │
                                              ▼
                       IDMLToHwpxConverter.convert()  (파사드, 이중 분기)
                              │
        renderedFloatingItems? ┴─ YES ─→ ResolvedToASTBuilder (Phase 0~7)  ← 신규 변경은 여기
                              │
                              └─ NO ──→ IDMLNormalizer (Stage1~3, legacy/)
                                              │
                                              ▼
                                       ASTDocument → ASTToHwpxConverter → .hwpx
```

**분기 기준**: `resolvedData.allRenderedFloatingItems()` 비어있지 않으면 새 파이프라인. 데스크탑 앱 추출은 **항상** 새 파이프라인.

**Phase 0~7 한 줄 요약**

| Phase | 클래스 | 역할 |
|-------|--------|------|
| 0 | `phase0/InfraSetup` | IDML 폰트/스타일/색상 정의 복사 + 정렬 보강 |
| 1 | `phase1/PageLayoutBuilder` | 페이지/섹션 빌드 |
| 2 | `phase2/FramePlacer` | TextFrame 분류/배치 (editable/decoration) |
| 3 | `phase3/StoryConverter` (+ 6 sub-module) | Story → AST + 수식 + 매칭 |
| 4 | `phase4/TableBuilder` | 표 변환 + SPEC-017 품질 게이트 |
| 4.5 | `phase4_5/BulletInserter` | 불릿 자동 삽입 |
| 5 | `phase5/WrapPhase5` | textwrap 글상자 분할 |
| 6 | `phase6/BackgroundInjector` | 페이지 배경 PNG 주입 |
| 7 | `phase7/RenderableFramePlacer` | renderable TF(배지) 플로팅 배치 |

## 4. "어디를 고쳐야 하나" 결정 표

| 증상 | 손볼 곳 |
|------|---------|
| 텍스트 내용/스타일이 안 맞음 | `phase3/StoryConverter` 계열 (RunBuilder, StoryLoader) |
| 인라인 이미지/도형이 안 보임 | `phase3/InlineFrameHandler` |
| 수식이 깨짐 (BT/EH/NP) | `phase3/MathProcessor` + `equationconverter/idml/` |
| 표 셀 변환 이상 | `phase4/TableBuilder` |
| 페이지 배경 PNG 이슈 | `phase6/BackgroundInjector` + ExtendScript `exportPageBackgrounds()` |
| 배지/회전 텍스트가 PNG로 못 빠짐 | `scripts/extract_indd.jsx` (`classifyTextFrame`, `isRenderableTextFrame`) → SPEC-025 참조 |
| HWPX 출력에서 단락 높이/줄간격 이상 | `converter/LineSpacingResolver` |
| HWPX 글상자 모양 이상 (회전/라운드) | `converter/FrameTransformations` |
| 폰트가 잘못 매핑됨 | `font/FontCandidateMatcher` + `font-mapping.json` |

레거시(`normalizer/legacy/`)는 **resolved.json 없는 입력**에서만 동작. 신규 작업은 새 파이프라인에 추가.

## 5. 빌드 & 테스트

```bash
# Java JAR 빌드
mvn clean package -q -DskipTests
# 결과: target/idml-to-something-1.0.9-cli.jar

# CLI 변환 (항상 --resolved + --links-directory 권장)
/opt/homebrew/opt/openjdk/bin/java -jar target/idml-to-something-1.0.9-cli.jar \
  --convert output.idml out.hwpx \
  --resolved resolved.json \
  --links-directory /path/to/Links

# Desktop 개발 모드
cd desktop && npm run tauri dev
```

**데스크탑 추출 결과 위치**: `/var/folders/.../T/indd-extract-{timestamp}/`
**추출 캐시 (SPEC-011)**: `~/Library/Caches/idml-to-hwpx/extracts/<sha256>/`

## 6. 활성 작업 (2026-05-20)

- **SPEC-025** (텍스트 이미지 렌더링 제거): Tier A.5/A.6/B 구현 완료, **미테스트** — InDesign 재추출 필요
- 잔여 Tier: A.1.5 / A.4 / A.8 / Tier B 효과 / Phase 5 마스터 스프레드 instance화
- 다른 Active: SPEC-012, 014, 015, 018, desktop-app
- 자세한 인덱스: [docs/specs/INDEX.md](docs/specs/INDEX.md)

## 7. 트랩 톱5 (실수 방지)

1. **lazy IDMLDocument 초기화 순서** — `ctx.idmlDocumentSupplier.get()` 전에 반드시 `ctx.ensureIdmlInfra.run()` (SPEC-024 회귀 사례)
2. **resolved 좌표 단위** — InDesign DOM은 문서 측정 단위(mm/in/pt), IDML은 항상 pt. `ResolvedData.normalizeToPoints(idmlPageWidthPt)`로 자동 정규화. ExtendScript `viewPreferences` 변경은 무효
3. **hex/decimal ID 변환** — IDML `u1735` (hex) ↔ InDesign DOM `5941` (decimal). `parseInt("1735", 16)`
4. **HWPX 연결 글상자** — 한글은 후속 프레임 콘텐츠 무시. distributed 프레임은 `linkListIDRef=0`으로 해제
5. **수식 폰트 한국어 오적용** — BT/EH/NP 필터는 한국어 보호하나 단일 라틴 문자는 통과. 혼합 텍스트 경계 케이스 잔존

추가 트랩: [CLAUDE.md "알려진 이슈 & 주의사항"](CLAUDE.md#알려진-이슈--주의사항)

## 8. 사용자 선호

- 커밋 메시지: **한글**, 기능 설명 중심
- **SPEC 기반 개발 워크플로우** 선호 (`docs/specs/SPEC-NNN-*.md`)
- CLI 테스트 시 **반드시 `--resolved` + `--links-directory`** 포함
- 한컴바탕 폰트 **절대 사용 금지**

## 9. 새 SPEC 시작 시

1. `docs/specs/SPEC-NNN-<feature>.md` 작성 (문제/목표/해결 방안/수정 파일/검증)
2. [docs/specs/INDEX.md](docs/specs/INDEX.md)에 Active로 추가
3. 신 파이프라인이면 phase 클래스, 레거시 호환 필요하면 명시
4. 빌드 검증 (`mvn clean package -q -DskipTests`) + CLI 변환 1회
5. 완료 시 INDEX.md에서 Done으로 이동, [CLAUDE.md "활성 작업"](CLAUDE.md#활성-작업-2026-05-20-기준) 갱신
