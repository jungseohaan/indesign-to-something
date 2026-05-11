package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHGrepFractionConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTMathGrouper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 수식 처리 — BT/EH/NP 폰트 런을 ASTEquation으로 변환 (W3 Step B).
 * StoryConverter에서 분리됨.
 *
 * 책임:
 * - resolved-only 단락의 수식 폰트 런을 ASTMathGrouper로 위임 변환
 * - 분수 패턴 (EHGrepFractionConverter) 텍스트 분할
 * - EH 제곱근 (sqrt) 컨텐츠 감지
 */
class MathProcessor {

    private MathProcessor() {}

    /**
     * resolved-only 단락 내 수식 폰트 런(EH/BT/NP)을 ASTEquation으로 변환.
     * ASTTextRun의 fontFamily를 기반으로 IDMLCharacterRun 어댑터를 생성하여
     * ASTMathGrouper.flush* 메서드로 위임.
     */
    static void convertMathRunsInParagraph(ResolvedBuildContext ctx, ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;

        // IDML 경로에서 이미 ASTEquation으로 변환된 단락은 건너뜀 (중복 변환 방지)
        boolean hasEquation = false;
        boolean hasEHRun = false;
        for (ASTInlineItem it : items) {
            if (it instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) hasEquation = true;
            if (it instanceof ASTTextRun) {
                String ff = ((ASTTextRun) it).fontFamily();
                if (ff != null && EHFontGlyphMap.isEHFontFamily(ff)) hasEHRun = true;
            }
        }
        if (hasEquation) {
            if (hasEHRun) {
                // 수식과 EH TextRun이 공존: EH TextRun은 수식 변환 잔여물 → 제거
                items.removeIf(it -> it instanceof ASTTextRun
                        && ((ASTTextRun) it).fontFamily() != null
                        && EHFontGlyphMap.isEHFontFamily(((ASTTextRun) it).fontFamily()));
            }
            return;
        }

        List<ASTInlineItem> newItems = new ArrayList<>();
        List<IDMLCharacterRun> mathGroup = new ArrayList<>();
        String mathType = null; // "EH", "BT", "NP"

        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) {
                flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                mathGroup.clear();
                mathType = null;
                newItems.add(item);
                continue;
            }

            ASTTextRun tr = (ASTTextRun) item;
            String ff = tr.fontFamily();
            String currentType = null;
            if (ff != null) {
                if (EHFontGlyphMap.isEHFontFamily(ff)) currentType = "EH";
                else if (BTFontGlyphMap.isBTFontFamily(ff)) currentType = "BT";
                else if (NPFontGlyphMap.isNPFont(ff)) currentType = "NP";
            }

