package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.tool.equationconverter.EquationBuilder;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.CharPrBuilder;

/**
 * AST 단락/텍스트 런/수식/줄바꿈을 HWPX SubList 내 Para로 변환한다.
 */
public class HwpxParagraphBuilder {

    /** 분수 수식 높이 배율: 분자(1) + 분수선(0.2) + 분모(1) + 여유(0.6) */
    private static final double FRACTION_HEIGHT_MULTIPLIER = 2.8;
    /** 일반 수식 높이 배율 */
    private static final double NORMAL_EQUATION_HEIGHT_MULTIPLIER = 1.4;

    final HwpxConverterContext ctx;

    // 순환 의존 해소를 위해 setter 주입
    private HwpxTextBoxBuilder textBoxBuilder;
    private HwpxTableBuilder tableBuilder;
    private HwpxImageBuilder imageBuilder;

    public HwpxParagraphBuilder(HwpxConverterContext ctx) {
        this.ctx = ctx;
    }

    public void setBuilders(HwpxTextBoxBuilder tb, HwpxTableBuilder tab, HwpxImageBuilder img) {
        this.textBoxBuilder = tb;
        this.tableBuilder = tab;
        this.imageBuilder = img;
    }

    // ── 단락 변환 (SubList 내) ──

    void addParagraphToSubList(SubList subList, ASTParagraph astPara) {
        addParagraphToSubList(subList, astPara, 0);
    }

    void addParagraphToSubList(SubList subList, ASTParagraph astPara, long cellHeight) {
        String paraPrId = "3";
        String styleId = "0";
        String paraCharPrId = "0";

        // 스타일 해결
        if (astPara.paragraphStyleRef() != null) {
            String ref = HwpxUtil.resolveStyleRef(astPara.paragraphStyleRef(), ctx.styleRegistry);
            String mapped = ctx.styleRegistry.getParaPrId(ref);
            if (mapped != null) paraPrId = mapped;
            String mappedStyle = ctx.styleRegistry.getStyleId(ref);
            if (mappedStyle != null) styleId = mappedStyle;
            String mappedCharPr = ctx.styleRegistry.getCharPrId(ref);
            if (mappedCharPr != null) paraCharPrId = mappedCharPr;
        }

        // 단락 속성 오버라이드가 있으면 새 ParaPr 생성
        if (hasParagraphOverrides(astPara)) {
            paraPrId = createOverrideParaPr(astPara, paraPrId);
        }

        // 셀 높이가 작으면 줄간격을 FIXED로 강제하여 한컴의 최소 행높이 확장 방지
        if (cellHeight > 0 && cellHeight < ConverterConstants.MIN_TEXT_BOX_HEIGHT) {
            paraPrId = getOrCreateTinyParaPr(cellHeight);
            paraCharPrId = getOrCreateTinyCharPr();
        }

        // 인라인 텍스트 프레임이 줄 간격보다 크면 줄 간격 확장
        long maxInlineH = maxInlineObjectHeight(astPara);
        if (maxInlineH > 2000) {
            paraPrId = ensureLineSpacingForInline(paraPrId, maxInlineH);
        }

        // 분수 수식이 줄 간격보다 크면 줄 간격 확장
        long maxEqH = maxFractionEquationHeight(astPara);
        if (maxEqH > maxInlineH && maxEqH > 2000) {
            paraPrId = ensureLineSpacingForInline(paraPrId, maxEqH);
        }

        Para para = subList.addNewPara();
        para.idAnd(HwpxUtil.nextParaId())
                .paraPrIDRefAnd(paraPrId)
                .styleIDRefAnd(styleId)
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        // 인라인 항목 변환
        for (ASTInlineItem item : astPara.items()) {
            switch (item.itemType()) {
                case TEXT_RUN:
                    addTextRun(para, (ASTTextRun) item, paraCharPrId);
                    break;
                case INLINE_OBJECT:
                    addInlineObject(para, (ASTInlineObject) item);
                    break;
                case BREAK:
                    addBreak(para, (ASTBreak) item);
                    break;
                case EQUATION:
                    addEquationRun(para, (ASTEquation) item);
                    break;
            }
        }

        // 빈 단락이면 최소 Run 추가
        if (!para.runs().iterator().hasNext()) {
            Run run = para.addNewRun();
            run.charPrIDRef(paraCharPrId);
            run.addNewT();
        }

        // 셀 내 Y 커서 업데이트 (오버레이 좌표 계산용)
        ctx.cellContentYCursor += estimateParagraphHeight(astPara);
    }

