# InDesign to Something

Adobe InDesign(.indd/.idml)을 HWPX(한글) 형식으로 변환하는 Java 라이브러리 + Tauri 데스크탑 앱입니다.

## 개요

```
.indd → [ExtendScript] → .idml + resolved.json + 페이지 PNG
                               ↓
                     ResolvedToASTBuilder (하이브리드)
                      ├─ 텍스트: IDML Story XML (정확한 단락 구조)
                      ├─ 좌표/스타일: resolved.json (ExtendScript bounds)
                      ├─ 그래픽: 페이지 배경 PNG + 인라인 객체 PNG
                      └─ 수식: BT/NP/EH 폰트 → HWP 수식 스크립트
                               ↓
                         ASTDocument (중간 표현)
                               ↓
                     ASTToHwpxConverter → .hwpx 출력
```

## 주요 기능

- **하이브리드 변환 파이프라인**: IDML Story XML(텍스트) + resolved.json(좌표/스타일) + ExtendScript PNG(그래픽)
- **페이지 배경 PNG**: 비편집 영역을 InDesign이 직접 렌더링 → 원본 충실도 보장
- **수식 변환**: BT/NP/EH 수식 폰트 → HWP 수식 스크립트, GREP 수식 감지
- **배지 추출**: 도형+짧은 텍스트 조합을 단일 PNG로 렌더링
- **장식 텍스트 렌더링**: 회전/TextPath/비블랙 장식 텍스트 → PNG
- **폰트 매핑**: fontStyle 기반 가변 폰트 매핑, 비율 기반 장평 보정
- **변환 속도**: Java 래스터화 비활성화로 84초 → 4.5초

## 빌드

```bash
# Java (Maven) — CLI JAR 빌드
mvn clean package -q -DskipTests
# 결과: target/idml-to-something-1.0.9-cli.jar

# Desktop 앱 빌드
cd desktop && npm run tauri build

# Desktop 개발 모드
cd desktop && npm run tauri dev
```

## 사용법

### CLI

```bash
# IDML + resolved.json → HWPX (권장)
java -jar target/idml-to-something-1.0.9-cli.jar \
  --convert input.idml output.hwpx \
  --resolved resolved.json \
  --links-directory /path/to/Links

# IDML만으로 변환 (레거시)
java -jar target/idml-to-something-1.0.9-cli.jar \
  --convert input.idml output.hwpx
```

### Desktop 앱 (Tauri)

1. `.indd` 파일을 앱에 드래그 & 드롭
2. InDesign ExtendScript가 자동으로 `.idml` + `resolved.json` + 페이지 PNG 추출
3. Java CLI가 자동으로 HWPX 변환 실행

## 변환 파이프라인

### 새 파이프라인 (ResolvedToASTBuilder) — 현재 기본

```
ExtendScript 추출
 ├─ output.idml (IDML — Story XML만 사용)
 ├─ resolved.json (좌표, 스타일, 색상, 프레임 정보)
 ├─ page_bg_*.png (페이지 배경 — 편집 TextFrame 숨김 후 렌더링)
 ├─ rendered_frames/*.png (인라인 객체, 배지, 장식 텍스트)
 └─ Links/ (원본 이미지)

ResolvedToASTBuilder (Phase 0~6 + Stage 3)
 ├─ Phase 0: InfraSetup (IDML 폰트/스타일/색상 정의 복사 + 정렬 보강)
 ├─ Phase 1: PageLayoutBuilder (페이지/섹션 빌드)
 ├─ Phase 2: FramePlacer (TextFrame 분류/배치, facing pages 보정)
 ├─ Phase 3: StoryConverter (Story → AST, W3로 7개 sub-module 분리)
 │           StoryLoader / RunBuilder / InlineFrameHandler /
 │           ParagraphDistributor / MathProcessor / RunPostProcessor
 ├─ Phase 4: TableBuilder (테이블 + SPEC-017 품질 게이트)
 ├─ Phase 4.5: BulletInserter (불릿 자동 삽입)
 ├─ Phase 5: WrapPhase5 (textwrap 글상자 분할)
 ├─ Stage 2.5: 시각 ownership refine (ObjectPlan 권위 확정)
 └─ Stage 3: VisualBuilder (모든 시각 배치 — 배경/플로팅/배지/renderable)
             내부: BackgroundInjector.inject + stage3/Visual* 헬퍼
             (구 Phase 6 BackgroundInjector + Phase 7 RenderableFramePlacer 통합, SPEC-035)

ASTToHwpxConverter → .hwpx
```

