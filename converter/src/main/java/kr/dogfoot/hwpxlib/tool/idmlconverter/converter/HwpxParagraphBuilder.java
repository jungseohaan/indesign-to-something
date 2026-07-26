package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * AST 단락/텍스트 런/수식/줄바꿈을 HWPX SubList 내 Para로 변환한다.
 *
 * 책임: 단락 빌드 오케스트레이션 + sub-module 호출 시퀀스 + 외부 delegate.
 * 구체 로직은 sub-module로 분리:
 * - LineSpacingResolver: 단락 높이 추정 + 줄간격 자동 보정
 * - ParaPrFactory: ParaPr 생성 + override + 단락 속성
 * - CharPrFactory: TextRun + CharPr + 공백 분리 + 폰트 스타일
 * - InlineItemDispatcher: 인라인 객체 dispatch + Break + Equation
 *
 * 신규 로직 추가 시 적절한 sub-module을 먼저 검토. 본 클래스에 직접 추가하면 W4-2 작업이 무효화됨.
 */
public class HwpxParagraphBuilder {

    /** 분수 수식 높이 배율: 분자(1) + 분수선(0.2) + 분모(1) + 여유(0.6) */
    static final double FRACTION_HEIGHT_MULTIPLIER = 2.2;
    /** 일반 수식 높이 배율 */
    static final double NORMAL_EQUATION_HEIGHT_MULTIPLIER = 1.0;

    final HwpxConverterContext ctx;

    // 순환 의존 해소를 위해 setter 주입
    HwpxTextBoxBuilder textBoxBuilder;
    HwpxTableBuilder tableBuilder;
    HwpxImageBuilder imageBuilder;

    private final LineSpacingResolver lineSpacingResolver;
    private final ParaPrFactory paraPrFactory;
    private final CharPrFactory charPrFactory;
    private final InlineItemDispatcher inlineItemDispatcher;

    public HwpxParagraphBuilder(HwpxConverterContext ctx) {
        this.ctx = ctx;
        this.lineSpacingResolver = new LineSpacingResolver(ctx, this);
        this.paraPrFactory = new ParaPrFactory(ctx);
        this.charPrFactory = new CharPrFactory(ctx);
        this.inlineItemDispatcher = new InlineItemDispatcher(ctx, this);
    }

    // ── InlineItemDispatcher delegate ──
    void addBreak(Para para, ASTBreak breakItem) {
        inlineItemDispatcher.addBreak(para, breakItem);
    }
    void addEquationRun(Para para, ASTEquation eq) {
        inlineItemDispatcher.addEquationRun(para, eq);
    }
    void addInlineObject(Para para, ASTInlineObject obj) {
        inlineItemDispatcher.addInlineObject(para, obj);
    }

    // ── ParaPrFactory delegate (외부 호출자 호환 유지) ──
    ParaPr findParaPrById(String id) {
        return paraPrFactory.findParaPrById(id);
    }
    boolean hasParagraphOverrides(ASTParagraph para) {
        return paraPrFactory.hasParagraphOverrides(para);
    }
    ASTStyleDef findParagraphStyle(String styleRef) {
        return paraPrFactory.findParagraphStyle(styleRef);
    }
    String createOverrideParaPr(ASTParagraph astPara, String baseParaPrId) {
        return paraPrFactory.createOverrideParaPr(astPara, baseParaPrId);
    }
    String createOverrideParaPr(ASTParagraph astPara, String baseParaPrId, boolean preserveSourceFixedLeading) {
        return paraPrFactory.createOverrideParaPr(astPara, baseParaPrId, preserveSourceFixedLeading);
    }
    boolean isHangingIndentParagraph(ASTParagraph astPara) {
        return paraPrFactory.isHangingIndentParagraph(astPara);
    }
    static int resolveParaLong(Long paraOverride, Long styleValue) {
        return ParaPrFactory.resolveParaLong(paraOverride, styleValue);
    }

    // ── CharPrFactory delegate ──
    void addTextRun(Para para, ASTTextRun textRun, String defaultCharPrId) {
        charPrFactory.addTextRun(para, textRun, defaultCharPrId);
    }
    void addTextRun(Para para, ASTTextRun textRun, String defaultCharPrId, long indentToHerePosition) {
        charPrFactory.addTextRun(para, textRun, defaultCharPrId, indentToHerePosition);
    }
    void addTextWithSpecialChars(Run run, String text, long indentToHerePosition) {
        charPrFactory.addTextWithSpecialChars(run, text, indentToHerePosition);
    }
    static boolean isBoldStyle(String fontStyle) {
        return CharPrFactory.isBoldStyle(fontStyle);
    }
    static boolean isItalicStyle(String fontStyle) {
        return CharPrFactory.isItalicStyle(fontStyle);
    }
    static boolean isEquationFont(String fontFamily) {
        return CharPrFactory.isEquationFont(fontFamily);
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
        addParagraphToSubList(subList, astPara, cellHeight, false);
    }

