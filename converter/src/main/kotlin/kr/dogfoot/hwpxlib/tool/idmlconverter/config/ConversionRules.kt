package kr.dogfoot.hwpxlib.tool.idmlconverter.config

import kr.dogfoot.hwpxlib.tool.idmlconverter.rule.HwpxRuleRegistry

/**
 * 교과서 서식 변환 규칙 정의 (SPEC-031).
 *
 * AI Agent 및 담당자가 수정하는 유일한 파일.
 * IDMLToHwpxConverter 클래스 로딩 시 1회 호출된다.
 *
 * 규칙 작성 원칙:
 * - 스타일 이름은 IDML Styles.xml의 Self= 속성값 그대로 (ParagraphStyle/ 또는 CharacterStyle/ 접두사 포함)
 * - 폰트명은 font-mapping.json의 hwpxFontMetrics에 등록된 이름만 사용
 * - fontSizePt: pt 단위 소수 / lineSpacingPct: % 정수 / leftIndentMm, spaceBeforeMm: mm 소수
 */
fun loadConversionRules() {
    val r = HwpxRuleRegistry.instance

    // ── 단락 스타일 규칙 ─────────────────────────────────────────
    // 아래는 샘플 규칙입니다. 실제 교과서 스타일에 맞게 추가/수정하세요.

    r.paragraphRule("ParagraphStyle/표빈공간") {
        convert {
            lineSpacingPct = 100
            spaceBeforeMm = 0.0
        }
    }

    r.paragraphRule("ParagraphStyle/표빈공간(가운데)") {
        convert {
            lineSpacingPct = 100
            spaceBeforeMm = 0.0
        }
    }

    r.paragraphRule("ParagraphStyle/표빈공간(우측)") {
        convert {
            lineSpacingPct = 100
            spaceBeforeMm = 0.0
        }
    }

    // 학습활동 손글씨(빈칸 채우기 답안) 단락은 IDML 루트 [No paragraph style]=FullyJustified를
    // 상속해 한글에서 양쪽맞춤(justify)으로 깨진다 → 디자인 의도대로 왼쪽 정렬 강제.
    // (중앙 변형은 명시적 CenterJustify라 영향 없음)
    r.paragraphRule("ParagraphStyle/03_학습활동_손글씨") {
        convert { alignment = "left" }
    }
    r.paragraphRule("ParagraphStyle/03_학습활동_손글씨(들여쓰기)") {
        convert { alignment = "left" }
    }
    r.paragraphRule("ParagraphStyle/0_도표_예시손글씨") {
        convert { alignment = "left" }
    }

    // ── 키워드 폰트 폴백 규칙 ────────────────────────────────────
    // FontMapper.keywordMapping() 대체. 등록 순서 우선 (첫 매치).
    // keyword/keyword2 는 소문자. isSerif=true → defaultSerifKo, false → defaultSansKo.
    // koFont 직접 지정 시 config 기본값 무시.

    r.keywordFontRule("윤고딕")       { isSerif = false }
    r.keywordFontRule("윤명조")       { isSerif = true }
    r.keywordFontRule("나눔고딕")     { isSerif = false }
    r.keywordFontRule("nanum gothic") { isSerif = false }
    r.keywordFontRule("나눔명조")     { isSerif = true }
    r.keywordFontRule("nanum myeongjo") { isSerif = true }
    r.keywordFontRule("나눔스퀘어")   { isSerif = false }
    r.keywordFontRule("nanumsquare")  { isSerif = false }
    r.keywordFontRule("본고딕")       { isSerif = false }
    r.keywordFontRule("noto sans")    { isSerif = false }
    r.keywordFontRule("본명조")       { isSerif = true }
    r.keywordFontRule("noto serif")   { isSerif = true }
    r.keywordFontRule("맑은")        { keyword2 = "고딕"; koFont = "맑은 고딕" }
    r.keywordFontRule("함초롬")      { keyword2 = "바탕"; koFont = "함초롬바탕" }
    r.keywordFontRule("함초롬")      { keyword2 = "돋움"; koFont = "함초롬돋움" }

    // ── 폰트 기본값 ──────────────────────────────────────────────
    // 키워드/카테고리 폴백 시 사용하는 세리프/산세리프 기본 폰트.
    // conversion-config.json의 fontMapping.defaultSerifKo/SansKo 대체.

    r.fontDefaults {
        defaultSerifKo = "함초롬바탕"
        defaultSerifEn = "Times New Roman"
        defaultSansKo  = "함초롬돋움"
        defaultSansEn  = "Arial"
    }

    // ── 폰트 매핑 규칙 ──────────────────────────────────────────
    // Exact InDesign font -> HWPX font mappings live only in font-mapping.json.
    // Keep this Kotlin DSL for paragraph/style rules, font defaults, and keyword fallbacks.

    // ── 문자 스타일 규칙 ─────────────────────────────────────────
    // 샘플: 특정 CharacterStyle에 폰트/크기 강제 지정

    // r.characterRule("CharacterStyle/#대단원 제목") {
    //     koFont = "윤고딕"
    //     fontSizePt = 24.0
    // }
}
