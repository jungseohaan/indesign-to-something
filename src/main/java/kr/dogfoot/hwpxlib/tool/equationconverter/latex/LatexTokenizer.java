package kr.dogfoot.hwpxlib.tool.equationconverter.latex;

import kr.dogfoot.hwpxlib.tool.equationconverter.ConvertException;

import java.util.ArrayList;
import java.util.List;

public class LatexTokenizer {
    private final String input;
    private int pos;

    public LatexTokenizer(String input) {
        this.input = (input != null) ? input : "";
        this.pos = 0;
    }

    public List<LatexToken> tokenize() throws ConvertException {
        List<LatexToken> tokens = new ArrayList<LatexToken>();
        while (!isAtEnd()) {
            char c = peek();
            if (c == '\\') {
                tokens.add(readBackslash());
            } else if (c == '{') {
                tokens.add(new LatexToken(LatexTokenType.OPEN_BRACE, "{", pos));
                advance();
            } else if (c == '}') {
                tokens.add(new LatexToken(LatexTokenType.CLOSE_BRACE, "}", pos));
                advance();
            } else if (c == '[') {
                tokens.add(new LatexToken(LatexTokenType.OPEN_BRACKET, "[", pos));
                advance();
            } else if (c == ']') {
                tokens.add(new LatexToken(LatexTokenType.CLOSE_BRACKET, "]", pos));
                advance();
            } else if (c == '(') {
                tokens.add(new LatexToken(LatexTokenType.OPEN_PAREN, "(", pos));
                advance();
            } else if (c == ')') {
                tokens.add(new LatexToken(LatexTokenType.CLOSE_PAREN, ")", pos));
                advance();
            } else if (c == '^') {
                tokens.add(new LatexToken(LatexTokenType.SUPERSCRIPT, "^", pos));
                advance();
            } else if (c == '_') {
                tokens.add(new LatexToken(LatexTokenType.SUBSCRIPT, "_", pos));
                advance();
            } else if (c == '&') {
                tokens.add(new LatexToken(LatexTokenType.AMPERSAND, "&", pos));
                advance();
            } else if (c == '|') {
                tokens.add(new LatexToken(LatexTokenType.PIPE, "|", pos));
                advance();
            } else if (Character.isWhitespace(c)) {
                tokens.add(readWhitespace());
            } else if (Character.isDigit(c)) {
                tokens.add(readNumber());
            } else if (Character.isLetter(c)) {
                tokens.add(readText());
            } else if (isOperatorChar(c)) {
                tokens.add(new LatexToken(LatexTokenType.OPERATOR, String.valueOf(c), pos));
                advance();
            } else {
                throw new ConvertException("Unexpected character: '" + c + "'", input, pos);
            }
        }
        tokens.add(new LatexToken(LatexTokenType.EOF, "", pos));
        return tokens;
    }

    private LatexToken readBackslash() throws ConvertException {
        int startPos = pos;
        advance(); // consume '\'
        if (isAtEnd()) {
            throw new ConvertException("Unexpected end of input after backslash", input, startPos);
        }
        char next = peek();
        if (next == '\\') {
            advance();
            return new LatexToken(LatexTokenType.DOUBLE_BACKSLASH, "\\\\", startPos);
        }
        if (next == '{' || next == '}' || next == '|') {
            advance();
            return new LatexToken(LatexTokenType.COMMAND, String.valueOf(next), startPos);
        }
        if (next == ',' || next == ';' || next == '!' || next == ' ') {
            advance();
            return new LatexToken(LatexTokenType.COMMAND, String.valueOf(next), startPos);
        }
        if (Character.isLetter(next)) {
            return readCommandName(startPos);
        }
        throw new ConvertException("Unexpected character after backslash: '" + next + "'", input, pos);
    }

    private LatexToken readCommandName(int startPos) {
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && Character.isLetter(peek())) {
            sb.append(advance());
        }
        return new LatexToken(LatexTokenType.COMMAND, sb.toString(), startPos);
    }

    private LatexToken readNumber() {
        int startPos = pos;
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && (Character.isDigit(peek()) || peek() == '.')) {
            sb.append(advance());
        }
        return new LatexToken(LatexTokenType.NUMBER, sb.toString(), startPos);
    }

    private LatexToken readText() {
        int startPos = pos;
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && Character.isLetter(peek())) {
            sb.append(advance());
        }
        return new LatexToken(LatexTokenType.TEXT, sb.toString(), startPos);
    }

    private LatexToken readWhitespace() {
        int startPos = pos;
        while (!isAtEnd() && Character.isWhitespace(peek())) {
            advance();
        }
        return new LatexToken(LatexTokenType.WHITESPACE, " ", startPos);
    }

    private boolean isOperatorChar(char c) {
        return c == '+' || c == '-' || c == '=' || c == '<' || c == '>'
                || c == ',' || c == ';' || c == '!' || c == '\'' || c == ':'
                || c == '/' || c == '*' || c == '.';
    }

    private boolean isAtEnd() {
        return pos >= input.length();
    }

    private char peek() {
        return input.charAt(pos);
    }

    private char advance() {
        return input.charAt(pos++);
    }
}
