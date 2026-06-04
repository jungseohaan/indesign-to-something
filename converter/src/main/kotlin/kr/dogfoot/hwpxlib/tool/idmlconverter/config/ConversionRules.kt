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
    // InDesign 폰트명 → HWPX 폰트명. conversion-config.json 대체.
    // FontMapper Tier 0 (JSON 명시 매핑보다 우선 적용).

    r.fontRule("Sandoll 곧은부리")         { koFont = "한컴돋움"; enFont = "Arial" }
    r.fontRule("Sandoll 고고RoundCond")    { koFont = "한컴돋움"; enFont = "Calibri"; spacing = -5 }
    r.fontRule("Helvetica Neue")           { koFont = "한컴돋움"; enFont = "Arial"; scaleAdjust = 5 }
    r.fontRule("Times New Roman")          { koFont = "한컴바탕"; enFont = "Times New Roman" }
    r.fontRule("210 한반도OTF")            { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.8 }
    r.fontRule("Sandoll 고딕Neo1")         { koFont = "한컴돋움"; enFont = "Arial" }
    r.fontRule("ViMaru OTF")               { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("210 라임OTF")              { koFont = "함초롬돋움"; enFont = "함초롬돋움" }
    r.fontRule("210 밤의해변OTF")          { koFont = "함초롬돋움"; enFont = "함초롬돋움" }
    r.fontRule("210 나무굴림OTF")          { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("210 나무굴림")             { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("210 데이라잇OTF")          { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.88 }
    r.fontRule("210 꽃길OTF")             { koFont = "함초롬돋움"; enFont = "함초롬돋움" }
    r.fontRule("210 비밀정원OTF")          { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("210 리얼러브OTF")          { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.67 }
    r.fontRule("210 네모진OTF")            { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("210 가장자리OTF")          { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.82 }
    r.fontRule("210 자연공원OTF")          { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.84 }
    r.fontRule("210 나무젓가락OTF")        { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.75 }
    r.fontRule("210 늘솔길OTF")            { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.67 }
    r.fontRule("210 잎새바람OTF")          { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.65 }
    r.fontRule("210 딱지치기OTF")          { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.9 }
    r.fontRule("210 공중전화OTF")          { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.94 }
    r.fontRule("210 생활반장OTF")          { koFont = "함초롬돋움"; enFont = "함초롬돋움" }
    r.fontRule("210 핸드메이드OTF")        { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.84 }
    r.fontRule("210 라이프OTF")            { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("둘기마요_고딕")            { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.95 }
    r.fontRule("마루 부리OTF")             { koFont = "한컴바탕"; enFont = "한컴바탕" }
    r.fontRule("HU가는펜글씨")             { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.89 }
    r.fontRule("HU금요일오후")             { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.81 }
    r.fontRule("HU너무자몽다")             { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.86 }
    r.fontRule("HU달달한코코아130")        { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.88 }
    r.fontRule("HU바야흐로꽃")             { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.82 }
    r.fontRule("HU츄러스")                { koFont = "가는공한"; enFont = "가는공한" }
    r.fontRule("210 속삭임OTF")            { koFont = "중간안상수체"; enFont = "중간안상수체" }
    r.fontRule("Rix착한아이_Pro")          { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("Rix착한아이 M")            { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("Rix개봉박두_Pro")          { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("Rix개봉박두 B")            { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("Rix대학일기_Pro")          { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("Rix정고딕_Pro")            { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("Rix열정도")               { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("[Yoon가변] 윤고딕100_OTF") { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.95 }
    r.fontRule("Sandoll 네모니2")          { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("강원교육새음 OTF")         { koFont = "한컴돋움"; enFont = "한컴돋움"; spacing = -5; scaleAdjust = -20 }
    r.fontRule("Sandoll 고딕NeoRound")     { koFont = "한컴돋움"; enFont = "한컴돋움"; scaleAdjust = 5 }
    r.fontRule("Sandoll 고고Round")        { koFont = "한컴돋움"; enFont = "한컴돋움"; ratio = 0.72 }
    r.fontRule("Sandoll 명조Neo1")         { koFont = "한컴바탕"; enFont = "한컴바탕" }
    r.fontRule("Sandoll 안단테")           { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("Sandoll 제비2")            { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("ONE 모바일POP OTF")        { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("THE삐끗삐끗 (TT)")        { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("THE수수깡")               { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("TT더좋은날에")             { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("땅스부대찌개 OTF")         { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("양진체")                  { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("DX바른필기")              { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("DX새명조고어 Std")         { koFont = "함초롬바탕"; enFont = "함초롬바탕" }
    r.fontRule("DIN (TT)")                { koFont = "한컴돋움"; enFont = "Arial" }
    r.fontRule("양진체 ")                 { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("HU바르게쓰다")             { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("나눔스퀘어라운드OTF")      { koFont = "한컴돋움"; enFont = "Arial" }
    r.fontRule("상상토끼 정묵바위")        { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("SD 격동명조2")             { koFont = "함초롬바탕"; enFont = "Times New Roman" }
    r.fontRule("SD 무브잇 TT")             { koFont = "한컴돋움"; enFont = "한컴돋움" }
    r.fontRule("Univers LT Std")           { koFont = "함초롬돋움"; enFont = "Arial"; ratio = 0.85 }

    // ── 문자 스타일 규칙 ─────────────────────────────────────────
    // 샘플: 특정 CharacterStyle에 폰트/크기 강제 지정

    // r.characterRule("CharacterStyle/#대단원 제목") {
    //     koFont = "윤고딕"
    //     fontSizePt = 24.0
    // }
}
