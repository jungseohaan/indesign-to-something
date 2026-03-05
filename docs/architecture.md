# InDesign → Multi-Format Converter (v2)

> **Desktop App (Tauri + React) + Java CLI 변환 엔진**
> InDesign 문서(.indd)를 범용 AST로 변환하고, 다양한 포맷(HWPX, HTML, PPTX, EPUB)으로 내보내는 데스크톱 애플리케이션
>
> ⚡ v2 업데이트: IDML 4종 비교 분석 결과 반영 (수학교과서/국어교과서/풀수록수학/풀수록물리)

---

## 1. 아키텍처 개요

### 핵심 설계 원칙: AST-First

AST(Abstract Syntax Tree)는 이 시스템의 **단일 진실 공급원(Single Source of Truth)**이다. InDesign 문서의 구조와 resolved 속성을 포맷 중립적인 트리 구조로 표현하며, 모든 출력 포맷은 이 AST로부터 생성된다.

```
                    ┌──────────────────┐
                    │  InDesign (.indd) │
                    └────────┬─────────┘
                             │
               ┌─────────────┴─────────────┐
               │   InDesign UXP 스크립트     │
               ├─────────────┬─────────────┤
               ▼             ▼             ▼
          output.idml   resolved.json  preview.pdf
               │             │
               └──────┬──────┘
                      ▼
         ┌────────────────────────┐
         │  Raw IDML AST          │  ← IDML 구조 충실 파싱
         │  (포맷 종속적)          │
         └───────────┬────────────┘
                     │
         ┌───────────┴────────────┐
         │  Structure Recognizer   │  ← ★ 문서 유형별 분기점
         │  ├─ ProblemRecognizer   │     (문제집 vs 교과서)
         │  └─ TextbookRecognizer  │
         └───────────┬────────────┘
                     ▼
         ┌────────────────────────┐
         │                        │
         │  ★ Canonical AST ★     │  ← 범용 중간 표현 (IR)
         │     (.ast.json)        │
         │                        │
         └───┬────┬────┬────┬────┘
             │    │    │    │
             ▼    ▼    ▼    ▼
          HWPX  HTML  PPTX  EPUB
```

### v1 → v2 아키텍처 변경점

| 항목 | v1 | v2 (분석 반영) |
|------|-----|------|
| AST 모델 | 단일 Document AST | **이원 AST** (Base + Problem/Textbook 확장) |
| 파싱 레이어 | IDML → AST 직접 | IDML → **Raw AST** → **Structure Recognizer** → Canonical AST |
| Story 로딩 | 외부 파일만 가정 | **인라인 + 외부 파일** 이중 경로 (수학교과서 98% 인라인) |
| 폰트 매핑 | 단순 1:1 매핑 | **4단계 Fallback 전략** (SUBSTITUTE/IMAGE/MATH/DROP) |
| 스타일 시스템 | 이름 기반 매핑 | **sourceStyleName + semanticRole** 분리 |
| 레이아웃 정보 | 미포함 | **readingOrder, spatialPosition, isDecoration** 추가 |
| 장식 분류 | 미고려 | **Decoration Classifier** 파이프라인 추가 |
| 개발 순서 | 기능 중심 | **문서 복잡도 순** (풀수록→수학교과서→국어교과서) |

### 왜 AST가 가장 중요한가

1. **관심사 분리**: 파싱(InDesign → AST)과 생성(AST → 출력)이 완전히 독립
2. **N:M 확장**: 입력 N개 × 출력 M개를 N+M개 모듈로 해결 (N×M이 아님)
3. **테스트 용이**: AST를 JSON으로 직렬화하여 스냅샷 테스트 가능
4. **디버깅**: 변환 문제 발생 시 AST 단계에서 원인 분리 가능
5. **재사용**: 같은 문서를 여러 포맷으로 반복 변환할 때 AST 캐싱

### 시스템 아키텍처

```
┌─ Tauri + React (Desktop App) ────────────────────────┐
│                                                      │
│  React (Webview)                                     │
│  └─ 파일 선택 → 추출 → 매핑 확인 → 포맷 선택 → 결과   │
│                                                      │
│  Rust (Backend)                                      │
│  ├─ InDesign 프로세스 실행/감시                        │
│  ├─ java -jar converter.jar (subprocess)             │
│  └─ 뷰어 실행                                        │
└──────────┬──────────────────┬────────────────────────┘
           │                  │
           ▼                  ▼
   InDesign Desktop    java -jar converter.jar
   (UXP 스크립트)       ├─ parse   → .ast.json
                        ├─ export  → .hwpx / .html / .pptx / .epub
                        └─ analyze → analysis.json
```

### 기술 스택

| 레이어 | 기술 | 역할 |
|--------|------|------|
| UI | React + Tailwind | 파일 선택, 매핑 확인, 포맷 선택, 프리뷰 |
| Desktop Shell | Tauri (Rust) | 프로세스 관리, 파일 I/O, IPC |
| 추출 | InDesign Desktop + UXP | IDML 내보내기, resolved 속성 수집 |
| 변환 엔진 | Java CLI (converter.jar) | IDML 파싱, AST 생성, 멀티포맷 내보내기 |
| HWPX 생성 | hwpxlib (Java) | HWPX 문서 구조 생성/패키징 |
| HTML 생성 | 자체 구현 | 시맨틱 HTML + CSS 생성 |
| PPTX 생성 | Apache POI (Java) | PowerPoint 문서 생성 |
| EPUB 생성 | 자체 구현 | EPUB3 패키징 |

---

## 2. 파싱 파이프라인: IDML → Raw AST → Canonical AST

### 2.1 3단계 파이프라인

IDML 4종 분석 결과, **단일 단계 파싱은 불가능**함이 확인되었다. 문제집과 교과서의 구조가 근본적으로 다르기 때문에 3단계 파이프라인이 필요하다.

```
Stage 1: IDML Parser (공통)
    IDML ZIP → Story/Spread/Style XML 파싱
    Story 로딩: 인라인(designmap.xml 내장) + 외부(.xml 파일) 이중 경로
    스타일 Resolve: 상속 체인 → 최종 계산값
    ↓
Stage 2: Structure Recognizer (문서 유형별 분기)
    ├─ ProblemRecognizer: Table 패턴 → Problem 구조 (풀수록/기출)
    └─ TextbookRecognizer: 스타일 시맨틱 → 교육과정 구조 (교과서)
    ↓
Stage 3: Canonical AST Builder (공통)
    인식된 구조 → 포맷 중립 AST 트리 구성
```

### 2.2 Story 로딩 전략

| 문서 유형 | 인라인 Story | 외부 Story | 로딩 전략 |
|-----------|:----------:|:----------:|----------|
| A 수학교과서 | 98% | 2% | **인라인 우선**, 외부 보조 |
| B 국어교과서 | 1% | 99% | 외부 파일 중심 |
| C 풀수록 수학 | 1% | 99% | 외부 파일 중심 |
| D 풀수록 물리 | 1% | 99% | 외부 파일 중심 |

```kotlin
class StoryLoader {
    fun loadStories(idmlPackage: IdmlPackage): List<Story> {
        val stories = mutableListOf<Story>()

        // 1. designmap.xml 내 인라인 Story 로드
        stories += loadInlineStories(idmlPackage.designMap)

        // 2. Stories/ 디렉토리의 외부 Story 파일 로드
        stories += loadExternalStories(idmlPackage.storyFiles)

        // 3. 중복 제거 (인라인과 외부가 동일 Story를 참조할 수 있음)
        return stories.distinctBy { it.selfId }
    }
}
```

### 2.3 문서 유형 자동 감지

```kotlin
enum class DocumentType {
    WORKBOOK_MATH,      // 풀수록 수학 (Phase 0)
    WORKBOOK_PHYSICS,   // 풀수록 물리 (Phase 1)
    TEXTBOOK_MATH,      // 수학 교과서 (Phase 2)
    TEXTBOOK_KOREAN,    // 국어 교과서 (Phase 3)
    UNKNOWN
}

class DocumentTypeDetector {
    fun detect(idml: IdmlPackage, resolved: ResolvedJson): DocumentType {
        val signals = DocumentSignals(
            tableCount = idml.tables.size,
            storyStorageMode = if (idml.inlineStoryRatio > 0.5) INLINE else EXTERNAL,
            stylePatterns = analyzeStyleNames(idml.paragraphStyles),
            fontFamilies = idml.fonts.map { it.family }.toSet(),
            visualElementDensity = idml.visualElementCount / idml.spreadCount,
            imageCount = idml.images.size
        )

        return when {
            // 풀수록 수학: 극규칙 Table(4×1), NP_ 수식 폰트 28종
            signals.hasUniformTables("4x1") && signals.hasFontPrefix("NP_")
                -> WORKBOOK_MATH

            // 풀수록 물리: 다양한 Table(11×2, 10×2, 7×2), BT수식H
            signals.hasMultipleTablePatterns() && signals.hasFontPrefix("BT수식H")
                -> WORKBOOK_PHYSICS

            // 수학 교과서: 인라인 Story, BT수식M, Table 없음
            signals.storyStorageMode == INLINE && signals.hasFontPrefix("BT수식M")
                -> TEXTBOOK_MATH

            // 국어 교과서: 60+ 폰트, 3,200+ 시각 요소, 30+ 디자인 서체
            signals.fontFamilies.size > 50 && signals.visualElementDensity > 200
                -> TEXTBOOK_KOREAN

            else -> UNKNOWN
        }
    }
}
```

### 2.4 Structure Recognizer

#### ProblemRecognizer (문제집용)

```kotlin
/**
 * Table 패턴을 감지하여 Problem 구조로 변환
 *
 * 풀수록 수학: 4×1 Table → [문번|발문|보기|풀이]
 * 풀수록 물리: 11×2, 10×2, 7×2 Table → [출처|문번|발문|보기|선지|풀이]
 */
class ProblemRecognizer : StructureRecognizer {

    fun recognize(rawAst: RawIdmlAst): List<ProblemNode> {
        return rawAst.tables.mapNotNull { table ->
            when (detectTablePattern(table)) {
                TablePattern.MATH_4x1 -> parseMathProblem(table)
                TablePattern.PHYSICS_11x2 -> parsePhysicsProblem(table)
                TablePattern.PHYSICS_10x2 -> parsePhysicsProblem(table)
                TablePattern.PHYSICS_7x2 -> parsePhysicsShortProblem(table)
                TablePattern.CONTENT_TABLE -> null  // 콘텐츠 표는 그대로 유지
                else -> null
            }
        }
    }

    private fun detectTablePattern(table: RawTable): TablePattern {
        val key = "${table.rows}x${table.columns}"
        val hasNestedTable = table.cells.any { it.containsTable }
        val styleHints = table.cells.flatMap { it.paragraphStyles }.toSet()

        return when {
            key == "4x1" && styleHints.any { it.contains("풀") } -> TablePattern.MATH_4x1
            key == "11x2" && hasNestedTable -> TablePattern.PHYSICS_11x2
            // ... 추가 패턴
            else -> TablePattern.UNKNOWN
        }
    }
}
```

#### TextbookRecognizer (교과서용)

