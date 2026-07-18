package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * EH 수식 폰트 변환기 골든 단위테스트.
 *
 * <p>입력 = 폰트/스타일 지정된 IDMLCharacterRun 시퀀스, 기대 = hwpScript.
 * 실측 데이터(비상 중3수학 971 Story 조사)의 대표 패턴을 케이스로 삼는다.
 * 원본 EH 인코딩(hook 0x27/0xAE, 자리구분 0x8C, GREP 분수 ;..; 등)을
 * content String 으로 직접 조립한다.
 *
 * <p>재작성 진행 단계에 따라 일부 케이스는 아직 실패(빨간 상태)할 수 있다.
 * Lexer→Parser→Emitter 순으로 통과시킨다.
 */
public class EHFontEquationConverterTest {

    private static final String CS_FRAC_UPPER = "CharacterStyle/분수대문자";
    private static final String CS_SUP = "CharacterStyle/상부자";
    private static final String CS_YAKMUL = "CharacterStyle/약물";

    /** 분수대문자 폰트 EH 런 (근호 hook·radicand·GREP 분수). */
    private static IDMLCharacterRun fracUpper(String content) {
        return run(content, "EH분수대문자", CS_FRAC_UPPER);
    }

    /** 일반 본문 런(비EH). radicand 숫자·연산자 등. */
    private static IDMLCharacterRun body(String content) {
        return run(content, null, "CharacterStyle/$ID/[No character style]");
    }

    private static IDMLCharacterRun run(String content, String fontFamily, String charStyle) {
        IDMLCharacterRun r = new IDMLCharacterRun();
        r.content(content);
        if (fontFamily != null) r.fontFamily(fontFamily);
        r.appliedCharacterStyle(charStyle);
        return r;
    }

    /** EH상부자 폰트 런 (위첨자 또는 작은 크기 radicand). */
    private static IDMLCharacterRun sup(String content) {
        return run(content, "EH상부자", CS_SUP);
    }

    /** 정사각형 빈 답란 박스 인라인 그래픽을 가진 런 (content = FFFC). */
    private static IDMLCharacterRun answerBox() {
        IDMLCharacterRun r = body("￼");
        IDMLCharacterRun.InlineGraphic g = new IDMLCharacterRun.InlineGraphic();
        g.type("rectangle");
        g.widthPoints(14.17);
        g.heightPoints(14.17);
        r.addInlineGraphic(g);
        return r;
    }

    private static String convert(IDMLCharacterRun... runs) {
        List<IDMLCharacterRun> list = new ArrayList<>();
        for (IDMLCharacterRun r : runs) list.add(r);
        return EHFontEquationConverter.convert(list);
    }

    // ── 근호 radicand 구조 ──

    @Test
    public void sqrt_single_digit() {
        // '3 → √3
        Assert.assertEquals("sqrt{3}", convert(fracUpper("'"), body("3")));
    }

    @Test
    public void sqrt_split_number_10() {
        // '1[8C]0 → √10 (0x8C 자리구분자 제거하고 이어붙임)
        Assert.assertEquals("sqrt{10}",
                convert(fracUpper("'"), body("1"), fracUpper(""), body("0")));
    }

    @Test
    public void sqrt_split_number_144() {
        // -'1[8C]4[8C]4 → -sqrt{144}
        Assert.assertEquals("-sqrt{144}",
                convert(body("-"), fracUpper("'"), body("1"),
                        fracUpper(""), body("4"),
                        fracUpper(""), body("4")));
    }

    @Test
    public void sqrt_variable() {
        // 'b → √b
        Assert.assertEquals("sqrt{b}", convert(fracUpper("'"), body("b")));
    }

    @Test
    public void sqrt_superscript_font_radicand() {
        // '8[8C]0 — 8·0 이 EH상부자 폰트(작은 크기)로 조판된 radicand → √80
        // 상부자 폰트라도 hook 직후 radicand 는 위첨자가 아니라 근호 안 숫자.
        Assert.assertEquals("sqrt{80}",
                convert(fracUpper("'"), sup("8"), fracUpper(""), sup("0")));
    }

    @Test
    public void sqrt_radicand_then_exponent() {
        // √3²  — hook + radicand 3 + 진짜 지수 2 → {sqrt{3}}^{2}
        // radicand 를 다 모은 뒤의 SUPERSCRIPT 만 지수.
        Assert.assertEquals("{sqrt{3}}^{2}",
                convert(fracUpper("'"), body("3"), sup("2`")));
    }

    @Test
    public void sqrt_variable_after_leading_sep() {
        // '[8C]a — hook 직후 자리구분자(폭 마커)가 오고 radicand 는 변수 a.
        // DIGIT_SEP 을 폭 마커로 스킵해 √a 로 (sqrt{ }a 로 새면 안 됨).
        Assert.assertEquals("sqrt{a}",
                convert(fracUpper("'"), fracUpper(""), body("a")));
    }

