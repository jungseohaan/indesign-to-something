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
 * - 한컴바탕은 절대 사용 금지
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

    // ── 문자 스타일 규칙 ─────────────────────────────────────────
    // 샘플: 특정 CharacterStyle에 폰트/크기 강제 지정

    // r.characterRule("CharacterStyle/#대단원 제목") {
    //     koFont = "윤고딕"
    //     fontSizePt = 24.0
    // }
}
