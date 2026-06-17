package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

import java.util.*;

/**
 * ASTDocument를 LLM 컨텍스트에 맞는 Semantic Block 추출 청크로 분할한다.
 */
public final class DocumentChunker {

    private static final int PAGES_PER_FALLBACK_CHUNK = 4;
    private static final int MAX_CHUNK_CHARS = 12_000;

    private DocumentChunker() {}

    public static class DocumentChunk {
        public int unitIndex;
        public String unitTitle;
        public List<Integer> pages = new ArrayList<Integer>();
        public String textContent;
        public List<String> imageRefs = new ArrayList<String>();
    }

    public static List<DocumentChunk> chunk(ASTDocument doc) {
        if (doc == null || doc.sections() == null) return Collections.emptyList();

        List<DocumentChunk> chunks = detectUnits(doc);
        if (chunks.isEmpty()) {
            chunks = chunkByPages(doc, PAGES_PER_FALLBACK_CHUNK);
        }
        return splitLargeChunks(chunks);
    }

    private static List<DocumentChunk> detectUnits(ASTDocument doc) {
        List<DocumentChunk> result = new ArrayList<DocumentChunk>();
        DocumentChunk current = null;
        StringBuilder sb = null;

        for (ASTSection section : doc.sections()) {
            if (section.blocks() == null) continue;

            for (ASTBlock block : section.blocks()) {
                if (block instanceof ASTTextFrameBlock) {
                    ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
                    if (tf.paragraphs() == null) continue;

                    for (ASTParagraph para : tf.paragraphs()) {
                        String style = para.paragraphStyleRef();
                        String text = extractText(para);
                        if (text.isEmpty()) continue;

                        if (isUnitTitle(style, text)) {
                            if (current != null) {
                                current.textContent = sb.toString();
                                result.add(current);
                            }
                            current = new DocumentChunk();
                            current.unitIndex = result.size() + 1;
                            current.unitTitle = text;
                            current.pages.add(section.pageNumber());
                            sb = new StringBuilder();
                            sb.append("# ").append(text).append("\n\n");
                        } else if (current != null) {
                            addPage(current, section.pageNumber());
                            appendParagraph(sb, style, text);
                        }
                    }
                } else if (block instanceof ASTFigure && current != null) {
                    ASTFigure fig = (ASTFigure) block;
                    if (fig.imagePath() != null) current.imageRefs.add(fig.imagePath());
                    addPage(current, section.pageNumber());
                }
            }
        }

        if (current != null) {
            current.textContent = sb.toString();
            result.add(current);
        }
        return result;
    }

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

            addPage(current, page);
            if (section.blocks() == null) continue;
            for (ASTBlock block : section.blocks()) {
                if (block instanceof ASTTextFrameBlock) {
                    ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
                    if (tf.paragraphs() == null) continue;
                    for (ASTParagraph para : tf.paragraphs()) {
                        String text = extractText(para);
                        if (!text.isEmpty()) sb.append(text).append("\n\n");
                    }
                } else if (block instanceof ASTFigure) {
                    ASTFigure fig = (ASTFigure) block;
                    if (fig.imagePath() != null) current.imageRefs.add(fig.imagePath());
                }
            }
        }

        if (current != null) {
            current.textContent = sb.toString();
            result.add(current);
        }
        return result;
    }

    private static List<DocumentChunk> splitLargeChunks(List<DocumentChunk> chunks) {
        List<DocumentChunk> result = new ArrayList<DocumentChunk>();
        for (DocumentChunk chunk : chunks) {
            String text = chunk.textContent != null ? chunk.textContent : "";
            if (text.length() <= MAX_CHUNK_CHARS) {
                chunk.unitIndex = result.size() + 1;
                result.add(chunk);
                continue;
            }

            int part = 1;
            for (int start = 0; start < text.length(); start += MAX_CHUNK_CHARS) {
                int end = Math.min(text.length(), start + MAX_CHUNK_CHARS);
                if (end < text.length()) {
                    int boundary = text.lastIndexOf("\n\n", end);
                    if (boundary > start + MAX_CHUNK_CHARS / 2) {
                        end = boundary + 2;
                    }
                }

                DocumentChunk split = new DocumentChunk();
                split.unitIndex = result.size() + 1;
                split.unitTitle = chunk.unitTitle + " (" + part + ")";
                split.pages.addAll(chunk.pages);
                split.imageRefs.addAll(chunk.imageRefs);
                split.textContent = text.substring(start, end);
                result.add(split);
                part++;
                start = end - MAX_CHUNK_CHARS;
            }
        }
        return result;
    }

    private static boolean isUnitTitle(String style, String text) {
        if (text.matches("^[0-9]+단원.*")) return true;
        if (text.matches("(?i)^(Unit|Chapter)\\s+[0-9]+.*")) return true;
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

    private static String extractText(ASTParagraph para) {
        if (para.items() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ASTInlineItem item : para.items()) {
            if (item instanceof ASTTextRun) {
                String t = ((ASTTextRun) item).text();
                if (t != null) sb.append(t);
            } else if (item instanceof ASTBreak) {
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static void addPage(DocumentChunk chunk, int page) {
        if (!chunk.pages.contains(page)) chunk.pages.add(page);
    }
}