    @Test
    public void sqrt_width_selector_run_then_number() {
        // '[B6]1[8C]2[8C]1 — hook 다음 폭 선택자(0xB6)가 별도 런으로 오고 radicand 121.
        // 폭 선택자는 버리고 √121 로 (실측: 1단원 p16). d 로 디코딩해 sqrt{d121} 로 새면 안 됨.
        Assert.assertEquals("sqrt{121}",
                convert(fracUpper("'"), fracUpper("¶"), body("1"),
                        fracUpper(""), body("2"),
                        fracUpper(""), body("1")));
    }

    @Test
    public void sqrt_width_selector_glyph_as_variable_u() {
        // '[C9] — 폭 선택자와 같은 코드포인트지만 뒤에 radicand 가 없음 → 진짜 변수 u.
        Assert.assertEquals("sqrt{u}",
                convert(fracUpper("'"), fracUpper("É")));
    }

    @Test
    public void sqrt_width_selector_glyph_as_variable_l() {
        // '[C2] — 마찬가지로 radicand 없음 → 변수 l (실측: 1단원 p16 √u·√l).
        Assert.assertEquals("sqrt{l}",
                convert(fracUpper("'"), fracUpper("Â")));
    }

    @Test
    public void empty_sqrt_superscript_is_radicand() {
        // '2` — hook 직후 radicand 가 상부자(작은크기)로 조판되어 SUPERSCRIPT 로 렉싱될 때,
        // 빈 근호 + 위첨자면 그 위첨자는 지수가 아니라 radicand → sqrt{2}.
        Assert.assertEquals("sqrt{2}",
                convert(fracUpper("'"), sup("2`")));
    }

    @Test
    public void two_sqrts_product() {
        // '2_'3 → √2·√3 (hook 2개 → Sqrt 2개, _ = ×)
        Assert.assertEquals("sqrt{2} TIMES sqrt{3}",
                convert(fracUpper("'"), body("2_"), fracUpper("'"), body("3")));
    }

    @Test
    public void no_space_before_closing_paren() {
        // ('5␣)Û` — 원문이 근호 가로줄과 ')' 겹침을 피하려고 넣은 U+2009 thin space 는
        // HWP 수식에서 쓸데없는 틈이 되므로 제거 (실측: p17 (√5 )²=5).
        Assert.assertEquals("left(sqrt{5} right)^{2}=5",
                convert(body("("), fracUpper("'"), body("5 )Û`=5")));
    }

    // ── GREP 분수 ──

    @Test
    public void grep_fraction_half() {
        // ;2!; → 1/2 (2=분모, !=shift-1=분자)
        Assert.assertEquals("{1} over {2}", convert(body(";2!;")));
    }

    @Test
    public void sqrt_fraction() {
        // '[선택자];9!; → √(1/9) 형태 — 분수대문자 hook + GREP 분수
        Assert.assertEquals("sqrt{{1} over {9}}",
                convert(fracUpper("'"), body(";9!;")));
    }

    @Test
    public void two_sqrts_fraction_then_number() {
        // √(5/2)√10 — 분수 근호 뒤 새 근호 (뭉치면 안 됨)
        Assert.assertEquals("sqrt{{5} over {2}}sqrt{10}",
                convert(fracUpper("'"), body(";2%;"),
                        fracUpper("'"), body("1"), fracUpper(""), body("0")));
    }

    // ── 빈 답란 박스 ──

    @Test
    public void sqrt_answer_box() {
        // '□ → sqrt{box{~}}
        Assert.assertEquals("sqrt{box{~}}", convert(fracUpper("'"), answerBox()));
    }

    @Test
    public void sqrt_number_times_box() {
        // '2_□ → sqrt{2 TIMES box{~}}
        Assert.assertEquals("sqrt{2 TIMES box{~}}",
                convert(fracUpper("'"), body("2_"), answerBox()));
    }

    // ── 첨자·특수기호 ──

    @Test
    public void superscript_square() {
        // xÛ` → x^{2} (Û=위첨자², `=상부자 닫기)
        Assert.assertEquals("x^{2}", convert(body("x"), run("Û`", "EH상부자", CS_SUP)));
    }

    @Test
    public void recurring_decimal() {
        // 0.H4 → 0.dot{4} (약물 H = 순환마디 점)
        Assert.assertEquals("0.dot{4}",
                convert(body("0."), run("H", "EH약물", CS_YAKMUL), body("4")));
    }

    @Test
    public void pi() {
        // 약물 p → pi
        Assert.assertEquals("pi", convert(run("p", "EH약물", CS_YAKMUL)));
    }
}