```kotlin
/**
 * 스타일 이름의 시맨틱 계층을 파싱하여 교육과정 구조로 변환
 *
 * 수학교과서: 8단계 (00~07), 7종 섹션 유형
 * 국어교과서: 10단계 (01~단원마무리), 12종+ 섹션 유형
 */
class TextbookRecognizer : StructureRecognizer {

    /** 스타일 이름 → 시맨틱 역할 매핑 */
    private val styleSemanticMap = mapOf(
        // 공통
        "단원제목" to SemanticRole.UNIT_TITLE,
        "학습목표" to SemanticRole.LEARNING_OBJECTIVE,
        "탐구" to SemanticRole.INQUIRY_ACTIVITY,
        "소단원" to SemanticRole.SUB_UNIT,

        // 국어 교과서 고유
        "본문 지문" to SemanticRole.TEXT_PASSAGE,
        "즐책" to SemanticRole.READING_ACTIVITY,
        "어휘력" to SemanticRole.VOCAB_ACTIVITY,
        "창의융합" to SemanticRole.CREATIVE_ACTIVITY,
        "생각열기" to SemanticRole.OPENER_ACTIVITY,

        // 수진 정리 (제작자 스타일 → 시맨틱 정규화)
        "수진 정리:택배와 아빠 색강조" to SemanticRole.TEXT_EMPHASIS,
        "수진 정리:표내용_산돌고딕" to SemanticRole.TABLE_CONTENT,
        "수진 정리:표제목_산돌" to SemanticRole.TABLE_HEADER,
        // ... 27개 매핑
    )

    fun recognize(rawAst: RawIdmlAst): TextbookDocument {
        val units = mutableListOf<UnitNode>()
        var currentUnit: UnitBuilder? = null

        for (block in rawAst.linearizedBlocks) {
            val role = resolveSemanticRole(block.styleName)

            when (role) {
                SemanticRole.UNIT_TITLE -> {
                    currentUnit?.let { units += it.build() }
                    currentUnit = UnitBuilder(title = block)
                }
                SemanticRole.INQUIRY_ACTIVITY -> currentUnit?.addActivity(block)
                SemanticRole.TEXT_PASSAGE -> currentUnit?.addPassage(block)
                SemanticRole.SUB_UNIT_REVIEW -> currentUnit?.addReview(block)
                // ...
            }
        }

        return TextbookDocument(units = units)
    }

    /** 스타일 이름에서 시맨틱 역할 추론 (접두사 기반 + 퍼지 매칭) */
    private fun resolveSemanticRole(styleName: String): SemanticRole {
        // 1. 정확한 매칭
        styleSemanticMap[styleName]?.let { return it }

        // 2. 접두사 패턴 매칭 ("02_단원시작" → 02 = 단원시작 섹션)
        val prefixMatch = styleName.substringBefore("_").toIntOrNull()
        if (prefixMatch != null) {
            return sectionPrefixMap[prefixMatch] ?: SemanticRole.UNKNOWN
        }

        // 3. 수진 정리 패턴
        if (styleName.startsWith("수진 정리:")) {
            return inferFromSujinStyle(styleName)
        }

        return SemanticRole.UNKNOWN
    }
}
```

---

## 3. Document AST 스키마 (핵심)

### 3.1 설계 원칙

| 원칙 | 설명 |
|------|------|
| **포맷 중립** | InDesign 용어도, HWPX 용어도 아닌 범용 용어 사용 |
| **Resolved 우선** | 스타일 상속이 해석된 최종 값을 저장 |
| **트리 구조** | Document → Section → Block → Inline 4단계 |
| **타입 안전** | 모든 노드는 `type` 필드로 구별, 유니온 타입 |
| **단위 통일** | 모든 치수는 **pt (포인트)** 단위로 통일 |
| **원본 참조** | 각 노드에 원본 소스 위치 정보 보존 (디버깅용) |
| **이원 구조** | 공통 Base + 도메인 확장 (Problem / Textbook) |
| **스타일 이중 저장** | `sourceStyleName` (원본) + `semanticRole` (정규화) |
| **레이아웃 메타** | 교과서용 공간 정보 (readingOrder, isDecoration) |

### 3.2 전체 노드 계층

```
Document
├── meta                    # 문서 메타데이터
│   └── documentType        # ★ WORKBOOK_MATH | WORKBOOK_PHYSICS | TEXTBOOK_MATH | TEXTBOOK_KOREAN
├── settings                # 페이지 크기, 여백, 단 설정
├── styles                  # 스타일 사전 (정의 + resolved)
│   ├── paragraphStyles[]
│   │   ├── source.indesign   # 원본 InDesign 스타일 이름
│   │   └── semanticRole      # ★ 정규화된 시맨틱 역할
│   └── characterStyles[]
├── resources               # 외부 리소스 (이미지, 폰트)
│   ├── images[]
│   └── fonts[]
│       └── fallbackStrategy  # ★ SUBSTITUTE | IMAGE_FALLBACK | MATH_CONVERT | DROP
├── sections[]              # 구역 (페이지 나누기 단위)
│   └── blocks[]            # 블록 레벨 노드
│       ├── Paragraph
│       │   └── inlines[]
│       │       ├── TextRun
│       │       ├── LineBreak
│       │       ├── Tab
│       │       ├── SpecialSpace
│       │       ├── Symbol
│       │       ├── InlineImage
│       │       ├── Footnote
│       │       ├── Ruby
│       │       └── Math       # ★ 수식 (확장)
│       ├── Table
│       │   └── rows[] → cells[] → blocks[] (재귀)
│       ├── Image              # 블록 레벨 이미지
│       ├── PageBreak
│       │
│       │── ─── 도메인 확장 블록 ──────────
│       │
│       ├── Problem            # ★ 문제집 전용
│       │   ├── source?        #   출처 (년도/시험명) — 물리 기출
│       │   ├── sidebar?       #   보조단 — 풀수록 수학
│       │   ├── stem           #   발문
│       │   ├── figure?        #   그래프/표/그림
│       │   ├── box?           #   <보기> 박스 — 물리
│       │   ├── choices?       #   ①②③④⑤ 선지
│       │   └── solution?      #   교사용 풀이
│       │
│       ├── UnitOpener         # ★ 교과서 전용
│       │   ├── title
│       │   ├── objectives
│       │   ├── openerActivity
│       │   └── decorations[]  #   말풍선, 캐릭터, 삽화
│       ├── TextPassage        # ★ 국어 교과서 전용 (본문 지문)
│       │   ├── content
│       │   ├── sourceInfo     #   작품 출처
│       │   └── passageFont    #   전용 서체 (ViMaru OTF 등)
│       ├── Activity           # ★ 교과서 전용 (탐구/활동)
│       │   ├── prompt
│       │   ├── conceptBox?
│       │   ├── chart?
│       │   ├── sidebar?
│       │   └── studentArea?   #   빈칸/체크리스트
│       └── SectionReview      # ★ 교과서 전용 (소단원 마무리)
│           ├── selfCheck
│           └── practice[]
│
└── sourceMap               # 원본 소스 매핑 (선택)
```

### 3.3 AST JSON 스키마

#### Document (루트)

```json
{
  "$schema": "https://visang.com/ast/v2/document.schema.json",
  "version": "2.0.0",
  "meta": {
    "title": "풀수록 수1 본문 10일차",
    "creator": "InDesign 2025",
    "created": "2026-02-20T09:00:00Z",
    "sourceFile": "풀수록_수1본문10일차.indd",
    "generator": "indd-converter/2.0.0",
    "documentType": "WORKBOOK_MATH",
    "analysisProfile": {
      "storyCount": 442,
      "storyStorageMode": "EXTERNAL",
      "tableCount": 28,
      "tablePattern": "UNIFORM_4x1",
      "imageCount": 0,
      "paragraphStyleCount": 18,
      "characterStyleCount": 20,
      "fontCount": 42,
      "visualElementDensity": "LOW",
      "anchoredObjectCount": 53
    }
  },
  "settings": {
    "page": {
      "width": 210,
      "height": 297,
      "unit": "mm"
    },
    "margins": {
      "top": 20,
      "bottom": 20,
      "left": 25,
      "right": 25
    },
    "columns": {
      "count": 1,
      "gap": 0
    },
    "defaultParagraphStyle": "body",
    "defaultCharacterStyle": "default"
  },
  "styles": { "..." : "see 3.4" },
  "resources": { "..." : "see 3.5" },
  "sections": [ "..." ]
}
```

#### 3.4 styles — 스타일 사전 (시맨틱 역할 추가)

```json
{
  "styles": {
    "paragraphStyles": [
      {
        "id": "body",
        "name": "본문-기본",
        "basedOn": null,
        "source": {
          "indesign": "본문-기본",
          "rawName": "본문-기본"
        },
        "semanticRole": "BODY_TEXT",
        "semanticContext": null,
        "resolved": {
          "font": {
            "family": "Yoon Gothic 100",
            "style": "Regular",
            "size": 10
          },
          "leading": { "type": "fixed", "value": 16 },
          "alignment": "left",
          "indent": {
            "left": 0,
            "right": 0,
            "firstLine": 0,
            "hanging": 0
          },
          "spacing": {
            "before": 0,
            "after": 3
          },
          "hyphenation": false,
          "keepWithNext": false,
          "keepLines": false,
          "color": { "type": "cmyk", "c": 0, "m": 0, "y": 0, "k": 100 }
        }
      },
      {
        "id": "inquiry-prompt",
        "name": "04_탐구 발문",
        "basedOn": "body",
        "source": {
          "indesign": "04_탐구 발문",
          "rawName": "04_탐구 발문",
          "sectionPrefix": "04",
          "sectionName": "탐구"
        },
        "semanticRole": "INQUIRY_PROMPT",
        "semanticContext": "INQUIRY_ACTIVITY",
        "resolved": {
          "font": {
            "family": "Yoon Gothic 100",
            "style": "Bold",
            "size": 11
          },
          "leading": { "type": "fixed", "value": 17 },
          "alignment": "left",
          "color": { "type": "cmyk", "c": 0, "m": 0, "y": 0, "k": 100 }
        }
      },
      {
        "id": "sujin-table-content",
        "name": "수진 정리:표내용_산돌고딕",
        "basedOn": "body",
        "source": {
          "indesign": "수진 정리:표내용_산돌고딕",
          "rawName": "수진 정리:표내용_산돌고딕",
          "makerTag": "수진 정리"
        },
        "semanticRole": "TABLE_CONTENT",
        "semanticContext": null,
        "resolved": {
          "font": {
            "family": "Sandoll Gothic Neo1",
            "style": "Regular",
            "size": 10
          }
        }
      }
    ],
    "characterStyles": [
      {
        "id": "emphasis",
        "name": "강조",
        "source": { "indesign": "강조-볼드" },
        "semanticRole": "EMPHASIS",
        "resolved": {
          "font": { "style": "Bold" },
          "color": { "type": "cmyk", "c": 100, "m": 0, "y": 0, "k": 0 }
        }
      }
    ]
  }
}
```

**semanticRole 전체 목록:**

