package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.util.List;

/**
 * BT/NP 수식 그룹 플러시 + 분수 통합 로직.
 * ASTStoryConverter에서 분리됨.
 */
class ASTMathFlushHelper {

    /**
     * BT 수식 그룹을 flush하면서 인라인 분수 TextFrame을 통합.
     * hasIndentToHere가 true이면 첫 분수 위치에서 줄바꿈 분할.
     * hasIndentToHere가 false이면 분수를 인라인으로 통합 (줄바꿈 없음).
     * 분수가 없는 경우에도 잔존 \uFFFC를 제거한다.
     */
    static void flushMathGroupWithFractions(List<IDMLCharacterRun> mathGroup,
                                              ASTParagraph para,
                                              List<ASTEquation> fractions,
                                              boolean hasIndentToHere) {
        flushMathGroup(mathGroup, para, fractions, hasIndentToHere, false);
    }

    /**
     * NP 수식 그룹을 flush하면서 인라인 분수 TextFrame을 통합.
     */
    static void flushNPMathGroupWithFractions(List<IDMLCharacterRun> npGroup,
                                                ASTParagraph para,
                                                List<ASTEquation> fractions,
                                                boolean hasIndentToHere) {
        flushMathGroup(npGroup, para, fractions, hasIndentToHere, true);
    }

    /**
     * BT/NP 통합 수식 그룹 flush.
     * @param isNP true이면 NP 수식, false이면 BT 수식
     */
    private static void flushMathGroup(List<IDMLCharacterRun> group,
                                         ASTParagraph para,
                                         List<ASTEquation> fractions,
                                         boolean hasIndentToHere,
                                         boolean isNP) {
        if (isNP) {
            ASTMathGrouper.flushNPMathGroup(group, para);
        } else {
            ASTMathGrouper.flushMathGroup(group, para);
        }

        // flush가 방금 추가한 마지막 수식 항목 찾기
        List<ASTInlineItem> items = para.items();
        ASTEquation lastEq = null;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).itemType() == ASTInlineItem.ItemType.EQUATION) {
                lastEq = (ASTEquation) items.get(i);
                break;
            }
        }

        if (lastEq == null) {
            for (ASTEquation eq : fractions) {
                para.addItem(eq);
            }
            return;
        }

        String script = lastEq.hwpScript();
        if (script == null || script.indexOf('\uFFFC') < 0) {
            for (ASTEquation eq : fractions) {
                para.addItem(eq);
            }
            return;
        }

        if (!fractions.isEmpty()) {
            if (hasIndentToHere && !isNP) {
                // Indent to Here가 있는 경우 (BT만): 첫 번째 \uFFFC에서 줄바꿈 분할
                int firstFffc = script.indexOf('\uFFFC');
                String beforeFrac = script.substring(0, firstFffc).replace("\uFFFC", "").trim();
                String afterFrac = script.substring(firstFffc);

                lastEq.hwpScript(beforeFrac);
                para.addItem(new ASTBreak(ASTBreak.BreakType.LINE));

                String fracLine = replaceFffcWithFractions(afterFrac, fractions);
                if (!fracLine.isEmpty()) {
                    para.addItem(new ASTEquation(fracLine, "FRACTION_FRAME"));
                }
            } else {
                // 인라인으로 분수 통합 (줄바꿈 없음)
                String merged = replaceFffcWithFractions(script, fractions);
                lastEq.hwpScript(merged);
            }
        } else {
            // 분수 없이 \uFFFC만 있는 경우 → 제거
            lastEq.hwpScript(script.replace("\uFFFC", ""));
        }
    }

    /**
     * 문자열 내 \uFFFC를 분수 스크립트로 순서대로 교체하고, 남은 \uFFFC는 제거.
     */
    static String replaceFffcWithFractions(String text, List<ASTEquation> fractions) {
        StringBuilder sb = new StringBuilder();
        int fracIdx = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFC') {
                if (fracIdx < fractions.size()) {
                    sb.append(fractions.get(fracIdx).hwpScript());
                    fracIdx++;
                }
            } else {
                sb.append(text.charAt(i));
            }
        }
        return sb.toString().trim();
    }

    /**
     * 단락에 분수 수식(FRACTION_FRAME 또는 over 포함)이 있는지 확인.
     */
    static boolean hasFractionEquation(ASTParagraph para) {
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.EQUATION) {
                ASTEquation eq = (ASTEquation) item;
                if ("FRACTION_FRAME".equals(eq.sourceType())) return true;
                if (eq.hwpScript() != null && eq.hwpScript().contains(" over ")) return true;
            }
        }
        return false;
    }

    /**
     * 런의 인라인 TextFrame 중 분수 TextFrame을 찾아 ASTEquation으로 변환하여 리스트에 추가.
     * 수식 그룹에 들어가는 런에서 호출 (flushMathGroup은 텍스트만 처리하므로).
     */
    static void extractFractionFrames(IDMLCharacterRun run, IDMLDocument idmlDoc,
                                        List<ASTEquation> fractionList) {
        for (IDMLTextFrame tf : run.inlineFrames()) {
            ASTEquation eq = ASTStoryConverter.tryConvertFractionTextFrame(tf, idmlDoc);
            if (eq != null) {
                fractionList.add(eq);
            }
        }
    }

    /**
     * 수식 그룹에 속한 런들의 인라인 그래픽을 수식 뒤에 출력.
     * flushMathGroup은 텍스트만 처리하므로, 수식 런에 포함된 인라인 그래픽(Group 등)은
     * 여기서 별도로 처리한다.
     */
    static void emitMathGroupInlineGraphics(List<IDMLCharacterRun> mathGroup,
                                              ASTParagraph para,
                                              IDMLDocument idmlDoc,
                                              ColorResolver colorResolver,
                                              ASTImageLoader imageLoader) {
        for (IDMLCharacterRun run : mathGroup) {
            if (run.inlineGraphics().isEmpty()) continue;
            for (IDMLCharacterRun.InlineGraphic ig : run.inlineGraphics()) {
                ASTRunConverter.processInlineGraphic(ig, para, idmlDoc, colorResolver, imageLoader, null);
            }
        }
    }
}