    /**
     * AST 단락의 높이를 추정한다.
     * 인라인 객체 중 가장 큰 높이를 사용하고, 텍스트만 있으면 기본 행높이(500 hwpunit ≈ 5pt).
     */
    private long estimateParagraphHeight(ASTParagraph para) {
        long maxInlineH = 0;
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                ASTInlineObject obj = (ASTInlineObject) item;
                long h = obj.height();
                // IMAGE with container: 컨테이너 높이 사용
                if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE
                        && obj.containerHeight() > 0) {
                    h = obj.containerHeight();
                }
                if (h > maxInlineH) maxInlineH = h;
            }
        }
        return maxInlineH > 0 ? maxInlineH : 500;
    }

    // ── 인라인 객체 디스패치 ──

    private void addInlineObject(Para para, ASTInlineObject obj) {
        if (obj.kind() == ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) {
            if (shouldFlattenInlineTextFrame(obj)) {
                flattenInlineTextFrame(para, obj);
            } else {
                textBoxBuilder.addInlineTextFrame(para, obj);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP) {
            // 단락 콘텐츠 또는 인라인 테이블이 있는 그룹은 글상자로, 없으면 이미지로
            boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
            boolean hasInlineTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
            if (hasParagraphs || hasInlineTables) {
                textBoxBuilder.addInlineTextFrame(para, obj);
            } else if (obj.imageData() != null && obj.imageData().length > 0) {
                imageBuilder.addInlineImage(para, obj);
            } else if (obj.width() > 0 || obj.height() > 0) {
                // 내용 없는 빈 인라인 사각형 → 스페이서 rect (공간 확보)
                textBoxBuilder.addSpacerRect(para, obj);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE) {
            if (obj.overlayFrames() != null && !obj.overlayFrames().isEmpty()) {
                imageBuilder.addInlineImageWithOverlays(para, obj, textBoxBuilder, this);
            } else {
                imageBuilder.addInlineImage(para, obj);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.SPACER_RECT) {
            textBoxBuilder.addSpacerRect(para, obj);
        }
    }

    // ── 인라인 TextFrame 펼침 (단순 구조일 때 hp:rect 없이 부모 문단에 직접 삽입) ──

    /**
     * 단순 인라인 TextFrame인지 판별:
     * - 단일 문단, 테이블 없음, 배경/테두리 없음, 오버레이 아님
     */
    private boolean shouldFlattenInlineTextFrame(ASTInlineObject obj) {
        if (obj.isOverlay()) return false;
        if (obj.inlineTables() != null && !obj.inlineTables().isEmpty()) return false;
        if (obj.paragraphs() == null || obj.paragraphs().size() != 1) return false;
        if (obj.fillColor() != null && !obj.fillColor().isEmpty()) return false;
        if (obj.strokeWeight() > 0.5) return false;
        // anchoredPosition이 있는 앵커 객체는 펼치지 않음
        String ap = obj.anchoredPosition();
        if (ap != null && !"InlinePosition".equals(ap)) return false;
        // 프레임 폭이 텍스트 내용 대비 과도하게 크면 펼치지 않음
        // (숫자 배지 등 시각적 간격을 제공하는 컨테이너)
        if (obj.width() > 1500) {
            ASTParagraph innerPara = obj.paragraphs().get(0);
            int charCount = 0;
            for (ASTInlineItem item : innerPara.items()) {
                if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null) charCount += text.length();
                }
            }
            // 프레임 폭 > 추정 텍스트 폭의 2배이면 컨테이너로 간주
            if (charCount > 0 && obj.width() > charCount * 700 * 2) return false;
        }
        return true;
    }

    /**
     * 인라인 TextFrame의 단일 문단 콘텐츠를 부모 문단에 직접 삽입.
     * 작은 글꼴의 짧은 텍스트(어휘 번호 등)는 위첨자로 표시.
     */
    private void flattenInlineTextFrame(Para para, ASTInlineObject obj) {
        ASTParagraph innerPara = obj.paragraphs().get(0);

        // 인라인 TextFrame이 위첨자 후보인지 판별:
        // - 단일 텍스트 런, 3글자 이하, 글꼴 크기 ≤ 8pt (800 HWPUNIT)
        boolean applySuperscript = false;
        if (innerPara.items().size() == 1
                && innerPara.items().get(0).itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
            ASTTextRun tr = (ASTTextRun) innerPara.items().get(0);
            String text = tr.text();
            int fontSize = tr.fontSizeHwpunits() != null ? tr.fontSizeHwpunits() : 0;
            if (text != null && text.trim().length() <= 3 && fontSize > 0 && fontSize <= 800) {
                applySuperscript = true;
            }
        }

        for (ASTInlineItem item : innerPara.items()) {
            switch (item.itemType()) {
                case TEXT_RUN:
                    ASTTextRun textRun = (ASTTextRun) item;
                    if (applySuperscript) textRun.superscript(true);
                    addTextRun(para, textRun, "0");
                    break;
                case INLINE_OBJECT:
                    addInlineObject(para, (ASTInlineObject) item);
                    break;
                case BREAK:
                    addBreak(para, (ASTBreak) item);
                    break;
                case EQUATION:
                    addEquationRun(para, (ASTEquation) item);
                    break;
            }
        }
    }

    // ── 인라인 객체 높이 기반 줄 간격 확장 ──

    private long maxInlineObjectHeight(ASTParagraph astPara) {
        long max = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                ASTInlineObject obj = (ASTInlineObject) item;
                // HWPX에서는 모든 인라인 객체가 줄 간격에 영향을 주므로
                // textWrapMode와 무관하게 높이를 반영한다
                if (obj.height() > max) {
                    max = obj.height();
                }
            }
        }
        return max;
    }

    private long maxFractionEquationHeight(ASTParagraph astPara) {
        long max = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.EQUATION) {
                ASTEquation eq = (ASTEquation) item;
                String script = eq.hwpScript();
                if (script != null && script.contains(" over ")) {
                    long estH = (long) (1100 * FRACTION_HEIGHT_MULTIPLIER);
                    if (estH > max) max = estH;
                }
            }
        }
        return max;
    }

    private String ensureLineSpacingForInline(String paraPrId, long inlineHeight) {
        ParaPr basePr = findParaPrById(paraPrId);
        if (basePr == null) return paraPrId;

        boolean needsExpand = false;
        if (basePr.lineSpacing() == null) {
            // lineSpacing 미지정 → 기본값(PERCENT 160 등)은 큰 인라인 객체를 수용 못함
            needsExpand = true;
        } else if (basePr.lineSpacing().type() == LineSpacingType.FIXED
                && basePr.lineSpacing().value() < (int) inlineHeight) {
            // FIXED 줄 간격이 인라인 객체보다 작으면 확장
            needsExpand = true;
        } else if (basePr.lineSpacing().type() == LineSpacingType.PERCENT) {
            // PERCENT 모드: 인라인 객체가 크면 FIXED로 전환
            // PERCENT 값은 글자 크기 기준이므로, 인라인 높이와 직접 비교 불가
            // → 인라인 높이가 충분히 크면 항상 FIXED로 전환
            needsExpand = true;
        }

        if (needsExpand) {
            String newId = ctx.styleRegistry.nextParaPrId();
            ParaPr newPr = ctx.hwpxFile.headerXMLFile().refList().paraProperties().addNew();
            newPr.copyFrom(basePr);
            newPr.id(newId);
            if (newPr.lineSpacing() == null) {
                newPr.createLineSpacing();
            }
            newPr.lineSpacing().typeAnd(LineSpacingType.FIXED).valueAnd((int) inlineHeight);
            return newId;
        }
        return paraPrId;
    }

    private ParaPr findParaPrById(String id) {
        for (ParaPr pr : ctx.hwpxFile.headerXMLFile().refList().paraProperties().items()) {
            if (id.equals(pr.id())) return pr;
        }
        return null;
    }

    // ── 단락 속성 오버라이드 ──

    boolean hasParagraphOverrides(ASTParagraph para) {
        return para.alignment() != null
                || para.firstLineIndent() != null
                || para.leftMargin() != null
                || para.rightMargin() != null
                || para.spaceBefore() != null
                || para.spaceAfter() != null
                || para.lineSpacing() != null
                || para.hasTabStops()
                || para.shadingOn();
    }

    ASTStyleDef findParagraphStyle(String styleRef) {
        if (styleRef == null) return null;
        for (ASTStyleDef sd : ctx.paragraphStyles) {
            if (styleRef.equals(sd.styleId())) return sd;
        }
        // ParagraphStyle/ 접두어 없이 검색
        for (ASTStyleDef sd : ctx.paragraphStyles) {
            String id = sd.styleId();
            if (id != null && id.endsWith("/" + styleRef)) return sd;
        }
        return null;
    }

    String createOverrideParaPr(ASTParagraph astPara, String baseParaPrId) {
        // 기본 스타일에서 값 상속
        ASTStyleDef baseStyle = findParagraphStyle(astPara.paragraphStyleRef());

        String newId = ctx.styleRegistry.nextParaPrId();
        ParaPr paraPr = ctx.hwpxFile.headerXMLFile().refList().paraProperties().addNew();

        // 문단 배경 → BorderFill 생성
        String borderFillRef = "2";
        if (astPara.shadingOn() && astPara.shadingColor() != null) {
            borderFillRef = createParaShadingBorderFill(astPara.shadingColor(), astPara.shadingTint());
        }

        // 마진: 단락 오버라이드 → 스타일 → 0
        int indent = resolveParaLong(astPara.firstLineIndent(),
                baseStyle != null ? baseStyle.firstLineIndent() : null);
        int left = resolveParaLong(astPara.leftMargin(),
                baseStyle != null ? baseStyle.leftMargin() : null);
        int right = resolveParaLong(astPara.rightMargin(),
                baseStyle != null ? baseStyle.rightMargin() : null);
        int prev = resolveParaLong(astPara.spaceBefore(),
                baseStyle != null ? baseStyle.spaceBefore() : null);
        int next = resolveParaLong(astPara.spaceAfter(),
                baseStyle != null ? baseStyle.spaceAfter() : null);

        // HWPX 탭 위치는 leftMargin 기준이므로,
        // 행잉 인덴트(indent < 0)일 때 탭 위치를 leftMargin만큼 감산하고 leftMargin=0으로 설정
        if (indent < 0 && left > 0 && astPara.hasTabStops()) {
            int origLeft = left;
            // 첫 줄 시작 위치를 보존하면서 leftMargin=0, indent=원래첫줄위치
            int origFirstLine = left + indent; // 원래 첫 줄 시작 위치
            indent = origFirstLine; // left=0이므로 indent만으로 첫줄 위치 결정
            left = 0;

            // InDesign 행잉 인덴트 + 탭 패턴: 탭이 LeftIndent 위치로 이동
            // 기존 탭 중 origLeft 이상인 것이 없으면 origLeft 위치에 암시적 탭 추가
            boolean hasTabAtLeft = false;
            for (ASTTabStop ts : astPara.tabStops()) {
                if (Math.abs(ts.position() - origLeft) < 50) { // 0.5pt 이내
                    hasTabAtLeft = true;
                    break;
                }
            }
            if (!hasTabAtLeft) {
                astPara.addTabStop(new ASTTabStop(origLeft, "left", null));
            }
        }

        // 인라인 탭 정지점 → TabPr 생성 (마진 조정 후에 실행해야 암시적 탭 포함)
        String tabPrId = "0";
        if (astPara.hasTabStops()) {
            tabPrId = ctx.styleRegistry.createInlineTabPr(astPara.tabStops());
        }

        paraPr.idAnd(newId)
                .tabPrIDRefAnd(tabPrId)
                .condenseAnd((byte) 0)
                .fontLineHeightAnd(false)
                .snapToGridAnd(true)
                .suppressLineNumbersAnd(false)
                .checked(false);

        // 문단 배경이 있으면 border에 borderFillIDRef 설정 + 음영 오프셋 적용
        if (!"2".equals(borderFillRef)) {
            paraPr.createBorder();
            paraPr.border().borderFillIDRefAnd(borderFillRef);
            // 단락 배경 여백 (InDesign shading offset → HWPX border offset)
            if (astPara.shadingLeftOffset() != null) paraPr.border().offsetLeft(astPara.shadingLeftOffset().intValue());
            if (astPara.shadingRightOffset() != null) paraPr.border().offsetRight(astPara.shadingRightOffset().intValue());
            if (astPara.shadingTopOffset() != null) paraPr.border().offsetTop(astPara.shadingTopOffset().intValue());
            if (astPara.shadingBottomOffset() != null) paraPr.border().offsetBottom(astPara.shadingBottomOffset().intValue());
        }

        // 정렬: 단락 오버라이드 → 스타일 → JUSTIFY
        String alignStr = astPara.alignment();
        if (alignStr == null && baseStyle != null) {
            alignStr = baseStyle.alignment();
        }
        HorizontalAlign2 hAlign = HwpxUtil.mapAlignment(alignStr);
        paraPr.createAlign();
        paraPr.align().horizontalAnd(hAlign).vertical(VerticalAlign1.BASELINE);

        paraPr.createHeading();
        paraPr.heading().typeAnd(ParaHeadingType.NONE).idRefAnd("0").level((byte) 0);

        paraPr.createBreakSetting();
        paraPr.breakSetting()
                .breakLatinWordAnd(LineBreakForLatin.KEEP_WORD)
                .breakNonLatinWordAnd(LineBreakForNonLatin.KEEP_WORD)
                .widowOrphanAnd(false)
                .keepWithNextAnd(astPara.keepWithNext())
                .keepLinesAnd(astPara.keepLinesTogether())
                .pageBreakBeforeAnd(astPara.pageBreakBefore())
                .lineWrap(LineWrap.BREAK);

        paraPr.createAutoSpacing();
        paraPr.autoSpacing().eAsianEngAnd(false).eAsianNum(false);

        paraPr.createMargin();
        paraPr.margin().createIntent();
        paraPr.margin().intent().valueAnd(indent).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createLeft();
        paraPr.margin().left().valueAnd(left).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createRight();
        paraPr.margin().right().valueAnd(right).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createPrev();
        paraPr.margin().prev().valueAnd(prev).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createNext();
        paraPr.margin().next().valueAnd(next).unit(ValueUnit2.HWPUNIT);

        // 줄 간격: 단락 오버라이드 → 스타일 → 160% PERCENT
        paraPr.createLineSpacing();
        Integer lsValue = astPara.lineSpacing();
        String lsType = astPara.lineSpacingType();
        if (lsValue == null && baseStyle != null && baseStyle.lineSpacing() != null) {
            lsValue = baseStyle.lineSpacing();
            lsType = baseStyle.lineSpacingType();
        }
        if (lsValue != null) {
            LineSpacingType hwpxType = "fixed".equals(lsType)
                    ? LineSpacingType.FIXED : LineSpacingType.PERCENT;
            paraPr.lineSpacing()
                    .typeAnd(hwpxType)
                    .valueAnd(lsValue)
                    .unit(ValueUnit2.HWPUNIT);
        } else {
            paraPr.lineSpacing()
                    .typeAnd(LineSpacingType.PERCENT)
                    .valueAnd(160)
                    .unit(ValueUnit2.HWPUNIT);
        }

        return newId;
    }

    /**
     * 문단 배경색용 BorderFill 생성.
     */
    String createParaShadingBorderFill(String color, Double tint) {
        String bfId = String.valueOf(ctx.borderFillIdCounter.getAndIncrement());
        BorderFill bf = ctx.hwpxFile.headerXMLFile().refList().borderFills().addNew();

        bf.idAnd(bfId)
                .threeDAnd(false)
                .shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE)
                .breakCellSeparateLine(false);

        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);

        bf.createLeftBorder();
        bf.leftBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createRightBorder();
        bf.rightBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createTopBorder();
        bf.topBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createBottomBorder();
        bf.bottomBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");

        // tint 적용: InDesign tint는 -1(100%)이 기본, 0~100 범위
        float alpha = 0f;
        if (tint != null && tint >= 0 && tint < 100) {
            alpha = (float) ((100.0 - tint) / 100.0);
        }
        bf.createFillBrush();
        bf.fillBrush().createWinBrush();
        bf.fillBrush().winBrush()
                .faceColorAnd(color)
                .hatchColorAnd("#FF000000")
                .alpha(alpha);

        return bfId;
    }

    /**
     * RuleBelow 답안 밑줄선 → 하단만 SOLID인 BorderFill 생성.
     */
    static int resolveParaLong(Long paraOverride, Long styleValue) {
        if (paraOverride != null) return paraOverride.intValue();
        if (styleValue != null) return styleValue.intValue();
        return 0;
    }

    // ── 텍스트 런 변환 ──

    void addTextRun(Para para, ASTTextRun textRun, String defaultCharPrId) {
        String text = HwpxUtil.sanitizeText(textRun.text());
        if (text == null || text.isEmpty()) return;

        String charPrId = defaultCharPrId;

        // 인라인 스타일 오버라이드
        if (hasCharacterOverrides(textRun)) {
            charPrId = createOverrideCharPr(textRun);
        } else if (textRun.characterStyleRef() != null) {
            String charRef = HwpxUtil.resolveStyleRef(textRun.characterStyleRef(), ctx.styleRegistry);
            String mapped = ctx.styleRegistry.getCharPrId(charRef);
            if (mapped != null) charPrId = mapped;
        }

        // 수식 폰트(NP_, BT수식, GREP 해석) → 밑줄 + 초록색 스타일
        if (isEquationFont(textRun.fontFamily()) || textRun.grepMathFont()) {
            charPrId = createEquationFontCharPr(textRun, charPrId);
        }

        Run run = para.addNewRun();
        run.charPrIDRef(charPrId);

        // 탭/줄바꿈 문자를 적절한 HWPX 요소로 변환
        if (text.indexOf('\t') >= 0 || text.indexOf('\n') >= 0) {
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T t = run.addNewT();
            addTextWithSpecialChars(t, text);
        } else {
            run.addNewT().addText(text);
        }
    }

    /**
     * 텍스트 내의 탭(\t)과 줄바꿈(\n) 문자를 HWPX 요소로 변환하여 T에 추가.
     * \t → <tab />, \n → <lineBreak />
     */
    void addTextWithSpecialChars(
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T t, String text) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t') {
                if (buf.length() > 0) {
                    t.addText(buf.toString());
                    buf.setLength(0);
                }
                t.addNewTab();
            } else if (c == '\n') {
                if (buf.length() > 0) {
                    t.addText(buf.toString());
                    buf.setLength(0);
                }
                t.addNewLineBreak();
            } else if (c == '\r') {
                // \r 무시 (\r\n의 경우 \n이 처리)
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            t.addText(buf.toString());
        }
    }

    boolean hasCharacterOverrides(ASTTextRun run) {
        return run.fontFamily() != null
                || run.fontSizeHwpunits() != null
                || run.textColor() != null
                || run.letterSpacing() != null
                || run.horizontalScale() != null
                || run.verticalScale() != null
                || run.baselineShift() != null
                || run.subscript()
                || run.superscript()
                || run.underline()
                || run.strikeThrough();
    }

    String charPrCacheKey(ASTTextRun textRun) {
        return (textRun.fontFamily() != null ? textRun.fontFamily() : "")
                + "|" + (textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : "")
                + "|" + (textRun.textColor() != null ? textRun.textColor() : "")
                + "|" + (textRun.fontStyle() != null ? textRun.fontStyle() : "")
                + "|" + (textRun.letterSpacing() != null ? textRun.letterSpacing() : "")
                + "|" + (textRun.horizontalScale() != null ? textRun.horizontalScale() : "")
                + "|" + (textRun.verticalScale() != null ? textRun.verticalScale() : "")
                + "|" + (textRun.baselineShift() != null ? textRun.baselineShift() : "")
                + "|" + textRun.superscript()
                + "|" + textRun.subscript()
                + "|" + textRun.underline()
                + "|" + (textRun.underlineColor() != null ? textRun.underlineColor() : "")
                + "|" + (textRun.underlineShape() != null ? textRun.underlineShape() : "")
                + "|" + textRun.strikeThrough()
                + "|" + textRun.grepMathFont();
    }

    String createOverrideCharPr(ASTTextRun textRun) {
        String cacheKey = charPrCacheKey(textRun);
        String cached = ctx.charPrCache.get(cacheKey);
        if (cached != null) return cached;

        String newId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int height = textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : 1000;
        String textColor = textRun.textColor() != null ? textRun.textColor() : "#000000";
        String fontStyle = textRun.fontStyle() != null ? textRun.fontStyle().toLowerCase() : "";

        // underline shape: ASTTextRun.underlineShape → LineType3
        LineType3 ulShape = null;
        if (textRun.underline() && textRun.underlineShape() != null) {
            ulShape = LineType3.fromString(textRun.underlineShape());
        }

        CharPrBuilder.build(charPr, newId, height, textColor,
                textRun.fontFamily(), ctx.fontRegistry,
                textRun.letterSpacing(),
                isBoldStyle(fontStyle),
                isItalicStyle(fontStyle),
                textRun.superscript(), textRun.subscript(),
                textRun.underline() ? UnderlineType.BOTTOM : UnderlineType.NONE,
                textRun.underline()
                        ? (textRun.underlineColor() != null ? textRun.underlineColor() : textColor)
                        : "#000000",
                ulShape,
                textRun.horizontalScale(),
                textRun.strikeThrough(),
                textRun.verticalScale(),
                textRun.baselineShift());

        ctx.charPrCache.put(cacheKey, newId);
        return newId;
    }

    /**
     * fontStyle에서 Bold 여부를 판별한다.
     * 단어 경계 기반 매칭으로 장식 폰트 이름(예: "SusicBoldItalicB140") 오인식을 방지.
     * Helvetica Neue 넘버링(65+)도 DemiBold 이상으로 판단.
     */
    static boolean isBoldStyle(String fontStyle) {
        if (fontStyle == null || fontStyle.isEmpty()) return false;
        String lower = fontStyle.toLowerCase();
        // "Bold", "Semi Bold", "SemiBold", "DemiBold" 등 단어 경계 매칭
        if (lower.matches(".*\\b(bold|semibold|demibold|heavy|black|extrabold)\\b.*")) return true;
        // Helvetica Neue 넘버링: "65 Medium", "75 Bold" 등 — 65 이상은 DemiBold급
        if (lower.matches("^(\\d{2,3})\\s+.*")) {
            try {
                int num = Integer.parseInt(lower.split("\\s+")[0]);
                if (num >= 65) return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    /**
     * fontStyle에서 Italic 여부를 판별한다.
     * 단어 경계 기반 매칭으로 장식 폰트 이름 오인식을 방지.
     */
    static boolean isItalicStyle(String fontStyle) {
        if (fontStyle == null || fontStyle.isEmpty()) return false;
        String lower = fontStyle.toLowerCase();
        return lower.matches(".*\\b(italic|oblique)\\b.*");
    }

    static boolean isEquationFont(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("NP_")
                || BTFontGlyphMap.isBTFontFamily(fontFamily)
                || EHFontGlyphMap.isEHFontFamily(fontFamily);
    }

    String createEquationFontCharPr(ASTTextRun textRun, String baseCharPrId) {
        String cacheKey = baseCharPrId + "|EQ|" + (textRun.fontFamily() != null ? textRun.fontFamily() : "")
                + "|" + (textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : "")
                + "|" + (textRun.textColor() != null ? textRun.textColor() : "");
        String cached = ctx.eqFontCharPrCache.get(cacheKey);
        if (cached != null) return cached;

        String newId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int height = textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : 1000;
        String textColor = textRun.textColor() != null ? textRun.textColor() : "#000000";
        String fontStyle = textRun.fontStyle() != null ? textRun.fontStyle().toLowerCase() : "";

        CharPrBuilder.build(charPr, newId, height, textColor,
                textRun.fontFamily(), ctx.fontRegistry,
                textRun.letterSpacing(),
                isBoldStyle(fontStyle),
                isItalicStyle(fontStyle),
                textRun.superscript(), textRun.subscript(),
                UnderlineType.NONE, textColor,
                null, // underlineShape
                null,
                false,
                null, null);

        ctx.eqFontCharPrCache.put(cacheKey, newId);
        return newId;
    }

    // ── 줄바꿈 ──

    void addBreak(Para para, ASTBreak breakItem) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addNewLineBreak();
    }

    /**
     * ASTEquation → HWPX Equation (인라인 수식).
     */
    void addEquationRun(Para para, ASTEquation eq) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        try {
            Equation template = EquationBuilder.fromHwpScript(eq.hwpScript());
            Equation hwpxEq = run.addNewEquation();
            // 수식 색상: AST에서 전달된 색상이 있으면 사용, 없으면 기본 검정
            String eqColor = eq.textColor() != null ? eq.textColor() : template.textColor();
            hwpxEq.versionAnd(template.version())
                    .textColorAnd(eqColor)
                    .baseUnitAnd(template.baseUnit())
                    .lineModeAnd(template.lineMode())
                    .fontAnd(template.font());

            // ShapeObject 기본 속성
            hwpxEq.numberingTypeAnd(NumberingType.EQUATION)
                    .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                    .textFlowAnd(TextFlowSide.BOTH_SIDES)
                    .lockAnd(false);

            // ShapeSize — 한글이 열 때 자동 계산하지만 초기값 필요
            int baseUnit = template.baseUnit() != null ? template.baseUnit() : 1100;
            String script = eq.hwpScript();
            long estW = (long) (script.length() * baseUnit * 0.7);
            // 분수(over) 수식은 분자+분수선+분모로 높이가 크므로 별도 추정
            boolean hasFraction = script.contains(" over ");
            long estH = hasFraction ? (long) (baseUnit * FRACTION_HEIGHT_MULTIPLIER)
                    : (long) (baseUnit * NORMAL_EQUATION_HEIGHT_MULTIPLIER);
            hwpxEq.createSZ();
            hwpxEq.sz().widthAnd(estW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                    .heightAnd(estH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                    .protectAnd(false);

            // ShapePosition — 글자처럼 취급 (인라인)
            hwpxEq.createPos();
            hwpxEq.pos().treatAsCharAnd(true)
                    .affectLSpacingAnd(false)
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(false)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(VertAlign.BOTTOM)
                    .horzAlignAnd(HorzAlign.LEFT)
                    .vertOffsetAnd(0L)
                    .horzOffset(0L);

            // OutMargin — 수식과 주변 텍스트 사이 여백 (좌우 100 HWPUNIT = 1pt)
            // 분수 수식은 상하 여백 추가 (고정 줄간격에서 겹침 방지)
            hwpxEq.createOutMargin();
            long topMargin = hasFraction ? 200L : 0L;
            long bottomMargin = hasFraction ? 200L : 0L;
            hwpxEq.outMargin().leftAnd(100L).rightAnd(100L).topAnd(topMargin).bottomAnd(bottomMargin);

            hwpxEq.createScript();
            hwpxEq.script().addText(eq.hwpScript());
            ctx.equationsConverted++;
        } catch (Exception e) {
            // 수식 파싱 실패 시 텍스트로 표시
            System.err.println("[HwpxParagraphBuilder] 수식 변환 실패: " + e.getMessage()
                    + " (script=" + eq.hwpScript() + ")");
            ctx.addWarning("Equation", "수식 변환 실패: " + eq.hwpScript());
            run.addNewT().addText("[수식: " + eq.hwpScript() + "]");
        }
    }

    // ── LineSeg ──

    public void addLineSegArray(Para para) {
        para.createLineSegArray();
        LineSeg lineSeg = para.lineSegArray().addNew();
        lineSeg.textposAnd(0).vertposAnd(0).vertsizeAnd(1000)
                .textheightAnd(1000).baselineAnd(850).spacingAnd(600)
                .horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
    }

    // ── 빈 SubList 단락 ──

    void addEmptySubListPara(SubList subList) {
        addEmptySubListPara(subList, 0);
    }

    void addEmptySubListPara(SubList subList, long cellHeight) {
        // 빈 셀은 항상 tiny 스타일(1pt 폰트 + FIXED 줄간격)을 적용하여
        // 한컴의 기본 줄 간격이 셀 높이를 늘리는 것을 방지
        String paraPrId = "3";
        String charPrId = "0";

        if (cellHeight > 0) {
            charPrId = getOrCreateTinyCharPr();
            paraPrId = getOrCreateTinyParaPr(cellHeight);
        }

        Para emptyPara = subList.addNewPara();
        emptyPara.idAnd(HwpxUtil.nextParaId())
                .paraPrIDRefAnd(paraPrId)
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        Run run = emptyPara.addNewRun();
        run.charPrIDRef(charPrId);
        run.addNewT();
    }

    // ── Tiny CharPr / ParaPr ──

    String getOrCreateTinyCharPr() {
        if (ctx.tinyCharPrId != null) return ctx.tinyCharPrId;
        ctx.tinyCharPrId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();
        charPr.idAnd(ctx.tinyCharPrId)
                .heightAnd(100)  // 1pt
                .textColorAnd("#000000")
                .shadeColorAnd("none")
                .useFontSpaceAnd(false)
                .useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE)
                .borderFillIDRef("2");
        return ctx.tinyCharPrId;
    }

    String getOrCreateTinyParaPr(long cellHeight) {
        String cached = ctx.tinyParaPrCache.get(cellHeight);
        if (cached != null) return cached;

        String newId = ctx.styleRegistry.nextParaPrId();
        ParaPr paraPr = ctx.hwpxFile.headerXMLFile().refList().paraProperties().addNew();
        paraPr.idAnd(newId);
        paraPr.createLineSpacing();
        paraPr.lineSpacing()
                .typeAnd(LineSpacingType.FIXED)
                .valueAnd((int) cellHeight)
                .unitAnd(ValueUnit2.HWPUNIT);
        paraPr.createMargin();
        paraPr.margin().createIntent();
        paraPr.margin().intent().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createLeft();
        paraPr.margin().left().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createRight();
        paraPr.margin().right().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createPrev();
        paraPr.margin().prev().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createNext();
        paraPr.margin().next().valueAnd(0).unit(ValueUnit2.HWPUNIT);

        ctx.tinyParaPrCache.put(cellHeight, newId);
        return newId;
    }

    // ── LineWidth / LineType 변환 유틸리티 (delegate to HwpxEnumMapper) ──

    static LineWidth hwpunitToLineWidth(double hwpunit) {
        return HwpxEnumMapper.hwpunitToLineWidth(hwpunit);
    }

    static LineType2 strokeTypeToLineType(String strokeType) {
        return HwpxEnumMapper.strokeTypeToLineType(strokeType);
    }

    /**
     * SubList에 ColPr(colCount=1) 리셋 단락 추가
     */
    void addColPrResetParagraph(SubList subList) {
        Para colPrPara = subList.addNewPara();
        colPrPara.idAnd(HwpxUtil.nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run colPrRun = colPrPara.addNewRun();
        colPrRun.charPrIDRef("0");

        Ctrl colCtrl = colPrRun.addNewCtrl();
        colCtrl.addNewColPr()
                .idAnd("").typeAnd(MultiColumnType.NEWSPAPER)
                .layoutAnd(ColumnDirection.LEFT)
                .colCountAnd(1).sameSzAnd(true).sameGap(0);
    }
}
