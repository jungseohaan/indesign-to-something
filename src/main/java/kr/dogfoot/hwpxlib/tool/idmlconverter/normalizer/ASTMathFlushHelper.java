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
        flushMathGroupWithFractions(mathGroup, para, fractions, hasIndentToHere, null);
    }

    static void flushMathGroupWithFractions(List<IDMLCharacterRun> mathGroup,
                                              ASTParagraph para,
                                              List<ASTEquation> fractions,
                                              boolean hasIndentToHere,
                                              ColorResolver colorResolver) {
        flushMathGroup(mathGroup, para, fractions, hasIndentToHere, FlushType.BT, colorResolver);
    }

    /**
     * NP 수식 그룹을 flush하면서 인라인 분수 TextFrame을 통합.
     */
    static void flushNPMathGroupWithFractions(List<IDMLCharacterRun> npGroup,
                                                ASTParagraph para,
                                                List<ASTEquation> fractions,
                                                boolean hasIndentToHere) {
        flushNPMathGroupWithFractions(npGroup, para, fractions, hasIndentToHere, null);
    }

    static void flushNPMathGroupWithFractions(List<IDMLCharacterRun> npGroup,
                                                ASTParagraph para,
                                                List<ASTEquation> fractions,
                                                boolean hasIndentToHere,
                                                ColorResolver colorResolver) {
        flushMathGroup(npGroup, para, fractions, hasIndentToHere, FlushType.NP, colorResolver);
    }

    /**
     * EH 수식 그룹을 flush하면서 인라인 분수 TextFrame을 통합.
     */
    static void flushEHMathGroupWithFractions(List<IDMLCharacterRun> ehGroup,
                                                ASTParagraph para,
                                                List<ASTEquation> fractions,
                                                boolean hasIndentToHere) {
        flushEHMathGroupWithFractions(ehGroup, para, fractions, hasIndentToHere, null);
    }

    static void flushEHMathGroupWithFractions(List<IDMLCharacterRun> ehGroup,
                                                ASTParagraph para,
                                                List<ASTEquation> fractions,
                                                boolean hasIndentToHere,
                                                ColorResolver colorResolver) {
        flushMathGroup(ehGroup, para, fractions, hasIndentToHere, FlushType.EH, colorResolver);
    }

    private enum FlushType { BT, NP, EH }

    /**
     * BT/NP/EH 통합 수식 그룹 flush.
     */
    private static void flushMathGroup(List<IDMLCharacterRun> group,
                                         ASTParagraph para,
                                         List<ASTEquation> fractions,
                                         boolean hasIndentToHere,
                                         FlushType type,
                                         ColorResolver colorResolver) {
        switch (type) {
            case NP:
                ASTMathGrouper.flushNPMathGroup(group, para);
                break;
            case EH:
                ASTMathGrouper.flushEHMathGroup(group, para);
                break;
            default:
                ASTMathGrouper.flushMathGroup(group, para);
                break;
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

        // 수식 텍스트 색상 설정 (소스 런의 fillColor 사용)
        if (lastEq != null) {
            String color = resolveGroupTextColor(group, colorResolver);
            if (color != null) {
                lastEq.textColor(color);
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
            // 인라인으로 분수 통합 (줄바꿈 없음)
            // Indent to Here (ACE 7)는 줄 자동 줄바꿈 시 들여쓰기 위치 지정이므로 강제 줄바꿈 안 함
            String merged = replaceFffcWithFractions(script, fractions);
            lastEq.hwpScript(merged);
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

    /**
     * 수식 그룹 런들에서 대표 텍스트 색상을 추출.
     * 첫 번째로 발견되는 non-null fillColor를 사용.
     */
    private static String resolveGroupTextColor(List<IDMLCharacterRun> group,
                                                  ColorResolver colorResolver) {
        if (colorResolver == null) return null;
        for (IDMLCharacterRun run : group) {
            String fill = run.fillColor();
            if (fill != null) {
                String hex = colorResolver.resolve(fill);
                if (hex != null && !hex.isEmpty()) {
                    return hex;
                }
            }
        }
        return null;
    }
}
