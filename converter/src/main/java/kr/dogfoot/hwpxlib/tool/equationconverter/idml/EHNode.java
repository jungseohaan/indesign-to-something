package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * EH 수식 IR(Intermediate Representation) 노드.
 * EHIRBuilder가 EHToken 리스트에서 구조를 인식하여 트리로 빌드.
 * EHHwpScriptEmitter가 이 트리를 hwpScript 문자열로 변환.
 */
public abstract class EHNode {

    public abstract String type();

    /** 일반 텍스트 */
    public static class Text extends EHNode {
        private final String text;
        public Text(String text) { this.text = text; }
        public String text() { return text; }
        public String type() { return "TEXT"; }
    }

    /** 위첨자 ^{content} */
    public static class Superscript extends EHNode {
        private final List<EHNode> content = new ArrayList<>();
        public List<EHNode> content() { return content; }
        public String type() { return "SUP"; }
    }

    /** 아래첨자 _{content} */
    public static class Subscript extends EHNode {
        private final List<EHNode> content = new ArrayList<>();
        public List<EHNode> content() { return content; }
        public String type() { return "SUB"; }
    }

    /** 분수 {numerator} over {denominator} */
    public static class Fraction extends EHNode {
        private final List<EHNode> numerator = new ArrayList<>();
        private final List<EHNode> denominator = new ArrayList<>();
        public List<EHNode> numerator() { return numerator; }
        public List<EHNode> denominator() { return denominator; }
        public String type() { return "FRAC"; }
    }

    /** 제곱근 sqrt{radicand} */
    public static class Sqrt extends EHNode {
        private final List<EHNode> radicand = new ArrayList<>();
        public List<EHNode> radicand() { return radicand; }
        public String type() { return "SQRT"; }
    }

    /** 노드 시퀀스 (루트 그룹) */
    public static class Group extends EHNode {
        private final List<EHNode> children = new ArrayList<>();
        public List<EHNode> children() { return children; }
        public String type() { return "GROUP"; }
    }

    /** 빈 답란 박스 placeholder → emit "box{~}" (ANSWER) / 무시 (VINCULUM) */
    public static class Box extends EHNode {
        public enum Kind { ANSWER, VINCULUM }
        private final Kind kind;
        public Box(Kind kind) { this.kind = kind; }
        public Kind kind() { return kind; }
        public String type() { return "BOX"; }
    }

    /** 선분 위 막대 → overline{content} (EH상부자 0xD3 Ó 마커) */
    public static class Overline extends EHNode {
        private final List<EHNode> content = new ArrayList<>();
        public List<EHNode> content() { return content; }
        public String type() { return "OVERLINE"; }
    }

    /** 순환소수 순환마디 점 → dot{digit} (EH약물 H) */
    public static class RecurDot extends EHNode {
        private final String digit;
        public RecurDot(String digit) { this.digit = digit; }
        public String digit() { return digit; }
        public String type() { return "RECURDOT"; }
    }

    /** 괄호 그룹 (크기 자동조절 판단용, Text 대신 명시) → ( content ) */
    public static class Paren extends EHNode {
        private final List<EHNode> content = new ArrayList<>();
        public List<EHNode> content() { return content; }
        public String type() { return "PAREN"; }
    }
}
