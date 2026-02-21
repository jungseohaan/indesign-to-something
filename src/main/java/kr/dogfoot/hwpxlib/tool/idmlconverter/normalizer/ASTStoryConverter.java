package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.util.*;

/**
 * 스토리 내 단락/런 변환 로직.
 * Stage4_BuildAST에서 분리됨.
 */
class ASTStoryConverter {

    /** 이 높이(HWPUNIT)를 넘는 인라인 이미지는 별도 단락으로 분리 (~30pt ≈ 1cm) */
    static final long IMAGE_SPLIT_THRESHOLD = 3000;

    /**
     * IDMLParagraph → ASTParagraph 변환.
     */
    static ASTParagraph convertParagraph(IDMLParagraph idmlPara,
                                         FlattenedObjectPool pool,
                                         IDMLDocument idmlDoc,
                                         ColorResolver colorResolver,
                                         ASTImageLoader imageLoader,
                                         boolean storyHasBTRuns) {
        ASTParagraph para = new ASTParagraph();

        // 단락 스타일
        String paraStyleRef = idmlPara.appliedParagraphStyle();
        if (paraStyleRef != null) {
            para.paragraphStyleRef(cleanStyleRef(paraStyleRef));
        }

        // 단락 속성
        if (idmlPara.justification() != null) {
            para.alignment(idmlPara.justification());
        }
        if (idmlPara.firstLineIndent() != null && idmlPara.firstLineIndent() != 0) {
            para.firstLineIndent(CoordinateConverter.pointsToHwpunits(idmlPara.firstLineIndent()));
        }
        if (idmlPara.leftIndent() != null && idmlPara.leftIndent() != 0) {
            para.leftMargin(CoordinateConverter.pointsToHwpunits(idmlPara.leftIndent()));
        }
        if (idmlPara.rightIndent() != null && idmlPara.rightIndent() != 0) {
            para.rightMargin(CoordinateConverter.pointsToHwpunits(idmlPara.rightIndent()));
        }
        if (idmlPara.spaceBefore() != null && idmlPara.spaceBefore() != 0) {
            para.spaceBefore(CoordinateConverter.pointsToHwpunits(idmlPara.spaceBefore()));
        }
        if (idmlPara.spaceAfter() != null && idmlPara.spaceAfter() != 0) {
            para.spaceAfter(CoordinateConverter.pointsToHwpunits(idmlPara.spaceAfter()));
        }
        // 줄간격 (leading)
        if (idmlPara.leading() != null) {
            para.lineSpacingType("fixed");
            para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(idmlPara.leading()));
        }

        // 단락 배경
        if (idmlPara.shadingOn()) {
            para.shadingOn(true);
            String shadingColor = idmlPara.shadingColor();
            if (shadingColor != null) {
                para.shadingColor(colorResolver.resolve(shadingColor));
            }
            para.shadingTint(idmlPara.shadingTint());
        }

        // 탭 정지점 (인라인 오버라이드)
        if (idmlPara.tabStops() != null) {
            for (IDMLStyleDef.TabStop ts : idmlPara.tabStops()) {
                long posHwpunits = CoordinateConverter.pointsToHwpunits(ts.position());
                String alignment = mapTabAlignment(ts.alignment());
                para.addTabStop(new ASTTabStop(posHwpunits, alignment, ts.leader()));
            }
        }

        // Character Runs → 인라인 항목 (BT수식M 폰트 런은 그룹핑하여 ASTEquation으로 변환)
        // NP 폰트는 인라인 주석(아래첨자, 근호 등)이므로 그룹핑하지 않고 텍스트 런으로 처리
        // BT 런 사이에 끼인 짧은 일반 텍스트(변수명 등)는 "브릿지"로 수식 그룹에 포함

        // 전처리: 한국어+수식마커 혼합 런을 분리 (예: "_r를 구해" → "_r" + "를 구해")
        List<IDMLCharacterRun> runs = ASTMathGrouper.splitMathKoreanMixedRuns(idmlPara.characterRuns());
        List<IDMLCharacterRun> mathGroup = new ArrayList<>();