```
// 공통
BODY_TEXT, HEADING, EMPHASIS, TABLE_HEADER, TABLE_CONTENT,
PAGE_NUMBER, HEADER_TEXT, FOOTER_TEXT, CAPTION, FOOTNOTE_TEXT

// 문제집 전용
PROBLEM_NUMBER, PROBLEM_STEM, PROBLEM_CHOICES, PROBLEM_SOLUTION,
PROBLEM_SOURCE, PROBLEM_SIDEBAR, BOX_CONTENT, SCORE_TEXT,
FRACTION_NUMERATOR, FRACTION_DENOMINATOR

// 교과서 전용
UNIT_TITLE, LEARNING_OBJECTIVE, OPENER_ACTIVITY,
INQUIRY_PROMPT, INQUIRY_NUMBER, CONCEPT_TITLE, CONCEPT_CONTENT,
CHART_TITLE, CHART_CONTENT, ACTIVITY_METHOD, SIDEBAR_HELP,
TEXT_PASSAGE, PASSAGE_SOURCE, READING_ACTIVITY, VOCAB_ACTIVITY,
CREATIVE_TITLE, SECTION_REVIEW, SELF_CHECK, PRACTICE_PROBLEM,
DECORATION_SPEECH_BUBBLE, DECORATION_LABEL,
TEXT_EMPHASIS, BALLOON_TEXT
```

#### 3.5 resources — 폰트 Fallback 전략 포함

```json
{
  "resources": {
    "images": [
      {
        "id": "img-001",
        "originalPath": "Links/figure_3_1.eps",
        "mimeType": "image/png",
        "width": 320,
        "height": 240,
        "dpi": 300,
        "embedded": true,
        "dataPath": "resources/img-001.png"
      }
    ],
    "fonts": [
      {
        "family": "Yoon Gothic 100",
        "styles": ["Regular", "Bold", "Light"],
        "type": "opentype",
        "category": "BODY_GOTHIC",
        "fallbackStrategy": "SUBSTITUTE",
        "hwpxMapping": "윤고딕 100",
        "htmlMapping": "'Noto Sans KR', sans-serif"
      },
      {
        "family": "Yoon Myungjo 100",
        "styles": ["Regular", "Bold"],
        "type": "opentype",
        "category": "BODY_MYUNGJO",
        "fallbackStrategy": "SUBSTITUTE",
        "hwpxMapping": "윤명조 100",
        "htmlMapping": "'Noto Serif KR', serif"
      },
      {
        "family": "NP_기본정체수식M",
        "styles": ["Regular"],
        "type": "opentype",
        "category": "MATH_FORMULA",
        "fallbackStrategy": "MATH_CONVERT",
        "mathEngine": "equation_editor",
        "note": "풀수록 수학 28종 NP_ 수식 폰트 중 하나"
      },
      {
        "family": "BT수식M",
        "styles": ["Regular", "Bold"],
        "type": "opentype",
        "category": "MATH_FORMULA",
        "fallbackStrategy": "MATH_CONVERT",
        "mathEngine": "equation_editor",
        "note": "수학 교과서 수식 폰트 11종"
      },
      {
        "family": "ViMaru OTF",
        "styles": ["Regular"],
        "type": "opentype",
        "category": "PASSAGE_DEDICATED",
        "fallbackStrategy": "SUBSTITUTE",
        "hwpxMapping": "맑은 명조",
        "note": "국어 교과서 본문 지문 전용 서체"
      },
      {
        "family": "HU금요일오후",
        "styles": ["120"],
        "type": "opentype",
        "category": "DESIGN_HANDWRITING",
        "fallbackStrategy": "IMAGE_FALLBACK",
        "note": "국어 교과서 손글씨 장식 서체 — HWPX 렌더링 불가"
      },
      {
        "family": "210 자연공원OTF",
        "styles": ["Regular"],
        "type": "opentype",
        "category": "DESIGN_DECORATIVE",
        "fallbackStrategy": "IMAGE_FALLBACK",
        "note": "국어 교과서 15종 210 계열 디자인 서체 중 하나"
      },
      {
        "family": "DX새명조고어 Std",
        "styles": ["Regular"],
        "type": "opentype",
        "category": "ARCHAIC_TEXT",
        "fallbackStrategy": "IMAGE_FALLBACK",
        "note": "국어 교과서 고어체"
      },
      {
        "family": "EH분수대문자",
        "styles": ["Regular"],
        "type": "opentype",
        "category": "MATH_FRACTION",
        "fallbackStrategy": "MATH_CONVERT",
        "note": "국어 교과서 분수 전용"
      }
    ]
  }
}
```

**Font FallbackStrategy:**

| 전략 | 설명 | 적용 대상 |
|------|------|----------|
| `SUBSTITUTE` | 유사 폰트로 대체 | 본문 서체 (윤고딕, 윤명조, Sandoll, ViMaru) |
| `IMAGE_FALLBACK` | 텍스트를 이미지로 래스터화 | 디자인 서체 (210계열, HU계열, THE계열, Rix계열, 손글씨) |
| `MATH_CONVERT` | 수식 엔진으로 변환 | 수식 폰트 (NP_, BT수식, EH분수/상부자) |
| `DROP` | 무시 (비콘텐츠) | 장식 전용, 조판지시서 |

**4종 문서별 폰트 매핑 프로파일:**

| 분류 | A 수학교과서 (36종) | B 국어교과서 (60종) | C 풀수록수학 (42종) | D 풀수록물리 (26종) |
|------|:---:|:---:|:---:|:---:|
| SUBSTITUTE | 윤고딕/명조100 | Sandoll+윤+ViMaru | 윤고딕/명조100 | 윤고딕100/300 |
| IMAGE_FALLBACK | 1종 | **30종+** (210·HU·THE·Rix·손글씨) | 1종 | 1종 |
| MATH_CONVERT | BT수식M 11종 | EH분수/상부자 2종 | NP_ 28종 | BT수식H 10종 |
| DROP | - | 조판지시서 | - | - |

#### 3.6 sections & blocks — 본문 구조

```json
{
  "sections": [
    {
      "id": "sec-1",
      "settings": null,
      "blocks": [

        {
          "type": "paragraph",
          "styleId": "heading-problem",
          "overrides": {},
          "inlines": [
            {
              "type": "text",
              "text": "다음 식을 간단히 하시오.",
              "styleId": null,
              "overrides": {}
            }
          ],
          "source": { "storyId": "u283", "paraIndex": 0 }
        },

        {
          "type": "paragraph",
          "styleId": "body",
          "overrides": {
            "indent": { "left": 20 }
          },
          "inlines": [
            {
              "type": "text",
              "text": "(1)",
              "styleId": null,
              "overrides": { "font": { "style": "Bold" } }
            },
            { "type": "tab" },
            {
              "type": "text",
              "text": "3x",
              "styleId": null,
              "overrides": { "font": { "family": "Times New Roman", "style": "Italic" } }
            },
            {
              "type": "text",
              "text": " + 5",
              "styleId": null,
              "overrides": {}
            },
            {
              "type": "text",
              "text": "2",
              "styleId": null,
              "overrides": { "position": "superscript" }
            }
          ],
          "source": { "storyId": "u283", "paraIndex": 1 }
        },

        {
          "type": "table",
          "rows": 3,
          "columns": 4,
          "width": 160,
          "columnWidths": [40, 40, 40, 40],
          "cells": [
            {
              "row": 0, "col": 0,
              "rowSpan": 1, "colSpan": 2,
              "verticalAlign": "middle",
              "borders": {
                "top": { "width": 0.5, "style": "solid", "color": { "type": "cmyk", "c": 0, "m": 0, "y": 0, "k": 100 } },
                "bottom": { "width": 0.5, "style": "solid", "color": { "type": "cmyk", "c": 0, "m": 0, "y": 0, "k": 100 } },
                "left": { "width": 0.5, "style": "solid", "color": { "type": "cmyk", "c": 0, "m": 0, "y": 0, "k": 100 } },
                "right": { "width": 0.5, "style": "solid", "color": { "type": "cmyk", "c": 0, "m": 0, "y": 0, "k": 100 } }
              },
              "padding": { "top": 2, "bottom": 2, "left": 3, "right": 3 },
              "background": null,
              "blocks": [
                {
                  "type": "paragraph",
                  "styleId": "table-header",
                  "inlines": [
                    { "type": "text", "text": "항목", "overrides": {} }
                  ]
                }
              ]
            }
          ],
          "source": { "storyId": "u283", "paraIndex": 5 }
        },

        {
          "type": "image",
          "resourceId": "img-001",
          "width": 120,
          "height": 90,
          "alignment": "center",
          "caption": null,
          "source": { "storyId": "u283", "paraIndex": 8 }
        },

        { "type": "pageBreak" }
      ]
    }
  ]
}
```

#### 3.7 도메인 확장 블록 — Problem (문제집)

```json
{
  "type": "problem",
  "problemNumber": 15,
  "source": {
    "year": 2024,
    "exam": "수능",
    "originalNumber": 21,
    "subject": "물리학I"
  },
  "sidebar": {
    "type": "difficulty",
    "label": "상",
    "score": 4
  },
  "stem": {
    "type": "paragraph",
    "styleId": "problem-stem",
    "inlines": [
      { "type": "text", "text": "그림은 xy 평면에서 원운동하는 물체의 ...", "overrides": {} }
    ]
  },
  "figure": {
    "type": "image",
    "resourceId": "img-physics-21",
    "width": 150,
    "height": 100,
    "alignment": "center"
  },
  "box": {
    "label": "<보기>",
    "blocks": [
      {
        "type": "paragraph",
        "inlines": [
          { "type": "text", "text": "ㄱ. 물체의 속력은 일정하다.", "overrides": {} }
        ]
      }
    ]
  },
  "choices": [
    { "number": 1, "text": "ㄱ" },
    { "number": 2, "text": "ㄴ" },
    { "number": 3, "text": "ㄱ, ㄷ" },
    { "number": 4, "text": "ㄴ, ㄷ" },
    { "number": 5, "text": "ㄱ, ㄴ, ㄷ" }
  ],
  "solution": {
    "answer": 5,
    "explanation": [
      {
        "type": "paragraph",
        "styleId": "solution-body",
        "inlines": [
          { "type": "text", "text": "원운동에서 ...", "overrides": {} }
        ]
      }
    ]
  }
}
```

#### 3.8 도메인 확장 블록 — 교과서 구조

```json
{
  "type": "unitOpener",
  "unitNumber": 1,
  "title": "문학 작품의 이해",
  "objectives": [
    "작품에 나타난 인물의 심리를 파악할 수 있다.",
    "작품의 사회적·문화적 배경을 이해할 수 있다."
  ],
  "openerActivity": {
    "type": "activity",
    "prompt": {
      "type": "paragraph",
      "styleId": "opener-prompt",
      "inlines": [
        { "type": "text", "text": "다음 그림을 보고 이야기를 나눠 봅시다.", "overrides": {} }
      ]
    }
  },
  "decorations": [
    {
      "type": "decoration",
      "decorationType": "SPEECH_BUBBLE",
      "content": "등장인물은 어떤 마음이었을까?",
      "layout": {
        "spatialPosition": { "x": 120, "y": 340, "width": 180, "height": 60 },
        "zIndex": 3,
        "isDecoration": true,
        "readingOrder": null
      }
    },
    {
      "type": "decoration",
      "decorationType": "CHARACTER_IMAGE",
      "resourceId": "img-char-01",
      "layout": {
        "spatialPosition": { "x": 50, "y": 280, "width": 80, "height": 120 },
        "zIndex": 2,
        "isDecoration": true,
        "readingOrder": null
      }
    }
  ]
}
```