    void addParagraphToSubList(SubList subList,
                               ASTParagraph astPara,
                               long cellHeight,
                               boolean preserveSourceFixedLeading) {
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

        // "Indent to Here" 탭 정지점 추가 (lineBreak 후 탭이 이 위치로 이동)
        if (astPara.indentToHerePosition() > 0) {
            astPara.addTabStop(new ASTTabStop(astPara.indentToHerePosition(), "left", null));
        }


        // 단락 속성 오버라이드가 있으면 새 ParaPr 생성
        if (astPara.lineSpacing() != null && astPara.lineSpacing() == 0) {
            astPara.lineSpacing(null); // lineSpacing=0은 무의미 → null로 복원
        }
        if (astPara.alignment() == null) {
            String carrierAlignment = inferObjectOnlyCarrierAlignment(astPara);
            if (carrierAlignment != null) {
                astPara.alignment(carrierAlignment);
            }
        }
        // SPEC-031: DSL para rule이 있으면 기존 override 없이도 createOverrideParaPr 호출
        boolean hasDslParaRules = kr.dogfoot.hwpxlib.tool.idmlconverter.rule.HwpxRuleRegistry
                .hasParaRule(astPara.paragraphStyleRef());
        if (hasParagraphOverrides(astPara) || hasDslParaRules) {
            paraPrId = createOverrideParaPr(astPara, paraPrId, preserveSourceFixedLeading);
        }

        // SPEC-031: CharPrFactory에 현재 단락 스타일 전달 (char DSL rule 참조용)
        charPrFactory.currentParaStyleRef = astPara.paragraphStyleRef();

        // 셀 높이가 작으면 줄간격을 FIXED로 강제하여 한컴의 최소 행높이 확장 방지
        if (cellHeight > 0 && cellHeight < ConverterConstants.MIN_TEXT_BOX_HEIGHT) {
            paraPrId = getOrCreateTinyParaPr(cellHeight);
            paraCharPrId = getOrCreateTinyCharPr();
        }

        // 인라인 객체 또는 혼합 폰트 크기가 있으면 BETWEEN_LINES(여백만) 줄간격 적용.
        // source fixed leading은 원본 조판 간격이므로 후속 자동 보정으로 덮지 않는다.
        long maxInlineH = lineSpacingResolver.maxInlineObjectHeight(astPara);
        boolean hasMixedFontSizes = lineSpacingResolver.hasMixedFontSizeRuns(astPara);
        boolean inlineObjectOnlyCarrier = lineSpacingResolver.isInlineObjectOnlyCarrier(astPara);
        boolean sourceFixedLeading = lineSpacingResolver.hasSourceFixedLeading(astPara, paraPrId);
        if (!inlineObjectOnlyCarrier
                && !sourceFixedLeading
                && !astPara.sourceTextWrapSpacing()
                && (maxInlineH > 0 || hasMixedFontSizes)) {
            paraPrId = lineSpacingResolver.applyBetweenLinesSpacing(paraPrId, astPara);
        }

        // 분수 수식이 줄 간격보다 크면 줄 간격 확장.
        // 원본이 고정 줄간격(FIXED)을 지정했더라도, 분수는 세로 2줄 높이라 그 값보다
        // 크면 윗줄에 겹쳐 붙는다(실측: 3단원 (3)y=5/x 가 (1)(2)줄에 붙음). 확장 여부는
        // ensureLineSpacingForInline 내부가 "FIXED 값 < 분수 높이"일 때만 늘리도록 판단한다.
        long maxEqH = lineSpacingResolver.maxFractionEquationHeight(astPara);
        if (maxEqH > maxInlineH && maxEqH > ConverterConstants.INLINE_LINE_SPACING_THRESHOLD) {
            paraPrId = lineSpacingResolver.ensureLineSpacingForInline(paraPrId, maxEqH);
        }

        Para para = subList.addNewPara();
        para.idAnd(HwpxUtil.nextParaId())
                .paraPrIDRefAnd(paraPrId)
                .styleIDRefAnd(styleId)
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        // 행잉 인덴트 단락은 "A:[tab]Is..." 패턴을 IDML 처럼 "A:_Is..." (단일 공백)로 표시.
        // (Hancom 의 탭 디폴트 간격이 IDML 의 시각적 의도보다 넓게 렌더링되는 케이스 보정.)
        boolean replaceTabsInRuns = isHangingIndentParagraph(astPara);

        // 단위 제곱 병합: "…cm"(텍스트) + "^{2}"(base 없는 수식) → "…"(텍스트) +
        // "rm cm^{2}"(수식). 원본 3cmÛ`(3cm²)가 3·cm·^{2} 로 쪼개져 지수가 밑수 없이
        // 떠 있던 것을 합친다 (SPEC-081 클래스 D). 렌더 루프 전에 AST 를 정규화한다.
        mergeUnitExponentEquations(astPara);

        // GREP+수식 전수 조사 (MATH_CENSUS 환경변수 있을 때만)
        MathCensusDumper.dumpParagraph(astPara);

        // 인라인 항목 변환
        for (int i = 0; i < astPara.items().size(); i++) {
            ASTInlineItem item = astPara.items().get(i);
            if (shouldDropLeadingSmallInlineObject(astPara, item, i)) {
                continue;
            }
            switch (item.itemType()) {
                case TEXT_RUN:
                    ASTEquation triangleLabel = triangleLabelEquationAt(astPara.items(), i);
                    if (triangleLabel != null) {
                        addEquationRun(para, triangleLabel);
                        i++; // consume the following equation fragment (for example BC)
                        break;
                    }
                    ASTTextRun tr = coalescedTextRunAt(astPara.items(), i);
                    i = skipCoalescedTextRuns(astPara.items(), i);
                    if (replaceTabsInRuns && tr.text() != null && tr.text().indexOf('\t') >= 0) {
                        tr.text(tr.text().replace('\t', ' '));
                    }
                    addTextRun(para, tr, paraCharPrId, astPara.indentToHerePosition());
                    break;
                case INLINE_OBJECT:
                    inlineItemDispatcher.addInlineObject(para, (ASTInlineObject) item,
                            hasMeaningfulFollowingInlineContent(astPara.items(), i + 1));
                    break;
                case BREAK:
                    addBreak(para, (ASTBreak) item);
                    break;
                case EQUATION:
                    ASTEquation mergedTriangleLabel = triangleLabelEquationAt(astPara.items(), i);
                    if (mergedTriangleLabel != null) {
                        addEquationRun(para, mergedTriangleLabel);
                        i++;
                    } else {
                        addEquationRun(para, (ASTEquation) item);
                    }
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
        ctx.cellContentYCursor += lineSpacingResolver.estimateParagraphHeight(astPara);
    }

    /** 밑수 없는 지수 수식 앞의 단위 텍스트를 흡수해 "rm <단위>^{n}" 한 덩어리로 만든다. */
    private static final java.util.regex.Pattern BARE_EXPONENT =
            java.util.regex.Pattern.compile("\\s*\\^\\{(\\d+)\\}\\s*");
    // 원본 3cmÛ`(3cm²)에서 지수 앞에 붙는 단위. 긴 것부터 검사(cm/mm/km 이 m 보다 먼저).
    private static final String[] EXPONENT_UNITS =
            {"cm", "mm", "km", "kg", "m", "g", "L", "t"};

    /**
     * "…cm"(텍스트) 뒤에 base 없는 "^{n}"(수식)이 붙은 인라인 열을, 단위를 텍스트에서
     * 떼어 "rm cm^{n}" 수식으로 흡수하도록 AST 를 in-place 정규화한다 (SPEC-081 클래스 D).
     * 원본 3cmÛ`(3cm²)가 3·cm·^{2} 로 쪼개져 cm 은 텍스트, ² 는 밑수 없는 수식으로
     * 떠 있던 것을 합친다. 단위 앞 텍스트(숫자·한글)는 그대로 둔다.
     */
    private static void mergeUnitExponentEquations(ASTParagraph astPara) {
        java.util.List<ASTInlineItem> items = astPara.items();
        if (items == null) return;
        for (int i = 1; i < items.size(); i++) {
            if (!(items.get(i) instanceof ASTEquation)) continue;
            ASTEquation eq = (ASTEquation) items.get(i);
            String script = eq.hwpScript();
            if (script == null) continue;
            java.util.regex.Matcher m = BARE_EXPONENT.matcher(script);
            if (!m.matches()) continue;                 // 순수 ^{n} 만 대상
            if (!(items.get(i - 1) instanceof ASTTextRun)) continue;
            ASTTextRun tr = (ASTTextRun) items.get(i - 1);
            String text = tr.text();
            if (text == null || text.isEmpty()) continue;
            String unit = trailingUnit(text);
            if (unit == null) continue;
            // 텍스트에서 단위 제거, 수식에 rm <단위>^{n} 로 흡수.
            tr.text(text.substring(0, text.length() - unit.length()));
            eq.hwpScript("rm " + unit + "^{" + m.group(1) + "}");
        }
    }

    private static String trailingUnit(String text) {
        for (String u : EXPONENT_UNITS) {
            if (text.endsWith(u)) {
                // 단위 앞이 문자(알파벳)면 단위가 아니라 단어의 일부 → 제외.
                int at = text.length() - u.length();
                if (at > 0) {
                    char prev = text.charAt(at - 1);
                    if (Character.isLetter(prev) && prev < 0x80) return null;
                }
                return u;
            }
        }
        return null;
    }

    /**
     * Final text materialization guard for a source triangle label that reached the executor as
     * text A + equation BC.  The preceding source △ is explicit geometry structure, so the two
     * adjacent uppercase fragments are one equation label rather than unrelated prose/chemistry.
     */
    private static ASTEquation triangleLabelEquationAt(
            java.util.List<ASTInlineItem> items, int index) {
        if (items == null || index <= 0 || index + 1 >= items.size()) return null;
        if (!(items.get(index + 1) instanceof ASTEquation)) return null;
        String marker = inlineTextOrEquation(items.get(index - 1));
        String head = inlineUppercaseLabel(items.get(index));
        ASTEquation tailEquation = (ASTEquation) items.get(index + 1);
        String tail = tailEquation.hwpScript();
        if (marker == null || !marker.endsWith("△")
                || !isUppercaseLabelFragment(head) || !isUppercaseLabelFragment(tail)) return null;

        ASTEquation merged = new ASTEquation(head + tail, "EH_FONT");
        merged.preferredBaseUnit(tailEquation.preferredBaseUnit());
        merged.preferredFontFamily(tailEquation.preferredFontFamily());
        merged.textColor(tailEquation.textColor());
        return merged;
    }

    private static String inlineUppercaseLabel(ASTInlineItem item) {
        if (item instanceof ASTTextRun) return ((ASTTextRun) item).text();
        if (item instanceof ASTEquation) return ((ASTEquation) item).hwpScript();
        return null;
    }

    private static String inlineTextOrEquation(ASTInlineItem item) {
        if (item instanceof ASTTextRun) return ((ASTTextRun) item).text();
        if (item instanceof ASTEquation) return ((ASTEquation) item).hwpScript();
        return null;
    }

    private static boolean isUppercaseLabelFragment(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }

    private static String inferObjectOnlyCarrierAlignment(ASTParagraph para) {
        if (para == null || para.alignment() != null || para.items() == null || para.items().isEmpty()) {
            return null;
        }
        String resolved = null;
        boolean foundObject = false;
        for (ASTInlineItem item : para.items()) {
            if (item == null) continue;
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !text.trim().isEmpty()) return null;
                continue;
            }
            if (item.itemType() != ASTInlineItem.ItemType.INLINE_OBJECT) return null;
            String alignment = inlineObjectTextAlignment((ASTInlineObject) item);
            if (alignment == null) return null;
            if (resolved == null) {
                resolved = alignment;
            } else if (!resolved.equalsIgnoreCase(alignment)) {
                return null;
            }
            foundObject = true;
        }
        return foundObject ? resolved : null;
    }

