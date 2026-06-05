package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

import java.util.*;

/**
 * ASTDocument → 단원별 텍스트 청크 분할.
 *
 * 단원 경계 감지: 단락 스타일명에 "제목1", "단원", "Unit", "Chapter", "UNIT" 포함.
 * 단원을 찾지 못하면 페이지 10개 단위로 분할.
 */
public final class DocumentChunker {

    private DocumentChunker() {}

    public static class DocumentChunk {
        public int unitIndex;
        public String unitTitle;
        public List<Integer> pages = new ArrayList<Integer>();
        public String textContent;   // Markdown 형식 텍스트
        public List<String> imageRefs = new ArrayList<String>(); // ASTFigure 경로
    }

    public static List<DocumentChunk> chunk(ASTDocument doc) {
        if (doc == null || doc.sections() == null) return Collections.emptyList();

        List<DocumentChunk> units = detectUnits(doc);
        if (units.isEmpty()) {
            units = chunkByPages(doc, 10);
        }
        return units;
    }

    // -------------------------------------------------------
    // 단원 경계 감지
    // -------------------------------------------------------

    private static List<DocumentChunk> detectUnits(ASTDocument doc) {
        List<DocumentChunk> result = new ArrayList<DocumentChunk>();
        DocumentChunk current = null;
        StringBuilder sb = null;

        for (ASTSection section : doc.sections()) {
            for (ASTBlock block : section.blocks()) {
                if (!(block instanceof ASTTextFrameBlock)) continue;
                ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
                if (tf.paragraphs() == null) continue;

                for (ASTParagraph para : tf.paragraphs()) {
                    String style = para.paragraphStyleRef();
                    String text  = extractText(para);
                    if (text.isEmpty()) continue;

                    if (isUnitTitle(style, text)) {
                        // 이전 단원 마무리
                        if (current != null) {
                            current.textContent = sb.toString();
                            result.add(current);
                        }
                        // 새 단원 시작
                        current = new DocumentChunk();
                        current.unitIndex = result.size() + 1;
                        current.unitTitle = text;
                        current.pages.add(section.pageNumber());
                        sb = new StringBuilder();
                        sb.append("# ").append(text).append("\n\n");
                    } else if (current != null) {
                        if (!current.pages.contains(section.pageNumber())) {
                            current.pages.add(section.pageNumber());
                        }
                        appendParagraph(sb, style, text);
                    }
                }

                // 이미지 참조 수집
                if (current != null && block instanceof ASTTextFrameBlock) {
                    // TextFrame 안의 이미지는 텍스트로 표시됨 — 별도 처리 불필요
                }
            }

            // ASTFigure 처리
            for (ASTBlock block : section.blocks()) {
                if (block instanceof ASTFigure && current != null) {
                    ASTFigure fig = (ASTFigure) block;
                    if (fig.imagePath() != null) {
                        current.imageRefs.add(fig.imagePath());
                    }
                }
            }
        }

        // 마지막 단원 마무리
        if (current != null) {
            current.textContent = sb.toString();
            result.add(current);
        }

        return result;
    }

    private static boolean isUnitTitle(String style, String text) {
        // 텍스트 패턴 우선: "1단원", "Unit 1", "Chapter 1" 형태만 신뢰
        if (text.matches("^[0-9]+단원.*")) return true;
        if (text.matches("(?i)^(Unit|Chapter)\\s+[0-9]+.*")) return true;
        // 스타일명은 정확 일치 또는 접두사만 허용 (contains는 "학습단원목표" 같은 오탐 위험)
        if (style == null) return false;
        String lower = style.toLowerCase().trim();
        return lower.equals("제목1") || lower.startsWith("제목1_")
            || lower.equals("heading 1") || lower.equals("heading1")
            || lower.equals("h1");
    }

    private static void appendParagraph(StringBuilder sb, String style, String text) {
        if (style != null) {
            String lower = style.toLowerCase();
            if (lower.contains("제목2") || lower.contains("heading2") || lower.contains("h2")) {
                sb.append("## ").append(text).append("\n\n");
                return;
            }
            if (lower.contains("제목3") || lower.contains("heading3") || lower.contains("h3")) {
                sb.append("### ").append(text).append("\n\n");
                return;
            }
        }
        sb.append(text).append("\n\n");
    }

    // -------------------------------------------------------
    // 페이지 단위 폴백
    // -------------------------------------------------------

    private static List<DocumentChunk> chunkByPages(ASTDocument doc, int pagesPerChunk) {
        List<DocumentChunk> result = new ArrayList<DocumentChunk>();
        DocumentChunk current = null;
        StringBuilder sb = null;
        int startPage = -1;

        for (ASTSection section : doc.sections()) {
            int page = section.pageNumber();
            if (current == null || (page - startPage) >= pagesPerChunk) {
                if (current != null) {
                    current.textContent = sb.toString();
                    result.add(current);
                }
                current = new DocumentChunk();
                current.unitIndex = result.size() + 1;
                current.unitTitle = "페이지 " + page + " ~";
                startPage = page;
                sb = new StringBuilder();
                sb.append("# 페이지 ").append(page).append("\n\n");
            }
            current.pages.add(page);
            for (ASTBlock block : section.blocks()) {
                if (block instanceof ASTTextFrameBlock) {
                    ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
                    if (tf.paragraphs() == null) continue;
                    for (ASTParagraph para : tf.paragraphs()) {
                        String text = extractText(para);
                        if (!text.isEmpty()) sb.append(text).append("\n\n");
                    }
                }
            }
        }
        if (current != null) {
            current.textContent = sb.toString();
            result.add(current);
        }
        return result;
    }

    // -------------------------------------------------------
    // 텍스트 추출
    // -------------------------------------------------------

    private static String extractText(ASTParagraph para) {
        if (para.items() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ASTInlineItem item : para.items()) {
            if (item instanceof ASTTextRun) {
                String t = ((ASTTextRun) item).text();
                if (t != null) sb.append(t);
            } else if (item instanceof ASTBreak) {
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }
}