```json
{
  "type": "textPassage",
  "passageId": "passage-01",
  "title": "택배 아저씨와 아빠",
  "author": "김유정",
  "sourceInfo": "『한국 단편 소설선』, 2015",
  "passageFont": {
    "family": "ViMaru OTF",
    "fallbackStrategy": "SUBSTITUTE",
    "hwpxFallback": "맑은 명조"
  },
  "content": [
    {
      "type": "paragraph",
      "styleId": "passage-body",
      "inlines": [
        { "type": "text", "text": "아버지는 늘 택배 상자를 들고 왔다. ...", "overrides": {} }
      ]
    }
  ],
  "annotations": [
    {
      "type": "vocab",
      "word": "아련하다",
      "definition": "기억이나 감정이 흐릿하게 떠오르는 듯하다",
      "position": { "paragraphIndex": 2, "charOffset": 15 }
    }
  ]
}
```

```json
{
  "type": "activity",
  "activityType": "INQUIRY",
  "title": "인물의 심리 파악하기",
  "prompt": {
    "type": "paragraph",
    "styleId": "inquiry-prompt",
    "inlines": [
      { "type": "text", "text": "다음 글을 읽고, 인물의 심리 변화를 정리해 봅시다.", "overrides": {} }
    ]
  },
  "conceptBox": {
    "title": "인물의 심리",
    "content": [
      {
        "type": "paragraph",
        "styleId": "concept-content",
        "inlines": [
          { "type": "text", "text": "인물의 심리란 작품 속 인물이 느끼는 ...", "overrides": {} }
        ]
      }
    ]
  },
  "chart": {
    "type": "table",
    "rows": 3,
    "columns": 2,
    "cells": []
  },
  "sidebar": {
    "type": "help",
    "label": "도움말",
    "content": "심리 변화를 파악할 때는 인물의 말과 행동에 주목하세요."
  },
  "studentArea": {
    "type": "BLANK_LINES",
    "lineCount": 5,
    "label": "내 생각 쓰기"
  }
}
```

#### 3.9 레이아웃 메타데이터 (교과서용)

교과서의 자유 레이아웃(Polygon, GraphicLine, Group 등)을 처리하기 위한 공간 정보:

```json
{
  "layout": {
    "readingOrder": 3,
    "spatialPosition": {
      "x": 45.5,
      "y": 120.3,
      "width": 180.0,
      "height": 60.0,
      "pageIndex": 0,
      "spreadIndex": 3
    },
    "zIndex": 5,
    "isDecoration": false,
    "decorationType": null,
    "groupId": "grp-042",
    "anchoredTo": "para-15",
    "anchorType": "ABSOLUTE"
  }
}
```

**isDecoration 분류 기준** (국어 교과서 3,200+ 시각 요소 분류용):

| 분류 | 기준 | 예시 | isDecoration |
|------|------|------|:---:|
| 콘텐츠 텍스트 | TextFrame에 교육 콘텐츠 포함 | 발문, 지문, 학습목표 | `false` |
| 콘텐츠 이미지 | 학습 관련 삽화/사진 | 교과서 삽화, 도표 그림 | `false` |
| 콘텐츠 도형 | 의미 전달 도형 | 개념 관계도, 순서도 | `false` |
| 장식 도형 | 배경/테두리 목적 | 색 배경 Rectangle, 장식 Polygon | `true` |
| 장식 선 | 구분/꾸밈 목적 | 밑줄, 구분선, 화살표 장식 | `true` |
| 장식 아이콘 | UI 아이콘 | 번호 원형 배경 (Oval), 섹션 아이콘 | `true` |
| 장식 텍스트 | 폰트가 디자인 서체 | 210계열/HU계열 장식 텍스트 | `true` |

#### 3.10 inline 노드 타입 전체

```json
// TextRun — 텍스트
{ "type": "text", "text": "본문 텍스트", "styleId": null, "overrides": {} }

// LineBreak — 문단 내 강제 줄바꿈
{ "type": "lineBreak" }

// Tab — 탭
{ "type": "tab" }

// SpecialSpace — 특수 공백
{ "type": "space", "variant": "nonBreaking" }
// variant: "nonBreaking" | "em" | "en" | "thin" | "figure" | "hair" | "sixPerEm" | "fourPerEm"
//          ★ 4종 분석에서 발견된 모든 공백 타입 반영

// Symbol — 기호
{ "type": "symbol", "char": "•", "unicode": "U+2022" }

// InlineImage — 인라인 이미지 (앵커 객체)
{
  "type": "inlineImage",
  "resourceId": "img-002",
  "width": 24,
  "height": 24,
  "anchorType": "INLINE"
}
// anchorType: "INLINE" | "ABSOLUTE" | "UNSPECIFIED"
// ★ 4종 분석에서 확인된 앵커 타입 반영

// Footnote — 각주
{ "type": "footnote", "noteId": "fn-1", "blocks": [] }

// Ruby — 루비
{ "type": "ruby", "base": "漢字", "annotation": "한자" }

// Math — 수식 (★ 확장)
{
  "type": "math",
  "format": "latex",
  "value": "x^2 + y^2 = r^2",
  "sourceFontFamily": "NP_기본정체수식M",
  "fallbackImage": "resources/math-001.png"
}
// format: "latex" | "mathml" | "image" | "font_based"
// ★ font_based: 수식 전용 폰트로 표현된 수식 (NP_, BT수식 등)
```

#### 3.11 overrides — 스타일 오버라이드

```json
// 인라인 수준 overrides
{
  "overrides": {
    "font": { "style": "Bold" },
    "color": { "type": "rgb", "r": 255, "g": 0, "b": 0 },
    "underline": true,
    "position": "superscript",
    "tracking": 50,
    "horizontalScale": 90,
    "baselineShift": -2
  }
}

// 문단 수준 overrides
{
  "overrides": {
    "alignment": "center",
    "spacing": { "before": 12 },
    "indent": { "firstLine": 10 }
  }
}
```

#### 3.12 color 타입

```json
// CMYK
{ "type": "cmyk", "c": 100, "m": 0, "y": 0, "k": 0 }

// RGB
{ "type": "rgb", "r": 0, "g": 102, "b": 204 }

// Named/Semantic Color (★ 국어 교과서 디자인 시스템 색상)
{
  "type": "named",
  "name": "1단원_C95M15",
  "category": "UNIT_THEME",
  "resolved": { "type": "cmyk", "c": 95, "m": 15, "y": 0, "k": 0 }
}

// UI Component Color (★ 국어 교과서)
{
  "type": "named",
  "name": "box그린",
  "category": "UI_COMPONENT",
  "variant": "default",
  "resolved": { "type": "cmyk", "c": 80, "m": 0, "y": 60, "k": 0 }
}
// variant: "default" | "medium" | "dark" — box그린 / box그린m / box그린DD

// Spot Color
{ "type": "spot", "name": "PANTONE 286 C", "fallback": { "type": "cmyk", "c": 100, "m": 66, "y": 0, "k": 2 } }

// None (투명)
null
```

**4종 문서별 색상 복잡도:**

| 문서 | 시맨틱 색상 수 | 명명 규칙 | AST 처리 |
|------|:---:|------|------|
| A 수학교과서 | 95 | CMYK 값 기반 | `cmyk` 타입으로 저장 |
| B 국어교과서 | 90+ | 혼합 (시맨틱+RGB+CMYK) | `named` 타입으로 시맨틱 보존 |
| C 풀수록 수학 | 2 | 역할 기반 | `named` 타입 |
| D 풀수록 물리 | 6 | 역할 기반 | `named` 타입 |

#### 3.13 AST 파일 구조

대용량 문서의 경우, 리소스(이미지)를 분리한 디렉토리 구조:

```
document.ast/
├── document.ast.json        # AST 본문 (트리 구조)
├── resources/
│   ├── img-001.png
│   ├── img-002.jpg
│   ├── img-003.svg
│   └── math-fallback/      # ★ 수식 이미지 폴백
│       ├── math-001.png
│       └── math-002.png
├── font-fallback/           # ★ 디자인 서체 이미지 폴백 (국어 교과서)
│   ├── design-text-001.png
│   └── design-text-002.png
└── preview.pdf              # 원본 프리뷰 (선택)
```

#### 3.14 AST 설계 체크리스트 (v2 업데이트)

| 항목 | 지원 | 비고 |
|------|:----:|------|
| 문단 + 글자 스타일 (resolved) | ✅ | styles 사전 + overrides |
| 시맨틱 역할 매핑 | ✅ | ★ semanticRole + semanticContext |
| 폰트 (family, style, size) | ✅ | resolved.font |
| 폰트 Fallback 전략 | ✅ | ★ 4단계 전략 (SUBSTITUTE/IMAGE/MATH/DROP) |
| 행간 (고정/자동/퍼센트) | ✅ | resolved.leading |
| 정렬 | ✅ | resolved.alignment |
| 들여쓰기/여백 | ✅ | resolved.indent, spacing |
| 색상 (CMYK/RGB/Spot/Named) | ✅ | ★ named 타입 추가 (디자인 시스템 색상) |
| 밑줄/취소선 | ✅ | overrides |
| 위첨자/아래첨자 | ✅ | overrides.position |
| 자간/장평 | ✅ | ★ overrides.tracking, horizontalScale |
| 표 (셀 병합, 테두리, 배경) | ✅ | table 블록 |
| 이미지 (블록/인라인) | ✅ | image 블록, inlineImage 인라인 |
| 앵커 객체 타입 | ✅ | ★ INLINE / ABSOLUTE / UNSPECIFIED |
| 각주/미주 | ✅ | footnote 인라인 |
| 특수문자/공백 | ✅ | ★ 8종 공백 타입 (분석 결과 반영) |
| 루비 | ✅ | ruby 인라인 |
| 수식 (폰트 기반 포함) | ✅ | ★ math 인라인 + font_based 포맷 |
| 페이지 나누기 | ✅ | pageBreak 블록 |
| 다단 레이아웃 | ✅ | settings.columns |
| 원본 소스 참조 | ✅ | source 필드 |
| 문서 유형 감지 | ✅ | ★ meta.documentType |
| 문제 구조 (문제집) | ✅ | ★ Problem 도메인 블록 |
| 교과서 구조 (교과서) | ✅ | ★ UnitOpener/TextPassage/Activity 등 |
| 레이아웃 메타데이터 | ✅ | ★ readingOrder, spatialPosition, isDecoration |
| 장식 분류 | ✅ | ★ isDecoration + decorationType |
| 제작자 스타일 정규화 | ✅ | ★ makerTag → semanticRole 변환 |
| Story 인라인/외부 이중 로딩 | ✅ | ★ StoryLoader 이중 경로 |

---

## 4. 멀티포맷 Exporter 아키텍처

### 4.1 Exporter 플러그인 구조