        // 단락 또는 스토리에 BT 수식 폰트 런이 하나라도 있는지 확인
        boolean paraHasBTRuns = storyHasBTRuns;
        if (!paraHasBTRuns) {
            for (IDMLCharacterRun r : runs) {
                if (r.isBTFont() || r.grepMathFont()) { paraHasBTRuns = true; break; }
            }
        }

        for (int idx = 0; idx < runs.size(); idx++) {
            IDMLCharacterRun run = runs.get(idx);
            if ((run.isBTFont() || run.grepMathFont()) && !ASTMathGrouper.isBTRunWithOnlyKorean(run.content())) {
                mathGroup.add(run);
            } else if (!mathGroup.isEmpty() && ASTMathGrouper.isMathBridgeRun(run, runs, idx)) {
                // BT 런 사이 또는 뒤의 비한국어 텍스트 → 수식 그룹에 포함
                mathGroup.add(run);
            } else if (paraHasBTRuns && ASTMathGrouper.looksLikeMathRun(run.content())) {
                // 단락에 BT 런이 있고 이 런에 BT 마커(_^&\) 포함 → 수식 그룹 시작/계속
                mathGroup.add(run);
            } else {
                // 수식 그룹 종료 → 변환
                if (!mathGroup.isEmpty()) {
                    ASTMathGrouper.flushMathGroup(mathGroup, para);
                    mathGroup.clear();
                }
                convertCharacterRun(run, idmlPara, para, pool, idmlDoc, colorResolver, imageLoader);
            }
        }
        // 마지막 수식 그룹 처리
        if (!mathGroup.isEmpty()) {
            ASTMathGrouper.flushMathGroup(mathGroup, para);
        }

