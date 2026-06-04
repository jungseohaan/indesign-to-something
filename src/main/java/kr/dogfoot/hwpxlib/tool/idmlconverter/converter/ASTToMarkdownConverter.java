package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * ASTDocument → Markdown 내보내기.
 * LLM 학습/RAG 데이터 생성 목적: 글/이미지 블럭을 읽기 순서로 배치 + 단락 스타일 이름 주석.
 *
 * SPEC-markdown 참조.
 */
public class ASTToMarkdownConverter {

    // --- instance state (convert() 호출마다 초기화) ---
    private StringBuilder out;
    private Map<String, String> styleMap;   // styleId → styleName
    private File imagesDir;                 // <name>_images/
    private String imagesDirName;           // markdown 내 상대 경로용
    private int figSeq;
    private int inlineSeq;

    // -------------------------------------------------------

    public void convert(ASTDocument doc, File outputFile) throws IOException {
        this.out = new StringBuilder();
        this.figSeq = 0;
        this.inlineSeq = 0;

        // images dir: <outputBaseName>_images/ (같은 디렉토리)
        File parentDir = outputFile.getParentFile();
        if (parentDir == null) parentDir = new File(".");
        String mdName = outputFile.getName();
        String baseName = mdName.toLowerCase().endsWith(".md")
                ? mdName.substring(0, mdName.length() - 3) : mdName;
        this.imagesDir = new File(parentDir, baseName + "_images");
        this.imagesDirName = baseName + "_images";

        this.styleMap = buildStyleMap(doc);
        Map<String, StoryGroup> allGroups = buildStoryGroups(doc);

        // YAML front matter
        out.append("---\n");
        out.append("source: ").append(doc.sourceFile() != null ? doc.sourceFile() : "").append("\n");
        out.append("pages: ").append(doc.sections().size()).append("\n");
        out.append("---\n\n");

        // sections
        boolean firstSection = true;
        for (ASTSection section : doc.sections()) {
            List<SortableBlock> blocks = collectAndSort(section, allGroups);
            if (blocks.isEmpty()) continue;

            if (!firstSection) {
                out.append("\n---\n\n");
            }
            out.append("<!-- page ").append(section.pageNumber()).append(" -->\n\n");
            firstSection = false;

            for (SortableBlock sb : blocks) {
                writeBlock(sb);
            }
        }

        // write file
        parentDir.mkdirs();
        try (Writer w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outputFile), "UTF-8"))) {
            w.write(out.toString());
        }
    }

    // --------------------------------------------------------
    // Pre-pass: storyId 그룹핑
    // --------------------------------------------------------

    private Map<String, String> buildStyleMap(ASTDocument doc) {
        Map<String, String> map = new HashMap<>();
        if (doc.paragraphStyles() != null) {
            for (ASTStyleDef style : doc.paragraphStyles()) {
                if (style.styleId() != null && style.styleName() != null) {
                    map.put(style.styleId(), style.styleName());
                }
            }
        }
        return map;
    }

    private Map<String, StoryGroup> buildStoryGroups(ASTDocument doc) {
        Map<String, StoryGroup> map = new LinkedHashMap<>();

        // Pass 1: collect frames
        for (ASTSection section : doc.sections()) {
            for (ASTBlock block : section.blocks()) {
                if (block.blockType() != ASTBlock.BlockType.TEXT_FRAME_BLOCK) continue;
                ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
                if (tf.sourceId() != null && tf.sourceId().startsWith("page_num_")) continue;
                if (tf.isBackgroundOnly()) continue;

                String key;
                if (tf.storyId() != null) {
                    key = tf.storyId();
                } else if (tf.sourceId() != null) {
                    key = "tf_" + tf.sourceId();
                } else {
                    continue; // both null — skip
                }

                StoryGroup group = map.get(key);
                if (group == null) {
                    group = new StoryGroup();
                    group.key = key;
                    group.frames = new ArrayList<>();
                    group.pages = new ArrayList<>();
                    map.put(key, group);
                }
                group.frames.add(new FrameEntry(tf, section.pageNumber()));
                if (!group.pages.contains(section.pageNumber())) {
                    group.pages.add(section.pageNumber());
                }
            }
        }

        // Pass 2: sort frames + compute derived fields
        for (StoryGroup group : map.values()) {
            Collections.sort(group.frames, new Comparator<FrameEntry>() {
                public int compare(FrameEntry a, FrameEntry b) {
                    if (a.pageNumber != b.pageNumber) return a.pageNumber - b.pageNumber;
                    return Long.compare(a.tf.y(), b.tf.y());
                }
            });
            Collections.sort(group.pages);

            FrameEntry first = group.frames.get(0);
            group.ownerPage = first.pageNumber;
            group.representativeY = first.tf.y();
            group.centerX = first.tf.x() + first.tf.width() / 2;
            group.width = first.tf.width();

            for (FrameEntry fe : group.frames) {
                String fc = fe.tf.fillColor();
                if (fc != null && !fc.isEmpty()) {
                    group.fillColor = fc;
                    break;
                }
            }
        }

        return map;
    }

    // --------------------------------------------------------
    // 섹션별 블럭 수집 + Y밴드 정렬
    // --------------------------------------------------------

    private List<SortableBlock> collectAndSort(ASTSection section, Map<String, StoryGroup> all) {
        long pageWidth = (section.layout() != null) ? section.layout().pageWidth() : 0;
        List<SortableBlock> blocks = new ArrayList<>();

        // 1. StoryGroups whose ownerPage == this section
        for (StoryGroup group : all.values()) {
            if (group.ownerPage != section.pageNumber()) continue;
            SortableBlock sb = new SortableBlock();
            sb.block = group;
            sb.representativeY = group.representativeY;
            sb.centerX = group.centerX;
            sb.width = group.width;
            sb.column = classifyColumn(sb.centerX, sb.width, pageWidth);
            blocks.add(sb);
        }

        // 2. ASTFigure, ASTTable, page_num TF from section.blocks()
        for (ASTBlock block : section.blocks()) {
            if (block.blockType() == ASTBlock.BlockType.FIGURE) {
                ASTFigure fig = (ASTFigure) block;
                SortableBlock sb = new SortableBlock();
                sb.block = fig;
                sb.representativeY = fig.y();
                sb.centerX = fig.x() + fig.width() / 2;
                sb.width = fig.width();
                sb.column = classifyColumn(sb.centerX, sb.width, pageWidth);
                blocks.add(sb);
            } else if (block.blockType() == ASTBlock.BlockType.TABLE) {
                ASTTable table = (ASTTable) block;
                SortableBlock sb = new SortableBlock();
                sb.block = table;
                sb.representativeY = table.y();
                sb.centerX = table.x() + table.width() / 2;
                sb.width = table.width();
                sb.column = classifyColumn(sb.centerX, sb.width, pageWidth);
                blocks.add(sb);
            } else if (block.blockType() == ASTBlock.BlockType.TEXT_FRAME_BLOCK) {
                ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
                if (tf.sourceId() != null && tf.sourceId().startsWith("page_num_")) {
                    SortableBlock sb = new SortableBlock();
                    sb.block = tf;
                    sb.representativeY = tf.y();
                    sb.centerX = tf.x() + tf.width() / 2;
                    sb.width = tf.width();
                    sb.column = classifyColumn(sb.centerX, sb.width, pageWidth);
                    blocks.add(sb);
                }
            }
        }

        return applyYBandSort(blocks);
    }

    private SortableBlock.Column classifyColumn(long centerX, long width, long pageWidth) {
        if (pageWidth > 0 && width * 10 > pageWidth * 6) return SortableBlock.Column.FULL;
        if (pageWidth > 0 && centerX * 2 < pageWidth) return SortableBlock.Column.LEFT;
        if (pageWidth > 0) return SortableBlock.Column.RIGHT;
        return SortableBlock.Column.LEFT;
    }

    private List<SortableBlock> applyYBandSort(List<SortableBlock> blocks) {
        // Step 1: Y 오름차순 stable sort
        Collections.sort(blocks, new Comparator<SortableBlock>() {
            public int compare(SortableBlock a, SortableBlock b) {
                return Long.compare(a.representativeY, b.representativeY);
            }
        });

        // Step 2: 인접 Y 차이 ≤ 300 hwpunit이면 같은 밴드 (체인 방식)
        List<List<SortableBlock>> bands = new ArrayList<>();
        List<SortableBlock> current = new ArrayList<>();
        for (SortableBlock b : blocks) {
            if (!current.isEmpty()
                    && b.representativeY - current.get(current.size() - 1).representativeY > 300) {
                bands.add(current);
                current = new ArrayList<>();
            }
            current.add(b);
        }
        if (!current.isEmpty()) bands.add(current);

        // Step 3: 밴드 내 FULL → LEFT → RIGHT 순
        List<SortableBlock> sorted = new ArrayList<>();
        for (List<SortableBlock> band : bands) {
            Collections.sort(band, new Comparator<SortableBlock>() {
                public int compare(SortableBlock a, SortableBlock b) {
                    return a.column.ordinal() - b.column.ordinal();
                }
            });
            sorted.addAll(band);
        }
        return sorted;
    }

    // --------------------------------------------------------
    // Block writers
    // --------------------------------------------------------

    private void writeBlock(SortableBlock sb) {
        if (sb.block instanceof StoryGroup) {
            writeStoryGroup((StoryGroup) sb.block);
        } else if (sb.block instanceof ASTFigure) {
            writeFigure((ASTFigure) sb.block);
        } else if (sb.block instanceof ASTTable) {
            writeTable((ASTTable) sb.block);
        } else if (sb.block instanceof ASTTextFrameBlock) {
            writePageNumberTf((ASTTextFrameBlock) sb.block);
        }
    }

    private void writeStoryGroup(StoryGroup group) {
        // 블럭 헤더 주석
        out.append("<!-- textframe storyId=").append(group.key);
        StringBuilder pageList = new StringBuilder();
        for (int i = 0; i < group.pages.size(); i++) {
            if (i > 0) pageList.append(",");
            pageList.append(group.pages.get(i));
        }
        out.append(" pages=").append(pageList);
        if (group.fillColor != null && !group.fillColor.isEmpty()) {
            out.append(" fillColor=").append(group.fillColor);
        }
        out.append(" -->\n\n");

        // 모든 프레임의 단락을 순서대로 출력
        for (FrameEntry fe : group.frames) {
            for (ASTParagraph para : fe.tf.paragraphs()) {
                writeParagraph(para);
            }
        }
        out.append("\n");
    }

    private void writePageNumberTf(ASTTextFrameBlock tf) {
        StringBuilder text = new StringBuilder();
        for (ASTParagraph para : tf.paragraphs()) {
            for (ASTInlineItem item : para.items()) {
                if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                    String t = ((ASTTextRun) item).text();
                    if (t != null) text.append(t);
                }
            }
        }
        String value = text.toString().trim();
        try {
            Integer.parseInt(value);
            out.append("<!-- page-number: ").append(value).append(" -->\n\n");
        } catch (NumberFormatException e) {
            // 정수가 아니면 무시
        }
    }

    private void writeParagraph(ASTParagraph para) {
        // Step 1: inlineTable 체크
        if (para.inlineTable() != null) {
            writeTable(para.inlineTable());
            return;
        }

        // Step 2: 빈 단락 스킵
        boolean blank = true;
        for (ASTInlineItem item : para.items()) {
            switch (item.itemType()) {
                case TEXT_RUN:
                    String t = ((ASTTextRun) item).text();
                    if (t != null && !t.trim().isEmpty()) { blank = false; }
                    break;
                case INLINE_OBJECT:
                case EQUATION:
                    blank = false;
                    break;
                default:
                    break;
            }
            if (!blank) break;
        }
        if (blank) return;

        // Step 3: 스타일 이름 주석
        String styleRef = para.paragraphStyleRef();
        String styleName = (styleRef != null) ? styleMap.getOrDefault(styleRef, styleRef) : "";
        if (styleName != null && !styleName.isEmpty()) {
            out.append("<!-- style: ").append(styleName).append(" -->\n");
        }

        // Step 4: 접두사 결정
        int level = detectHeadingLevel(styleName);
        String prefix;
        if (level > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < level; i++) sb.append('#');
            sb.append(' ');
            prefix = sb.toString();
        } else if (para.bulletParagraph()) {
            prefix = "- ";
        } else {
            prefix = "";
        }

        // Step 5: 인라인 항목 렌더링
        StringBuilder buf = new StringBuilder();
        for (ASTInlineItem item : para.items()) {
            switch (item.itemType()) {
                case TEXT_RUN:
                    buf.append(renderRun((ASTTextRun) item));
                    break;
                case BREAK:
                    buf.append(renderBreak((ASTBreak) item));
                    break;
                case INLINE_OBJECT:
                    buf.append(renderInlineObject((ASTInlineObject) item));
                    break;
                case EQUATION:
                    buf.append(renderEquation((ASTEquation) item));
                    break;
            }
        }

        // Step 6: 줄 맨 앞 이스케이프 (prefix 없을 때만)
        String content = buf.toString();
        if (prefix.isEmpty()) {
            content = escapeLineStart(content);
        }

        // Step 7: 출력
        out.append(prefix).append(content).append("\n");
    }

    private void writeFigure(ASTFigure fig) {
        long widthPt = fig.width() / 100;
        long heightPt = fig.height() / 100;

        out.append("<!-- figure kind=")
           .append(fig.kind() != null ? fig.kind().name() : "IMAGE")
           .append(" zOrder=").append(fig.zOrder())
           .append(" width=").append(widthPt).append("pt")
           .append(" height=").append(heightPt).append("pt");
        if (fig.fallbackRendered()) out.append(" fallback=true");
        out.append(" -->\n");

        String ext = fig.imageFormat() != null ? fig.imageFormat().toLowerCase() : "png";
        if (fig.imageData() != null && fig.imageData().length > 0) {
            String path = saveImage(fig.imageData(), ext, "fig");
            if (path != null) {
                out.append("![](").append(path).append(")\n\n");
            } else {
                out.append("<!-- figure: no-image-data -->\n\n");
            }
        } else if (fig.imagePath() != null && !fig.imagePath().isEmpty()) {
            String path = copyImage(fig.imagePath(), ext, "fig");
            if (path != null) {
                out.append("![](").append(path).append(")\n\n");
            } else {
                out.append("<!-- figure: no-image-data -->\n\n");
            }
        } else {
            out.append("<!-- figure: no-image-data -->\n\n");
        }
    }

    private void writeTable(ASTTable table) {
        if (table.rows().isEmpty()) return;

        // 최대 컬럼 수 산출
        int colCount = 0;
        for (ASTTableRow row : table.rows()) {
            colCount = Math.max(colCount, row.cells().size());
        }
        if (colCount == 0) return;

        out.append("<!-- table rows=").append(table.rows().size())
           .append(" cols=").append(colCount).append(" -->\n");

        // 헤더 행
        ASTTableRow headerRow = table.rows().get(0);
        out.append("|");
        for (int col = 0; col < colCount; col++) {
            String cell = col < headerRow.cells().size()
                    ? renderCellContent(headerRow.cells().get(col)) : "";
            out.append(" ").append(cell).append(" |");
        }
        out.append("\n");

        // 구분선
        out.append("|");
        for (int col = 0; col < colCount; col++) {
            out.append("-----|");
        }
        out.append("\n");

        // 데이터 행
        for (int rowIdx = 1; rowIdx < table.rows().size(); rowIdx++) {
            ASTTableRow row = table.rows().get(rowIdx);
            out.append("|");
            for (int col = 0; col < colCount; col++) {
                String cell = col < row.cells().size()
                        ? renderCellContent(row.cells().get(col)) : "";
                out.append(" ").append(cell).append(" |");
            }
            out.append("\n");
        }
        out.append("\n");
    }

    private String renderCellContent(ASTTableCell cell) {
        StringBuilder buf = new StringBuilder();
        for (int pIdx = 0; pIdx < cell.paragraphs().size(); pIdx++) {
            if (pIdx > 0) buf.append("<br>");
            ASTParagraph para = cell.paragraphs().get(pIdx);
            for (ASTInlineItem item : para.items()) {
                switch (item.itemType()) {
                    case TEXT_RUN:
                        buf.append(renderRun((ASTTextRun) item));
                        break;
                    case BREAK:
                        ASTBreak br = (ASTBreak) item;
                        if (br.breakType() == ASTBreak.BreakType.LINE) buf.append("<br>");
                        break;
                    case INLINE_OBJECT:
                        buf.append(renderInlineObject((ASTInlineObject) item));
                        break;
                    case EQUATION:
                        buf.append(renderEquation((ASTEquation) item));
                        break;
                }
            }
        }
        return buf.toString();
    }

    // --------------------------------------------------------
    // Inline renderers
    // --------------------------------------------------------

    private String renderRun(ASTTextRun run) {
        String text = run.text();
        if (text == null || text.isEmpty()) return "";
        text = normalizeSpaces(text);
        text = escape(text);

        boolean bold   = run.fontStyle() != null && run.fontStyle().contains("Bold");
        boolean italic = run.fontStyle() != null && run.fontStyle().contains("Italic");

        if (bold && italic) text = "**_" + text + "_**";
        else if (bold)      text = "**" + text + "**";
        else if (italic)    text = "_" + text + "_";

        if (run.underline())     text = "<u>" + text + "</u>";
        if (run.strikeThrough()) text = "~~" + text + "~~";
        if (run.superscript())   text = "<sup>" + text + "</sup>";
        if (run.subscript())     text = "<sub>" + text + "</sub>";

        return text;
    }

    private String renderBreak(ASTBreak br) {
        if (br.breakType() == ASTBreak.BreakType.LINE) return "  \n";
        return "";
    }

    private String renderInlineObject(ASTInlineObject obj) {
        if (obj.kind() == null) return "";
        switch (obj.kind()) {
            case IMAGE:
            case RENDERED_GROUP:
            case INLINE_BADGE_GROUP: {
                String ext = obj.imageFormat() != null ? obj.imageFormat().toLowerCase() : "png";
                String path = null;
                if (obj.imageData() != null && obj.imageData().length > 0) {
                    path = saveImage(obj.imageData(), ext, "inline");
                } else if (obj.imagePath() != null && !obj.imagePath().isEmpty()) {
                    path = copyImage(obj.imagePath(), ext, "inline");
                }
                return path != null ? "![](" + path + ")" : "";
            }
            case INLINE_TEXT_FRAME: {
                if (obj.paragraphs() == null) return "";
                StringBuilder buf = new StringBuilder();
                for (ASTParagraph para : obj.paragraphs()) {
                    for (ASTInlineItem item : para.items()) {
                        if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                            buf.append(renderRun((ASTTextRun) item));
                        } else if (item.itemType() == ASTInlineItem.ItemType.BREAK
                                && ((ASTBreak) item).breakType() == ASTBreak.BreakType.LINE) {
                            buf.append(" ");
                        }
                    }
                }
                return buf.toString();
            }
            case SPACER_RECT:
            default:
                return "";
        }
    }

    private String renderEquation(ASTEquation eq) {
        String script = eq.hwpScript();
        return (script != null && !script.isEmpty())
                ? "`[수식: " + script + "]`"
                : "`[수식]`";
    }

    // --------------------------------------------------------
    // Text processing
    // --------------------------------------------------------

    private String normalizeSpaces(String text) {
        text = text.replace('　', ' '); // Ideographic Space — 전각 CJK
        text = text.replace(' ', ' '); // Em Space — 1각
        text = text.replace(' ', ' '); // En Space — 반각
        text = text.replace(' ', ' '); // Four-Per-Em Space — 1/4각
        text = text.replace(' ', ' '); // Six-Per-Em Space — 1/6각
        text = text.replace('\t', ' ');
        return text;
    }

    private String escape(String text) {
        text = text.replace("\\", "\\\\");
        text = text.replace("`",  "\\`");
        text = text.replace("*",  "\\*");
        text = text.replace("_",  "\\_");
        text = text.replace("{",  "\\{");
        text = text.replace("}",  "\\}");
        text = text.replace("[",  "\\[");
        text = text.replace("]",  "\\]");
        text = text.replace("(",  "\\(");
        text = text.replace(")",  "\\)");
        text = text.replace("|",  "\\|");
        text = text.replace("~",  "\\~");
        return text;
    }

    private String escapeLineStart(String content) {
        if (content == null || content.isEmpty()) return content;
        // 첫 줄 앞부분만 검사
        int nlIdx = content.indexOf('\n');
        String firstLine = nlIdx >= 0 ? content.substring(0, nlIdx) : content;

        // ordered list 방지: ^\\d+\\.\\s
        if (firstLine.matches("\\d+\\.\\s.*")) {
            content = content.replaceFirst("(\\d+)\\.", "$1\\\\.");
        }
        // heading 방지
        if (content.startsWith("#")) content = "\\" + content;
        // unordered list 방지
        if (content.startsWith("+") || content.startsWith("-")) content = "\\" + content;
        return content;
    }

    private int detectHeadingLevel(String styleName) {
        if (styleName == null || styleName.isEmpty()) return 0;
        String lower = styleName.toLowerCase();
        if (lower.contains("heading 1") || lower.contains("제목1")
                || lower.contains("대단원") || lower.contains("chapter")) return 1;
        if (lower.contains("heading 2") || lower.contains("제목2")
                || lower.contains("소단원")
                || (lower.contains("section") && !lower.contains("subsection"))) return 2;
        if (lower.contains("subsection") || lower.contains("heading 3")
                || lower.contains("제목3")) return 3;
        if (lower.contains("heading 4") || lower.contains("제목4")) return 4;
        return 0;
    }

    // --------------------------------------------------------
    // Image I/O
    // --------------------------------------------------------

    private String saveImage(byte[] data, String ext, String prefix) {
        try {
            imagesDir.mkdirs();
            int seq = "fig".equals(prefix) ? ++figSeq : ++inlineSeq;
            String fileName = prefix + "_" + String.format("%03d", seq) + "." + ext;
            File file = new File(imagesDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }
            return imagesDirName + "/" + fileName;
        } catch (IOException e) {
            System.err.println("[Markdown] image save failed: " + e.getMessage());
            return null;
        }
    }

    private String copyImage(String srcPath, String ext, String prefix) {
        try {
            File src = new File(srcPath);
            if (!src.exists()) return null;
            imagesDir.mkdirs();
            // ext from caller, but derive from srcPath if available
            int dotIdx = srcPath.lastIndexOf('.');
            if (dotIdx >= 0) ext = srcPath.substring(dotIdx + 1).toLowerCase();
            int seq = "fig".equals(prefix) ? ++figSeq : ++inlineSeq;
            String fileName = prefix + "_" + String.format("%03d", seq) + "." + ext;
            File dest = new File(imagesDir, fileName);
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return imagesDirName + "/" + fileName;
        } catch (IOException e) {
            System.err.println("[Markdown] image copy failed: " + e.getMessage());
            return null;
        }
    }

    // --------------------------------------------------------
    // Inner classes
    // --------------------------------------------------------

    static class StoryGroup {
        String key;
        List<FrameEntry> frames;
        int ownerPage;
        List<Integer> pages;
        long representativeY;
        long centerX;
        long width;
        String fillColor;
    }

    static class FrameEntry {
        ASTTextFrameBlock tf;
        int pageNumber;
        FrameEntry(ASTTextFrameBlock tf, int pageNumber) {
            this.tf = tf;
            this.pageNumber = pageNumber;
        }
    }

    static class SortableBlock {
        enum Column { FULL, LEFT, RIGHT }
        Object block; // StoryGroup | ASTFigure | ASTTable | ASTTextFrameBlock(page_num)
        long representativeY;
        long centerX;
        long width;
        Column column;
    }
}