            if (currentType != null) {
                if (mathType == null || mathType.equals(currentType)) {
                    mathType = currentType;
                    IDMLCharacterRun cr = new IDMLCharacterRun();
                    cr.content(tr.text());
                    cr.fontFamily(ff);
                    mathGroup.add(cr);
                } else {
                    flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                    mathGroup.clear();
                    mathType = currentType;
                    IDMLCharacterRun cr = new IDMLCharacterRun();
                    cr.content(tr.text());
                    cr.fontFamily(ff);
                    mathGroup.add(cr);
                }
            } else {
                // EH 그룹이 열려있으면 비EH 런의 bridge 가능성 확인
                // 짧은 특수 공백(thin/four-per-em space) 또는 연산자 1문자만 bridge 허용
                boolean bridge = false;
                if ("EH".equals(mathType) && !mathGroup.isEmpty()) {
                    String text = tr.text();
                    if (text != null && text.length() <= 2) {
                        boolean allBridgeable = true;
                        for (int ci = 0; ci < text.length(); ci++) {
                            char c = text.charAt(ci);
                            // 특수 공백, 연산 기호만 bridge
                            if (c != '\u2005' && c != '\u2009' && c != '\u2003' && c != ' '
                                    && c != '+' && c != '-' && c != '=' && c != '\u00D7'
                                    && c != '\u00F7') {
                                allBridgeable = false;
                                break;
                            }
                        }
                        if (allBridgeable) {
                            // 뒤에 EH 폰트 런이 있는지 확인
                            for (int ni = i + 1; ni < items.size(); ni++) {
                                ASTInlineItem next = items.get(ni);
                                if (next instanceof ASTTextRun) {
                                    String nff = ((ASTTextRun) next).fontFamily();
                                    if (nff != null && EHFontGlyphMap.isEHFontFamily(nff)) {
                                        bridge = true;
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
                if (bridge) {
                    IDMLCharacterRun cr = new IDMLCharacterRun();
                    cr.content(tr.text());
                    cr.fontFamily(tr.fontFamily() != null ? tr.fontFamily() : "");
                    mathGroup.add(cr);
                } else {
                    flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);
                    mathGroup.clear();
                    mathType = null;
                    newItems.add(item);
                }
            }
        }
        flushResolvedMathGroup(ctx, mathGroup, mathType, newItems, para);

        if (newItems.size() != items.size() || !newItems.equals(items)) {
            items.clear();
            items.addAll(newItems);
        }
    }

    private static void flushResolvedMathGroup(ResolvedBuildContext ctx, List<IDMLCharacterRun> group, String type,
                                         List<ASTInlineItem> out, ASTParagraph ignoredPara) {
        if (group == null || group.isEmpty()) return;
        // flush 메서드는 para에 직접 추가하므로, 임시 para를 사용하여 결과를 꺼냄
        ASTParagraph tempPara = new ASTParagraph();
        if ("EH".equals(type)) {
            ASTMathGrouper.flushEHMathGroup(group, tempPara);
        } else if ("BT".equals(type)) {
            ASTMathGrouper.flushMathGroup(group, tempPara);
        } else if ("NP".equals(type)) {
            ASTMathGrouper.flushNPMathGroup(group, tempPara);
        }
        out.addAll(tempPara.items());
    }

    /**
     * 수식 그룹 flush 헬퍼: null이 아닌 그룹만 flush하고 clear.
     */
    static void flushMathGroups(ResolvedBuildContext ctx, List<IDMLCharacterRun> btGroup,
                                  List<IDMLCharacterRun> npGroup,
                                  List<IDMLCharacterRun> ehGroup,
                                  ASTParagraph para) {
        if (btGroup != null && !btGroup.isEmpty()) {
            ASTMathGrouper.flushMathGroup(btGroup, para);
            btGroup.clear();
        }
        if (npGroup != null && !npGroup.isEmpty()) {
            ASTMathGrouper.flushNPMathGroup(npGroup, para);
            npGroup.clear();
        }
        if (ehGroup != null && !ehGroup.isEmpty()) {
            ASTMathGrouper.flushEHMathGroup(ehGroup, para);
            ehGroup.clear();
        }
    }

    /**
     * 텍스트 내 ;...; 분수 GREP 패턴을 인라인 수식(ASTEquation)으로 분리.
     * 예: "이므로 ;4!;의 제곱근은" → "이므로 " + ASTEquation({1} over {4}) + "의 제곱근은"
     */
    static void splitFractionPatternInText(ResolvedBuildContext ctx, String text, ASTTextRun templateRun, ASTParagraph para) {
        for (EHGrepFractionConverter.Segment seg : EHGrepFractionConverter.splitAndConvert(text)) {
            if (seg.type() == EHGrepFractionConverter.Segment.Type.EQUATION) {
                para.addItem(new ASTEquation(seg.content(), "EH_FONT"));
            } else {
                ASTTextRun tr = new ASTTextRun();
                tr.text(seg.content());
                tr.fontFamily(templateRun.fontFamily());
                tr.fontStyle(templateRun.fontStyle());
                tr.fontSizeHwpunits(templateRun.fontSizeHwpunits());
                tr.textColor(templateRun.textColor());
                para.addItem(tr);
            }
        }
    }

    /**
     * EH 그룹이 열려있고 마지막이 EH분수대문자(√)일 때,
     * 바로 뒤의 짧은 비EH 런이 루트 내용(radicand)인지 판단.
     * GREP 스타일이 IDML에 반영되지 않아 fontFamily=null인 런도 포함.
     */
    static boolean isEHSqrtContent(IDMLCharacterRun run,
                                            List<IDMLCharacterRun> ehGroup) {
        if (ehGroup.isEmpty()) return false;
        // 마지막 EH 런이 분수대문자(√)인지
        IDMLCharacterRun last = ehGroup.get(ehGroup.size() - 1);
        if (!EHFontGlyphMap.isFractionNumeratorFont(last.fontFamily())) return false;
        // 현재 런이 짧은 라틴/수학 텍스트인지 (한국어만으로 시작하면 제외)
        String text = run.content();
        if (text == null || text.isEmpty()) return false;
        char first = text.charAt(0);
        // 첫 문자가 라틴 알파벳, 숫자, 수학 기호이면 루트 내용
        return Character.isLetterOrDigit(first)
                && !(first >= 0xAC00 && first <= 0xD7AF)
                && !(first >= 0x3131 && first <= 0x318E);
    }
}