        // 단락 끝의 trailing lineBreak 제거
        // 인라인 객체(InlineObject)가 뒤에 있어도, 마지막 텍스트런 이후의 break를 제거
        List<ASTInlineItem> items = para.items();
        int lastTextIdx = -1;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                lastTextIdx = i;
                break;
            }
        }
        for (int i = items.size() - 1; i > lastTextIdx; i--) {
            if (items.get(i).itemType() == ASTInlineItem.ItemType.BREAK) {
                items.remove(i);
            }
        }

        return para;
    }

    /**
     * IDML 탭 정렬 문자열을 HWPX 탭 타입으로 매핑.
     */
    static String mapTabAlignment(String idmlAlignment) {
        if (idmlAlignment == null) return "left";
        switch (idmlAlignment) {
            case "CenterAlign": return "center";
            case "RightAlign": return "right";
            case "DecimalAlign": // IDML uses "Character" for decimal
            case "Character": return "decimal";
            default: return "left"; // LeftAlign 또는 기타
        }
    }

    /**
     * IDMLCharacterRun → ASTTextRun + ASTInlineObject + ASTBreak 변환.
     */
    static void convertCharacterRun(IDMLCharacterRun run, IDMLParagraph parentPara,
                                     ASTParagraph para,
                                     FlattenedObjectPool pool,
                                     IDMLDocument idmlDoc,
                                     ColorResolver colorResolver,
                                     ASTImageLoader imageLoader) {
        String text = run.content();
        if (text != null && !text.isEmpty()) {
            // NP 폰트 글리프 → 유니코드 변환
            if (run.isNPFont()) {
                text = kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap
                        .convertRunToUnicode(run.npFontName(), text);
                if (text.isEmpty()) return; // 분수 괄호 등 변환 후 빈 텍스트
            }
            // 연속 줄바꿈(\n\n+)을 하나로 머지
            text = text.replaceAll("\n{2,}", "\n");
            // 줄바꿈 분리
            String[] segments = text.split("\n", -1);
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) {
                    para.addItem(new ASTBreak(ASTBreak.BreakType.LINE));
                }
                String seg = segments[i];
                if (!seg.isEmpty()) {
                    ASTTextRun textRun = createTextRun(run, seg, parentPara, idmlDoc, colorResolver);
                    para.addItem(textRun);
                }
            }
        }

        // 인라인 텍스트 프레임 (Anchored 위치의 프레임은 본문 뒤로 이동하므로 건너뜀)
        for (IDMLTextFrame inlineTf : run.inlineFrames()) {
            if (Stage4_BuildAST.shouldDeferInlineFrame(inlineTf)) {
                continue;
            }
            ASTInlineObject inlineObj = createInlineObjectFromTextFrame(inlineTf, idmlDoc, colorResolver, imageLoader);
            if (inlineObj != null) {
                para.addItem(inlineObj);
            }
        }

        // 인라인 그래픽
        for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
            ASTInlineObject inlineObj = ASTInlineObjectBuilder.createInlineObjectFromGraphic(ig, imageLoader, colorResolver);
            if (inlineObj != null) {
                // 크기 0인 RENDERED_GROUP 래퍼는 추가하지 않음 (배경 사각형+텍스트프레임 구조의 Group)
                boolean isEmptyWrapper = inlineObj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP
                        && inlineObj.width() <= 0 && inlineObj.height() <= 0
                        && (inlineObj.imageData() == null || inlineObj.imageData().length == 0);
                if (!isEmptyWrapper) {
                    para.addItem(inlineObj);
                }
                // IMAGE로 처리된 Group은 자식 텍스트프레임을 별도 추출하지 않음
                // (이미지와 텍스트 오버레이가 하나의 시각 단위이므로 분리하면 겹침)
                if (inlineObj.kind() == ASTInlineObject.ObjectKind.IMAGE) {
                    continue;
                }
            }
            // 부모 Group의 배경 사각형에서 전체 스타일 추출 (fill, stroke, cornerRadius)
            ASTInlineObjectBuilder.GroupBackground bg = ASTInlineObjectBuilder.extractGroupBackground(ig, colorResolver);
            // 인라인 그래픽 내부의 자식 텍스트프레임 처리 (중첩 Group 포함, 재귀)
            ASTInlineObjectBuilder.collectChildTextFrames(ig, para, idmlDoc, colorResolver, imageLoader, bg);
        }
    }

    /**
     * ASTTextRun 생성.
     * IDML 스타일 상속을 해결하여 fontFamily/fontSize/fillColor 등을 설정.
     *
     * 해결 순서: 런 직접 속성 → 적용된 CharacterStyle → 적용된 ParagraphStyle (basedOn 체인 포함)
     */
    static ASTTextRun createTextRun(IDMLCharacterRun run, String text,
                                     IDMLParagraph parentPara,
                                     IDMLDocument idmlDoc,
                                     ColorResolver colorResolver) {
        ASTTextRun textRun = new ASTTextRun();
        textRun.text(Stage4_BuildAST.stripACEPlaceholders(text));

        String charStyleRef = run.appliedCharacterStyle();
        if (charStyleRef != null) {
            textRun.characterStyleRef(cleanStyleRef(charStyleRef));
        }

        // 스타일 상속 해결: 런 → CharacterStyle → ParagraphStyle
        String fontFamily = run.fontFamily();
        Double fontSize = run.fontSize();
        String fillColor = run.fillColor();
        String fontStyle = run.fontStyle();
        Double tracking = run.tracking();

        // CharacterStyle에서 빈 속성 채우기
        if (charStyleRef != null) {
            IDMLStyleDef charStyle = resolveStyle(charStyleRef, idmlDoc.charStyles());
            if (charStyle != null) {
                if (fontFamily == null) fontFamily = charStyle.fontFamily();
                if (fontSize == null) fontSize = charStyle.fontSize();
                if (fillColor == null) fillColor = charStyle.fillColor();
                if (fontStyle == null) fontStyle = charStyle.fontStyle();
                if (tracking == null) tracking = charStyle.tracking();
            }
        }

        // ParagraphStyle에서 빈 속성 채우기
        String paraStyleRef = parentPara != null ? parentPara.appliedParagraphStyle() : null;
        if (paraStyleRef != null) {
            IDMLStyleDef paraStyle = resolveStyle(paraStyleRef, idmlDoc.paraStyles());
            if (paraStyle != null) {
                if (fontFamily == null) fontFamily = paraStyle.fontFamily();
                if (fontSize == null) fontSize = paraStyle.fontSize();
                if (fillColor == null) fillColor = paraStyle.fillColor();
                if (fontStyle == null) fontStyle = paraStyle.fontStyle();
                if (tracking == null) tracking = paraStyle.tracking();
            }
        }

        textRun.fontFamily(fontFamily);
        textRun.fontStyle(fontStyle);

        if (fontSize != null) {
            textRun.fontSizeHwpunits((int) (fontSize * 100));
        }

        if (fillColor != null) {
            textRun.textColor(colorResolver.resolve(fillColor));
        }

        if (tracking != null) {
            // IDML tracking: 1/1000 em → HWPX spacing: %
            textRun.letterSpacing((short) Math.round(tracking / 10.0));
        }

        textRun.subscript(run.isSubscript());
        textRun.superscript(run.isSuperscript());
        textRun.grepMathFont(run.grepMathFont());

        return textRun;
    }

    /**
     * 스타일 상속 체인(basedOn)을 따라 속성을 해결한다.
     */
    static IDMLStyleDef resolveStyle(String styleRef, Map<String, IDMLStyleDef> allStyles) {
        IDMLStyleDef style = findStyle(styleRef, allStyles);
        if (style == null) return null;
        if (style.basedOn() == null || style.basedOn().isEmpty()) return style;

        // 재귀적으로 부모 해결
        IDMLStyleDef parent = resolveStyle(style.basedOn(), allStyles);
        if (parent == null) return style;

        // 병합: 자식 우선, 빈 속성은 부모에서
        IDMLStyleDef merged = new IDMLStyleDef();
        merged.selfRef(style.selfRef());
        merged.name(style.name());
        merged.fontFamily(style.fontFamily() != null ? style.fontFamily() : parent.fontFamily());
        merged.fontSize(style.fontSize() != null ? style.fontSize() : parent.fontSize());
        merged.fillColor(style.fillColor() != null ? style.fillColor() : parent.fillColor());
        merged.fontStyle(style.fontStyle() != null ? style.fontStyle() : parent.fontStyle());
        merged.bold(style.bold() != null ? style.bold() : parent.bold());
        merged.italic(style.italic() != null ? style.italic() : parent.italic());
        merged.tracking(style.tracking() != null ? style.tracking() : parent.tracking());
        merged.leading(style.leading() != null ? style.leading() : parent.leading());
        merged.leadingType(style.leadingType() != null ? style.leadingType() : parent.leadingType());
        merged.autoLeading(style.autoLeading() != null ? style.autoLeading() : parent.autoLeading());
        return merged;
    }

    /**
     * 스타일 맵에서 스타일을 찾는다.
     * IDML의 basedOn 값은 접두사가 없을 수 있으므로 (예: "$ID/[No paragraph style]"),
     * 직접 조회 실패 시 "ParagraphStyle/" 또는 "CharacterStyle/" 접두사를 붙여 재시도.
     */
    static IDMLStyleDef findStyle(String styleRef, Map<String, IDMLStyleDef> allStyles) {
        if (styleRef == null) return null;
        IDMLStyleDef style = allStyles.get(styleRef);
        if (style != null) return style;

        // 접두사 붙여서 재시도
        for (String prefix : new String[]{"ParagraphStyle/", "CharacterStyle/"}) {
            style = allStyles.get(prefix + styleRef);
            if (style != null) {
                return style;
            }
        }
        return null;
    }

    /**
     * 인라인 텍스트 프레임 → ASTInlineObject(INLINE_TEXT_FRAME) 변환.
     * 인라인 스토리의 단락을 ASTParagraph로 재귀 변환하여 보존.
     */
    static ASTInlineObject createInlineObjectFromTextFrame(IDMLTextFrame tf,
                                                            IDMLDocument idmlDoc,
                                                            ColorResolver colorResolver,
                                                            ASTImageLoader imageLoader) {
        if (tf.parentStoryId() == null) return null;

        IDMLStory inlineStory = idmlDoc.getStory(tf.parentStoryId());
        if (inlineStory == null) return null;

        // 텍스트 내용이 있는지 확인
        boolean hasContent = false;
        for (IDMLParagraph para : inlineStory.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (run.content() != null && !run.content().trim().isEmpty()) {
                    hasContent = true;
                    break;
                }
            }
            if (hasContent) break;
        }
        if (!hasContent && inlineStory.tables().isEmpty()) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.sourceId(tf.selfId());

        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));

        // 인라인 스토리의 단락을 ASTParagraph로 변환 (큰 이미지는 별도 단락으로 분리)
        FlattenedObjectPool emptyPool = new FlattenedObjectPool();
        for (IDMLParagraph idmlPara : inlineStory.paragraphs()) {
            ASTParagraph astPara = convertParagraph(idmlPara, emptyPool, idmlDoc, colorResolver, imageLoader, false);
            if (astPara != null && !astPara.items().isEmpty()) {
                for (ASTParagraph split : splitParagraphAtLargeImages(astPara)) {
                    obj.addParagraph(split);
                }
            }
        }

        // 인라인 스토리의 테이블을 ASTTable로 변환
        for (IDMLTable idmlTable : inlineStory.tables()) {
            ASTTable table = convertInlineTable(idmlTable, idmlDoc, colorResolver, imageLoader);
            if (table != null) {
                obj.addInlineTable(table);
            }
        }

        boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
        boolean hasTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
        return (hasParagraphs || hasTables) ? obj : null;
    }

    /**
     * 단락 내 큰 인라인 이미지를 별도 단락으로 분리.
     * 텍스트와 큰 이미지가 같은 단락에 있으면 고정 줄간격으로 인해 겹침이 발생하므로,
     * [Text, LargeImage, Text] → [TextPara, ImagePara, TextPara] 로 분리.
     */
    static List<ASTParagraph> splitParagraphAtLargeImages(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();

        // 큰 이미지가 있는지 + 텍스트가 있는지 확인
        boolean hasLargeImage = false;
        boolean hasText = false;
        for (ASTInlineItem item : items) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                ASTInlineObject obj = (ASTInlineObject) item;
                if (isLargeImage(obj)) hasLargeImage = true;
            } else if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                hasText = true;
            }
        }
        if (!hasLargeImage || !hasText) {
            return Collections.singletonList(para);
        }

        // 큰 이미지를 경계로 분할
        List<ASTParagraph> result = new ArrayList<>();
        List<ASTInlineItem> currentItems = new ArrayList<>();

        for (ASTInlineItem item : items) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT
                    && isLargeImage((ASTInlineObject) item)) {
                // 축적된 아이템을 단락으로
                if (!currentItems.isEmpty()) {
                    result.add(createSplitParagraph(para, currentItems, result.isEmpty()));
                    currentItems = new ArrayList<>();
                }
                // 큰 이미지를 독립 단락으로
                List<ASTInlineItem> imgItems = new ArrayList<>();
                imgItems.add(item);
                result.add(createSplitParagraph(para, imgItems, result.isEmpty()));
            } else {
                currentItems.add(item);
            }
        }
        // 나머지 아이템
        if (!currentItems.isEmpty()) {
            result.add(createSplitParagraph(para, currentItems, result.isEmpty()));
        }

        // 단락 간격 보존: spaceBefore → 첫 단락만, spaceAfter → 마지막 단락만
        if (result.size() > 1) {
            for (int i = 1; i < result.size(); i++) {
                result.get(i).spaceBefore(0L);
            }
            for (int i = 0; i < result.size() - 1; i++) {
                result.get(i).spaceAfter(0L);
            }
        }

        return result;
    }

    static boolean isLargeImage(ASTInlineObject obj) {
        if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE)
            return obj.height() > IMAGE_SPLIT_THRESHOLD;
        if (obj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP
                && (obj.paragraphs() == null || obj.paragraphs().isEmpty()))
            return obj.height() > IMAGE_SPLIT_THRESHOLD;
        return false;
    }

    /**
     * 원본 단락의 스타일 속성을 복제하여 새 단락 생성.
     * isFirst=true일 때만 firstLineIndent 보존.
     * 이미지 단독 단락은 lineSpacing을 설정하지 않아 자동 확장.
     */
    static ASTParagraph createSplitParagraph(ASTParagraph source,
                                              List<ASTInlineItem> items,
                                              boolean isFirst) {
        ASTParagraph p = new ASTParagraph();
        p.paragraphStyleRef(source.paragraphStyleRef());
        p.alignment(source.alignment());
        p.leftMargin(source.leftMargin());
        p.rightMargin(source.rightMargin());
        p.spaceBefore(source.spaceBefore());
        p.spaceAfter(source.spaceAfter());
        if (isFirst) {
            p.firstLineIndent(source.firstLineIndent());
        }
        // 이미지 단독 단락에는 lineSpacing 미설정 (자동 확장)
        boolean isImageOnly = items.size() == 1
                && items.get(0).itemType() == ASTInlineItem.ItemType.INLINE_OBJECT;
        if (!isImageOnly) {
            p.lineSpacingType(source.lineSpacingType());
            p.lineSpacing(source.lineSpacing());
        }
        p.letterSpacing(source.letterSpacing());
        if (source.tabStops() != null) {
            for (ASTTabStop ts : source.tabStops()) {
                p.addTabStop(ts);
            }
        }
        p.shadingOn(source.shadingOn());
        p.shadingColor(source.shadingColor());
        p.shadingTint(source.shadingTint());

        for (ASTInlineItem item : items) {
            p.addItem(item);
        }
        return p;
    }

    /**
     * 인라인 스토리 내 테이블 → ASTTable 변환 (위치 정보 없이).
     */
    static ASTTable convertInlineTable(IDMLTable idmlTable,
                                        IDMLDocument idmlDoc,
                                        ColorResolver colorResolver,
                                        ASTImageLoader imageLoader) {
        ASTTable table = new ASTTable();
        table.sourceId(idmlTable.selfId());

        // 컬럼 너비
        for (double cw : idmlTable.columnWidths()) {
            table.addColumnWidth(CoordinateConverter.pointsToHwpunits(cw));
        }
        table.colCount(idmlTable.columnWidths().size());

        // 행 변환
        long totalHeight = 0;
        int rowIdx = 0;
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(rowIdx);
            row.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));
            row.autoGrow(idmlRow.autoGrow());
            totalHeight += row.rowHeight();

            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                int colIdx = idmlCell.columnIndex();
                ASTTableCell cell = ASTInlineObjectBuilder.convertTableCell(idmlCell, rowIdx, colIdx,
                        idmlDoc, colorResolver, imageLoader);
                row.addCell(cell);
            }

            table.addRow(row);
            rowIdx++;
        }
        table.rowCount(rowIdx);

        // 테이블 크기
        long totalWidth = 0;
        for (long cw : table.columnWidths()) {
            totalWidth += cw;
        }
        table.width(totalWidth);
        table.height(totalHeight);

        // 셀 크기 계산
        List<Long> colWidths = table.columnWidths();
        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                long cellWidth = 0;
                int startCol = cell.columnIndex();
                int endCol = Math.min(startCol + cell.columnSpan(), colWidths.size());
                for (int c = startCol; c < endCol; c++) {
                    cellWidth += colWidths.get(c);
                }
                cell.width(cellWidth);

                long cellHeight = 0;
                int startRow = cell.rowIndex();
                int endRow = Math.min(startRow + cell.rowSpan(), table.rows().size());
                for (int r = startRow; r < endRow; r++) {
                    cellHeight += table.rows().get(r).rowHeight();
                }
                cell.height(cellHeight);
            }
        }

        ASTTableSpacerMerger.merge(table);
        return table;
    }

    /**
     * 스타일 참조에서 "ParagraphStyle/" 또는 "CharacterStyle/" 접두사 제거.
     */
    static String cleanStyleRef(String ref) {
        if (ref == null) return null;
        if (ref.startsWith("ParagraphStyle/")) {
            return ref.substring("ParagraphStyle/".length());
        }
        if (ref.startsWith("CharacterStyle/")) {
            return ref.substring("CharacterStyle/".length());
        }
        return ref;
    }
}
