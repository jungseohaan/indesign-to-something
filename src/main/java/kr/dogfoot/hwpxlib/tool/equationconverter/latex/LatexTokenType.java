package kr.dogfoot.hwpxlib.tool.equationconverter.latex;

public enum LatexTokenType {
    COMMAND,
    OPEN_BRACE,
    CLOSE_BRACE,
    OPEN_BRACKET,
    CLOSE_BRACKET,
    OPEN_PAREN,
    CLOSE_PAREN,
    SUPERSCRIPT,
    SUBSCRIPT,
    AMPERSAND,
    DOUBLE_BACKSLASH,
    TEXT,
    NUMBER,
    WHITESPACE,
    PIPE,
    OPERATOR,
    EOF
}