```
converter.jar
├── core/
│   ├── ast/
│   │   ├── AstModel.kt              # AST 노드 정의 (sealed class)
│   │   ├── AstReader.kt             # .ast.json 파서
│   │   ├── AstBuilder.kt            # Raw IDML AST → Canonical AST
│   │   └── AstValidator.kt          # AST 검증
│   ├── parser/
│   │   ├── IdmlParser.kt            # IDML ZIP → Raw AST
│   │   ├── StoryLoader.kt           # ★ 인라인 + 외부 이중 로딩
│   │   ├── StyleResolver.kt         # 스타일 상속 체인 resolve
│   │   └── ResolvedJsonParser.kt    # UXP resolved JSON 파서
│   ├── recognizer/                   # ★ 구조 인식 레이어
│   │   ├── StructureRecognizer.kt   # 공통 인터페이스
│   │   ├── DocumentTypeDetector.kt  # 문서 유형 자동 감지
│   │   ├── ProblemRecognizer.kt     # 문제집 구조 인식
│   │   ├── TextbookRecognizer.kt    # 교과서 구조 인식
│   │   └── DecorationClassifier.kt  # ★ 장식 vs 콘텐츠 분류
│   └── mapping/
│       ├── FontMapper.kt            # 폰트 매핑 + Fallback 전략
│       ├── StyleMapper.kt           # 스타일 매핑 + 시맨틱 역할
│       ├── ColorMapper.kt           # ★ 색상 매핑 (Named 색상 포함)
│       └── SpecialCharMapper.kt     # 특수문자 매핑
│
├── exporters/
│   ├── Exporter.kt                  # 공통 인터페이스
│   ├── ExporterRegistry.kt          # 플러그인 레지스트리
│   │
│   ├── hwpx/
│   │   ├── HwpxExporter.kt
│   │   ├── HwpxStyleMapper.kt
│   │   ├── HwpxFontMapper.kt
│   │   ├── HwpxSpecialCharMapper.kt
│   │   ├── HwpxMathHandler.kt       # ★ 수식 폰트 → 수식 편집기 변환
│   │   └── HwpxImageFallback.kt     # ★ 디자인 서체 이미지 폴백
│   │
│   ├── html/
│   │   ├── HtmlExporter.kt
│   │   ├── HtmlStyleGenerator.kt
│   │   └── HtmlTemplateEngine.kt
│   │
│   ├── pptx/
│   │   ├── PptxExporter.kt
│   │   ├── PptxLayoutEngine.kt
│   │   └── PptxSlideBuilder.kt
│   │
│   └── epub/
│       ├── EpubExporter.kt
│       ├── EpubChapterSplitter.kt
│       └── EpubPackager.kt
│
└── profiles/                         # ★ 문서 유형별 프로파일
    ├── WorkbookMathProfile.kt
    ├── WorkbookPhysicsProfile.kt
    ├── TextbookMathProfile.kt
    └── TextbookKoreanProfile.kt
```

### 4.2 Exporter 공통 인터페이스

```kotlin
interface Exporter {
    val formatId: String          // "hwpx", "html", "pptx", "epub"
    val formatName: String        // "한글 문서 (.hwpx)"
    val fileExtension: String     // ".hwpx"

    fun export(
        ast: Document,
        outputPath: Path,
        options: ExportOptions,
        progress: ProgressCallback,
    )

    fun supportedFeatures(): Set<AstFeature>
}

enum class AstFeature {
    PARAGRAPH_STYLES,
    CHARACTER_STYLES,
    TABLES,
    IMAGES,
    FOOTNOTES,
    RUBY,
    MATH,
    PAGE_BREAKS,
    MULTI_COLUMN,
    SPOT_COLORS,
    SUPERSCRIPT,
    SUBSCRIPT,
    // ★ v2 추가
    PROBLEM_STRUCTURE,        // 문제집 구조
    TEXTBOOK_STRUCTURE,       // 교과서 구조
    FONT_IMAGE_FALLBACK,      // 디자인 서체 이미지 폴백
    MATH_FONT_CONVERSION,     // 수식 폰트 변환
    NAMED_COLORS,             // 시맨틱/디자인 색상
    DECORATION_HANDLING,      // 장식 요소 처리
    READING_ORDER,            // 읽기 순서 메타데이터
}

data class ExportOptions(
    val fontMappings: Map<String, String> = emptyMap(),
    val styleMappings: Map<String, String> = emptyMap(),
    val imageQuality: ImageQuality = ImageQuality.HIGH,
    val embedFonts: Boolean = false,
    val embedImages: Boolean = true,
    // ★ v2 추가
    val documentProfile: DocumentProfile? = null,
    val fontFallbackMode: FontFallbackMode = FontFallbackMode.AUTO,
    val decorationHandling: DecorationHandling = DecorationHandling.INCLUDE_AS_IMAGE,
    val mathConversion: MathConversionMode = MathConversionMode.EQUATION_EDITOR,
    val formatSpecific: Map<String, Any> = emptyMap(),
)

enum class FontFallbackMode {
    AUTO,               // FontMapper의 fallbackStrategy 사용
    ALWAYS_SUBSTITUTE,  // 모든 폰트를 유사 폰트로 대체
    ALWAYS_IMAGE,       // 모든 비표준 폰트를 이미지로
    MANUAL_ONLY,        // 사용자 지정 매핑만 사용
}

enum class DecorationHandling {
    INCLUDE_AS_IMAGE,   // 장식 요소를 이미지로 포함
    DROP,               // 장식 요소 제거
    CONVERT_SIMPLE,     // 단순 도형(사각형, 원)만 변환
}

enum class MathConversionMode {
    EQUATION_EDITOR,    // HWPX 수식 편집기
    IMAGE,              // 이미지로 래스터화
    LATEX,              // LaTeX 소스 보존
}
```

### 4.3 각 Exporter 특성

| 포맷 | 주요 과제 | 미지원 기능 |
|------|----------|------------|
| **HWPX** | 폰트 매핑 (4단계 전략), 수식 폰트 변환, 디자인 서체 이미지 폴백 | 그라데이션, 210계열 디자인 서체 네이티브 렌더링 |
| **HTML** | CSS 스타일 생성, 반응형, Named 색상 → CSS 변수, 웹폰트 | 페이지 개념 없음 (section으로 대체) |
| **PPTX** | 페이지→슬라이드 매핑, 레이아웃 | 장문 텍스트, 복잡한 표 |
| **EPUB** | 챕터 분할, 리플로우, 메타데이터 | 고정 레이아웃 (fixed-layout EPUB으로 대체 가능) |

### 4.4 포맷별 AST 해석 차이

```
AST: pageBreak
  → HWPX: <hp:secPr><hp:pageBreak/>
  → HTML:  <div class="page-break" style="break-after:page">
  → PPTX:  새 슬라이드 시작
  → EPUB:  새 챕터 (또는 CSS page-break)

AST: table (10열 복잡한 표)
  → HWPX:  그대로 변환
  → HTML:   반응형 테이블 + 가로 스크롤
  → PPTX:  이미지로 대체 (복잡한 표는 슬라이드에 부적합)
  → EPUB:  단순화 또는 이미지 대체

AST: footnote
  → HWPX:  각주 컨트롤
  → HTML:   <a href="#fn1"> + 페이지 하단 <aside>
  → PPTX:  슬라이드 노트로 이동
  → EPUB:  EPUB 각주 (팝업)

AST: math (format: "font_based", sourceFontFamily: "NP_기본정체수식M")
  → HWPX:  ★ 수식 편집기로 변환 (mathConversion 옵션)
  → HTML:   KaTeX/MathJax 렌더링 (LaTeX 변환 필요)
  → PPTX:  이미지로 래스터화
  → EPUB:  MathML 또는 이미지

AST: textRun (font: "210 자연공원OTF", fallbackStrategy: "IMAGE_FALLBACK")
  → HWPX:  ★ 이미지로 래스터화 후 인라인 이미지 삽입
  → HTML:   웹폰트 로드 시도 → 실패 시 이미지
  → PPTX:  이미지 삽입
  → EPUB:  이미지 삽입

AST: problem (문제집 도메인 블록)
  → HWPX:  문제 구조 → 표 + 문단으로 변환
  → HTML:   시맨틱 HTML (<article class="problem">)
  → PPTX:  문제당 1슬라이드
  → EPUB:  챕터 내 섹션

AST: decoration (isDecoration: true)
  → HWPX:  decorationHandling 옵션에 따라 이미지/제거/단순변환
  → HTML:   CSS background/border로 변환 가능한 것만
  → PPTX:  이미지로 포함
  → EPUB:  대부분 제거 (리플로우 호환성)
```

---

## 5. Decoration Classifier (★ 신규)

국어 교과서의 3,200+ 시각 요소를 **장식 vs 콘텐츠**로 분류하는 파이프라인.

### 5.1 분류 알고리즘

```kotlin
class DecorationClassifier {

    /**
     * 시각 요소를 장식/콘텐츠로 분류
     *
     * 국어 교과서 기준:
     * - Polygon 753개: 대부분 장식 (말풍선 배경, 배경 도형)
     * - GraphicLine 640개: 대부분 장식 (밑줄, 구분선)
     * - Oval 183개: 대부분 장식 (번호 배경 원)
     * - Image 134개: 대부분 콘텐츠 (삽화, 사진)
     * - TextFrame 501개: 혼합 (콘텐츠 텍스트 + 장식 라벨)
     */
    fun classify(element: VisualElement): ClassificationResult {
        val signals = mutableListOf<Signal>()

        // Signal 1: 요소 타입 기본 확률
        signals += typeBasedSignal(element)

        // Signal 2: 텍스트 포함 여부 및 내용
        signals += textContentSignal(element)

        // Signal 3: 레이어 위치
        signals += layerSignal(element)

        // Signal 4: 스타일/폰트 분석
        signals += styleSignal(element)

        // Signal 5: 공간적 관계 (다른 요소와의 겹침)
        signals += spatialRelationSignal(element)

        // Signal 6: Group 소속 여부
        signals += groupSignal(element)

        return aggregate(signals)
    }

    private fun typeBasedSignal(element: VisualElement): Signal {
        return when (element) {
            is Polygon -> Signal(DECORATION, confidence = 0.7)   // 대부분 장식
            is GraphicLine -> Signal(DECORATION, confidence = 0.8)
            is Oval -> Signal(DECORATION, confidence = 0.85)     // 번호 배경
            is Rectangle -> Signal(DECORATION, confidence = 0.6) // 배경 vs 텍스트 박스 혼합
            is TextFrame -> Signal(CONTENT, confidence = 0.6)    // 대부분 콘텐츠
            is Image -> Signal(CONTENT, confidence = 0.9)        // 대부분 콘텐츠
            else -> Signal(UNKNOWN, confidence = 0.5)
        }
    }

    private fun styleSignal(element: VisualElement): Signal {
        if (element is TextFrame) {
            val fontFamily = element.primaryFontFamily
            // 디자인 서체 = 장식 가능성 높음
            if (fontFamily != null && isDesignFont(fontFamily)) {
                return Signal(DECORATION, confidence = 0.75)
            }
            // 본문 서체 = 콘텐츠 가능성 높음
            if (fontFamily != null && isBodyFont(fontFamily)) {
                return Signal(CONTENT, confidence = 0.8)
            }
        }
        return Signal(UNKNOWN, confidence = 0.5)
    }

    private fun isDesignFont(family: String): Boolean {
        return family.startsWith("210 ") ||
               family.startsWith("HU") ||
               family.startsWith("THE") ||
               family.startsWith("Rix") ||
               family.contains("POP") ||
               family.contains("손글씨") ||
               family.contains("헤움") ||
               family.contains("둘기마요")
    }
}
```

### 5.2 레이어 기반 필터링

