package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.tool.equationconverter.EquationBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * 인라인 객체 dispatch + Break + Equation 변환 (W4-2 Step D).
 * - addInlineObject: ASTInlineObject 종류별 dispatch (TextFrame/Image/Group/Equation 등)
 * - shouldFlattenInlineTextFrame / flattenInlineTextFrame: 인라인 텍스트프레임 평탄화
 * - addBreak: ASTBreak → hp:lineBreak/columnBreak/pageBreak
 * - addEquationRun: ASTEquation → hp:equation (MathML)
 *
 * 4중 Builder(textBoxBuilder/tableBuilder/imageBuilder) 의존 — paragraphBuilder 참조 통해 액세스.
 * HwpxParagraphBuilder에서 분리됨.
 */
final class InlineItemDispatcher {

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;

    InlineItemDispatcher(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
    }


    // ── 인라인 객체 디스패치 ──

    void addInlineObject(Para para, ASTInlineObject obj) {
        if (obj.kind() == ASTInlineObject.ObjectKind.INLINE_BADGE_GROUP) {
            paragraphBuilder.imageBuilder.addInlineBadgeGroup(para, obj,
                    paragraphBuilder.textBoxBuilder, paragraphBuilder);
        } else if (obj.kind() == ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) {
            if (shouldFlattenInlineTextFrame(obj)) {
                flattenInlineTextFrame(para, obj);
            } else {
                paragraphBuilder.textBoxBuilder.addInlineTextFrame(para, obj);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP) {
            // 단락 콘텐츠 또는 인라인 테이블이 있는 그룹은 글상자로, 없으면 이미지로
            boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
            boolean hasInlineTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
            if (hasParagraphs || hasInlineTables) {
                paragraphBuilder.textBoxBuilder.addInlineTextFrame(para, obj);
            } else if (obj.imageData() != null && obj.imageData().length > 0) {
                paragraphBuilder.imageBuilder.addInlineImage(para, obj);
            } else if (obj.width() > 0 || obj.height() > 0) {
                // 내용 없는 빈 인라인 사각형 → 스페이서 rect (공간 확보)
                paragraphBuilder.textBoxBuilder.addSpacerRect(para, obj);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE) {
            if (obj.overlayFrames() != null && !obj.overlayFrames().isEmpty()) {
                paragraphBuilder.imageBuilder.addInlineImageWithOverlays(para, obj, paragraphBuilder.textBoxBuilder, paragraphBuilder);
            } else {
                paragraphBuilder.imageBuilder.addInlineImage(para, obj);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.SPACER_RECT) {
            paragraphBuilder.textBoxBuilder.addSpacerRect(para, obj);
        }
    }

    // ── 인라인 TextFrame 펼침 (단순 구조일 때 hp:rect 없이 부모 문단에 직접 삽입) ──

    /**
     * 단순 인라인 TextFrame인지 판별:
     * - 단일 문단, 테이블 없음, 배경/테두리 없음, 오버레이 아님
     */
    boolean shouldFlattenInlineTextFrame(ASTInlineObject obj) {
        if (obj.isOverlay()) return false;
        if (obj.inlineTables() != null && !obj.inlineTables().isEmpty()) return false;
        if (obj.paragraphs() == null || obj.paragraphs().size() != 1) return false;
        if (obj.fillColor() != null && !obj.fillColor().isEmpty()) return false;
        if (obj.strokeWeight() > 0.5) return false;
        // anchoredPosition이 있는 앵커 객체는 펼치지 않음
        String ap = obj.anchoredPosition();
        if (ap != null && !"InlinePosition".equals(ap)) return false;
        // 공백만 있거나 비어있는 인라인 TextFrame(빈칸박스)은 단어 사이 간격을 확보해야 하므로
        // 납작화하지 않음 — 실제 폭을 가진 인라인 박스로 렌더링해서
        // 배경 PNG 위에 그려진 빈칸 밑줄과 위치를 맞춘다.
        ASTParagraph innerParaW = obj.paragraphs().get(0);
        boolean onlyWhitespace = true;  // 비어있어도 빈칸으로 간주
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
    void flattenInlineTextFrame(Para para, ASTInlineObject obj) {
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
                    paragraphBuilder.addTextRun(para, textRun, "0");
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
        // 다행 수식 (예: 분배법칙 전개)에 포함된 U+2028(LINE SEPARATOR) 또는 \n 을
        // HwpScript 의 줄바꿈 토큰 '#' 으로 변환. (탭은 공백으로 정규화)
        String rawScript = eq.hwpScript();
        if (rawScript != null && (rawScript.indexOf(' ') >= 0 || rawScript.indexOf('\n') >= 0)) {
            String normalized = rawScript
                    .replace("\t", " ")
                    .replace(" ", " # ")
                    .replace("\n", " # ")
                    .replaceAll(" +", " ")
                    .trim();
            // # 가 양쪽에 빈 항이 되지 않도록 정리
            normalized = normalized.replaceAll("^#\\s*", "")
                    .replaceAll("\\s*#$", "")
                    .replaceAll("#\\s*#", "#");
            eq.hwpScript(normalized);
        }
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
            long estH = hasFraction ? (long) (baseUnit * HwpxParagraphBuilder.FRACTION_HEIGHT_MULTIPLIER)
                    : (long) (baseUnit * HwpxParagraphBuilder.NORMAL_EQUATION_HEIGHT_MULTIPLIER);
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
}