    private static String inlineObjectTextAlignment(ASTInlineObject obj) {
        if (obj == null || obj.paragraphs() == null || obj.paragraphs().isEmpty()) return null;
        String resolved = null;
        for (ASTParagraph paragraph : obj.paragraphs()) {
            if (paragraph == null) continue;
            String alignment = paragraph.alignment();
            if (alignment == null || alignment.isEmpty()) return null;
            if (resolved == null) {
                resolved = alignment;
            } else if (!resolved.equalsIgnoreCase(alignment)) {
                return null;
            }
        }
        return resolved;
    }

    private static ASTTextRun coalescedTextRunAt(java.util.List<ASTInlineItem> items, int index) {
        ASTTextRun first = (ASTTextRun) items.get(index);
        String firstText = first.text();
        if (firstText == null) return first;
        StringBuilder merged = null;
        for (int cursor = index + 1; cursor < items.size(); cursor++) {
            ASTInlineItem nextItem = items.get(cursor);
            if (!(nextItem instanceof ASTTextRun)) break;
            ASTTextRun next = (ASTTextRun) nextItem;
            if (next.text() == null || !first.hasSameStyle(next)) break;
            if (merged == null) merged = new StringBuilder(firstText);
            merged.append(next.text());
        }
        if (merged != null) {
            return first.copyWithText(merged.toString());
        }
        return first;
    }