```kotlin
/**
 * 레이어 분석으로 무시 대상 식별
 *
 * 국어 교과서 레이어 구조:
 * - 배경: 장식 (DROP)
 * - 본문: 콘텐츠 (KEEP)
 * - 하시라: 콘텐츠 (KEEP)
 * - 조판지시서: locked → 무시 (DROP)
 * - 3차 가쇄: hidden → 무시 (DROP)
 * - 수정부분표시: locked → 무시 (DROP)
 */
class LayerFilter {
    private val ignoreLayers = setOf(
        "조판지시서",
        "3차 가쇄",
        "수정부분표시",
        "가쇄 수정",
        "Guides"
    )

    fun shouldInclude(layerName: String, isVisible: Boolean, isLocked: Boolean): Boolean {
        if (layerName in ignoreLayers) return false
        if (!isVisible) return false  // hidden 레이어 무시
        return true
    }
}
```

---

## 6. converter.jar CLI 인터페이스

### 명령어

```bash
# 1단계: IDML + resolved → AST 생성 (핵심)
java -jar converter.jar parse \
  --idml /path/to/doc.idml \
  --resolved /path/to/resolved.json \
  --output /path/to/document.ast.json

# ★ 문서 유형 자동 감지 또는 수동 지정
java -jar converter.jar parse \
  --idml /path/to/doc.idml \
  --resolved /path/to/resolved.json \
  --document-type WORKBOOK_MATH \
  --output /path/to/document.ast.json

# 2단계: AST → 특정 포맷으로 내보내기
java -jar converter.jar export \
  --ast /path/to/document.ast.json \
  --format hwpx \
  --output /path/to/output.hwpx

# 한 번에 (parse + export)
java -jar converter.jar convert \
  --idml /path/to/doc.idml \
  --resolved /path/to/resolved.json \
  --format hwpx \
  --output /path/to/output.hwpx

# 분석 (매핑 확인용)
java -jar converter.jar analyze \
  --idml /path/to/doc.idml \
  --resolved /path/to/resolved.json \
  --format hwpx \
  --output /path/to/analysis.json

# 여러 포맷 동시 내보내기
java -jar converter.jar export \
  --ast /path/to/document.ast.json \
  --format hwpx,html,epub \
  --output-dir /path/to/outputs/

# ★ 포맷별 + 문서 유형별 옵션
java -jar converter.jar export \
  --ast /path/to/document.ast.json \
  --format hwpx \
  --font-fallback auto \
  --decoration-handling include-as-image \
  --math-conversion equation-editor \
  --output /path/to/output.hwpx

# 지원 포맷 목록
java -jar converter.jar formats

# AST 검증
java -jar converter.jar validate \
  --ast /path/to/document.ast.json
```

### 진행률 프로토콜 (stdout JSON lines)

```json
{"phase":"DETECT_TYPE","progress":2,"message":"문서 유형 감지 중...","detail":"WORKBOOK_MATH"}
{"phase":"PARSE_IDML","progress":5,"message":"IDML 파싱 중..."}
{"phase":"LOAD_STORIES","progress":10,"message":"Story 로딩 중...","detail":"외부 441개"}
{"phase":"PARSE_RESOLVED","progress":15,"message":"Resolved JSON 파싱 중..."}
{"phase":"RESOLVE_STYLES","progress":20,"message":"스타일 resolve 중...","detail":"18개 문단스타일"}
{"phase":"RECOGNIZE_STRUCTURE","progress":30,"message":"문제 구조 인식 중...","detail":"28개 Table → Problem"}
{"phase":"BUILD_AST","progress":40,"message":"AST 생성 중..."}
{"phase":"MAP_FONTS","progress":50,"message":"폰트 매핑 중...","detail":"42종 → SUBSTITUTE:12, MATH:28, DROP:2"}
{"phase":"CLASSIFY_DECORATIONS","progress":55,"message":"장식 요소 분류 중..."}
{"phase":"VALIDATE_AST","progress":60,"message":"AST 검증 중..."}
{"phase":"EXPORT_HWPX","progress":70,"message":"HWPX 생성 중..."}
{"phase":"DONE","progress":100,"message":"완료","output":"/path/to/output.hwpx"}
```

### 분석 결과 (analysis.json) — v2

```json
{
  "summary": {
    "documentType": "WORKBOOK_MATH",
    "documentTypeConfidence": 0.95,
    "pageCount": 10,
    "paragraphCount": 156,
    "tableCount": 28,
    "tablePattern": "UNIFORM_4x1",
    "imageCount": 0,
    "paragraphStyleCount": 18,
    "characterStyleCount": 20,
    "problemCount": 28,
    "anchoredObjectCount": 53,
    "storyStorageMode": "EXTERNAL",
    "storyCount": 442
  },
  "targetFormat": "hwpx",
  "fontAnalysis": {
    "totalFamilies": 42,
    "byStrategy": {
      "SUBSTITUTE": {
        "count": 12,
        "fonts": ["Yoon Gothic 100", "Yoon Myungjo 100"]
      },
      "MATH_CONVERT": {
        "count": 28,
        "fonts": ["NP_기본정체수식M", "NP_기본장체수식M"],
        "note": "풀수록 수학 전용 수식 폰트"
      },
      "IMAGE_FALLBACK": {
        "count": 0,
        "fonts": []
      },
      "DROP": {
        "count": 2,
        "fonts": ["DIN"]
      }
    }
  },
  "fontMappings": [
    {
      "source": "Yoon Gothic 100",
      "suggested": "윤고딕 100",
      "strategy": "SUBSTITUTE",
      "confidence": 0.95,
      "status": "AUTO"
    },
    {
      "source": "NP_기본정체수식M",
      "suggested": null,
      "strategy": "MATH_CONVERT",
      "confidence": 0.8,
      "status": "AUTO",
      "note": "수식 편집기로 자동 변환"
    }
  ],
  "styleMappings": [
    {
      "source": "본문-문제지시",
      "semanticRole": "PROBLEM_STEM",
      "suggested": "본문",
      "resolvedProps": {
        "fontFamily": "Yoon Gothic 100",
        "fontSize": 11,
        "leading": 16.5,
        "alignment": "left"
      }
    }
  ],
  "structureAnalysis": {
    "recognizerUsed": "ProblemRecognizer",
    "tablePatternsDetected": {
      "4x1": 28
    },
    "problemsExtracted": 28,
    "unrecognizedBlocks": 3
  },
  "decorationAnalysis": {
    "totalVisualElements": 98,
    "classified": {
      "content": 79,
      "decoration": 13,
      "uncertain": 6
    }
  },
  "featureSupport": {
    "supported": ["PARAGRAPH_STYLES", "TABLES", "PROBLEM_STRUCTURE", "MATH_FONT_CONVERSION"],
    "unsupported": ["SPOT_COLORS"],
    "partial": ["MATH"]
  },
  "warnings": [
    {
      "type": "MATH_FONT_COMPLEX",
      "count": 28,
      "message": "NP_ 계열 수식 폰트 28종 → 수식 편집기 변환 필요",
      "fallback": "이미지 폴백 가능"
    }
  ]
}
```

---

## 7. UX 플로우

```
┌─────────────────────────────────────────────────────────┐
│  1. 문서 선택                                             │
│  ┌───────────────────────────────────┐                   │
│  │  📁 INDD 파일 선택 (드래그 or 탐색)  │                   │
│  └──────────────┬────────────────────┘                   │
│                 ▼                                        │
│  2. 자동 추출 + ★ 문서 유형 감지                            │
│  ┌───────────────────────────────────┐                   │
│  │  InDesign headless 실행            │                   │
│  │  ├─ IDML 내보내기                  │                   │
│  │  ├─ UXP resolved JSON 추출        │                   │
│  │  ├─ ★ 문서 유형 자동 감지           │                   │
│  │  │   (WORKBOOK_MATH 감지됨)        │                   │
│  │  ├─ ★ Structure Recognizer 실행    │                   │
│  │  ├─ AST 생성 (.ast.json)          │  ← 핵심 단계      │
│  │  └─ 썸네일/PDF 프리뷰 생성         │                   │
│  └──────────────┬────────────────────┘                   │
│                 ▼                                        │
│  3. 포맷 선택 + 매핑 확인                                  │
│  ┌───────────────────────────────────┐                   │
│  │  문서 유형: 풀수록 수학 (자동 감지)   │                   │
│  │  문제 28개 인식됨 ✓                │                   │
│  │                                   │                   │
│  │  출력 포맷: [HWPX] [HTML] [PPTX] [EPUB]              │
│  │                                   │                   │
│  │  [원본 PDF]  ←→  [변환 미리보기]   │                   │
│  │                                   │                   │
│  │  ⚙ 매핑 상세                      │                   │
│  │  · 폰트: SUBSTITUTE 12종 ✓        │                   │
│  │         MATH_CONVERT 28종 ⚠       │                   │
│  │  · 스타일: 18개 매핑 완료 ✓        │                   │
│  │  · ⚠ 수식 변환 확인 필요 3건       │                   │
│  └──────────────┬────────────────────┘                   │
│                 ▼                                        │
│  4. 내보내기 실행                                         │
│  ┌───────────────────────────────────┐                   │
│  │  converter.jar export             │                   │
│  │  AST → 선택한 포맷 생성            │                   │
│  └──────────────┬────────────────────┘                   │
│                 ▼                                        │
│  5. 결과 확인                                             │
│  ┌───────────────────────────────────┐                   │
│  │  뷰어로 열기 / 웹 미리보기          │                   │
│  │  [📥 다운로드]  [📂 폴더 열기]      │                   │
│  │  [🔄 다른 포맷으로 추가 내보내기]    │  ← AST 재사용    │
│  └───────────────────────────────────┘                   │
└─────────────────────────────────────────────────────────┘
```

---

## 8. 특수문자 매핑 테이블 (4종 분석 완전 반영)

### 8.1 공백 문자 매핑

| Unicode | 설명 | A수학 | B국어 | C풀수학 | D풀물리 | AST Inline | HWPX | HTML |
|---------|------|:---:|:---:|:---:|:---:|-----------|------|------|
| U+00A0 | 줄바꿈없는 공백 | ✓ | ✓ | ✓ | ✓ | `space:nonBreaking` | `<hp:nbSpace/>` | `&nbsp;` |
| U+2002 | 반각 공백 (EN) | - | ✓ | - | - | `space:en` | `<hp:enSpace/>` | `<span class="en-sp">` |
| U+2003 | 전각 공백 (EM) | ✓ | ✓ | ✓ | ✓ | `space:em` | `<hp:emSpace/>` | `<span class="em-sp">` |
| U+2005 | 1/4 공백 | - | - | - | ✓ | `space:fourPerEm` | `<hp:qSpace/>` | `<span class="qem-sp">` |
| U+2006 | 1/6 공백 | ✓ | - | ✓ | - | `space:sixPerEm` | `<hp:sixthSpace/>` | `<span class="sem-sp">` |
| U+2009 | 가는 공백 | - | ✓ | ✓ | ✓ | `space:thin` | `<hp:thinSpace/>` | `&thinsp;` |
| U+200A | 머리카락 공백 | - | - | - | ✓ | `space:hair` | `<hp:hairSpace/>` | `<span class="hair-sp">` |

### 8.2 제어/서식 문자

