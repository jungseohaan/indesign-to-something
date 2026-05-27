package kr.dogfoot.hwpxlib.tool.idmlconverter.rule

// ── DSL 빌더 ─────────────────────────────────────────────────────

class ParagraphRuleBuilder(val styleRef: String) {
    var koFont: String? = null
    var enFont: String? = null
    var fontSizePt: Double? = null
    var lineSpacingPct: Int? = null
    /** 자간 (‰, HWPX 단위 — 예: -30 = -3%) */
    var letterSpacing: Short? = null
    var leftIndentMm: Double? = null
    var spaceBeforeMm: Double? = null

    fun convert(block: ParagraphRuleBuilder.() -> Unit) = this.block()
}

class CharacterRuleBuilder(val styleRef: String) {
    var koFont: String? = null
    var enFont: String? = null
    var fontSizePt: Double? = null
    /** 자간 (‰, HWPX 단위 — 예: -30 = -3%) */
    var letterSpacing: Short? = null
}

// ── 레지스트리 ────────────────────────────────────────────────────

/**
 * HWPX 변환 규칙 싱글턴 레지스트리 (SPEC-031).
 *
 * ConversionRules.kt에서 규칙을 등록하고,
 * Java의 CharPrFactory / ParaPrFactory에서 @JvmStatic 메서드를 통해 규칙을 적용한다.
 */
class HwpxRuleRegistry private constructor() {

    private val paraRules = mutableMapOf<String, ParagraphRuleBuilder>()
    private val charRules = mutableMapOf<String, CharacterRuleBuilder>()

    companion object {
        @JvmStatic
        val instance = HwpxRuleRegistry()

        /** Java의 ParaPrFactory에서 호출 */
        @JvmStatic
        fun applyParaRule(ctx: RuleContext) = instance.applyPara(ctx)

        /** Java의 CharPrFactory에서 호출 */
        @JvmStatic
        fun applyCharRule(ctx: RuleContext) = instance.applyChar(ctx)

        /** 단락 스타일에 대한 DSL 규칙이 존재하는지 확인 (HwpxParagraphBuilder용) */
        @JvmStatic
        fun hasParaRule(paragraphStyleRef: String?): Boolean {
            if (paragraphStyleRef == null) return false
            return instance.paraRules.containsKey(paragraphStyleRef)
        }

        /** 문자 스타일에 대한 DSL 규칙이 존재하는지 확인 (CharPrFactory용) */
        @JvmStatic
        fun hasCharRule(characterStyleRef: String?): Boolean {
            if (characterStyleRef == null) return false
            return instance.charRules.containsKey(characterStyleRef)
        }
    }

    fun paragraphRule(styleRef: String, init: ParagraphRuleBuilder.() -> Unit) {
        paraRules[styleRef] = ParagraphRuleBuilder(styleRef).apply(init)
    }

    fun characterRule(styleRef: String, init: CharacterRuleBuilder.() -> Unit) {
        charRules[styleRef] = CharacterRuleBuilder(styleRef).apply(init)
    }

    private fun applyPara(ctx: RuleContext) {
        paraRules[ctx.paragraphStyleRef]?.let { r ->
            r.koFont?.let           { ctx.targetKoFont = it }
            r.enFont?.let           { ctx.targetEnFont = it }
            r.fontSizePt?.let       { ctx.targetFontSizePt = it }
            r.lineSpacingPct?.let   { ctx.targetLineSpacingPct = it }
            r.letterSpacing?.let    { ctx.targetLetterSpacing = it }
            r.leftIndentMm?.let     { ctx.targetLeftIndentMm = it }
            r.spaceBeforeMm?.let    { ctx.targetSpaceBeforeMm = it }
        }
    }

    private fun applyChar(ctx: RuleContext) {
        charRules[ctx.characterStyleRef]?.let { r ->
            r.koFont?.let        { ctx.targetKoFont = it }
            r.enFont?.let        { ctx.targetEnFont = it }
            r.fontSizePt?.let    { ctx.targetFontSizePt = it }
            r.letterSpacing?.let { ctx.targetLetterSpacing = it }
        }
    }
}
