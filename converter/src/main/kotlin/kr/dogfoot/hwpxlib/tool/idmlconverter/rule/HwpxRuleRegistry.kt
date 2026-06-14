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
    /** 가로 정렬 강제: "left" / "center" / "right" / "justify" (IDML 상속값 무시) */
    var alignment: String? = null

    fun convert(block: ParagraphRuleBuilder.() -> Unit) = this.block()
}

class CharacterRuleBuilder(val styleRef: String) {
    var koFont: String? = null
    var enFont: String? = null
    var fontSizePt: Double? = null
    /** 자간 (‰, HWPX 단위 — 예: -30 = -3%) */
    var letterSpacing: Short? = null
}

class FontRuleBuilder(val indesignFontName: String) {
    var koFont: String? = null
    var enFont: String? = null
    /** 자간 보정 (%, FontMapper.MappingResult.spacingAdjustPercent) */
    var spacing: Int = 0
    /** 크기 보정 (%, FontMapper.MappingResult.scaleAdjust) */
    var scaleAdjust: Int = 0
    /** 장평 보정 (FontMapper.MappingResult.ratio) */
    var ratio: Double = 1.0
}

/**
 * 폰트명 키워드 기반 폴백 규칙 (FontMapper.keywordMapping() 대체).
 * keyword (필수) + keyword2 (선택, AND 조건) 모두 소문자로 지정.
 * isSerif=true → configSerifKo, false → configSansKo 사용.
 * koFont를 직접 지정하면 config 기본값 무시.
 */
class KeywordFontRuleBuilder(val keyword: String) {
    var keyword2: String? = null
    var koFont: String? = null
    var enFont: String? = null
    var isSerif: Boolean = false
}

class FontDefaultsBuilder {
    /** 세리프 계열 기본 한글 폰트 (명조/바탕 계열 폴백) */
    var defaultSerifKo: String = "함초롬바탕"
    /** 세리프 계열 기본 영문 폰트 */
    var defaultSerifEn: String = "Times New Roman"
    /** 산세리프 계열 기본 한글 폰트 (고딕/돋움 계열 폴백) */
    var defaultSansKo: String = "함초롬돋움"
    /** 산세리프 계열 기본 영문 폰트 */
    var defaultSansEn: String = "Arial"
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
    private val fontRules = mutableMapOf<String, FontRuleBuilder>()
    private val keywordFontRules = mutableListOf<KeywordFontRuleBuilder>()
    private var fontDefaults = FontDefaultsBuilder()

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
            return instance.paraRules.containsKey(normalizeParaRef(paragraphStyleRef))
        }

        /** 문자 스타일에 대한 DSL 규칙이 존재하는지 확인 (CharPrFactory용) */
        @JvmStatic
        fun hasCharRule(characterStyleRef: String?): Boolean {
            if (characterStyleRef == null) return false
            return instance.charRules.containsKey(normalizeCharRef(characterStyleRef))
        }

        /**
         * InDesign 폰트명에 대한 DSL fontRule 반환 (FontMapper Tier 0).
         * null이면 DSL 규칙 없음 → JSON/키워드 폴백 사용.
         */
        @JvmStatic
        fun applyFontRule(idmlFontName: String?): FontRuleBuilder? {
            if (idmlFontName == null) return null
            return instance.fontRules[idmlFontName]
        }

        /** DSL fontDefaults 반환 (FontMapper의 configSerif/Sans 초기화용) */
        @JvmStatic
        fun getFontDefaults(): FontDefaultsBuilder = instance.fontDefaults

        /**
         * 소문자 폰트명으로 keywordFontRule 매칭 (FontMapper.keywordMapping() 대체).
         * 등록 순서 우선, 첫 매치 반환. null이면 매칭 없음.
         */
        @JvmStatic
        fun applyKeywordFontRule(lowerFontName: String?): KeywordFontRuleBuilder? {
            if (lowerFontName == null) return null
            return instance.keywordFontRules.firstOrNull { rule ->
                lowerFontName.contains(rule.keyword) &&
                        (rule.keyword2 == null || lowerFontName.contains(rule.keyword2!!))
            }
        }

        /** "ParagraphStyle/이름" → "이름", 또는 이미 짧은 형식이면 그대로 */
        private fun normalizeParaRef(ref: String): String =
            if (ref.startsWith("ParagraphStyle/")) ref.removePrefix("ParagraphStyle/") else ref

        /** "CharacterStyle/이름" → "이름", 또는 이미 짧은 형식이면 그대로 */
        private fun normalizeCharRef(ref: String): String =
            if (ref.startsWith("CharacterStyle/")) ref.removePrefix("CharacterStyle/") else ref
    }

    fun paragraphRule(styleRef: String, init: ParagraphRuleBuilder.() -> Unit) {
        // 접두사 있는 형태와 없는 형태 모두 처리 (AST는 짧은 형식, IDML은 긴 형식)
        val key = Companion.normalizeParaRef(styleRef)
        paraRules[key] = ParagraphRuleBuilder(styleRef).apply(init)
    }

    fun characterRule(styleRef: String, init: CharacterRuleBuilder.() -> Unit) {
        val key = Companion.normalizeCharRef(styleRef)
        charRules[key] = CharacterRuleBuilder(styleRef).apply(init)
    }

    fun fontRule(indesignFontName: String, init: FontRuleBuilder.() -> Unit) {
        fontRules[indesignFontName] = FontRuleBuilder(indesignFontName).apply(init)
    }

    fun keywordFontRule(keyword: String, init: KeywordFontRuleBuilder.() -> Unit) {
        keywordFontRules += KeywordFontRuleBuilder(keyword).apply(init)
    }

    fun fontDefaults(init: FontDefaultsBuilder.() -> Unit) {
        fontDefaults = FontDefaultsBuilder().apply(init)
    }

    private fun applyPara(ctx: RuleContext) {
        val key = Companion.normalizeParaRef(ctx.paragraphStyleRef ?: return)
        paraRules[key]?.let { r ->
            r.koFont?.let           { ctx.targetKoFont = it }
            r.enFont?.let           { ctx.targetEnFont = it }
            r.fontSizePt?.let       { ctx.targetFontSizePt = it }
            r.lineSpacingPct?.let   { ctx.targetLineSpacingPct = it }
            r.letterSpacing?.let    { ctx.targetLetterSpacing = it }
            r.leftIndentMm?.let     { ctx.targetLeftIndentMm = it }
            r.spaceBeforeMm?.let    { ctx.targetSpaceBeforeMm = it }
            r.alignment?.let        { ctx.targetAlignment = it }
        }
    }

    private fun applyChar(ctx: RuleContext) {
        val key = Companion.normalizeCharRef(ctx.characterStyleRef ?: return)
        charRules[key]?.let { r ->
            r.koFont?.let        { ctx.targetKoFont = it }
            r.enFont?.let        { ctx.targetEnFont = it }
            r.fontSizePt?.let    { ctx.targetFontSizePt = it }
            r.letterSpacing?.let { ctx.targetLetterSpacing = it }
        }
    }
}