    private static int skipCoalescedTextRuns(java.util.List<ASTInlineItem> items, int index) {
        ASTTextRun first = (ASTTextRun) items.get(index);
        if (first.text() == null) return index;
        int cursor = index + 1;
        while (cursor < items.size()) {
            ASTInlineItem nextItem = items.get(cursor);
            if (!(nextItem instanceof ASTTextRun)) break;
            ASTTextRun next = (ASTTextRun) nextItem;
            if (next.text() == null || !first.hasSameStyle(next)) break;
            cursor++;
        }
        return cursor - 1;
    }

    private static boolean shouldDropLeadingSmallInlineObject(ASTParagraph paragraph,
                                                              ASTInlineItem item,
                                                              int index) {
        if (paragraph == null || !paragraph.dropLeadingSmallInlineObjects()) return false;
        if (!(item instanceof ASTInlineObject)) return false;
        ASTInlineObject obj = (ASTInlineObject) item;
        if (obj.kind() != ASTInlineObject.ObjectKind.IMAGE
                && obj.kind() != ASTInlineObject.ObjectKind.RENDERED_GROUP) {
            return false;
        }
        if (obj.width() <= 0 || obj.height() <= 0) return false;
        if (obj.width() > CoordinateConverter.pointsToHwpunits(35)
                || obj.height() > CoordinateConverter.pointsToHwpunits(25)) {
            return false;
        }
        return !hasMeaningfulTextBefore(paragraph.items(), index);
    }