### 레거시 파이프라인 (resolved.json 없을 때)

```
IDML → IDMLNormalizer (Stage1~3) → ResolvedMerger → ASTToHwpxConverter → .hwpx
```
> 레거시는 `normalizer/legacy/` 패키지로 격리되어 있다. 신규 변경은 새 파이프라인(Phase 0~6 + Stage 3)에 적용한다.

### 편집 TextFrame 분류 (배경 PNG에서 제외)

| 조건 | 결과 | 이유 |
|------|------|------|
| masterPageItem | 배경 | 마스터 페이지 요소 (머리글/바닥글) |
| 내용에 `\u0018` 포함 | 배경 | 자동 페이지 번호 |
| 상단/하단 10% 영역 + 짧은 텍스트 | 배경 | 페이징/머리말 |
| Group 내 + 10자 이하 + 비블랙 텍스트 | 배경 | 장식 텍스트 |
| 그 외 | 편집 가능 | 본문 텍스트 (배경에서 숨김 → 텍스트로 변환) |

### 단위 변환

| 변환 | 공식 |
|------|------|
| 행간 (Leading pt → HWPX %) | `(leading / fontSize) × 100` |
| 자간 (Tracking 1/1000em → HWPX %) | `tracking / 10` |
| baselineShift (pt → HWPX %) | `(shift / fontSize) × 100` |
| 좌표 (points → HWPUNIT) | `pt × 100` |

## 설정 (conversion-config.json)

```json
{
  "pngExportResolution": 220,
  "textBoxWidthExpandPercent": 5,
  "groupShapeMaxTextLength": 20,
  "spaceCondenseRatio": 50,
  "fontMappings": { ... },
  "badgeConditions": { ... }
}
```

## 프로젝트 구조

```
src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/
├── ConverterCLI.java              # CLI 엔트리포인트
├── IDMLToHwpxConverter.java       # 변환 파사드
├── ast/                           # 중간 표현 (ASTDocument, ASTParagraph, ...)
├── normalizer/
│   ├── ResolvedToASTBuilder.java  # 새 파이프라인 오케스트레이터 (247 LOC)
│   ├── resolved/phase0~6/         # Phase 0~6 + phase4_5 + shared
│   ├── resolved/stage3/           # Stage 3 시각 배치 (VisualBuilder + Visual*)
│   └── legacy/                    # 레거시: IDMLNormalizer + Stage1~3
├── converter/                     # W4로 9개 sub-module 분리
│   ├── ASTToHwpxConverter.java    # AST → HWPX 메인
│   ├── HwpxParagraphBuilder.java  # 단락 빌더 (327 LOC, 구 1206)
│   │  ↳ LineSpacingResolver / ParaPrFactory / CharPrFactory /
│   │     InlineItemDispatcher (W4-2)
│   ├── HwpxTextBoxBuilder.java    # 글상자 빌더 (986 LOC, 구 1980)
│   │  ↳ TextBoxLayoutHelpers / PageOverlayBuilder /
│   │     InlineFrameBuilder / SingleColumnTableConverter /
│   │     FrameTransformations (W4)
│   ├── HwpxTableBuilder.java      # 표 빌더
│   ├── HwpxImageBuilder.java      # 이미지 빌더
│   └── FontMapper.java            # 3계층 폰트 매핑
├── resolved/                      # resolved.json 데이터 처리
└── idml/                          # IDML 파서

scripts/extract_indd.jsx           # InDesign ExtendScript 추출

desktop/
├── src/                           # React 18 + Zustand + Tailwind 프론트엔드
└── src-tauri/src/                 # Tauri 2.0 (Rust) 브릿지
```

## 관련 프로젝트

이 프로젝트는 [hwpxlib](https://github.com/neolord0/hwpxlib)를 기반으로 합니다.

- [hwplib](https://github.com/neolord0/hwplib) - HWP 파일 라이브러리
- [hwpxlib](https://github.com/neolord0/hwpxlib) - HWPX 파일 라이브러리 (원본)

## 참고 문서

- [OWPML 문서](http://www.hancom.com/etc/hwpDownload.do?gnb0=269&gnb1=271&gnb0=101&gnb1=140) - 한글과컴퓨터 공개 문서
- [IDML 스펙](https://www.adobe.com/devnet/indesign/documentation.html) - Adobe InDesign IDML 문서

## 라이선스

Apache-2.0 License

---

*"본 제품은 한글과컴퓨터의 HWP 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다."*
