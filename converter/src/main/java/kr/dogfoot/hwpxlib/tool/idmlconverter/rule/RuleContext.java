package kr.dogfoot.hwpxlib.tool.idmlconverter.rule;

/**
 * 변환 규칙 엔진용 DTO (SPEC-031).
 *
 * CharPrFactory / ParaPrFactory가 채워서 HwpxRuleRegistry에 넘기고,
 * Kotlin 규칙이 target 필드를 직접 Mutate한다.
 */
public class RuleContext {

    // ── Source (Read-Only) ─────────────────────────────────────────
    /** IDML ParagraphStyle Self 값. 예: "ParagraphStyle/대단원 설명" */
    public final String paragraphStyleRef;
    /** IDML CharacterStyle Self 값. 예: "CharacterStyle/#대단원 제목" */
    public final String characterStyleRef;
    /** 해당 런의 텍스트 내용 (빈 단락이면 "") */
    public final String textContent;

    // ── Target (Kotlin Rule Engine이 Mutate) ───────────────────────
    /** HWPX 한글/기본 폰트 패밀리 */
    public String targetKoFont = null;
    /** HWPX 영문 폰트 패밀리 */
    public String targetEnFont = null;
    /** 폰트 크기 (pt) */
    public Double targetFontSizePt = null;
    /** 줄간격 (%, 예: 160) */
    public Integer targetLineSpacingPct = null;
    /** 자간 (‰, HWPX 단위 — 100 = 10%) */
    public Short targetLetterSpacing = null;
    /** 왼쪽 들여쓰기 (mm) */
    public Double targetLeftIndentMm = null;
    /** 단락 앞 여백 (mm) */
    public Double targetSpaceBeforeMm = null;

    public RuleContext(String paragraphStyleRef, String characterStyleRef, String textContent) {
        this.paragraphStyleRef = paragraphStyleRef;
        this.characterStyleRef = characterStyleRef;
        this.textContent = textContent != null ? textContent : "";
    }

    /** target 필드 중 하나라도 non-null이면 true (CharPr override 필요 여부 판별용) */
    public boolean hasCharOverride() {
        return targetKoFont != null || targetEnFont != null
                || targetFontSizePt != null || targetLetterSpacing != null;
    }

    /** target 필드 중 단락 관련이 하나라도 non-null이면 true */
    public boolean hasParaOverride() {
        return targetLineSpacingPct != null
                || targetLeftIndentMm != null
                || targetSpaceBeforeMm != null;
    }
}