    private static boolean hasMeaningfulTextBefore(java.util.List<ASTInlineItem> items, int index) {
        if (items == null) return false;
        for (int i = 0; i < index && i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            String text = ((ASTTextRun) item).text();
            if (text != null && !text.replace("\uFFFC", "").trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMeaningfulFollowingInlineContent(java.util.List<ASTInlineItem> items, int startIndex) {
        if (items == null) return false;
        for (int i = startIndex; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (item == null || item.itemType() == ASTInlineItem.ItemType.BREAK) continue;
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !text.trim().isEmpty()) return true;
                continue;
            }
            return true;
        }
        return false;
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

    /**
     * SubList 콘텐츠 채우기 공용 루틴 — drawText 박스와 테이블 셀(1×1 포함)이 동일 로직 사용.
     * 단락별 inlineTable은 표로, 그 외는 단락으로 추가하고, 추가 inlineTable 목록을 이어 붙인 뒤
     * 비어 있으면 빈 단락을 넣는다. cellHeight는 tiny-cell 보정용(0이면 미적용).
     */
    void fillSubListContent(SubList subList,
                            java.util.List<ASTParagraph> paragraphs,
                            java.util.List<ASTTable> extraInlineTables,
                            long cellHeight) {
        fillSubListContent(subList, paragraphs, extraInlineTables, cellHeight, false);
    }

    void fillSubListContent(SubList subList,
                            java.util.List<ASTParagraph> paragraphs,
                            java.util.List<ASTTable> extraInlineTables,
                            long cellHeight,
                            boolean preserveSourceFixedLeading) {
        if (paragraphs != null) {
            for (ASTParagraph para : paragraphs) {
                if (para != null && para.inlineTable() != null && ctx.tableBuilderRef != null) {
                    ctx.tableBuilderRef.addInlineTableToSubList(subList, para.inlineTable());
                } else {
                    addParagraphToSubList(subList, para, cellHeight, preserveSourceFixedLeading);
                }
            }
        }
        if (extraInlineTables != null && ctx.tableBuilderRef != null) {
            for (ASTTable table : extraInlineTables) {
                if (table != null) ctx.tableBuilderRef.addInlineTableToSubList(subList, table);
            }
        }
        if (subList.countOfPara() == 0) {
            addEmptySubListPara(subList, cellHeight);
        }
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