| Unicode | 설명 | A | B | C | D | AST Inline | HWPX | HTML |
|---------|------|:---:|:---:|:---:|:---:|-----------|------|------|
| U+0009 | 탭 | ✓ | ✓ | ✓ | ✓ | `tab` | `<hp:tab/>` | CSS tab-stop |
| U+2028 | 줄구분자 | ✓ | ✓ | ✓ | ✓ | `lineBreak` | `<hp:lineBreak/>` | `<br>` |
| U+200C | 제로폭비결합 | - | ✓ | ✓ | ✓ | `symbol:zwnj` | 제거 | `&zwnj;` |
| U+2011 | 줄바꿈없는 하이픈 | - | - | - | ✓ | `symbol:nbHyphen` | `<hp:nbHyphen/>` | `&#8209;` |
| U+00AD | 소프트 하이픈 | - | - | - | - | `symbol:softHyphen` | `<hp:softHyphen/>` | `&shy;` |

### 8.3 구두점/기호

| Unicode | 설명 | A | B | C | D | AST Inline | HWPX | HTML |
|---------|------|:---:|:---:|:---:|:---:|-----------|------|------|
| U+2018 | 왼쪽 작은따옴표 | - | ✓ | - | ✓ | text `'` | `'` | `&lsquo;` |
| U+2019 | 오른쪽 작은따옴표 | - | ✓ | - | ✓ | text `'` | `'` | `&rsquo;` |
| U+201C | 왼쪽 큰따옴표 | - | ✓ | - | - | text `"` | `"` | `&ldquo;` |
| U+201D | 오른쪽 큰따옴표 | - | ✓ | - | - | text `"` | `"` | `&rdquo;` |
| U+2022 | 불릿 | - | ✓ | - | ✓ | `symbol:bullet` | `•` | `&bull;` |
| U+2026 | 말줄임표 | - | ✓ | - | ✓ | text `…` | `…` | `&hellip;` |
| U+2032 | 프라임 | - | - | - | ✓ | `symbol:prime` | `′` | `&prime;` |
| U+203B | ※ 참고표시 | - | - | - | ✓ | text `※` | `※` | `※` |

---

## 9. 변환 파이프라인 (v2)

```
InDesign (.indd)
    │
    ├─ [InDesign UXP] ──→ output.idml (구조 정보)
    ├─ [InDesign UXP] ──→ resolved.json (계산된 속성)
    └─ [InDesign UXP] ──→ preview.pdf (원본 대조용)

converter.jar parse
    │
    ├─ 1. 문서 유형 감지 (★ DocumentTypeDetector)
    ├─ 2. IDML 파싱 (ZIP → XML → Stories, Spreads, Styles)
    │     └─ Story 로딩: 인라인 + 외부 이중 경로 (★ StoryLoader)
    ├─ 3. Resolved JSON 파싱
    ├─ 4. 스타일 Resolve (상속 체인 → 최종값)
    │     └─ 시맨틱 역할 매핑 (★ StyleMapper.resolveSemanticRole)
    ├─ 5. 머지: IDML 구조 + resolved 속성 → Raw IDML AST
    ├─ 6. 구조 인식 (★ Structure Recognizer)
    │     ├─ ProblemRecognizer (문제집 Table → Problem)
    │     └─ TextbookRecognizer (스타일 시맨틱 → 교육과정 구조)
    ├─ 7. 장식 분류 (★ DecorationClassifier) — 교과서만
    ├─ 8. 폰트 매핑 + Fallback 전략 결정
    ├─ 9. Canonical AST 트리 구성
    ├─ 10. 리소스 추출 (이미지 → resources/)
    │      └─ 디자인 서체 이미지 폴백 생성 (★ 국어 교과서)
    └─ 11. AST 검증 + 직렬화 → document.ast.json

converter.jar export --format hwpx
    │
    ├─ 1. AST 로드 + 문서 프로파일 적용
    ├─ 2. 폰트 매핑 적용 (4단계 Fallback)
    │     ├─ SUBSTITUTE: 유사 폰트 치환
    │     ├─ MATH_CONVERT: 수식 편집기 변환
    │     ├─ IMAGE_FALLBACK: 이미지 래스터화
    │     └─ DROP: 제거
    ├─ 3. 스타일 매핑 적용
    ├─ 4. 특수문자 매핑 (8종 공백 + 구두점)
    ├─ 5. 도메인 블록 변환
    │     ├─ Problem → 표 + 문단
    │     └─ Activity/TextPassage → HWPX 구조
    ├─ 6. hwpxlib으로 문서 조립
    └─ 7. HWPX 패키징 (ZIP)
```

---

## 10. UXP 스크립트 (InDesign 추출)

### 플러그인 구조

```
uxp-scripts/
├── manifest.json
├── index.html
└── src/
    ├── main.js          # 엔트리포인트
    ├── collector.js     # resolved 속성 수집 핵심
    ├── runs.js          # character run 병합
    └── utils.js         # 컬러, 스타일 체인 헬퍼
```

### 수집 대상 resolved 속성

**문단 수준:**
- 적용된 스타일 (name + basedOn chain)
- 폰트 (family, style, size)
- 행간 (fixed / auto + percentage)
- 정렬 (left, center, right, justify)
- 여백 (spaceBefore, spaceAfter, firstLineIndent, leftIndent, rightIndent)
- 글머리/번호 매기기
- 탭 정지
- Keep 옵션 (keepWithNext, keepAllLinesTogether)
- 하이프네이션

**글자 수준 (Run):**
- 폰트 (family, style, size)
- 색상 (CMYK / RGB / Spot)
- 밑줄, 취소선
- 위첨자/아래첨자 (baselineShift, position)
- 적용된 글자 스타일
- 자간 (tracking, kerning)
- 장평 (horizontalScale, verticalScale)
- 루비 (한국어/일본어 교육 콘텐츠용)

**조판 결과:**
- 실제 줄 수 (lineCount)
- 각 줄의 텍스트, 글자 수, baseline 위치
- 오버플로 여부

**표:**
- 행/열 수, 셀 병합 (rowSpan, columnSpan)
- 셀 크기, 여백, 테두리
- 셀 내 문단 (재귀적으로 resolved 수집)

### 성능 최적화

```javascript
// ❌ 느림 — 글자마다 DOM 접근
for (const ch of para.characters.everyItem().getElements()) { ... }

// ✅ 빠름 — textStyleRanges 사용 (이미 run으로 묶여있음)
para.textStyleRanges.everyItem().getElements().map(range => ({
    text: range.contents,
    resolved: { /* ... */ }
}));
```

---

## 11. InDesign 프로세스 관리 (Rust)

### OS별 InDesign 제어

| OS | 실행 방법 | 백그라운드 |
|----|----------|-----------|
| macOS | `open -g -a "InDesign"` + osascript | `-g` 플래그 |
| Windows | COM / VBScript / 직접 실행 | `-no-splash` |

### Headless 제약 및 대안

| 전략 | 설명 | 안정성 |
|------|------|--------|
| InDesign Server | 유료 라이선스, 완전 headless | ★★★ |
| ExtendScript + osascript (macOS) | AppleScript로 스크립트 전달 | ★★☆ |
| COM/VBScript (Windows) | InDesign COM 인터페이스 | ★★☆ |
| Desktop 백그라운드 실행 | UI가 잠깐 뜰 수 있음 | ★☆☆ |

---

## 12. Rust — converter.jar Subprocess 관리

### 통신 방식

```
Tauri (Rust)
    │
    ├─ stdin:  (사용 안 함)
    ├─ stdout: JSON lines 진행률 수신 → React로 이벤트 emit
    ├─ stderr: 에러/로그 수집
    └─ exit code: 성공(0) / 실패(1)
```

### Java 탐색 우선순위

1. 번들 JRE (`resources/jre/bin/java`)
2. JAVA_HOME 환경변수
3. PATH의 java

---

## 13. 통신 시퀀스

```
사용자          React            Rust              InDesign         converter.jar
  │                │               │                  │                 │
  │  파일 선택     │               │                  │                 │
  ├───────────────>│  invoke        │                  │                 │
  │                │  extract_indd │                  │                 │
  │                ├──────────────>│  프로세스 실행     │                 │
  │                │               ├─────────────────>│                 │
  │                │               │                  │ UXP: IDML+JSON  │
  │                │  진행률 이벤트  │  .done 감지       │                 │
  │                │<──────────────│<─────────────────│                 │
  │                │               │                  │                 │
  │                │  invoke        │                  │                 │
  │                │  parse_ast    │                  │                 │
  │                ├──────────────>│  java -jar parse  │                 │
  │                │               ├────────────────────────────────────>│
  │                │               │                  │  ★ detect type  │
  │                │               │                  │  ★ load stories │
  │                │               │                  │  ★ recognize    │
  │                │               │                  │  ★ classify     │
  │  AST 생성 완료  │  ast.json    │                  │  IDML+JSON→AST  │
  │<───────────────│<──────────────│<────────────────────────────────────│
  │                │               │                  │                 │
  │  포맷 선택     │  invoke        │                  │                 │
  │  매핑 확인     │  analyze       │                  │                 │
  │                ├──────────────>│  java -jar analyze│                 │
  │                │               ├────────────────────────────────────>│
  │  매핑 확인 UI  │  analysis.json│                  │                 │
  │<───────────────│<──────────────│<────────────────────────────────────│
  │                │               │                  │                 │
  │  내보내기 시작  │  invoke        │                  │                 │
  ├───────────────>│  export        │                  │                 │
  │                ├──────────────>│  java -jar export │                 │
  │                │               ├────────────────────────────────────>│
  │                │  진행률 이벤트  │  stdout 파싱      │  AST→HWPX      │
  │                │<──────────────│<────────────────────────────────────│
  │  결과 보기     │               │                  │                 │
  │<───────────────│               │                  │                 │
  │                │               │                  │                 │
  │  추가 내보내기  │  invoke        │                  │                 │
  │  (HTML)       │  export        │                  │                 │
  ├───────────────>├──────────────>│  java -jar export │                 │
  │                │               ├────────────────────────────────────>│
  │                │               │                  │  AST→HTML       │
  │  즉시 완료     │               │  (InDesign 불필요) │  (AST 재사용)   │
  │<───────────────│<──────────────│<────────────────────────────────────│
```

---

## 14. 주요 리스크 및 대응 (v2 업데이트)

| 리스크 | 영향도 | 대응 방안 |
|--------|:------:|----------|
| AST 스키마가 특정 포맷에 편향 | 높음 | 포맷 중립 용어 사용, 다수 포맷 동시 검증하며 설계 |
| **★ 교과서/문제집 AST 분기 복잡도** | 높음 | Base + 도메인 확장 패턴, Structure Recognizer 분리 |
| **★ 국어교과서 장식 분류 정확도** | 높음 | DecorationClassifier 점진적 개선, 수동 보정 UI |
| **★ 수식 폰트 → 수식 편집기 변환** | 높음 | NP_/BT수식 폰트별 매핑 테이블, 이미지 폴백 보장 |
| **★ 디자인 서체 60+ 이미지 폴백** | 높음 | InDesign UXP에서 사전 래스터화, 배치 처리 |
| InDesign headless 불안정 | 높음 | osascript/COM 제어, 타임아웃+재시도 |
| **★ 인라인 Story 파싱 (수학교과서)** | 중간 | StoryLoader 이중 경로, Phase 2에서 구현 |
| 포맷별 기능 격차 | 중간 | featureSupport 사전 분석, fallback 전략 명시 |
| AST 스키마 버전 관리 | 중간 | 시맨틱 버전, 하위 호환성 보장, 마이그레이션 스크립트 |
| 대용량 문서 성능 | 중간 | textStyleRanges 최적화, 스트리밍 파싱, 진행률 표시 |
| hwpxlib 미지원 기능 | 중간 | 직접 XML 조작으로 보완 |
| OS별 차이 (macOS/Windows) | 중간 | `#[cfg(target_os)]` 분기, CI 통합 테스트 |
| **★ 읽기 순서 추론 (자유 레이아웃)** | 중간 | 공간 좌표 기반 휴리스틱, Phase 3에서 고도화 |

