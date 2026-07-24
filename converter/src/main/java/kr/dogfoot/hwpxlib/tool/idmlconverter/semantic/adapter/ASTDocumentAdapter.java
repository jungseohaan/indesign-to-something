package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBreak;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.font.FontStyleClassifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ASTDocument 를 직접 감싸는 ASTAdapter 구현체.
 *
 * <p>{@link FeatureExtractor} 같은 시멘틱 코어 컴포넌트가 사용한다.
 * AST 객체와 1:1로 연결되어 인덱스 캐시를 유지.</p>
 *
 * <p>TS 측 ASTJsonAdapter 와 같은 결과를 만들도록 매핑 규칙을 1:1로 따른다.</p>
 */
public class ASTDocumentAdapter implements ASTAdapter {

    private final ASTDocument doc;
    private final Map<String, ASTBlock> blockIndex = new HashMap<>();
    private final Map<String, Integer> blockPageIndex = new HashMap<>();
    private final Map<String, ASTStory> storyIndex = new HashMap<>();
    private final Map<String, StyleInfo> styleIndex = new HashMap<>();
    private String cachedHash;

    public ASTDocumentAdapter(ASTDocument doc) {
        this.doc = doc;
        buildIndices();
    }

    private void buildIndices() {
        if (doc.sections() != null) {
            for (ASTSection section : doc.sections()) {
                int pageNumber = section.pageNumber();
                if (section.blocks() != null) {
                    for (ASTBlock block : section.blocks()) {
                        if (block.sourceId() != null) {
                            blockIndex.put(block.sourceId(), block);
                            blockPageIndex.put(block.sourceId(), pageNumber);
                        }
                    }
                }
            }
        }
        if (doc.stories() != null) {
            for (ASTStory story : doc.stories()) {
                if (story.storyId() != null) {
                    storyIndex.put(story.storyId(), story);
                }
            }
        }
        if (doc.paragraphStyles() != null) {
            for (ASTStyleDef s : doc.paragraphStyles()) {
                styleIndex.put(s.styleId(), mapStyle(s, StyleInfo.StyleType.paragraph));
            }
        }
        if (doc.characterStyles() != null) {
            for (ASTStyleDef s : doc.characterStyles()) {
                styleIndex.put(s.styleId(), mapStyle(s, StyleInfo.StyleType.character));
            }
        }
    }

    // ─── 페이지 / 블록 ────────────────────────────

    @Override
    public List<PageInfo> getPages() {
        List<PageInfo> out = new ArrayList<>();
        if (doc.sections() == null) return out;
        for (ASTSection section : doc.sections()) {
            PageInfo p = new PageInfo();
            p.pageNumber = section.pageNumber();
            if (section.layout() != null) {
                p.width = section.layout().pageWidth();
                p.height = section.layout().pageHeight();
                p.marginTop = section.layout().marginTop();
                p.marginBottom = section.layout().marginBottom();
                p.marginLeft = section.layout().marginLeft();
                p.marginRight = section.layout().marginRight();
                p.columnCount = section.layout().columnCount();
                p.columnGutter = section.layout().columnGutter();
            }
            if (p.columnCount == 0) p.columnCount = 1;
            out.add(p);
        }
        return out;
    }

    @Override
    public List<BlockInfo> getBlocks(int pageNumber) {
        List<BlockInfo> out = new ArrayList<>();
        if (doc.sections() == null) return out;
        for (ASTSection section : doc.sections()) {
            if (section.pageNumber() != pageNumber) continue;
            if (section.blocks() == null) continue;
            for (ASTBlock block : section.blocks()) {
                out.add(mapBlock(block, pageNumber));
            }
        }
        return out;
    }

