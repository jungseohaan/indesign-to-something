package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;

import java.util.*;

/**
 * 스토리 내 단락/런 변환 로직.
 * Stage4_BuildAST에서 분리됨.
 */
public class ASTStoryConverter {

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
                                         boolean storyHasBTRuns,
                                         ResolvedData resolvedData) {
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
        if (idmlPara.firstLineIndent() != null) {
            para.firstLineIndent(CoordinateConverter.pointsToHwpunits(idmlPara.firstLineIndent()));
        }
        if (idmlPara.leftIndent() != null) {
            para.leftMargin(CoordinateConverter.pointsToHwpunits(idmlPara.leftIndent()));
        }
        if (idmlPara.rightIndent() != null) {
            para.rightMargin(CoordinateConverter.pointsToHwpunits(idmlPara.rightIndent()));
        }
        if (idmlPara.spaceBefore() != null) {
            para.spaceBefore(CoordinateConverter.pointsToHwpunits(idmlPara.spaceBefore()));
        }
        if (idmlPara.spaceAfter() != null) {
            para.spaceAfter(CoordinateConverter.pointsToHwpunits(idmlPara.spaceAfter()));
        }
        // 줄간격 (leading)
        if (idmlPara.leading() != null) {
            para.lineSpacingType("fixed");
            para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(idmlPara.leading()));
        } else if ("Auto".equalsIgnoreCase(idmlPara.leadingType())) {
            // Leading="Auto" — 스타일의 고정 줄간격을 오버라이드하여 자동 줄간격 사용
            para.lineSpacingType("percent");
            para.lineSpacing(160);  // HWPX 기본 퍼센트 (글꼴 크기 기준 160%)
        }

        // 단락 배경
        if (idmlPara.shadingOn()) {
            para.shadingOn(true);
            String shadingColor = idmlPara.shadingColor();
            if (shadingColor != null) {
                para.shadingColor(colorResolver.resolve(shadingColor));
            }
            para.shadingTint(idmlPara.shadingTint());
            // 음영 오프셋 (points → HWPUNIT)
            if (idmlPara.shadingOffsetLeft() != null) {
                para.shadingLeftOffset(CoordinateConverter.pointsToHwpunits(idmlPara.shadingOffsetLeft()));
            }
            if (idmlPara.shadingOffsetRight() != null) {
                para.shadingRightOffset(CoordinateConverter.pointsToHwpunits(idmlPara.shadingOffsetRight()));
            }
            if (idmlPara.shadingOffsetTop() != null) {
                para.shadingTopOffset(CoordinateConverter.pointsToHwpunits(idmlPara.shadingOffsetTop()));
            }
            if (idmlPara.shadingOffsetBottom() != null) {
                para.shadingBottomOffset(CoordinateConverter.pointsToHwpunits(idmlPara.shadingOffsetBottom()));
            }
        }

        // 탭 정지점 (인라인 오버라이드만 — 스타일 탭은 StyleRegistry에서 처리)
        // 스타일 탭을 AST 단락에 복제하면 override paraPr이 스타일 tabPr을 동일 값으로 덮어쓰는데,
        // 한글에서 텍스트 너비가 탭 위치에 근접할 때 탭이 무시되는 문제를 유발함.
        // 인라인 오버라이드만 복사하면, override paraPr의 tabPrIDRef="0"이 기본 탭 동작을 활성화.
        java.util.List<IDMLStyleDef.TabStop> tabStops = idmlPara.tabStops();
        if (tabStops != null) {
            for (IDMLStyleDef.TabStop ts : tabStops) {
                long posHwpunits = CoordinateConverter.pointsToHwpunits(ts.position());
                String alignment = mapTabAlignment(ts.alignment());
                para.addTabStop(new ASTTabStop(posHwpunits, alignment, ts.leader()));
            }
        }

        // 컬럼 브레이크
        if (idmlPara.columnBreakAfter()) {
            para.columnBreakAfter(true);
        }

        // 단락 분리 제어 (인라인 오버라이드 → 스타일 상속)
        boolean kwn2 = idmlPara.keepWithNext();
        boolean klt2 = idmlPara.keepLinesTogether();
        boolean pbb2 = idmlPara.pageBreakBefore();
        if (!kwn2 || !klt2 || !pbb2) {
            // 인라인 값이 false이면 스타일에서 상속 시도
            if (paraStyleRef != null) {
                IDMLStyleDef paraStyle = resolveStyle(paraStyleRef, idmlDoc.paraStyles());
                if (paraStyle != null) {
                    if (!kwn2 && Boolean.TRUE.equals(paraStyle.keepWithNext())) kwn2 = true;
                    if (!klt2 && Boolean.TRUE.equals(paraStyle.keepLinesTogether())) klt2 = true;
                    if (!pbb2 && Boolean.TRUE.equals(paraStyle.pageBreakBefore())) pbb2 = true;
                }
            }
        }
        if (kwn2) para.keepWithNext(true);
        if (klt2) para.keepLinesTogether(true);
        if (pbb2) para.pageBreakBefore(true);

        // Character Runs → 인라인 항목
        // BT수식M 폰트 런은 그룹핑하여 ASTEquation으로 변환
        // NP 폰트 런도 그룹핑하여 NPFontEquationConverter로 ASTEquation 변환
        // BT/NP 런 사이에 끼인 짧은 일반 텍스트(변수명 등)는 "브릿지"로 수식 그룹에 포함

        // 전처리: 한국어+수식마커 혼합 런을 분리 (예: "_r를 구해" → "_r" + "를 구해")
        // "Indent to Here" (ACE 7, U+0008) 처리 — split 전 원본 런에서 수행
        // splitMathKoreanMixedRuns가 런을 복제할 수 있으므로 원본에서 먼저 처리
        boolean hasIndentToHere = applyIndentToHere(para, idmlPara.characterRuns(), idmlPara, idmlDoc);

        List<IDMLCharacterRun> runs = ASTMathGrouper.splitMathKoreanMixedRuns(idmlPara.characterRuns());

        // r\d+par → 원문자(①②③...) 전처리 (수식 그룹화 전에 실행)
        ASTRunConverter.convertCircledNumberRuns(runs);

        List<IDMLCharacterRun> mathGroup = new ArrayList<>();
        List<ASTEquation> mathGroupFractions = new ArrayList<>(); // 수식 그룹 런의 인라인 분수
        List<IDMLCharacterRun> npMathGroup = new ArrayList<>(); // NP 폰트 수식 그룹
        List<ASTEquation> npMathGroupFractions = new ArrayList<>(); // NP 수식 그룹의 인라인 분수
        List<IDMLCharacterRun> ehMathGroup = new ArrayList<>(); // EH 폰트 수식 그룹
        List<ASTEquation> ehMathGroupFractions = new ArrayList<>(); // EH 수식 그룹의 인라인 분수
        List<IDMLCharacterRun> patternMathGroup = new ArrayList<>(); // 패턴 감지 수식 그룹

        // 단락 또는 스토리에 BT 수식 폰트 런이 하나라도 있는지 확인
        boolean paraHasBTRuns = storyHasBTRuns;
        if (!paraHasBTRuns) {
            for (IDMLCharacterRun r : runs) {
                if (r.isBTFont() || r.grepMathFont()) { paraHasBTRuns = true; break; }
            }
        }

        // 단락에 NP 구조 런(아래첨자, 근호, 분수 등)이 있는지 확인
        boolean paraHasNPStructuralRuns = false;
        for (IDMLCharacterRun r : runs) {
            if (r.isNPFont()) {
                NPFontGlyphMap.FontCategory cat = NPFontGlyphMap.getCategory(r.npFontName());
                if (cat == NPFontGlyphMap.FontCategory.SUBSCRIPT_INDEX
                        || cat == NPFontGlyphMap.FontCategory.SUPERSCRIPT_INDEX
                        || cat == NPFontGlyphMap.FontCategory.ROOT
                        || cat == NPFontGlyphMap.FontCategory.FRACTION_BAR
                        || cat == NPFontGlyphMap.FontCategory.INTEGRAL
                        || cat == NPFontGlyphMap.FontCategory.SUMMATION
                        || cat == NPFontGlyphMap.FontCategory.LIMIT
                        || cat == NPFontGlyphMap.FontCategory.SPECIAL_SYMBOL) {
                    paraHasNPStructuralRuns = true;
                    break;
                }
            }
        }

        for (int idx = 0; idx < runs.size(); idx++) {
            IDMLCharacterRun run = runs.get(idx);
            // 명시적으로 비BT 폰트가 지정된 순수 알파벳/숫자 런만 GREP 플래그 해제
            // 예: "1" (Helvetica Neue) → 해제, "y" (폰트 미지정, GREP으로 BT수식M 적용) → 유지
            if (run.grepMathFont() && ASTMathGrouper.isPlainAlphanumericRun(run)) {
                String ff = run.fontFamily();
                if (ff != null && !ff.contains("BT수식")) {
                    run.grepMathFont(false);
                }
            }

            // EH 수식 그룹 진입 여부 판단
            boolean enterEHMathGroup = false;
            if (run.isEHFont()) {
                enterEHMathGroup = true;
            } else if (EHFontGlyphMap.containsEHEncodedChars(run.content())) {
                // 폰트 미지정이지만 EH 인코딩 패턴(Û` 등) 포함 → EH 그룹으로 진입
                enterEHMathGroup = true;
            } else if (EHFontGlyphMap.containsEHFractionPattern(run.content())) {
                // 폰트 미지정이지만 ;...; 분수 GREP 패턴 포함 → EH 그룹으로 진입
                enterEHMathGroup = true;
            } else if (!ehMathGroup.isEmpty() && ASTMathGrouper.isEHMathBridgeRun(run, runs, idx)) {
                enterEHMathGroup = true;
            }

            // NP 수식 그룹 진입 여부 판단
            boolean enterNPMathGroup = false;
            if (!enterEHMathGroup) {
                if (run.isNPFont()) {
                    enterNPMathGroup = true;
                } else if (!npMathGroup.isEmpty() && ASTMathGrouper.isNPMathBridgeRun(run, runs, idx)) {
                    enterNPMathGroup = true;
                } else if (npMathGroup.isEmpty() && ASTMathGrouper.isPreNPMathRun(run, runs, idx)) {
                    // NP 그룹 시작 전 비NP 수학 텍스트 (예: "y=log" 뒤에 NP_ISHS:"2" 올 때)
                    enterNPMathGroup = true;
                } else if (paraHasNPStructuralRuns && !run.isNPFont() && !run.isBTFont()
                        && !run.grepMathFont() && !run.isEHFont() && ASTMathGrouper.isStandaloneMathRun(run)) {
                    // 단락에 NP 구조 런이 있으면, 독립 수학 텍스트(x=k, 0<k<8)도 수식으로 변환
                    enterNPMathGroup = true;
                }
            }

            // BT 수식 그룹 진입 여부 판단
            boolean enterMathGroup = false;
            if (!enterEHMathGroup && !enterNPMathGroup) {
                if ((run.isBTFont() || run.grepMathFont())
                        && !ASTMathGrouper.isBTRunWithOnlyKorean(run.content())
                        && !ASTMathGrouper.isPlainAlphanumericRun(run)) {
                    enterMathGroup = true;
                } else if (!mathGroup.isEmpty() && ASTMathGrouper.isMathBridgeRun(run, runs, idx)) {
                    enterMathGroup = true;
                } else if (paraHasBTRuns && ASTMathGrouper.looksLikeMathRun(run.content())) {
                    enterMathGroup = true;
                }
            }

            if (enterEHMathGroup) {
                // 다른 그룹이 열려있으면 먼저 flush
                if (!mathGroup.isEmpty()) {
                    ASTMathFlushHelper.flushMathGroupWithFractions(mathGroup, para, mathGroupFractions, hasIndentToHere, colorResolver);
                    ASTMathFlushHelper.emitMathGroupInlineGraphics(mathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                    mathGroup.clear();
                    mathGroupFractions.clear();
                }
                if (!npMathGroup.isEmpty()) {
                    ASTMathFlushHelper.flushNPMathGroupWithFractions(npMathGroup, para, npMathGroupFractions, hasIndentToHere, colorResolver);
                    ASTMathFlushHelper.emitMathGroupInlineGraphics(npMathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                    npMathGroup.clear();
                    npMathGroupFractions.clear();
                }
                ASTMathFlushHelper.extractFractionFrames(run, idmlDoc, ehMathGroupFractions);
                ehMathGroup.add(run);
            } else if (enterNPMathGroup) {
                // 다른 그룹이 열려있으면 먼저 flush
                if (!mathGroup.isEmpty()) {
                    ASTMathFlushHelper.flushMathGroupWithFractions(mathGroup, para, mathGroupFractions, hasIndentToHere, colorResolver);
                    ASTMathFlushHelper.emitMathGroupInlineGraphics(mathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                    mathGroup.clear();
                    mathGroupFractions.clear();
                }
                if (!ehMathGroup.isEmpty()) {
                    ASTMathFlushHelper.flushEHMathGroupWithFractions(ehMathGroup, para, ehMathGroupFractions, hasIndentToHere, colorResolver);
                    ASTMathFlushHelper.emitMathGroupInlineGraphics(ehMathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                    ehMathGroup.clear();
                    ehMathGroupFractions.clear();
                }
                ASTMathFlushHelper.extractFractionFrames(run, idmlDoc, npMathGroupFractions);
                npMathGroup.add(run);
            } else if (enterMathGroup) {
                // 다른 그룹이 열려있으면 먼저 flush
                if (!npMathGroup.isEmpty()) {
                    ASTMathFlushHelper.flushNPMathGroupWithFractions(npMathGroup, para, npMathGroupFractions, hasIndentToHere, colorResolver);
                    ASTMathFlushHelper.emitMathGroupInlineGraphics(npMathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                    npMathGroup.clear();
                    npMathGroupFractions.clear();
                }
                if (!ehMathGroup.isEmpty()) {
                    ASTMathFlushHelper.flushEHMathGroupWithFractions(ehMathGroup, para, ehMathGroupFractions, hasIndentToHere, colorResolver);
                    ASTMathFlushHelper.emitMathGroupInlineGraphics(ehMathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                    ehMathGroup.clear();
                    ehMathGroupFractions.clear();
                }
                // 수식 그룹에 들어가는 런의 인라인 분수 TextFrame 추출
                // (flushMathGroup은 텍스트만 처리하므로 인라인 프레임은 여기서 별도 수집)
                ASTMathFlushHelper.extractFractionFrames(run, idmlDoc, mathGroupFractions);
                mathGroup.add(run);
            } else {
                // 모두 종료 → 변환
                if (!mathGroup.isEmpty()) {
                    ASTMathFlushHelper.flushMathGroupWithFractions(mathGroup, para, mathGroupFractions, hasIndentToHere, colorResolver);
                    ASTMathFlushHelper.emitMathGroupInlineGraphics(mathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                    mathGroup.clear();
                    mathGroupFractions.clear();
                }
                if (!npMathGroup.isEmpty()) {
                    ASTMathFlushHelper.flushNPMathGroupWithFractions(npMathGroup, para, npMathGroupFractions, hasIndentToHere, colorResolver);
                    ASTMathFlushHelper.emitMathGroupInlineGraphics(npMathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                    npMathGroup.clear();
                    npMathGroupFractions.clear();
                }
                if (!ehMathGroup.isEmpty()) {
                    ASTMathFlushHelper.flushEHMathGroupWithFractions(ehMathGroup, para, ehMathGroupFractions, hasIndentToHere, colorResolver);
                    ASTMathFlushHelper.emitMathGroupInlineGraphics(ehMathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                    ehMathGroup.clear();
                    ehMathGroupFractions.clear();
                }
                if (!patternMathGroup.isEmpty()) {
                    ASTMathGrouper.flushPatternMathGroup(patternMathGroup, para, colorResolver);
                    patternMathGroup.clear();
                }

                // 패턴 감지 fallback: BT/NP/EH 어디에도 해당하지 않는 런에서 수식 패턴 감지
                if (MathPatternDetector.isMathInContext(run, runs, idx)) {
                    patternMathGroup.add(run);
                } else {
                    if (!patternMathGroup.isEmpty()) {
                        ASTMathGrouper.flushPatternMathGroup(patternMathGroup, para, colorResolver);
                        patternMathGroup.clear();
                    }
                    ASTRunConverter.convertCharacterRun(run, idmlPara, para, pool, idmlDoc, colorResolver, imageLoader, resolvedData);
                }
            }
        }
        // 마지막 수식 그룹 처리
        if (!mathGroup.isEmpty()) {
            ASTMathFlushHelper.flushMathGroupWithFractions(mathGroup, para, mathGroupFractions, hasIndentToHere, colorResolver);
            ASTMathFlushHelper.emitMathGroupInlineGraphics(mathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
        }
        if (!npMathGroup.isEmpty()) {
            ASTMathFlushHelper.flushNPMathGroupWithFractions(npMathGroup, para, npMathGroupFractions, hasIndentToHere, colorResolver);
            ASTMathFlushHelper.emitMathGroupInlineGraphics(npMathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
        }
        if (!ehMathGroup.isEmpty()) {
            ASTMathFlushHelper.flushEHMathGroupWithFractions(ehMathGroup, para, ehMathGroupFractions, hasIndentToHere, colorResolver);
            ASTMathFlushHelper.emitMathGroupInlineGraphics(ehMathGroup, para, idmlDoc, colorResolver, imageLoader, resolvedData);
        }
        if (!patternMathGroup.isEmpty()) {
            ASTMathGrouper.flushPatternMathGroup(patternMathGroup, para, colorResolver);
        }

        // 단락 끝의 trailing lineBreak 제거
        // 단락 맨 끝에 연속된 BREAK만 제거 (중간의 BREAK는 보존 — 수식 분할 줄바꿈 등)
        List<ASTInlineItem> items = para.items();
        while (!items.isEmpty()
                && items.get(items.size() - 1).itemType() == ASTInlineItem.ItemType.BREAK) {
            items.remove(items.size() - 1);
        }
        applyTrailingPageNumberLeader(para, idmlPara, idmlDoc);

        // 분수 수식(FRACTION_FRAME) 포함 단락: 줄간격을 PERCENT로 변경하고 여백 추가
        // 고정 줄간격에서 분수 수식이 다음 텍스트와 겹치는 것을 방지
        if (ASTMathFlushHelper.hasFractionEquation(para)) {
            para.lineSpacingType("percent");
            para.lineSpacing(200);
            if (para.spaceAfter() == null || para.spaceAfter() < 400) {
                para.spaceAfter(400L);
            }
        }

        return para;
    }

    private static void applyTrailingPageNumberLeader(ASTParagraph para,
                                                      IDMLParagraph idmlPara,
                                                      IDMLDocument idmlDoc) {
        if (para == null || para.items() == null || para.items().size() < 2) return;
        if (!hasVisibleText(para)) return;

        List<ASTInlineItem> items = para.items();
        for (int i = items.size() - 1; i >= 0; i--) {
            ASTInlineItem item = items.get(i);
            if (item == null || item.itemType() == ASTInlineItem.ItemType.BREAK) continue;
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                String text = ((ASTTextRun) item).text();
                if (text == null || text.trim().isEmpty()) continue;
                if (!isPageNumberText(text)) return;
            } else if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                ASTInlineObject obj = (ASTInlineObject) item;
                if (obj.kind() != ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) return;
                if (!isPageNumberInlineObject(obj)) return;
            } else {
                return;
            }

            if (i > 0 && items.get(i - 1) instanceof ASTTextRun) {
                String prev = ((ASTTextRun) items.get(i - 1)).text();
                if ("\t".equals(prev)) return;
            }
            if (!enableRightmostDotLeader(para, idmlPara, idmlDoc)) return;

            ASTTextRun tabRun = new ASTTextRun();
            tabRun.text("\t");
            tabRun.textColor("#000000");
            items.add(i, tabRun);
            return;
        }
    }

    private static boolean hasVisibleText(ASTParagraph para) {
        if (para.items() == null) return false;
        for (ASTInlineItem item : para.items()) {
            if (item == null || item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) continue;
            String text = ((ASTTextRun) item).text();
            if (text != null && !text.trim().isEmpty()) return true;
        }
        return false;
    }

    private static boolean isPageNumberInlineObject(ASTInlineObject obj) {
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return false;
        StringBuilder sb = new StringBuilder();
        for (ASTParagraph p : obj.paragraphs()) {
            if (p.items() == null) continue;
            for (ASTInlineItem item : p.items()) {
                if (item != null && item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null) sb.append(text);
                }
            }
        }
        return isPageNumberText(sb.toString());
    }

    private static boolean isPageNumberText(String text) {
        if (text == null) return false;
        String cleaned = text.replace("\r", "").replace("\n", "").trim();
        return cleaned.matches("\\d{1,4}");
    }

    private static boolean enableRightmostDotLeader(ASTParagraph para,
                                                   IDMLParagraph idmlPara,
                                                   IDMLDocument idmlDoc) {
        if ((para.tabStops() == null || para.tabStops().size() < 2)
                && idmlPara != null && idmlDoc != null) {
            IDMLStyleDef style = resolveStyle(idmlPara.appliedParagraphStyle(), idmlDoc.paraStyles());
            if (style != null && style.tabStops() != null) {
                for (IDMLStyleDef.TabStop ts : style.tabStops()) {
                    if (ts == null || ts.position() <= 0) continue;
                    long posHwpunits = CoordinateConverter.pointsToHwpunits(ts.position());
                    para.addTabStop(new ASTTabStop(posHwpunits, mapTabAlignment(ts.alignment()), ts.leader()));
                }
            }
        }
        if (para.tabStops() == null || para.tabStops().size() < 2) return false;

        ASTTabStop rightmost = null;
        for (ASTTabStop stop : para.tabStops()) {
            if (stop == null || stop.position() <= 0) continue;
            if (rightmost == null || stop.position() > rightmost.position()) {
                rightmost = stop;
            }
        }
        if (rightmost == null) return false;
        rightmost.leader(".");
        return true;
    }

    /**
     * IDML 탭 정렬 문자열을 HWPX 탭 타입으로 매핑.
     */
    public static String mapTabAlignment(String idmlAlignment) {
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
     * "Indent to Here" (U+0008) 마커를 원시 IDMLCharacterRun에서 제거하고,
     * 마커 위치까지의 텍스트 폭을 추정하여 hanging indent를 설정한다.
     * U+0008은 XML에서 허용되지 않는 제어 문자이므로 반드시 제거해야 한다.
     * 수식 그룹 변환 전에 호출해야 한다 (U+0008이 수식 경로로 들어가는 것을 방지).
     *
     * @return true if any U+0008 marker was found (수식 분할 여부 결정에 사용)
     */
    private static boolean applyIndentToHere(ASTParagraph para,
                                            List<IDMLCharacterRun> runs,
                                            IDMLParagraph idmlPara,
                                            IDMLDocument idmlDoc) {
        boolean found = false;
        int charsBeforeMarker = 0;
        boolean markerFound = false;

        for (IDMLCharacterRun run : runs) {
            String text = run.content();
            if (text == null) continue;
            int markerPos = text.indexOf('\u0008');
            if (markerPos >= 0) {
                found = true;
                markerFound = true;
                // 마커 전 텍스트에서 가시 문자 수 계산
                for (int i = 0; i < markerPos; i++) {
                    char c = text.charAt(i);
                    // 제로 폭 문자 제외
                    if (c != '\u200c' && c != '\u200b' && c != '\u200d' && c != '\ufeff') {
                        charsBeforeMarker++;
                    }
                }
                // InDesign "Indent to Here" (ACE 7): 탭+U+0008 조합에서 탭도 함께 제거
                text = text.replace("\t\u0008", "");
                text = text.replace("\u0008", "");
                run.content(text);
            } else if (!markerFound) {
                // 마커 전 런의 가시 문자 수 누적
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c != '\u200c' && c != '\u200b' && c != '\u200d' && c != '\ufeff') {
                        charsBeforeMarker++;
                    }
                }
            }
        }

        // indentToHere 위치 저장 (lineBreak 후 탭 삽입에 사용)
        if (found && charsBeforeMarker > 0) {
            double fontSizePt = 10.0;
            if (idmlDoc != null && idmlPara != null) {
                IDMLStyleDef style = resolveStyle(idmlPara.appliedParagraphStyle(), idmlDoc.paraStyles());
                if (style != null && style.fontSize() != null) {
                    fontSizePt = style.fontSize();
                }
            }
            long indentHwpunit = Math.round(charsBeforeMarker * fontSizePt * 0.45 * 100);
            para.indentToHerePosition(indentHwpunit);
        }

        return found;
    }

    /**
     * BT수식M 글리프 코드 r\d+par를 원문자(①②③...)로 변환한다.
     * 수식 그룹화 전에 호출해야 한다 (원문자는 수식이 아닌 일반 텍스트로 처리).
     */
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
        merged.underline(style.underline() != null ? style.underline() : parent.underline());
        merged.underlineType(style.underlineType() != null ? style.underlineType() : parent.underlineType());
        merged.underlineWeight(style.underlineWeight() != null ? style.underlineWeight() : parent.underlineWeight());
        merged.underlineOffset(style.underlineOffset() != null ? style.underlineOffset() : parent.underlineOffset());
        merged.underlineColor(style.underlineColor() != null ? style.underlineColor() : parent.underlineColor());
        merged.strikeThrough(style.strikeThrough() != null ? style.strikeThrough() : parent.strikeThrough());
        merged.leading(style.leading() != null ? style.leading() : parent.leading());
        merged.leadingType(style.leadingType() != null ? style.leadingType() : parent.leadingType());
        merged.autoLeading(style.autoLeading() != null ? style.autoLeading() : parent.autoLeading());
        merged.tabStops(style.tabStops() != null ? style.tabStops() : parent.tabStops());
        // 단락 속성
        merged.textAlignment(style.textAlignment() != null ? style.textAlignment() : parent.textAlignment());
        merged.firstLineIndent(style.firstLineIndent() != null ? style.firstLineIndent() : parent.firstLineIndent());
        merged.leftIndent(style.leftIndent() != null ? style.leftIndent() : parent.leftIndent());
        merged.rightIndent(style.rightIndent() != null ? style.rightIndent() : parent.rightIndent());
        merged.spaceBefore(style.spaceBefore() != null ? style.spaceBefore() : parent.spaceBefore());
        merged.spaceAfter(style.spaceAfter() != null ? style.spaceAfter() : parent.spaceAfter());
        // 문자 속성
        merged.horizontalScale(style.horizontalScale() != null ? style.horizontalScale() : parent.horizontalScale());
        merged.baselineShift(style.baselineShift() != null ? style.baselineShift() : parent.baselineShift());
        merged.capitalization(style.capitalization() != null ? style.capitalization() : parent.capitalization());
        // 단락 분리 제어
        merged.keepWithNext(style.keepWithNext() != null ? style.keepWithNext() : parent.keepWithNext());
        merged.keepLinesTogether(style.keepLinesTogether() != null ? style.keepLinesTogether() : parent.keepLinesTogether());
        merged.pageBreakBefore(style.pageBreakBefore() != null ? style.pageBreakBefore() : parent.pageBreakBefore());
        merged.ruleBelowOn(style.ruleBelowOn() != null ? style.ruleBelowOn() : parent.ruleBelowOn());
        merged.ruleAboveLineWeight(style.ruleAboveLineWeight() != null ? style.ruleAboveLineWeight() : parent.ruleAboveLineWeight());
        merged.ruleBelowLineWeight(style.ruleBelowLineWeight() != null ? style.ruleBelowLineWeight() : parent.ruleBelowLineWeight());
        merged.ruleAboveColor(style.ruleAboveColor() != null ? style.ruleAboveColor() : parent.ruleAboveColor());
        merged.ruleBelowColor(style.ruleBelowColor() != null ? style.ruleBelowColor() : parent.ruleBelowColor());
        // 두문자
        merged.dropCapLines(style.dropCapLines() != null ? style.dropCapLines() : parent.dropCapLines());
        merged.dropCapCharacters(style.dropCapCharacters() != null ? style.dropCapCharacters() : parent.dropCapCharacters());
        // 어절 간격
        merged.desiredWordSpacing(style.desiredWordSpacing() != null ? style.desiredWordSpacing() : parent.desiredWordSpacing());
        merged.minimumWordSpacing(style.minimumWordSpacing() != null ? style.minimumWordSpacing() : parent.minimumWordSpacing());
        merged.maximumWordSpacing(style.maximumWordSpacing() != null ? style.maximumWordSpacing() : parent.maximumWordSpacing());
        // GREP 스타일
        merged.grepStyles(style.grepStyles() != null ? style.grepStyles() : parent.grepStyles());
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
                                                            ASTImageLoader imageLoader,
                                                            ResolvedData resolvedData) {
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
        if (!hasContent && inlineStory.tables().isEmpty()) {
            // RuleBelow가 있는 빈 답안 상자 → 밑줄 있는 공백 인라인 프레임
            boolean hasRuleBelow = false;
            for (IDMLParagraph p : inlineStory.paragraphs()) {
                if (hasRuleBelowOn(p, idmlDoc)) { hasRuleBelow = true; break; }
            }
            if (hasRuleBelow) {
                double w0 = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
                double h0 = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
                ASTInlineObject obj0 = new ASTInlineObject();
                obj0.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
                obj0.sourceId(tf.selfId());
                obj0.width(CoordinateConverter.pointsToHwpunits(w0));
                obj0.height(CoordinateConverter.pointsToHwpunits(h0));
                // 밑줄 공백으로 답안 밑줄선 표현 (줄바꿈 방지: 공백 1개 ≈ 4pt 가정)
                ASTParagraph ulPara = new ASTParagraph();
                ASTTextRun ulRun = new ASTTextRun();
                int spaceCount = Math.max(3, (int) (w0 / 4.0));
                StringBuilder spaces = new StringBuilder(spaceCount);
                for (int si = 0; si < spaceCount; si++) spaces.append(' ');
                ulRun.text(spaces.toString());
                ulRun.underline(true);
                ulPara.addItem(ulRun);
                obj0.addParagraph(ulPara);
                obj0.anchoredPosition(tf.anchoredPosition());
                obj0.textWrapMode(tf.textWrapMode());
                return obj0;
            }
            // 채움색이 있는 시각 요소는 빈 콘텐츠여도 유지 (핑크 원 등)
            double w0 = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
            double h0 = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
            if (w0 > 0 && h0 > 0 && tf.fillColor() != null
                    && !"Swatch/None".equals(tf.fillColor())) {
                String fill = colorResolver.resolve(tf.fillColor());
                if (fill != null) {
                    ASTInlineObject spacer = new ASTInlineObject();
                    spacer.kind(ASTInlineObject.ObjectKind.SPACER_RECT);
                    spacer.sourceId(tf.selfId());
                    spacer.width(CoordinateConverter.pointsToHwpunits(w0));
                    spacer.height(CoordinateConverter.pointsToHwpunits(h0));
                    spacer.fillColor(fill);
                    spacer.fillTint(tf.fillTint());
                    return spacer;
                }
            }
            return null;
        }

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.sourceId(tf.selfId());

        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));

        // 테두리/채우기/모서리 속성 전달 (ASTPageProcessor.createTextFrameBlock과 동일 패턴)
        if (tf.strokeColor() != null) {
            obj.strokeColor(colorResolver.resolve(tf.strokeColor()));
        }
        obj.strokeWeight(tf.strokeWeight());
        obj.strokeTint(tf.strokeTint());
        if (tf.fillColor() != null) {
            obj.fillColor(colorResolver.resolve(tf.fillColor()));
        }
        obj.fillTint(tf.fillTint());
        obj.cornerRadius(tf.cornerRadius());
        obj.verticalJustification(tf.verticalJustification());

        // 내부 여백 전달
        if (tf.insetSpacing() != null) {
            double[] inset = tf.insetSpacing();
            obj.textMarginTop(CoordinateConverter.pointsToHwpunits(inset[0]));
            obj.textMarginLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
            obj.textMarginBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
            obj.textMarginRight(CoordinateConverter.pointsToHwpunits(inset[3]));
        }

        // 인라인 스토리의 단락을 ASTParagraph로 변환 (큰 이미지는 별도 단락으로 분리)
        FlattenedObjectPool emptyPool = new FlattenedObjectPool();
        for (IDMLParagraph idmlPara : inlineStory.paragraphs()) {
            ASTParagraph astPara = convertParagraph(idmlPara, emptyPool, idmlDoc, colorResolver, imageLoader, false, resolvedData);
            if (astPara != null && !astPara.items().isEmpty()) {
                // RuleBelow → 텍스트 런에 밑줄 전파 (답안 밑줄선)
                if (hasRuleBelowOn(idmlPara, idmlDoc)) {
                    for (ASTInlineItem item : astPara.items()) {
                        if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                            ((ASTTextRun) item).underline(true);
                        }
                    }
                }
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

        // 앵커/래핑 속성 전달
        obj.anchoredPosition(tf.anchoredPosition());
        obj.textWrapMode(tf.textWrapMode());

        boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
        boolean hasTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
        return (hasParagraphs || hasTables) ? obj : null;
    }

    /**
     * 인라인 TextFrame을 분수 HWP 수식으로 변환.
     * 감지 조건:
     *   (1) ObjectStyle에 "분수" 포함 (명시적 분수 스타일), 또는
     *   (2) 첫 단락에 RuleBelow가 설정됨 (단락 아래선 = 분수선)
     * 2개 단락 구조: 분자(para1) / 분모(para2).
     */

    /**
     * 단락에 RuleBelow가 설정되어 있는지 확인 (로컬 오버라이드 + 스타일 정의 폴백).
     */
    private static boolean hasRuleBelowOn(IDMLParagraph para, IDMLDocument idmlDoc) {
        if (para.ruleBelowOn()) return true;
        // 스타일 정의에서 RuleBelow 확인
        String styleRef = para.appliedParagraphStyle();
        if (styleRef != null && idmlDoc != null) {
            IDMLStyleDef styleDef = idmlDoc.getParagraphStyle(styleRef);
            if (styleDef != null && Boolean.TRUE.equals(styleDef.ruleBelowOn())) return true;
        }
        return false;
    }

    static ASTEquation tryConvertFractionTextFrame(IDMLTextFrame tf, IDMLDocument idmlDoc) {
        // 1. ObjectStyle 확인
        String objStyle = tf.appliedObjectStyle();
        boolean hasFractionStyle = objStyle != null && objStyle.contains("분수");

        // 간격용 스타일 제외 (#간격, 0_분수-보기간격 등)
        if (hasFractionStyle && objStyle.contains("간격")) return null;

        // 2. 인라인 스토리 로드
        if (tf.parentStoryId() == null) return null;
        IDMLStory story = idmlDoc.getStory(tf.parentStoryId());
        if (story == null) return null;

        List<IDMLParagraph> paras = story.paragraphs();

        // 3. ObjectStyle이 분수가 아닌 경우 → 첫 단락의 RuleBelow로 분수 감지
        if (!hasFractionStyle) {
            boolean hasRuleBelow = false;
            for (IDMLParagraph p : paras) {
                if (p.ruleBelowOn()) { hasRuleBelow = true; break; }
            }
            if (!hasRuleBelow) return null;
        }

        // 4. 비어 있지 않은 단락을 찾아 분자/분모 추출
        // IDML에서 <Br/>로 인해 빈 단락이 중간에 생길 수 있으므로 건너뜀
        String numText = null;
        String denText = null;
        for (IDMLParagraph p : paras) {
            String text = extractParagraphText(p);
            if (text.isEmpty()) continue;
            if (numText == null) {
                numText = text;
            } else {
                denText = text;
                break;
            }
        }
        if (numText == null || denText == null) {
            // 분수 스타일이지만 2단락 구조가 아닌 경우:
            // - 내용 자체가 없으면 빈 답안 상자 (□)
            // - 1단락에 텍스트가 있으면 분수가 아닌 일반 인라인 텍스트 → null 반환하여 일반 경로로 처리
            if (hasFractionStyle && numText == null) {
                return new ASTEquation("\u25A1", "ANSWER_BOX");
            }
            return null;
        }

        // 5. BT 수식 텍스트 → HWP 스크립트 변환
        String numScript = BTFontEquationConverter.convertRawToHwpScript(numText);
        String denScript = BTFontEquationConverter.convertRawToHwpScript(denText);

        // 6. 분수 수식 조립
        String hwpScript = "{" + numScript + "} over {" + denScript + "}";
        return new ASTEquation(hwpScript, "FRACTION_FRAME");
    }

    /**
     * 단락의 모든 런 텍스트를 연결하여 반환.
     */
    private static String extractParagraphText(IDMLParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (IDMLCharacterRun run : para.characterRuns()) {
            if (run.content() != null) {
                // \uFFFC (Object Replacement Char) → □ (빈 답안 상자 등 인라인 오브젝트 자리)
                sb.append(run.content().replace('\uFFFC', '\u25A1'));
            }
        }
        return sb.toString().trim();
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
                ASTTableCell cell = ASTTableConverter.convertTableCell(idmlCell, rowIdx, colIdx,
                        idmlDoc, colorResolver, imageLoader, null);
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
