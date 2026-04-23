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
    private static final double FRACTION_HEIGHT_MULTIPLIER = 2.2;
    /** 일반 수식 높이 배율 */
    private static final double NORMAL_EQUATION_HEIGHT_MULTIPLIER = 1.0;

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

        // "Indent to Here" 탭 정지점 추가 (lineBreak 후 탭이 이 위치로 이동)
        if (astPara.indentToHerePosition() > 0) {
            astPara.addTabStop(new ASTTabStop(astPara.indentToHerePosition(), "left", null));
        }


        // 단락 속성 오버라이드가 있으면 새 ParaPr 생성
        if (astPara.lineSpacing() != null && astPara.lineSpacing() == 0) {
            astPara.lineSpacing(null); // lineSpacing=0은 무의미 → null로 복원
        }
        if (hasParagraphOverrides(astPara)) {
            paraPrId = createOverrideParaPr(astPara, paraPrId);
        }

        // 셀 높이가 작으면 줄간격을 FIXED로 강제하여 한컴의 최소 행높이 확장 방지
        if (cellHeight > 0 && cellHeight < ConverterConstants.MIN_TEXT_BOX_HEIGHT) {
            paraPrId = getOrCreateTinyParaPr(cellHeight);
            paraCharPrId = getOrCreateTinyCharPr();
        }

        // 인라인 객체 또는 혼합 폰트 크기가 있으면 BETWEEN_LINES(여백만) 줄간격 적용
        // → 큰 인라인 객체/큰 폰트 런이 줄간격을 자연스럽게 확장
        long maxInlineH = maxInlineObjectHeight(astPara);
        boolean hasMixedFontSizes = hasMixedFontSizeRuns(astPara);
        if (maxInlineH > 0 || hasMixedFontSizes) {
            paraPrId = applyBetweenLinesSpacing(paraPrId, astPara);
        }

        // 분수 수식이 줄 간격보다 크면 줄 간격 확장 (명시적 lineSpacing 없을 때만)
        long maxEqH = maxFractionEquationHeight(astPara);
        if (maxEqH > maxInlineH && maxEqH > ConverterConstants.INLINE_LINE_SPACING_THRESHOLD && astPara.lineSpacing() == null) {
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
                    addTextRun(para, (ASTTextRun) item, paraCharPrId, astPara.indentToHerePosition());
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
        // 공백만 있는 인라인 TextFrame(빈칸박스)은 단어 사이 간격을 확보해야 하므로
        // 납작화하지 않음 — 실제 폭을 가진 인라인 박스로 렌더링해서
        // 배경 PNG 위에 그려진 빈칸 밑줄과 위치를 맞춘다.
        ASTParagraph innerParaW = obj.paragraphs().get(0);
        boolean onlyWhitespace = !innerParaW.items().isEmpty();
        for (ASTInlineItem item : innerParaW.items()) {
            if (item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) { onlyWhitespace = false; break; }
            String t = ((ASTTextRun) item).text();
            if (t == null || !t.trim().isEmpty()) { onlyWhitespace = false; break; }
        }
        if (onlyWhitespace && obj.width() >= 800) return false; // 8pt 이상 공백 박스
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

    /**
     * 단락 내 폰트 크기가 혼재(큰 인라인 텍스트 + 본문)될 때 줄간격을 본문 기준으로 고정.
     * 예: "2 「가시리」를 감상하고" — "2"=20pt, 본문=10pt → 본문 기준 줄간격
     */
    private String clampLineSpacingForMixedFontSizes(String paraPrId, ASTParagraph astPara) {
        int maxFs = 0, minFs = Integer.MAX_VALUE;
        int bodyFs = 0; // 가장 긴 텍스트의 폰트 크기 (본문 대표)
        int bodyMaxLen = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun tr =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) item;
                Integer fs = tr.fontSizeHwpunits();
                if (fs == null || fs <= 0) continue;
                if (fs > maxFs) maxFs = fs;
                if (fs < minFs) minFs = fs;
                String text = tr.text();
                int len = (text != null) ? text.trim().length() : 0;
                if (len > bodyMaxLen) {
                    bodyMaxLen = len;
                    bodyFs = fs;
                }
            }
        }
        // 가장 큰 폰트가 본문의 1.5배 이상이면 본문 기준 고정 줄간격
        if (bodyFs > 0 && maxFs > bodyFs * 1.5) {
            long fixedH = (long) (bodyFs * 1.6); // 본문 폰트 × 160%
            return ensureLineSpacingForInline(paraPrId, fixedH);
        }
        return paraPrId;
    }

    /**
     * 단락 내 텍스트 런의 폰트 크기가 본문 대비 1.5배 이상 차이나는지 판별.
     */
    private boolean hasMixedFontSizeRuns(ASTParagraph astPara) {
        int maxFs = 0, bodyFs = 0, bodyMaxLen = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun tr =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) item;
                Integer fs = tr.fontSizeHwpunits();
                if (fs == null || fs <= 0) continue;
                if (fs > maxFs) maxFs = fs;
                String text = tr.text();
                int len = (text != null) ? text.trim().length() : 0;
                if (len > bodyMaxLen) { bodyMaxLen = len; bodyFs = fs; }
            }
        }
        return bodyFs > 0 && maxFs > bodyFs * 1.5;
    }

    private long maxInlineObjectHeight(ASTParagraph astPara) {
        long max = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                ASTInlineObject obj = (ASTInlineObject) item;
                // INLINE_TEXT_FRAME 타입은 줄간격 확장에서 제외 (테이블 셀 내 인라인 프레임)
                if (obj.kind() == ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) continue;
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

    /**
     * 인라인 객체가 있는 단락의 줄간격을 BETWEEN_LINES(여백만)으로 전환.
     * FIXED leading에서 fontSize를 빼서 줄 사이 여백만 지정하면
     * 인라인 객체 높이에 따라 줄이 자연스럽게 확장됨.
     */
    private String applyBetweenLinesSpacing(String paraPrId, ASTParagraph astPara) {
        ParaPr basePr = findParaPrById(paraPrId);
        if (basePr == null) return paraPrId;

        // 인라인 객체 최대 높이
        long maxObjH = maxInlineObjectHeight(astPara);
        // 대표 폰트 크기 결정
        int bodyFs = 0;
        int bodyMaxLen = 0;
        for (ASTInlineItem item : astPara.items()) {
            if (item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun tr =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) item;
                Integer fs = tr.fontSizeHwpunits();
                String text = tr.text();
                int len = (text != null) ? text.trim().length() : 0;
                if (fs != null && fs > 0 && len > bodyMaxLen) { bodyMaxLen = len; bodyFs = fs; }
            }
        }
        if (bodyFs <= 0) bodyFs = 1000; // 기본 10pt
        // 여백 계산: 인라인 객체 기반 여백과 원본 leading 기반 여백 중 큰 값
        int inlineBetween = (int) Math.max((maxObjH - bodyFs) / 2, bodyFs / 3);
        // 원본 FIXED leading이 있으면 leading - bodyFs를 여백으로
        int leadingBetween = 0;
        if (basePr.lineSpacing() != null && basePr.lineSpacing().type() == LineSpacingType.FIXED) {
            leadingBetween = Math.max(basePr.lineSpacing().value() - bodyFs, 0);
        }
        if (astPara.lineSpacing() != null && "fixed".equals(astPara.lineSpacingType())) {
            leadingBetween = Math.max(astPara.lineSpacing() - bodyFs, leadingBetween);
        }
        int betweenValue = Math.max(inlineBetween, leadingBetween);

        String newId = ctx.styleRegistry.nextParaPrId();
        ParaPr newPr = ctx.hwpxFile.headerXMLFile().refList().paraProperties().addNew();
        newPr.copyFrom(basePr);
        newPr.id(newId);
        if (newPr.lineSpacing() == null) {
            newPr.createLineSpacing();
        }
        newPr.lineSpacing().typeAnd(LineSpacingType.BETWEEN_LINES).valueAnd(betweenValue).unit(ValueUnit2.HWPUNIT);
        return newId;
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

        // InDesign 탭 위치는 프레임 기준(절대) — HWPX에서도 동일하게 사용

        // 인라인 탭 정지점 → TabPr 생성 (마진 조정 후에 실행해야 암시적 탭 포함)
        String tabPrId;
        if (astPara.hasTabStops()) {
            tabPrId = ctx.styleRegistry.createInlineTabPr(astPara.tabStops());
        } else {
            // 인라인 탭이 없으면 기본 스타일의 tabPr 상속
            ParaPr basePr = findParaPrById(baseParaPrId);
            tabPrId = basePr != null ? basePr.tabPrIDRef() : "0";
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
        addTextRun(para, textRun, defaultCharPrId, 0);
    }

    void addTextRun(Para para, ASTTextRun textRun, String defaultCharPrId, long indentToHerePosition) {
        String text = HwpxUtil.sanitizeText(textRun.text());
        if (text == null || text.isEmpty()) return;

        String charPrId = defaultCharPrId;

        // CharacterStyle 이름에서 밑줄 추론 (AST에서 설정되지 않은 경우)
        if (!textRun.underline() && textRun.characterStyleRef() != null) {
            String csRef = textRun.characterStyleRef();
            if (csRef.contains("밑줄") || csRef.toLowerCase().contains("underline")) {
                textRun.underline(true);
            }
        }

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

        // 공백 문자를 별도 런으로 분리하여 장평(ratio) 축소 적용
        // 탭/줄바꿈이 포함된 경우는 기존 로직 유지 (복잡도 방지)
        boolean hasSpecial = text.indexOf('\t') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\u2028') >= 0;
        if (!hasSpecial && spaceRatio() < 100 && text.indexOf(' ') >= 0) {
            addTextRunWithSpaceSplit(para, text, charPrId, textRun);
        } else {
            Run run = para.addNewRun();
            run.charPrIDRef(charPrId);
            if (hasSpecial) {
                addTextWithSpecialChars(run, text, indentToHerePosition);
            } else {
                run.addNewT().addText(text);
            }
        }
    }

    /** 공백 장평 축소 비율 (100 = 변경 없음, 50 = 50%로 축소) */
    private short spaceRatio() {
        if (ctx.config != null) return (short) ctx.config.spaceCondenseRatio();
        return 50;
    }

    /**
     * 텍스트를 "비공백/공백" 세그먼트로 분할하여 출력.
     * 공백 세그먼트에는 ratio가 축소된 별도 CharPr을 적용.
     */
    private void addTextRunWithSpaceSplit(Para para, String text, String charPrId, ASTTextRun textRun) {
        String spaceCharPrId = getOrCreateSpaceCharPr(charPrId, textRun);
        int i = 0;
        while (i < text.length()) {
            boolean isSpace = text.charAt(i) == ' ';
            int start = i;
            while (i < text.length() && (text.charAt(i) == ' ') == isSpace) {
                i++;
            }
            String segment = text.substring(start, i);
            Run run = para.addNewRun();
            run.charPrIDRef(isSpace ? spaceCharPrId : charPrId);
            run.addNewT().addText(segment);
        }
    }

    /**
     * 공백용 CharPr을 생성 또는 캐시에서 가져온다.
     * 기존 CharPr과 동일하되 ratio만 spaceRatio()로 축소.
     */
    private String getOrCreateSpaceCharPr(String baseCharPrId, ASTTextRun textRun) {
        String cacheKey = "SP|" + baseCharPrId;
        String cached = ctx.charPrCache.get(cacheKey);
        if (cached != null) return cached;

        // 기존 CharPr을 기반으로 공백용 CharPr 생성 (ratio만 변경)
        String newId = ctx.styleRegistry.nextCharPrId();
        CharPr charPr = ctx.hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int height = textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : 1000;
        String textColor = textRun.textColor() != null ? textRun.textColor() : "#000000";
        String fontStyleStr = textRun.fontStyle() != null ? textRun.fontStyle().toLowerCase() : "";

        LineType3 ulShape = null;
        if (textRun.underline() && textRun.underlineShape() != null) {
            ulShape = LineType3.fromString(textRun.underlineShape());
        }

        // horizontalScale를 spaceRatio()로 강제 오버라이드
        CharPrBuilder.build(charPr, newId, height, textColor,
                textRun.fontFamily(), textRun.fontStyle(), ctx.fontRegistry,
                textRun.letterSpacing(),
                isBoldStyle(fontStyleStr),
                isItalicStyle(fontStyleStr),
                textRun.superscript(), textRun.subscript(),
                textRun.underline() ? UnderlineType.BOTTOM : UnderlineType.NONE,
                textRun.underline()
                        ? (textRun.underlineColor() != null ? textRun.underlineColor() : textColor)
                        : "#000000",
                ulShape,
                spaceRatio(),  // 공백 장평 축소
                textRun.strikeThrough(),
                textRun.verticalScale(),
                textRun.baselineShift());

        ctx.charPrCache.put(cacheKey, newId);
        return newId;
    }

    /**
     * 텍스트 내의 탭(\t)과 줄바꿈(\n, U+2028) 문자를 HWPX 요소로 변환.
     * 각 탭/줄바꿈은 별도의 T 요소로 분리 (한글 렌더링 호환).
     * indentToHerePosition > 0이면 lineBreak 직후 탭을 삽입하여 들여쓰기 재현.
     */
    void addTextWithSpecialChars(Run run, String text, long indentToHerePosition) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t') {
                if (buf.length() > 0) {
                    run.addNewT().addText(buf.toString());
                    buf.setLength(0);
                }
                run.addNewT().addNewTab();
            } else if (c == '\n' || c == '\u2028') {
                if (buf.length() > 0) {
                    run.addNewT().addText(buf.toString());
                    buf.setLength(0);
                }
                run.addNewT().addNewLineBreak();
                if (indentToHerePosition > 0) {
                    run.addNewT().addNewTab();
                }
            } else if (c == '\r') {
                // \r 무시 (\r\n의 경우 \n이 처리)
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            run.addNewT().addText(buf.toString());
        }
    }

    boolean hasCharacterOverrides(ASTTextRun run) {
        return run.fontFamily() != null
                || run.fontStyle() != null
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
                textRun.fontFamily(), textRun.fontStyle(), ctx.fontRegistry,
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
        // 가변폰트 숫자 weight: "40", "50" 등 — 30 이상이면 볼드 (기본 weight "20" 대비)
        if (lower.matches("^\\d+$")) {
            try {
                int weight = Integer.parseInt(lower);
                if (weight >= 30) return true;
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
                textRun.fontFamily(), textRun.fontStyle(), ctx.fontRegistry,
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