    @Override
    public List<ParagraphInfo> getParagraphs(String blockId) {
        ASTBlock block = blockIndex.get(blockId);
        if (block == null) return new ArrayList<>();
        List<ASTParagraph> paragraphs = null;
        if (block instanceof ASTTextFrameBlock) {
            paragraphs = ((ASTTextFrameBlock) block).paragraphs();
        }
        // ASTTable / ASTFigure 는 직접적인 paragraphs 없음 (테이블은 셀 안에 있음 — 별도 처리 필요시 확장)
        if (paragraphs == null) return new ArrayList<>();

        List<ParagraphInfo> out = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            out.add(mapParagraph(paragraphs.get(i), i));
        }
        return out;
    }

    // ─── 스토리 / 스타일 ──────────────────────────

    @Override
    public List<StoryInfo> getStories() {
        List<StoryInfo> out = new ArrayList<>();
        if (doc.stories() == null) return out;
        for (ASTStory s : doc.stories()) {
            out.add(mapStory(s));
        }
        return out;
    }

    @Override
    public StoryInfo getStory(String storyId) {
        ASTStory s = storyIndex.get(storyId);
        return s != null ? mapStory(s) : null;
    }

    @Override
    public StyleInfo getStyleByRef(String ref) {
        return styleIndex.get(ref);
    }

    // ─── 메타 ────────────────────────────────────

    @Override
    public String getDocumentHash() {
        if (cachedHash == null) {
            // 간단한 djb2 해시 — TS 측과 동일한 알고리즘
            // 입력은 sourceFile + 섹션 수 + 블록 수의 단순 시그니처.
            // (전체 직렬화 비용 회피)
            StringBuilder sb = new StringBuilder();
            sb.append(doc.sourceFile() != null ? doc.sourceFile() : "");
            sb.append("|sec=").append(doc.sections() != null ? doc.sections().size() : 0);
            int blockCount = 0;
            if (doc.sections() != null) {
                for (ASTSection s : doc.sections()) {
                    if (s.blocks() != null) blockCount += s.blocks().size();
                }
            }
            sb.append("|blk=").append(blockCount);
            cachedHash = djb2Hex(sb.toString());
        }
        return cachedHash;
    }

    private static String djb2Hex(String s) {
        long hash = 5381;
        for (int i = 0; i < s.length(); i++) {
            hash = ((hash << 5) + hash + s.charAt(i)) & 0xFFFFFFFFL;
        }
        return Long.toHexString(hash);
    }

    // ─── 매핑 헬퍼 ───────────────────────────────

    private BlockInfo mapBlock(ASTBlock block, int pageNumber) {
        BlockInfo b = new BlockInfo();
        b.id = block.sourceId() != null ? block.sourceId() : "";
        b.pageNumber = pageNumber;

        if (block instanceof ASTTextFrameBlock) {
            ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
            b.blockType = BlockInfo.BlockType.TEXT_FRAME;
            b.x = tf.x();
            b.y = tf.y();
            b.width = tf.width();
            b.height = tf.height();
            b.zOrder = tf.zOrder();
            b.rotation = tf.rotationAngle();
            b.storyId = tf.storyId();
            b.columnCount = tf.columnCount() > 0 ? tf.columnCount() : 1;
            b.fillColor = tf.fillColor();
            b.strokeColor = tf.strokeColor();
            b.hasFill = b.fillColor != null;
            b.hasStroke = b.strokeColor != null;
            b.isBackgroundOnly = detectBackgroundOnly(tf);
            b.verticalJustification = tf.verticalJustification();
        } else if (block instanceof ASTTable) {
            ASTTable t = (ASTTable) block;
            b.blockType = BlockInfo.BlockType.TABLE;
            b.x = t.x();
            b.y = t.y();
            b.width = t.width();
            b.height = t.height();
            b.zOrder = t.zOrder();
            b.columnCount = 0;
        } else if (block instanceof ASTFigure) {
            ASTFigure f = (ASTFigure) block;
            b.blockType = BlockInfo.BlockType.FIGURE;
            b.x = f.x();
            b.y = f.y();
            b.width = f.width();
            b.height = f.height();
            b.zOrder = f.zOrder();
            b.rotation = f.rotationAngle();
            b.columnCount = 0;
        }
        return b;
    }

    /**
     * TS {@code detectBackgroundOnly} 와 동일: 텍스트가 없고 배경색만 있는 프레임.
     */
    private static boolean detectBackgroundOnly(ASTTextFrameBlock tf) {
        if (tf.fillColor() == null) return false;
        List<ASTParagraph> paras = tf.paragraphs();
        if (paras == null || paras.isEmpty()) return true;
        if (paras.size() == 1) {
            List<ASTInlineItem> items = paras.get(0).items();
            if (items == null || items.isEmpty()) return true;
            if (items.size() == 1 && items.get(0) instanceof ASTTextRun) {
                String text = ((ASTTextRun) items.get(0)).text();
                if (text == null || text.trim().isEmpty()) return true;
            }
        }
        return false;
    }

    private ParagraphInfo mapParagraph(ASTParagraph p, int index) {
        ParagraphInfo pi = new ParagraphInfo();
        pi.index = index;
        pi.alignment = p.alignment();
        pi.paragraphStyleRef = p.paragraphStyleRef();
        pi.firstLineIndent = p.firstLineIndent();
        pi.spaceBefore = p.spaceBefore();
        pi.spaceAfter = p.spaceAfter();
        if (p.items() != null) {
            for (ASTInlineItem item : p.items()) {
                pi.items.add(mapInlineItem(item));
            }
        }
        return pi;
    }

    private static InlineItemInfo mapInlineItem(ASTInlineItem item) {
        InlineItemInfo r = new InlineItemInfo();
        if (item instanceof ASTTextRun) {
            ASTTextRun tr = (ASTTextRun) item;
            r.itemType = InlineItemInfo.ItemType.TEXT_RUN;
            r.text = tr.text();
            r.fontFamily = tr.fontFamily();
            r.fontStyle = tr.fontStyle();
            r.fontSize = tr.fontSizeHwpunits();
            r.textColor = tr.textColor();
            if (FontStyleClassifier.isBoldStyle(tr.fontStyle())) {
                r.bold = Boolean.TRUE;
            }
            r.underline = tr.underline() ? Boolean.TRUE : null;
            r.strikeThrough = tr.strikeThrough() ? Boolean.TRUE : null;
            r.subscript = tr.subscript() ? Boolean.TRUE : null;
            r.superscript = tr.superscript() ? Boolean.TRUE : null;
            r.characterStyleRef = tr.characterStyleRef();
        } else if (item instanceof ASTInlineObject) {
            ASTInlineObject io = (ASTInlineObject) item;
            r.itemType = InlineItemInfo.ItemType.INLINE_OBJECT;
            if (io.kind() != null) {
                r.objectKind = InlineItemInfo.InlineObjectKind.valueOf(io.kind().name());
            }
            r.objectSourceId = io.sourceId();
            r.objectWidth = io.width();
            r.objectHeight = io.height();
        } else if (item instanceof ASTEquation) {
            ASTEquation eq = (ASTEquation) item;
            r.itemType = InlineItemInfo.ItemType.EQUATION;
            r.equationScript = eq.hwpScript();
            r.equationSourceType = eq.sourceType();
            r.equationColor = eq.textColor();
        } else if (item instanceof ASTBreak) {
            ASTBreak br = (ASTBreak) item;
            r.itemType = InlineItemInfo.ItemType.BREAK;
            r.breakType = br.breakType() != null ? br.breakType().name() : null;
        }
        return r;
    }

    private static StoryInfo mapStory(ASTStory s) {
        StoryInfo si = new StoryInfo();
        si.storyId = s.storyId() != null ? s.storyId() : "";
        si.orientation = s.orientation();
        si.linkedFrameIds = s.linkedFrameIds() != null ? new ArrayList<>(s.linkedFrameIds()) : new ArrayList<String>();
        si.pages = s.pages() != null ? new ArrayList<>(s.pages()) : new ArrayList<Integer>();
        si.paragraphCount = s.paragraphCount();
        si.tableCount = s.tableCount();
        return si;
    }

    private static StyleInfo mapStyle(ASTStyleDef s, StyleInfo.StyleType type) {
        StyleInfo si = new StyleInfo();
        si.styleId = s.styleId() != null ? s.styleId() : "";
        si.styleName = s.styleName();
        si.type = type;
        si.basedOnStyleRef = s.basedOnStyleRef();
        si.fontFamily = s.fontFamily();
        si.fontStyle = s.fontStyle();
        si.fontSize = s.fontSizeHwpunits();
        si.bold = s.bold();
        si.italic = s.italic();
        si.alignment = s.alignment();
        si.textColor = s.textColor();
        // TS: bold = bold ?? (fontStyle?.includes('Bold'))
        if (si.bold == null && si.fontStyle != null && si.fontStyle.contains("Bold")) {
            si.bold = Boolean.TRUE;
        }
        if (si.italic == null && si.fontStyle != null && si.fontStyle.contains("Italic")) {
            si.italic = Boolean.TRUE;
        }
        return si;
    }
}