---

## 15. 개발 로드맵 (4종 분석 기반 재설계)

### Phase 0: 풀수록 수학 — Quick Win + 아키텍처 검증 (1~2주)

> **목표**: 가장 규칙적인 문서로 전체 파이프라인 검증

```
난이도: ★★☆   비즈니스 가치: ★★★★★
핵심 지표: Table 28개(4×1), 스타일 18개, 폰트 42종(NP_ 28종)
```

- [ ] AST v2 스키마 확정 + JSON Schema 작성
- [ ] AST Kotlin 모델 (sealed class) — **Base + Problem 확장**
- [ ] IDML 파서 기본 구현 (**외부 Story 로딩**)
- [ ] StyleResolver (상속 체인 → 최종값) — **18개 스타일로 검증**
- [ ] Resolved JSON 파서
- [ ] DocumentTypeDetector (WORKBOOK_MATH 자동 감지)
- [ ] ProblemRecognizer: **4×1 Table → ProblemNode**
- [ ] FontMapper 프레임워크 + **NP_ 28종 수식 폰트 매핑**
- [ ] AstBuilder (Raw → Canonical)
- [ ] AST 직렬화/역직렬화 (JSON)
- [ ] CLI parse 커맨드
- [ ] HwpxExporter 기본 구현 (hwpxlib)
- [ ] CLI export 커맨드
- [ ] **스냅샷 테스트**: 풀수록 수학 → AST → HWPX 왕복 검증

### Phase 1: 풀수록 물리 확장 (1~2주)

> **목표**: 다양한 Table 패턴 + Nested Table + Anchored Figure

```
난이도: ★★★   비즈니스 가치: ★★★★☆
핵심 지표: Table 204개(11×2/10×2/7×2), 폰트 26종(BT수식H 10종), Anchored 649개
```

- [ ] ProblemRecognizer 확장: **11×2, 10×2, 7×2 Table 패턴**
- [ ] Nested Table 처리
- [ ] **BT수식H 10종 폰트 매핑** (NP_ 매핑 프레임워크 재사용)
- [ ] Anchored Figure (그래프, 도표, <보기> 박스)
- [ ] Problem.source (출처: 년도/시험명)
- [ ] Problem.box (<보기> 구조)
- [ ] Problem.choices (선지 구조)
- [ ] 특수문자 추가 매핑 (프라임, 참고표시, 줄바꿈없는 하이픈)

### Phase 2: 수학 교과서 (2~3주)

> **목표**: 인라인 Story + 교과서 AST 구조 + 자유 레이아웃 진입

```
난이도: ★★★★   비즈니스 가치: ★★★☆☆
핵심 지표: 인라인 Story 98%, Table 0개(가짜 Table), 폰트 36종(BT수식M 11종)
```

- [ ] **StoryLoader 인라인 경로 구현** (designmap.xml 내 Story 파싱)
- [ ] TextbookRecognizer 기본 구현 (8단계 스타일 계층)
- [ ] AST Kotlin 모델 확장 — **Textbook 도메인 블록** (UnitOpener, Activity 등)
- [ ] "가짜 Table" 감지 (시각 도형 조합으로 만든 표)
- [ ] BT수식M 11종 폰트 매핑
- [ ] DecorationClassifier 기본 구현
- [ ] 레이아웃 메타데이터 (spatialPosition, readingOrder)
- [ ] HwpxExporter 교과서 구조 지원

### Phase 3: 국어 교과서 — 최종 보스 (3~4주)

> **목표**: 60+ 폰트 + 3,200+ 시각 요소 + 비정형 콘텐츠

```
난이도: ★★★★★   비즈니스 가치: ★★★☆☆
핵심 지표: 폰트 60종(디자인 30+), 시각 요소 3,200+, 스타일 102개, 이미지 134개
```

- [ ] **102개 스타일 → 시맨틱 역할 매핑** (수진 정리 27개 포함)
- [ ] TextbookRecognizer 국어 확장 (10단계 + 12종 섹션)
- [ ] TextPassage 노드 (지문 전용 서체 ViMaru OTF 처리)
- [ ] **30+ 디자인 서체 → 이미지 폴백 파이프라인**
  - 210계열 15종, HU계열 7종, THE계열 5종, Rix계열 3종, 손글씨 등
  - InDesign UXP에서 사전 래스터화 or 외부 렌더러
- [ ] **DecorationClassifier 고도화**
  - Polygon 753개 분류 (말풍선 배경 vs 장식)
  - GraphicLine 640개 분류 (밑줄 vs 장식선)
  - Oval 183개 분류 (번호 배경 vs 장식)
- [ ] **134개 이미지 처리** (삽화, 사진, 캐릭터)
- [ ] Named Color 매핑 (단원별 테마 + UI 컴포넌트 색상)
- [ ] **읽기 순서 추론** (자유 레이아웃 → 선형 순서)
- [ ] EH분수/상부자 수식 폰트 매핑
- [ ] 고어체 (DX새명조고어) 처리
- [ ] 수정 이력 레이어 무시 (3차 가쇄, 수정부분표시)
- [ ] Anchored 객체 251개 처리 (말풍선, 삽화, 캐릭터, 아이콘)

### Phase 4: Tauri Desktop App (2~3주)

- [ ] 프로젝트 세팅 (Tauri + React + Vite)
- [ ] InDesign 프로세스 관리 (Rust)
- [ ] UXP 스크립트 (자동 추출)
- [ ] converter.jar subprocess 관리 (Rust)
- [ ] UI 플로우 (5단계) + 문서 유형 표시
- [ ] 매핑 확인 UI (폰트 Fallback 전략별 표시)
- [ ] converter.jar 번들링

### Phase 5: 추가 Exporter (3~4주)

- [ ] HTML Exporter (반응형, 교육 콘텐츠 최적화, Named 색상 → CSS 변수)
- [ ] EPUB Exporter (EPUB3, 교과서 챕터 자동 분할)
- [ ] PPTX Exporter (Apache POI, 문제당 슬라이드)
- [ ] 포맷 선택 UI + 동시 내보내기

### Phase 6: 고도화

- [ ] 배치 변환 (여러 INDD 일괄 처리)
- [ ] 매핑 프리셋 저장/재사용 (문서 유형별)
- [ ] AST diff (버전 간 비교)
- [ ] GraalVM native-image (JRE 번들 제거)
- [ ] 추가 입력 포맷 (HWPX → AST, DOCX → AST)

### 로드맵 요약

| Phase | 대상 | 기간 | 난이도 | 비즈니스 가치 | 핵심 검증 |
|:-----:|------|:----:|:-----:|:-----------:|-----------|
| 0 | 풀수록 수학 | 1~2주 | ★★☆ | ★★★★★ | 파이프라인 전체, ProblemRecognizer |
| 1 | 풀수록 물리 | 1~2주 | ★★★ | ★★★★☆ | 다양한 Table, Nested, Anchored |
| 2 | 수학 교과서 | 2~3주 | ★★★★ | ★★★☆☆ | 인라인 Story, TextbookRecognizer |
| 3 | 국어 교과서 | 3~4주 | ★★★★★ | ★★★☆☆ | 디자인 폰트, 장식 분류, 읽기 순서 |
| 4 | Desktop App | 2~3주 | ★★★ | ★★★★☆ | Tauri + UXP 통합 |
| 5 | 추가 Exporter | 3~4주 | ★★★ | ★★★☆☆ | HTML/EPUB/PPTX |
| 6 | 고도화 | 지속 | ★★★ | ★★☆☆☆ | 배치, 프리셋, 추가 입력 |

---

## 16. 4종 문서 복잡도 매트릭스 (참조)

| 축 | A 수학교과서 | B 국어교과서 | C 풀수록수학 | D 풀수록물리 |
|---|:---:|:---:|:---:|:---:|
| 레이아웃 복잡도 | ★★★★☆ | ★★★★★ | ★☆☆☆☆ | ★★☆☆☆ |
| 시각 요소 밀도 | ★★★★☆ | ★★★★★ | ★☆☆☆☆ | ★☆☆☆☆ |
| 폰트 다양성 | ★★★☆☆ | ★★★★★ | ★★☆☆☆ | ★★☆☆☆ |
| 수식 복잡도 | ★★★★☆ | ★☆☆☆☆ | ★★★★★ | ★★★☆☆ |
| Table 구조화 | ☆☆☆☆☆ | ★★☆☆☆ | ★★★★★ | ★★★★☆ |
| 색상 복잡도 | ★★★★☆ | ★★★★★ | ★☆☆☆☆ | ★☆☆☆☆ |
| 스타일 규칙성 | ★★★★☆ | ★★☆☆☆ | ★★★★★ | ★★★★☆ |
| 이미지 처리 | ★★★☆☆ | ★★★★★ | ☆☆☆☆☆ | ☆☆☆☆☆ |
| IDML만으로 추출 | ★★☆☆☆ | ★★☆☆☆ | ★★★★★ | ★★★★★ |
| **변환 난이도** | **★★★★☆** | **★★★★★** | **★★☆☆☆** | **★★★☆☆** |

---

## 17. 향후 확장: 입력 포맷 추가

AST를 중심으로 입력 파서를 추가하면 N:M 변환이 가능:

```
입력 (파서)                    출력 (Exporter)
─────────                    ──────────────
InDesign (.indd) ──┐         ┌──→ HWPX
HWPX (.hwpx)    ──┤         ├──→ HTML
DOCX (.docx)    ──┼── AST ──┼──→ PPTX
Markdown (.md)  ──┤         ├──→ EPUB
HTML (.html)    ──┘         └──→ PDF (via headless)
```

---

## 참고 자료

- [hwpxlib GitHub](https://github.com/neolord0/hwpxlib) — HWPX 문서 생성 Java 라이브러리
- [한컴테크 HWPX 포맷](https://tech.hancom.com/hwpxformat/) — HWPX 포맷 구조
- [InDesign UXP API](https://developer.adobe.com/indesign/uxp/) — UXP 플러그인 개발
- [Tauri v2 Docs](https://v2.tauri.app/) — Tauri 데스크톱 앱 프레임워크
- [Apache POI](https://poi.apache.org/) — Java PPTX/DOCX 생성
- [EPUB3 Spec](https://www.w3.org/TR/epub-33/) — EPUB3 표준
- [Indiscripts InDesign Special Characters](https://indiscripts.com/blog/public/data/idcs4-special-characters/en_InDesignCS4SpecialChars.pdf)