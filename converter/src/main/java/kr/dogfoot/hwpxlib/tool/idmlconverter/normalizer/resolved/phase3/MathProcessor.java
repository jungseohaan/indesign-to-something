package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontEquationConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHGrepFractionConverter;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaClassifier;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTMathGrouper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

        // 화학식은 문자속성 첨자 텍스트가 아니라 ASTEquation("CHEM_FORMULA")으로
        // 변환한다. 원본 run의 font size/color는 collectFormulaEquationCluster 에서
        // equation metadata(preferredBaseUnit/textColor)로 전달된다.

        // IDML 경로에서 이미 ASTEquation으로 변환된 단락은 건너뜀 (중복 변환 방지)
        boolean hasEquation = false;
        boolean hasEHRun = false;
        for (ASTInlineItem it : items) {
            if (it instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) hasEquation = true;
            if (it instanceof ASTTextRun) {
                if (isEHTextRun((ASTTextRun) it)) hasEHRun = true;
            }
        }
        if (hasEquation) {
            stitchGrepSplitFormulaEquations(para);
            stitchTextSeparatedFormulaEquationFragments(para);
            collapseMixedFormulaEquationClusters(ctx, para);
            if (hasEHRun) {
                // 수식과 EH TextRun이 공존하더라도 의미 있는 원문 텍스트는 보존한다.
                // 제거 대상은 근호/분수선처럼 수식 구조를 그리던 장식 글리프뿐이다.
                items.removeIf(it -> it instanceof ASTTextRun
                        && isDiscardableEHStructureResidue((ASTTextRun) it));
            }
            convertSubscriptChemicalSegments(para);
            reassembleFragmentedChemicalFormula(para);
            stitchChemicalFormulaFragments(para);
            reassembleFragmentedChemicalReaction(para);
            demoteIsolatedSingleLetterMathEquation(para);
            return;
        }

        splitEHKoreanMixedTextRuns(items);

        promoteEquationFontReactionRanges(ctx, items);

        List<ASTInlineItem> newItems = new ArrayList<>();
        List<IDMLCharacterRun> mathGroup = new ArrayList<>();
        // SPEC-042: 그룹에 들어간 원본 ASTTextRun — flush 결과 텍스트런에 크기·굵기·
        // 첨자를 백필하기 위해 어댑터와 병렬로 유지 (어댑터 자체에 실으면 그룹핑
        // 하류 경로가 바뀌어 첨자 회귀가 났다. SPEC-042 실패 기록 참조)
        List<ASTTextRun> mathGroupSrc = new ArrayList<>();
        String mathType = null; // "EH", "BT", "NP"

        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) {
                flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                mathGroup.clear();
                mathGroupSrc.clear();
                mathType = null;
                newItems.add(item);
                continue;
            }

            ASTTextRun tr = (ASTTextRun) item;
            applyPositionFromCharacterStyle(tr);
            String ff = tr.fontFamily();
            String currentType = mathTypeOf(tr);

            FormulaCluster formulaCluster =
                    (currentType == null
                            || canStartExplicitBTFormulaCluster(currentType, tr)
                            || canStartBodyTextChemicalReactionCluster(items, i, tr))
                            ? collectFormulaEquationCluster(items, i)
                            : null;
            if (formulaCluster != null) {
                // 화살표(→ 정규화로 폰트가 벗겨진 런)에서 시작한 클러스터는 좌변을 안
                // 담는다. 대기 중인 수식 폰트 그룹(좌변, 예: "2H₂+O₂")이 있으면 별도
                // flush(텍스트 폴백 위험) 대신 클러스터 스크립트 앞에 흡수한다
                // (실측: 반응식 보기가 "좌변 텍스트 + '~ rarrow ~ 우변' 수식"으로 갈라짐).
                String absorbedLhs = null;
                int absorbedTailCount = 0;
                if (clusterScriptStartsWithArrow(formulaCluster.equation)) {
                    List<ASTTextRun> lhsRuns = new ArrayList<>();
                    absorbedTailCount = collectTrailingEquationFontRuns(newItems, lhsRuns);
                    lhsRuns.addAll(mathGroupSrc);
                    if (!lhsRuns.isEmpty()) {
                        absorbedLhs = buildFormulaScriptFromEquationFontRuns(lhsRuns);
                    }
                    if (absorbedLhs == null) absorbedTailCount = 0;
                }
                if (absorbedLhs != null && !absorbedLhs.isEmpty()) {
                    for (int r = 0; r < absorbedTailCount; r++) {
                        newItems.remove(newItems.size() - 1);
                    }
                    formulaCluster.equation.hwpScript(
                            normalizeFormulaScript(absorbedLhs + " "
                                    + formulaCluster.equation.hwpScript()));
                    mathGroup.clear();
                    mathGroupSrc.clear();
                    mathType = null;
                } else {
                    flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                    mathGroup.clear();
                    mathGroupSrc.clear();
                    mathType = null;
                }
                newItems.add(formulaCluster.equation);
                i = formulaCluster.endExclusive - 1;
                continue;
            }

            if (currentType != null && isSimplePositionedTextRun(tr)) {
                flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                mathGroup.clear();
                mathGroupSrc.clear();
                mathType = null;
                newItems.add(item);
                continue;
            }

            boolean formulaBoundaryOnly = isFormulaBoundaryText(tr.text());
            if (formulaBoundaryOnly) {
                flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                mathGroup.clear();
                mathGroupSrc.clear();
                mathType = null;
                inheritFormulaBoundaryTextColor(items, i, tr);
                newItems.add(item);
                continue;
            }

            // "BT수식H" 계열은 이름만 수식이고, 실제로는 본문 속 영문/숫자/기호를
            // 조판하는 폰트다(GREP 스타일 "00_영문", "00_숫자"가 적용). 실측:
            //   BT수식H-분수N   (356회)  "1-1", "1.", "2.", "H", "O", "2"
            //   BT수식H-편한글씨 (48회)   ":"  (비율 표기)
            // 수식 구조(분수/루트/시그마)가 전혀 없다.
            //
            // 이런 런을 무조건 수식 그룹에 넣으면 한글 문장 속 원소기호나 번호까지
            // HWP 수식(이탤릭)이 된다 — "수소 원자(H)" 의 H, "1." 같은 항목 번호.
            // → 수식 구조 문자가 없는 평범한 텍스트면 수식 그룹에 넣지 않는다.
            if (currentType != null && mathGroup.isEmpty()
                    && BTFontGlyphMap.isBTBodyTextFont(ff)
                    && !looksLikeMathContent(tr.text())) {
                newItems.add(item);
                continue;
            }

            if (currentType != null) {
                if (mathType == null || mathType.equals(currentType)) {
                    mathType = currentType;
                    IDMLCharacterRun cr = mathRunFromTextRun(tr, ff);
                    mathGroup.add(cr);
                    mathGroupSrc.add(tr);
                } else {
                    flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                    mathGroup.clear();
                    mathGroupSrc.clear();
                    mathType = currentType;
                    IDMLCharacterRun cr = mathRunFromTextRun(tr, ff);
                    mathGroup.add(cr);
                    mathGroupSrc.add(tr);
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
                                    if (isEHTextRun((ASTTextRun) next)) {
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
                    IDMLCharacterRun cr = mathRunFromTextRun(tr, tr.fontFamily() != null ? tr.fontFamily() : "");
                    mathGroup.add(cr);
                    mathGroupSrc.add(tr);
                } else {
                    flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);
                    mathGroup.clear();
                    mathGroupSrc.clear();
                    mathType = null;
                    newItems.add(item);
                }
            }
        }
        flushResolvedMathGroupWithBackfill(ctx, mathGroup, mathGroupSrc, mathType, newItems, para);

        if (newItems.size() != items.size() || !newItems.equals(items)) {
            items.clear();
            items.addAll(newItems);
        }
        collapseMixedFormulaEquationClusters(ctx, para);
        convertSubscriptChemicalSegments(para);
        reassembleFragmentedChemicalFormula(para);
        stitchChemicalFormulaFragments(para);
        reassembleFragmentedChemicalReaction(para);
        demoteIsolatedSingleLetterMathEquation(para);
    }

    private static boolean isDiscardableEHStructureResidue(ASTTextRun run) {
        if (run == null) return false;
        String ff = run.fontFamily();
        if (!isEHTextRun(run)) return false;
        String text = run.text();
        if (text == null || text.isEmpty()) return true;
        String decoded = EHFontGlyphMap.decodeStrayGlyphText(text, ff);
        if (decoded == null || decoded.trim().isEmpty()) return true;
        return EHFontGlyphMap.isFractionBarDecoration(text);
    }

    /**
     * EH 폰트가 붙었지만 한국어가 섞인 TextRun 을 첫 한국어 문자에서 분리한다.
     *
     * <p>InDesign DOM(resolved)은 √ 글리프 뒤 한국어 문장까지 EH상부자 폰트로
     * 보고하는 경우가 있다(실측: p20 표 셀 "a가 √a 보다 항상 더 큰지 말해 보자."의
     * "a 보다…보자." 전체가 EH상부자). 그대로 수식 그룹에 넣으면 lexSubSup 이
     * 미매핑 0x80+ 문자로 한국어를 통째로 버려 문장이 sqrt{a}. 로 잘린다.
     * 라틴 머리(radicand)는 EH 수식으로 남기고, 첫 한국어부터는 EH 폰트를 지운
     * 일반 텍스트 런으로 분리한다.
     */
    private static void splitEHKoreanMixedTextRuns(List<ASTInlineItem> items) {
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun tr = (ASTTextRun) item;
            String ff = tr.fontFamily();
            if (ff == null || !EHFontGlyphMap.isEHFontFamily(ff)) continue;
            String text = tr.text();
            if (text == null) continue;
            int k = firstKoreanIndex(text);
            if (k < 0) continue;
            if (k == 0) {
                tr.fontFamily(null);
                tr.fontStyle(null);
                continue;
            }
            ASTTextRun tail = new ASTTextRun();
            tail.text(text.substring(k));
            tail.fontSizeHwpunits(tr.fontSizeHwpunits());
            tail.textColor(tr.textColor());
            tail.shadeColor(tr.shadeColor());
            tail.letterSpacing(tr.letterSpacing());
            tr.text(text.substring(0, k));
            items.add(i + 1, tail);
            i++; // tail 은 EH 폰트가 없으므로 재검사 불필요
        }
    }

    private static int firstKoreanIndex(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) return i;
        }
        return -1;
    }

    /**
     * SPEC-067: GREP 정상화가 스타일 경계로 쪼갠 수식 조각을 다시 잇는다.
     *
     * <p>GREP 규칙이 숫자·변수는 이탤릭, 단위·연산자는 정자로 조판하려고 런을 나누는데,
     * IDML 수식 빌더는 이 런 경계를 수식 경계로 오인해 하나였던 수식을 여러 ASTEquation
     * 으로 쪼갠다. 예: "left(√a right)²=a,~left(-√a right)²=a" 가
     * <pre>[EQ: left(√a right)²=a,~(-√a]  [ ]  [EQ: )²]  [EQ: =a]</pre>
     * 로 갈라진다. 앞 조각은 괄호·중괄호 짝이 안 맞는다(열림 &gt; 닫힘)는 게 특징이다.</p>
     *
     * <p>선두 ASTEquation 의 괄호/중괄호가 미완결이면, 뒤따르는 (공백 런만 사이에 둔)
     * ASTEquation 들을 짝이 맞을 때까지 스크립트를 이어 붙여 하나로 합친다. 조각 각각은
     * 불균형이지만 합치면 균형이 복구된다. 균형이 이미 맞는 정상 수식은 건드리지 않는다.</p>
     */
    private static void stitchGrepSplitFormulaEquations(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.size() < 2) return;

        List<ASTInlineItem> out = new ArrayList<>();
        boolean changed = false;
        int i = 0;
        while (i < items.size()) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTEquation)) {
                out.add(item);
                i++;
                continue;
            }
            ASTEquation lead = (ASTEquation) item;
            if ("CHEM_FORMULA".equals(lead.sourceType()) || lead.hwpScript() == null || lead.hwpScript().isEmpty()) {
                out.add(item);
                i++;
                continue;
            }
            // SPEC-067: 빈 근호 재봉합. EHParser 가 키 큰 hook 뒤 radicand 가 조각나
            // 떨어져 나가면 근호를 "sqrt{ }"(빈 radicand)로 남긴다. 바로 뒤(공백만 사이)
            // 조각이 그 radicand 이므로 빈 중괄호 안에 주입한다.
            // 예: "sqrt{25} TIMES sqrt{ }" + "(-7)^{2}" → "sqrt{25} TIMES sqrt{(-7)^{2}}".
            int emptyRadEnd = tryStitchEmptyRadicand(lead, items, i);
            if (emptyRadEnd > i) {
                out.add(lead);
                i = emptyRadEnd;
                changed = true;
                continue;
            }
            // GREP 이 한 수식을 스타일 경계로 쪼개면 선두 조각의 괄호/중괄호 짝이
            // 미완결(열림 > 닫힘)로 남는다(예: "(-√a" + ")²"). 이 신호가 있을 때만,
            // 뒤따르는(공백만 사이에 둔) 수식 조각을 짝이 복구될 때까지 이어 붙인다.
            // 짝이 이미 맞는 완결 수식은 절대 대상이 아니다 — '='로 시작하는 조각까지
            // 흡수하면 원래부터 별개였던 등식(=overline{CD} 같은 도형 등식은 기준선에도
            // 별개 조각으로 존재)을 잘못 삼켜 과병합한다(실측: 수학u5 40건). 불균형
            // 복구는 조각이 명백히 미완결이라는 확실한 증거라서 안전하다.
            StringBuilder merged = new StringBuilder(lead.hwpScript());
            if (isBraceBalanced(merged.toString()) && isParenBalanced(merged.toString())) {
                out.add(item);
                i++;
                continue;
            }
            int j = i + 1;
            int consumedEnd = -1;   // 마지막으로 흡수한 ASTEquation 의 인덱스+1
            while (j < items.size()) {
                ASTInlineItem nxt = items.get(j);
                if (nxt instanceof ASTTextRun && isWhitespaceOnlyRun((ASTTextRun) nxt)) {
                    j++;   // 조각 사이 공백 런은 건너뛴다 (수식 밖 공백이므로 버림)
                    continue;
                }
                if (!(nxt instanceof ASTEquation)) break;
                ASTEquation follow = (ASTEquation) nxt;
                if ("CHEM_FORMULA".equals(follow.sourceType())
                        || follow.hwpScript() == null || follow.hwpScript().isEmpty()) break;
                merged.append(follow.hwpScript());
                consumedEnd = j + 1;
                if (isBraceBalanced(merged.toString()) && isParenBalanced(merged.toString())) {
                    break;  // 짝이 복구되면 완결
                }
                j++;
            }
            String mergedScript = merged.toString();
            if (consumedEnd > i + 1
                    && isBraceBalanced(mergedScript) && isParenBalanced(mergedScript)) {
                // 이미 빌드된 수식 스크립트끼리 이어 붙인 것이므로 normalizeFormulaScript 를
                // 다시 돌리면 안 된다 — RIGHT→rarrow 치환이 소괄호 키워드 "right)" 를 화살표로
                // 깨뜨린다. 중복 공백만 정리한다.
                lead.hwpScript(mergedScript.replaceAll("\\s+", " ").trim());
                out.add(lead);
                i = consumedEnd;   // 흡수한 조각·중간 공백을 모두 건너뛴다
                changed = true;
            } else {
                out.add(item);
                i++;
            }
        }
        if (changed) {
            items.clear();
            items.addAll(out);
        }
    }

    /**
     * SPEC-067: 선두 수식이 빈 근호 "sqrt{ }" 로 끝나면, 뒤따르는(공백만 사이) 수식
     * 조각을 그 빈 중괄호 안에 radicand 로 주입한다. 성공하면 소비한 마지막 인덱스+1 을,
     * 대상이 아니면 start 를 반환한다.
     *
     * <p>EHParser 가 GREP 조각화로 radicand 를 잃은 근호를 "sqrt{ }" 로 남기는데
     * (키 큰 hook + radicand 소실), 그 radicand 는 다음 ASTEquation 으로 떨어져 나가
     * 있다. 빈 근호는 괄호 짝이 맞아 balance-stitch 가 못 잡으므로 별도 처리한다.</p>
     */
    private static int tryStitchEmptyRadicand(ASTEquation lead, List<ASTInlineItem> items, int start) {
        String script = lead.hwpScript();
        if (script == null) return start;
        // 끝이 빈 근호인지: "sqrt{ }" 또는 "sqrt{}" (뒤 공백 허용)
        java.util.regex.Matcher m = EMPTY_RADICAND_TAIL.matcher(script);
        if (!m.find()) return start;
        int braceOpen = m.start(1);   // '{' 위치
        // 뒤따르는 첫 수식 조각(공백 런만 건너뜀)을 radicand 로 삼는다.
        int j = start + 1;
        while (j < items.size()) {
            ASTInlineItem nxt = items.get(j);
            if (nxt instanceof ASTTextRun && isWhitespaceOnlyRun((ASTTextRun) nxt)) { j++; continue; }
            break;
        }
        if (j >= items.size() || !(items.get(j) instanceof ASTEquation)) return start;
        ASTEquation rad = (ASTEquation) items.get(j);
        if ("CHEM_FORMULA".equals(rad.sourceType()) || rad.hwpScript() == null || rad.hwpScript().isEmpty()) {
            return start;
        }
        String radScript = rad.hwpScript().trim();
        // radicand 조각 자체는 괄호·중괄호 짝이 맞아야 안전하게 주입 가능
        // (예: "(-7)^{2}"). 안 맞으면 다른 조각화이므로 balance-stitch 에 맡긴다.
        if (!isBraceBalanced(radScript) || !isParenBalanced(radScript)) return start;
        // "sqrt{ " + radicand + "}" — 빈 중괄호 사이에 주입
        String injected = script.substring(0, braceOpen + 1) + radScript + "}";
        lead.hwpScript(injected.replaceAll("\\s+", " ").trim());
        return j + 1;
    }

    /** 스크립트 끝의 빈 근호 "sqrt{ }" / "sqrt{}" 를 잡는다. 그룹1은 '{'. */
    private static final java.util.regex.Pattern EMPTY_RADICAND_TAIL =
            java.util.regex.Pattern.compile("sqrt\\s*(\\{)\\s*\\}\\s*$");

    /**
     * SPEC-067: GREP 정상화 후 일반 텍스트 연속자에 의해 갈라진 순수 수학 수식을 잇는다.
     *
     * <p>예: {@code 1^{2}} / {@code =1,} / {@code 2^{2}} / {@code =4}.
     * 중간 {@link ASTTextRun} 은 숫자·단일 변수·연산자·쉼표만 포함한 연속자여야
     * 하고, 양옆 {@link ASTEquation} 은 화학식/인라인분수/근호/선분 같은 보호 수식이
     * 아니어야 한다.
     * {@code overline{AB}=overline{CD}} 계열은 기준선에서도 별개 조각일 수 있어 여기서
     * 병합하지 않는다.</p>
     */
    private static void stitchTextSeparatedFormulaEquationFragments(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.size() < 3) return;

        List<ASTInlineItem> out = new ArrayList<>();
        boolean changed = false;
        int i = 0;
        while (i < items.size()) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTEquation)
                    || !isTextSeparatedStitchableEquation((ASTEquation) item)) {
                out.add(item);
                i++;
                continue;
            }

            ASTEquation lead = (ASTEquation) item;
            StringBuilder merged = new StringBuilder(lead.hwpScript());
            int j = i + 1;
            boolean consumedEquation = false;
            boolean consumedAny = false;

            while (j < items.size()) {
                int connectorIndex = j;
                StringBuilder connector = new StringBuilder();
                while (connectorIndex < items.size()) {
                    ASTInlineItem connectorItem = items.get(connectorIndex);
                    if (!(connectorItem instanceof ASTTextRun)) break;
                    ASTTextRun tr = (ASTTextRun) connectorItem;
                    String text = tr.text();
                    if (isWhitespaceOnlyRun(tr)) {
                        connectorIndex++;
                        continue;
                    }
                    if (!isFormulaEquationConnectorText(text)) {
                        connectorIndex = -1;
                    } else {
                        connector.append(text);
                        connectorIndex++;
                    }
                    break;
                }
                if (connectorIndex < 0 || connector.length() == 0) break;

                int nextEquationIndex = connectorIndex;
                while (nextEquationIndex < items.size()
                        && items.get(nextEquationIndex) instanceof ASTTextRun
                        && isWhitespaceOnlyRun((ASTTextRun) items.get(nextEquationIndex))) {
                    nextEquationIndex++;
                }

                String connectorScript = normalizeFormulaConnectorScript(connector.toString());
                if (connectorScript.isEmpty()) break;
                if (nextEquationIndex < items.size()
                        && items.get(nextEquationIndex) instanceof ASTEquation
                        && isTextSeparatedStitchableEquation((ASTEquation) items.get(nextEquationIndex))) {
                    ASTEquation follow = (ASTEquation) items.get(nextEquationIndex);
                    merged.append(connectorScript).append(follow.hwpScript());
                    consumedEquation = true;
                    consumedAny = true;
                    j = nextEquationIndex + 1;
                    continue;
                }

                if (consumedEquation && isTerminalFormulaConnectorScript(connectorScript)) {
                    merged.append(connectorScript);
                    consumedAny = true;
                    j = connectorIndex;
                }
                break;
            }

            if (consumedEquation && consumedAny) {
                lead.hwpScript(normalizeTextSeparatedFormulaScript(merged.toString()));
                out.add(lead);
                i = j;
                changed = true;
            } else {
                out.add(item);
                i++;
            }
        }

        if (changed) {
            items.clear();
            items.addAll(out);
        }
    }

    private static boolean isTextSeparatedStitchableEquation(ASTEquation eq) {
        if (eq == null || eq.hwpScript() == null || eq.hwpScript().isEmpty()) return false;
        String sourceType = eq.sourceType();
        if ("CHEM_FORMULA".equals(sourceType) || "INLINE_FRACTION".equals(sourceType)) return false;
        String script = eq.hwpScript();
        String lower = script.toLowerCase(Locale.ROOT);
        if (lower.contains("overline") || lower.contains("sqrt")
                || lower.contains("root") || lower.contains(" over ")
                || lower.contains("rarrow")) {
            return false;
        }
        boolean hasToken = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (isFormulaSpace(c) || c == '_' || c == '^' || c == '{' || c == '}'
                    || c == '(' || c == ')' || c == '+' || c == '-' || c == '='
                    || c == ',' || c == '.') {
                continue;
            }
            if (isAsciiLetter(c) || Character.isDigit(c)) {
                hasToken = true;
                continue;
            }
            return false;
        }
        return hasToken;
    }

    private static boolean isFormulaEquationConnectorText(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasOperator = false;
        boolean hasValue = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isFormulaSpace(c) || c == ',' || c == '.') continue;
            if (c == '+' || c == '-' || c == '=') {
                hasOperator = true;
                continue;
            }
            if (Character.isDigit(c) || isAsciiLetter(c)) {
                hasValue = true;
                continue;
            }
            return false;
        }
        return hasOperator && hasValue && !hasLongLatinWord(text, 2);
    }

    private static String normalizeFormulaConnectorScript(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isFormulaSpace(c)) continue;
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isTerminalFormulaConnectorScript(String script) {
        if (script == null || script.isEmpty()) return false;
        boolean hasOperator = false;
        boolean hasValue = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '+' || c == '-' || c == '=') hasOperator = true;
            if (Character.isDigit(c) || isAsciiLetter(c)) hasValue = true;
        }
        return hasOperator && hasValue;
    }

    private static String normalizeTextSeparatedFormulaScript(String script) {
        if (script == null) return "";
        return script.replaceAll("\\s+", " ").trim();
    }

    /** 공백(가는공백 포함)만으로 이뤄진 텍스트 런인가. */
    private static boolean isWhitespaceOnlyRun(ASTTextRun run) {
        if (run == null) return false;
        String t = run.text();
        if (t == null || t.isEmpty()) return true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!Character.isWhitespace(c) && !isFormulaSpace(c)) return false;
        }
        return true;
    }

    private static void collapseMixedFormulaEquationClusters(ResolvedBuildContext ctx, ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;

        splitBoundaryWrappedFormulaEquations(items);

        List<ASTInlineItem> out = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            int duplicatePrefixEnd = duplicatePlaceholderPrefixBeforeBoxEquation(ctx, items, i);
            if (duplicatePrefixEnd > i) {
                out.add(items.get(duplicatePrefixEnd));
                i = duplicatePrefixEnd;
                changed = true;
                continue;
            }
            FormulaCluster cluster = collectMixedFormulaEquationCluster(ctx, items, i);
            if (cluster != null) {
                removeTrailingFormulaPlaceholdersAlreadyInEquation(ctx, out, cluster.equation);
                out.add(cluster.equation);
                i = cluster.endExclusive - 1;
                changed = true;
            } else {
                out.add(items.get(i));
            }
        }
        if (changed) {
            items.clear();
            items.addAll(out);
        }
    }

    private static void splitBoundaryWrappedFormulaEquations(List<ASTInlineItem> items) {
        if (items == null || items.isEmpty()) return;
        List<ASTInlineItem> out = new ArrayList<>();
        boolean changed = false;
        for (ASTInlineItem item : items) {
            if (!(item instanceof ASTEquation)) {
                out.add(item);
                continue;
            }
            ASTEquation eq = (ASTEquation) item;
            String script = normalizeExistingFormulaEquationScript(eq.hwpScript());
            if (script == null || script.isEmpty()) {
                out.add(item);
                continue;
            }
            int start = 0;
            while (start < script.length() && isEquationBoundaryChar(script.charAt(start))) start++;
            int end = script.length();
            while (end > start && isEquationBoundaryChar(script.charAt(end - 1))) end--;
            if (start == 0 && end == script.length()) {
                out.add(item);
                continue;
            }
            String core = script.substring(start, end);
            if (core.isEmpty() || !isFormulaEquationScript(core)) {
                out.add(item);
                continue;
            }
            // sqrt{...}·분수 {A} over {B} 의 구문 중괄호를 경계로 오인해 벗기면 짝이 깨져
            // 닫는 }가 리터럴 텍스트로 새고 수식이 열린 채 렌더된다(실측: 수학 1단원 근호
            // 선택지 -sqrt{121} → -sqrt{121 + "}"). core 의 { } 균형이 맞을 때만 벗긴다.
            if (!isBraceBalanced(core)) {
                out.add(item);
                continue;
            }
            // 소괄호도 마찬가지 — (-16)×…÷(-6/7) 의 바깥 ( ) 나 left( … right) 의 소괄호를
            // 경계로 벗기면 짝이 깨져 닫는 )·right 가 리터럴로 새거나 left( 가 미완결된다
            // (실측: 1단원 p30 "(-16)×3/4÷(-6/7)" → "-16)…right"). ( ) 균형이 맞을 때만 벗긴다.
            if (!isParenBalanced(core)) {
                out.add(item);
                continue;
            }
            if (start > 0) {
                out.add(textRunFromEquationBoundary(eq, script.substring(0, start)));
            }
            ASTEquation coreEq = new ASTEquation(core, eq.sourceType());
            copyEquationHints(eq, coreEq);
            out.add(coreEq);
            if (end < script.length()) {
                out.add(textRunFromEquationBoundary(eq, script.substring(end)));
            }
            changed = true;
        }
        if (changed) {
            items.clear();
            items.addAll(out);
        }
    }

    private static boolean isEquationBoundaryChar(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']'
                || c == '{' || c == '}' || c == ',' || c == '.' || c == ':';
    }

    /** HWP 수식 구문 중괄호 { } 짝이 맞는지. sqrt/분수 구문 보호용. */
    private static boolean isBraceBalanced(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth < 0) return false; }
        }
        return depth == 0;
    }

    /**
     * HWP 수식 소괄호 ( ) 짝이 맞는지. left( … right) 및 (-16)·(-6/7) 보호용.
     * left(/right 키워드의 소괄호도 리터럴 ( )와 함께 depth 로 센다.
     */
    private static boolean isParenBalanced(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth < 0) return false; }
        }
        return depth == 0;
    }

    private static ASTTextRun textRunFromEquationBoundary(ASTEquation source, String text) {
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        if (source != null) {
            run.textColor(source.textColor());
            run.fontSizeHwpunits(source.preferredBaseUnit());
            run.fontFamily(source.preferredFontFamily());
        }
        return run;
    }

    private static void copyEquationHints(ASTEquation source, ASTEquation target) {
        if (source == null || target == null) return;
        target.textColor(source.textColor());
        target.preferredBaseUnit(source.preferredBaseUnit());
        target.preferredFontFamily(source.preferredFontFamily());
    }

    private static int duplicatePlaceholderPrefixBeforeBoxEquation(
            ResolvedBuildContext ctx,
            List<ASTInlineItem> items,
            int start) {
        if (ctx == null || items == null || start < 0 || start >= items.size()) return -1;
        int i = start;
        int placeholders = 0;
        while (i < items.size()
                && items.get(i) instanceof ASTInlineObject
                && isFormulaAnswerPlaceholder(ctx, (ASTInlineObject) items.get(i))) {
            placeholders++;
            i++;
        }
        if (placeholders == 0 || i >= items.size() || !(items.get(i) instanceof ASTEquation)) {
            return -1;
        }
        ASTEquation equation = (ASTEquation) items.get(i);
        return countFormulaBoxes(equation.hwpScript()) >= placeholders ? i : -1;
    }

    private static void removeTrailingFormulaPlaceholdersAlreadyInEquation(
            ResolvedBuildContext ctx,
            List<ASTInlineItem> out,
            ASTEquation equation) {
        if (ctx == null || out == null || out.isEmpty() || equation == null) return;
        int remainingBoxes = countFormulaBoxes(equation.hwpScript());
        while (remainingBoxes > 0 && !out.isEmpty()) {
            ASTInlineItem last = out.get(out.size() - 1);
            if (!(last instanceof ASTInlineObject)
                    || !isFormulaAnswerPlaceholder(ctx, (ASTInlineObject) last)) {
                return;
            }
            out.remove(out.size() - 1);
            remainingBoxes--;
        }
    }

    private static int countFormulaBoxes(String script) {
        if (script == null || script.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '\u25A1' || c == '\uFFFC') count++;
        }
        return count;
    }

    private static FormulaCluster collectMixedFormulaEquationCluster(
            ResolvedBuildContext ctx,
            List<ASTInlineItem> items,
            int start) {
        if (items == null || start < 0 || start >= items.size()) return null;

        StringBuilder script = new StringBuilder();
        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasOperator = false;
        boolean hasBox = false;
        boolean hasArrow = false;
        boolean hasEquation = false;
        boolean hasPositioned = false;
        boolean hasChemicalSymbol = false;
        boolean hasFormulaFontEvidence = false;
        String color = null;
        Integer preferredBaseUnit = null;
        String preferredFontFamily = null;
        char previousVisible = 0;
        int end = start;

        for (int i = start; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTTextRun) {
                ASTTextRun tr = (ASTTextRun) item;
                applyPositionFromCharacterStyle(tr);
                String text = tr.text();
                if (text == null || text.isEmpty() || !isFormulaClusterText(text)) break;
                if (isFormulaBoundaryText(text)) {
                    if (i == start) return null;
                    break;
                }
                if (color == null) color = tr.textColor();
                if (preferredBaseUnit == null && tr.fontSizeHwpunits() != null && tr.fontSizeHwpunits() > 0) {
                    preferredBaseUnit = tr.fontSizeHwpunits();
                }
                if (preferredFontFamily == null && tr.fontFamily() != null && !tr.fontFamily().isEmpty()) {
                    preferredFontFamily = tr.fontFamily();
                }
                ScriptAppendResult result = appendFormulaScript(script, text, tr, previousVisible);
                if (!result.accepted) break;
                previousVisible = result.previousVisible;
                hasLetter |= result.hasLetter;
                hasDigit |= result.hasDigit;
                hasOperator |= result.hasOperator;
                hasBox |= result.hasBox;
                hasArrow |= result.hasArrow;
                hasPositioned |= hasFormulaScriptPositionEvidence(tr);
                hasChemicalSymbol |= result.hasChemicalSymbol;
                hasFormulaFontEvidence |= isFormulaFontEvidence(tr);
                end = i + 1;
                continue;
            }

            if (item instanceof ASTEquation) {
                ASTEquation eq = (ASTEquation) item;
                if (isProtectedInlineFormulaEquation(eq)) break;
                String eqScript = normalizeExistingFormulaEquationScript(eq.hwpScript());
                if (eqScript.isEmpty() || !isFormulaEquationScript(eqScript)) break;
                ScriptAppendResult result =
                        appendExistingFormulaEquationScript(script, eqScript, previousVisible);
                if (!result.accepted) break;
                hasLetter |= result.hasLetter;
                hasDigit |= result.hasDigit;
                hasOperator |= result.hasOperator;
                hasBox |= result.hasBox;
                hasArrow |= result.hasArrow;
                hasPositioned |= result.hasImplicitSubscript || eqScript.indexOf('_') >= 0 || eqScript.indexOf('^') >= 0;
                hasChemicalSymbol |= result.hasChemicalSymbol;
                hasEquation = true;
                hasFormulaFontEvidence = true;
                previousVisible = result.previousVisible;
                if (color == null) color = eq.textColor();
                if (preferredBaseUnit == null && eq.preferredBaseUnit() != null && eq.preferredBaseUnit() > 0) {
                    preferredBaseUnit = eq.preferredBaseUnit();
                }
                if (preferredFontFamily == null && eq.preferredFontFamily() != null && !eq.preferredFontFamily().isEmpty()) {
                    preferredFontFamily = eq.preferredFontFamily();
                }
                end = i + 1;
                continue;
            }

            if (item instanceof ASTInlineObject && isFormulaAnswerPlaceholder(ctx, (ASTInlineObject) item)) {
                script.append('\u25A1');
                hasBox = true;
                previousVisible = '\u25A1';
                end = i + 1;
                continue;
            }

            break;
        }

        String hwpScript = normalizeFormulaScript(script.toString());
        if (hwpScript.isEmpty() || hwpScript.length() > 160) return null;
        boolean chemicalElementSequence = isChemicalFormulaElementSequence(hwpScript)
                && (hasFormulaFontEvidence || hasChemicalFormulaDelimiterContext(items, start, end));
        if (end - start < 2 && !chemicalElementSequence) return null;
        if (!hasLetter) return null;
        if (!hasChemicalSymbol && !hasBox && !hasArrow) return null;
        if (!hasEquation && !hasBox && !chemicalElementSequence) return null;
        if (hasBox
                && !hasEquation
                && !hasFormulaFontEvidence
                && !hasDigit
                && !hasOperator
                && !hasArrow
                && !hasPositioned) {
            return null;
        }
        if (!hasEquation
                && !hasBox
                && !hasOperator
                && !hasArrow
                && hasPositioned
                && chemicalElementSequence) {
            return null;
        }
        if (!chemicalElementSequence
                && !hasDigit
                && !hasOperator
                && !hasBox
                && !hasArrow
                && !hasPositioned) {
            return null;
        }
        if (!isFormulaEquationMaterializationCandidate(
                hwpScript,
                chemicalElementSequence,
                hasFormulaFontEvidence,
                hasEquation,
                hasOperator,
                hasBox,
                hasArrow,
                hasPositioned,
                hasChemicalSymbol)) {
            return null;
        }

        // 화학식(CHEM_FORMULA)은 toChemicalTextRuns 로 낱글자 텍스트화된다. 그런데
        // hwpScript 에 화학식과 무관한 다글자 HWP 수식 키워드(angle/DEG/TIMES/div/
        // sqrt/over…)가 들어 있으면 낱글자화 시 그 키워드가 대문자 원소기호로 오인돼
        // raw 로 노출된다(실측: u5 p166 ∠PAO=∠PBO=90° → EH상부자 ù→"DEG" 가 포함된
        // "PBO=90 DEG" 가 D·E·G 낱글자로 깨짐). 이런 조각은 진짜 화학식이 아니라 각
        // 기호/도(°)가 섞인 텍스트이므로 CHEM_FORMULA 로 방출하지 않고 null 을 반환해
        // 원본 텍스트 런을 보존한다(decodeStrayGlyphInBodyFont 가 ù→° 로 처리).
        if (containsNonChemicalFormulaKeyword(hwpScript)) {
            return null;
        }

        ASTEquation eq = new ASTEquation(hwpScript, "CHEM_FORMULA");
        color = resolvedFormulaTextColor(items, start, end, color);
        if (color != null) eq.textColor(color);
        applyBodyTextEquationHints(eq, preferredBaseUnit, preferredFontFamily);
        return new FormulaCluster(eq, end);
    }

    private static String resolvedFormulaTextColor(
            List<ASTInlineItem> items,
            int start,
            int endExclusive,
            String clusterColor) {
        if (clusterColor != null && !clusterColor.isEmpty() && !isDefaultBlack(clusterColor)) {
            return clusterColor;
        }
        String nearby = nearbyBodyTextColor(items, start, endExclusive);
        if (nearby != null && !nearby.isEmpty()) {
            return nearby;
        }
        return clusterColor;
    }

    private static void inheritFormulaBoundaryTextColor(List<ASTInlineItem> items, int index, ASTTextRun boundaryRun) {
        if (boundaryRun == null) return;
        String color = boundaryRun.textColor();
        if (color != null && !color.isEmpty() && !isDefaultBlack(color)) return;
        String inherited = nearbyBodyTextColor(items, index, index + 1);
        if (inherited != null && !inherited.isEmpty()) {
            boundaryRun.textColor(inherited);
        }
    }

    private static String nearbyBodyTextColor(List<ASTInlineItem> items, int start, int endExclusive) {
        String before = nearbyBodyTextColor(items, start - 1, -1, -1);
        if (before != null) return before;
        return nearbyBodyTextColor(items, endExclusive, items != null ? items.size() : 0, 1);
    }

    private static String nearbyBodyTextColor(List<ASTInlineItem> items, int index, int stopExclusive, int step) {
        if (items == null || step == 0) return null;
        int seen = 0;
        for (int i = index; i != stopExclusive && i >= 0 && i < items.size() && seen < 12; i += step, seen++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            if (text == null || text.trim().isEmpty()) continue;
            String color = run.textColor();
            if (color == null || color.isEmpty() || isDefaultBlack(color)) continue;
            if (isFormulaFontEvidence(run)) continue;
            return color;
        }
        return null;
    }

    private static boolean isDefaultBlack(String color) {
        if (color == null) return false;
        String normalized = color.trim();
        return "#000000".equalsIgnoreCase(normalized)
                || "000000".equalsIgnoreCase(normalized);
    }

    private static boolean isProtectedInlineFormulaEquation(ASTEquation eq) {
        if (eq == null) return false;
        String sourceType = eq.sourceType();
        if ("INLINE_FRACTION".equals(sourceType)) return true;

        String script = eq.hwpScript();
        if (script == null) return false;
        String normalized = script.toLowerCase(Locale.ROOT);
        return normalized.contains(" over ")
                || normalized.contains("sqrt")
                || normalized.contains("root");
    }

    private static String normalizeExistingFormulaEquationScript(String script) {
        if (script == null) return "";
        return script
                .replace('\uFFFC', '\u25A1')
                .replace("@C", " rarrow ")
                .replace("@c", " rarrow ")
                .replace("?C", " rarrow ")
                .replace("?c", " rarrow ")
                .replace("RIGHT", " rarrow ")
                .replace("RARROW", " rarrow ")
                .replace("->", " rarrow ")
                .trim();
    }

    private static boolean needsFormulaSpaceBefore(StringBuilder script, String next) {
        if (script == null || script.length() == 0 || next == null || next.isEmpty()) return false;
        char prev = script.charAt(script.length() - 1);
        char first = next.charAt(0);
        return Character.isLetterOrDigit(prev) && Character.isLetterOrDigit(first);
    }

    private static ScriptAppendResult appendExistingFormulaEquationScript(
            StringBuilder out,
            String script,
            char previousVisible) {
        if (script == null || script.isEmpty()) {
            ScriptAppendResult empty = new ScriptAppendResult();
            empty.previousVisible = previousVisible;
            return empty;
        }
        if (script.indexOf('_') < 0 && script.indexOf('^') < 0 && !containsFormulaArrow(script)) {
            if (isChemicalFormulaElementSequence(script)) {
                String implicitScript = withImplicitChemicalSubscripts(script);
                if (!implicitScript.equals(script)) {
                    if (out.length() > 0 && needsFormulaSpaceBefore(out, implicitScript)) out.append(' ');
                    out.append(implicitScript);
                    FormulaScriptStats stats = statsForFormulaScript(implicitScript);
                    ScriptAppendResult result = new ScriptAppendResult();
                    result.hasLetter = stats.hasLetter;
                    result.hasDigit = stats.hasDigit;
                    result.hasOperator = stats.hasOperator;
                    result.hasBox = stats.hasBox;
                    result.hasArrow = stats.hasArrow;
                    result.hasImplicitSubscript = stats.hasPositioned;
                    result.hasChemicalSymbol = stats.hasChemicalSymbol;
                    result.previousVisible = stats.previousVisible != 0 ? stats.previousVisible : previousVisible;
                    return result;
                }
            }
            ASTTextRun run = new ASTTextRun();
            run.text(script);
            return appendFormulaScript(out, script, run, previousVisible);
        }

        ScriptAppendResult result = new ScriptAppendResult();
        result.previousVisible = previousVisible;
        if (out.length() > 0 && needsFormulaSpaceBefore(out, script)) out.append(' ');
        out.append(script);
        FormulaScriptStats stats = statsForFormulaScript(script);
        result.hasLetter = stats.hasLetter;
        result.hasDigit = stats.hasDigit;
        result.hasOperator = stats.hasOperator;
        result.hasBox = stats.hasBox;
        result.hasArrow = stats.hasArrow;
        result.hasImplicitSubscript = stats.hasPositioned;
        result.hasChemicalSymbol = stats.hasChemicalSymbol;
        result.previousVisible = stats.previousVisible != 0 ? stats.previousVisible : previousVisible;
        return result;
    }

    private static boolean isFormulaEquationScript(String script) {
        if (script == null || script.isEmpty()) return false;
        boolean hasFormulaToken = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (isFormulaSpace(c) || c == '_' || c == '^' || c == '{' || c == '}') continue;
            if (isAsciiLetter(c) || Character.isDigit(c)) {
                hasFormulaToken = true;
                continue;
            }
            if (script.regionMatches(true, i, "rarrow", 0, "rarrow".length())) {
                hasFormulaToken = true;
                i += "rarrow".length() - 1;
                continue;
            }
            if (c == '\u25A1' || c == '+' || c == '-' || c == '=' || c == '(' || c == ')') {
                hasFormulaToken = true;
                continue;
            }
            return false;
        }
        return hasFormulaToken;
    }

    private static String withImplicitChemicalSubscripts(String script) {
        String token = chemicalFormulaToken(script);
        if (token.isEmpty()) return script;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < token.length() && Character.isDigit(token.charAt(i))) {
            out.append(token.charAt(i));
            i++;
        }
        while (i < token.length()) {
            char c = token.charAt(i);
            if (c < 'A' || c > 'Z') return script;
            String symbol = String.valueOf(c);
            int next = i + 1;
            if (next < token.length()) {
                char nc = token.charAt(next);
                if (nc >= 'a' && nc <= 'z') {
                    String two = "" + c + nc;
                    if (isChemicalElement(two)) {
                        symbol = two;
                        next++;
                    }
                }
            }
            if (!isChemicalElement(symbol)) return script;
            out.append(symbol);
            i = next;
            StringBuilder digits = new StringBuilder();
            while (i < token.length() && Character.isDigit(token.charAt(i))) {
                digits.append(token.charAt(i));
                i++;
            }
            if (digits.length() > 0) {
                out.append("_{").append(digits).append("}");
            }
        }
        return out.toString();
    }

    private static final class FormulaScriptStats {
        boolean hasLetter;
        boolean hasDigit;
        boolean hasOperator;
        boolean hasBox;
        boolean hasArrow;
        boolean hasPositioned;
        boolean hasChemicalSymbol;
        char previousVisible;
    }

    private static FormulaScriptStats statsForFormulaScript(String script) {
        FormulaScriptStats stats = new FormulaScriptStats();
        if (script == null) return stats;
        char previous = 0;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (isFormulaSpace(c) || c == '{' || c == '}') continue;
            if (c == '_' || c == '^') {
                stats.hasPositioned = true;
                continue;
            }
            if (script.regionMatches(true, i, "rarrow", 0, "rarrow".length())) {
                stats.hasArrow = true;
                stats.hasOperator = true;
                previous = '\u2192';
                i += "rarrow".length() - 1;
                continue;
            }
            if (c == '\u25A1') {
                stats.hasBox = true;
                previous = c;
                continue;
            }
            if (c == '+' || c == '-' || c == '=') {
                stats.hasOperator = true;
                previous = c;
                continue;
            }
            if (isAsciiLetter(c)) {
                stats.hasLetter = true;
                stats.hasChemicalSymbol |= isLikelyChemicalElementAt(script, i);
                previous = c;
                continue;
            }
            if (Character.isDigit(c)) {
                stats.hasDigit = true;
                previous = c;
            }
        }
        stats.previousVisible = previous;
        return stats;
    }

    private static boolean isFormulaAnswerPlaceholder(ResolvedBuildContext ctx, ASTInlineObject obj) {
        if (ctx == null || obj == null || obj.sourceId() == null) return false;
        Integer sourceId = sourceIdToDomId(obj.sourceId());
        if (sourceId == null) return false;
        return isFormulaAnswerPlaceholderSource(ctx, sourceId);
    }

    public static boolean isFormulaAnswerPlaceholderRun(ResolvedBuildContext ctx, IDMLCharacterRun run) {
        if (run == null) return false;
        boolean sawAnchor = false;
        if (run.inlineAnchors() != null) {
            for (IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                Integer id = inlineAnchorDomId(run, anchor);
                if (id == null) continue;
                sawAnchor = true;
                if (!isFormulaAnswerPlaceholderSource(ctx, id)) return false;
            }
        }
        if (sawAnchor) return true;
        String text = run.content();
        return text != null && !text.isEmpty() && text.replace("\uFFFC", "").isEmpty();
    }

    private static Integer inlineAnchorDomId(IDMLCharacterRun run, IDMLCharacterRun.InlineAnchor anchor) {
        if (run == null || anchor == null) return null;
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME
                && run.inlineFrames() != null
                && anchor.index() >= 0
                && anchor.index() < run.inlineFrames().size()) {
            IDMLTextFrame tf = run.inlineFrames().get(anchor.index());
            return tf != null ? sourceIdToDomId(tf.selfId()) : null;
        }
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC
                && run.inlineGraphics() != null
                && anchor.index() >= 0
                && anchor.index() < run.inlineGraphics().size()) {
            IDMLCharacterRun.InlineGraphic graphic = run.inlineGraphics().get(anchor.index());
            return graphic != null ? sourceIdToDomId(graphic.selfId()) : null;
        }
        return null;
    }

    static boolean isFormulaAnswerPlaceholderSource(ResolvedBuildContext ctx, int sourceId) {
        if (ctx == null) return false;
        ObjectPlan plan = findInlinePlaceholderPlan(ctx, sourceId);
        if (plan == null) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (plan.visualAction != VisualAction.PLACE_INLINE_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (isEmptyShellTextFrame(ctx, sourceId)) return true;
        if (plan.visualSourceObjectIds != null) {
            for (int id : plan.visualSourceObjectIds) {
                if (isEmptyShellTextFrame(ctx, id) || isInlineAnswerBoxShape(ctx, id)) return true;
            }
        }
        if (plan.sourceObjectIds != null) {
            for (int id : plan.sourceObjectIds) {
                if (isEmptyShellTextFrame(ctx, id) || isInlineAnswerBoxShape(ctx, id)) return true;
            }
        }
        if (isContentInlineVisualPlan(plan)) return false;
        return isInlineAnswerBoxShape(ctx, sourceId);
    }

    private static boolean isContentInlineVisualPlan(ObjectPlan plan) {
        if (plan == null) return false;
        String slotRole = plan.slotRole != null ? plan.slotRole : "";
        if ("CONTENT_VISUAL_SLOT".equals(slotRole)) return true;
        if ("COMPLETE_PNG_SLOT".equals(slotRole)) return true;
        return plan.visualLayer == VisualLayer.CONTENT_VISUAL
                && plan.visualAction == VisualAction.PLACE_INLINE_PNG;
    }

    private static ObjectPlan findInlinePlaceholderPlan(ResolvedBuildContext ctx, int sourceId) {
        if (ctx == null || ctx.ownershipPlans == null) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            if (planContainsSource(plan, sourceId)) {
                if (plan.placement == Placement.INLINE
                        && (plan.visualAction == VisualAction.PLACE_INLINE_PNG
                        || plan.visualAction == VisualAction.PLACE_TEXT_SHELL)) {
                    return plan;
                }
            }
        }
        return null;
    }

    private static boolean planContainsSource(ObjectPlan plan, int sourceId) {
        return plan.domId == sourceId
                || contains(plan.sourceObjectIds, sourceId)
                || contains(plan.visualSourceObjectIds, sourceId)
                || contains(plan.exportSourceObjectIds, sourceId)
                || contains(plan.ownedTextFrameIds, sourceId);
    }

    private static boolean isEmptyShellTextFrame(ResolvedBuildContext ctx, int sourceId) {
        if (ctx == null || ctx.resolvedData == null) return false;
        ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(String.valueOf(sourceId));
        if (tf == null) return false;
        String text = tf.frameVisibleText();
        boolean emptyText = text == null || text.replace("\uFFFC", "").trim().isEmpty();
        if (!emptyText) return false;
        return visibleStroke(tf.strokeColor(), tf.strokeWeight()) || visibleFill(tf.fillColor());
    }

    private static boolean isInlineAnswerBoxShape(ResolvedBuildContext ctx, int sourceId) {
        if (ctx == null || ctx.resolvedData == null) return false;
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
        if (item == null) return false;
        String type = item.type();
        if (!"Rectangle".equals(type) && !"Polygon".equals(type) && !"Oval".equals(type)) return false;
        double[] b = item.visibleBounds();
        if (b == null || b.length < 4) b = item.geometricBounds();
        if (b == null || b.length < 4) return false;
        double width = Math.abs(b[3] - b[1]);
        double height = Math.abs(b[2] - b[0]);
        if (width <= 0.0 || height <= 0.0) return false;
        double ratio = Math.max(width / height, height / width);
        if (ratio > 6.0) return false;
        String storyAnchorPlacement = upper(item.storyAnchorPlacement());
        String anchoredPosition = upper(item.anchoredPosition());
        boolean inline = "INLINE".equals(storyAnchorPlacement)
                || "INLINE_POSITION".equals(anchoredPosition)
                || "INLINEPOSITION".equals(anchoredPosition);
        if (!inline) return false;
        return visibleStroke(item.strokeColorName(), item.strokeWeight()) || visibleFill(item.fillColorName());
    }

    private static boolean visibleStroke(String color, double weight) {
        return color != null && !color.isEmpty() && !"None".equals(color) && weight > 0.0;
    }

    private static boolean visibleFill(String color) {
        return color != null && !color.isEmpty() && !"None".equals(color);
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static Integer sourceIdToDomId(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return null;
        String s = sourceId;
        if (s.startsWith("child_")) s = s.substring("child_".length());
        int suffix = s.indexOf('_');
        if (suffix > 0) s = s.substring(0, suffix);
        if (s.startsWith("u") || s.startsWith("U")) {
            try {
                return Integer.parseInt(s.substring(1), 16);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean contains(int[] ids, int value) {
        if (ids == null) return false;
        for (int id : ids) if (id == value) return true;
        return false;
    }

    private static class FormulaCluster {
        final ASTEquation equation;
        final int endExclusive;

        FormulaCluster(ASTEquation equation, int endExclusive) {
            this.equation = equation;
            this.endExclusive = endExclusive;
        }
    }

    /**
     * newItems 꼬리에서 좌변 후보(수식 폰트 또는 첨자 런, 클러스터 문법 텍스트)를
     * 역방향 수집한다. 반환값은 흡수 시 제거해야 할 꼬리 아이템 수, 수집 결과는
     * out 에 원문 순서로 담긴다. 좌변은 isSimplePositionedTextRun flush 등으로
     * 이미 방출돼 있을 수 있다 (실측: "CH₄" 가 [CH][₄] 로 선방출).
     */
    /**
     * SPEC-058: 수식 전용 폰트 증거로만 조판된 반응식 구간을 통짜 ASTEquation 으로 승격.
     *
     * <p>박스 반응식(예: p47 "2H₂+O₂ → 2H₂O")은 전 런이 수식 증거(BT/EH/NP 폰트 또는
     * 00_수식* 문자스타일)를 갖지만 mathTypeOf 가 BT/EH 로 교차 배열이라, 타입별 그룹핑이
     * 매 런에서 flush 되어 전부 1런 조각 → 증거 부족 → 텍스트 폴백으로 떨어진다.
     * 화살표조차 00_수식(화살표) 문자스타일로 mathType=EH 라 화살표 시작 클러스터
     * (SPEC-059 경로)에 진입하지 못한다. 판정은 내용 휴리스틱이 아니라 폰트/문자스타일
     * 증거 기반 (SPEC-058 방침).</p>
     *
     * <p>게이트: 구간이 화살표(→) 런을 포함하고 화살표 <b>양쪽</b>에 수식 폰트 런이
     * 있어야 한다. 우변이 본문 폰트인 문제 보기 반응식(①~⑤)은 게이트에 걸리지 않아
     * 기존 클러스터+좌변 흡수 경로(SPEC-059)가 그대로 처리한다. 화살표 없는 토큰별
     * TF(p16-17 "2H2"/"+O2" 개별 프레임)도 자연 배제된다.</p>
     */
    private static void promoteEquationFontReactionRanges(ResolvedBuildContext ctx, List<ASTInlineItem> items) {
        if (items == null || items.size() < 3) return;
        for (int start = 0; start < items.size(); start++) {
            if (!(items.get(start) instanceof ASTTextRun)) continue;

            int end = start;
            int eqFontBeforeArrow = 0;
            int eqFontAfterArrow = 0;
            boolean sawArrow = false;
            while (end < items.size()) {
                ASTInlineItem item = items.get(end);
                if (item instanceof ASTInlineObject
                        && isFormulaAnswerPlaceholder(ctx, (ASTInlineObject) item)) {
                    end++;
                    continue;
                }
                if (!(item instanceof ASTTextRun)) break;

                ASTTextRun tr = (ASTTextRun) item;
                applyPositionFromCharacterStyle(tr);
                String text = tr.text();
                if (text == null || text.isEmpty()) break;
                if (isWhitespaceOnlyRun(tr)) {
                    // 공백 런은 중립 — 구간을 끊지 않는다
                    end++;
                    continue;
                }
                if ("→".equals(text.trim())) {
                    // 화살표 단독 런: 정규화로 폰트가 벗겨질 수 있어 폰트 증거 요구 예외
                    sawArrow = true;
                    end++;
                    continue;
                }
                if (mathTypeOf(tr) != null
                        && isFormulaClusterText(text)
                        && !isFormulaBoundaryText(text)) {
                    if (sawArrow) eqFontAfterArrow++;
                    else eqFontBeforeArrow++;
                    end++;
                    continue;
                }
                break;
            }

            if (!sawArrow || eqFontBeforeArrow < 1 || eqFontAfterArrow < 1) {
                // 실패 구간의 부분집합은 게이트를 새로 만족할 수 없다 → 구간 끝으로 점프
                start = Math.max(start, end - 1);
                continue;
            }

            // 구간 경계가 수식 본문 중간이면 승격 금지 — 본문 폰트 LHS 의 첨자 숫자만
            // eqfont 로 잡혀 반쪽 수식("2 rarrow 2NH3")이 만들어지는 오발화 방지.
            // 직전/직후 텍스트 런이 공백/경계 없이 영숫자로 이어지면 같은 수식의 연속이다.
            if (formulaContinuesBefore(items, start) || formulaContinuesAfter(items, end)) {
                start = Math.max(start, end - 1);
                continue;
            }

            // 양끝 공백 런은 승격 범위에서 제외 (본문에 남긴다)
            int s = start;
            int e = end;
            while (s < e && isWhitespaceOnlyRun(items.get(s))) s++;
            while (e > s && isWhitespaceOnlyRun(items.get(e - 1))) e--;

            StringBuilder script = new StringBuilder();
            char previousVisible = 0;
            boolean hasLetter = false;
            boolean hasDigit = false;
            boolean hasOperator = false;
            boolean hasPositioned = false;
            boolean accepted = true;
            String color = null;
            Integer preferredBaseUnit = null;
            String preferredFontFamily = null;
            for (int k = s; k < e; k++) {
                ASTInlineItem item = items.get(k);
                if (item instanceof ASTInlineObject
                        && isFormulaAnswerPlaceholder(ctx, (ASTInlineObject) item)) {
                    script.append('\u25A1');
                    previousVisible = '\u25A1';
                    continue;
                }
                ASTTextRun tr = (ASTTextRun) item;
                String text = tr.text();
                if (text == null || isWhitespaceOnlyRun(tr)) continue;
                ScriptAppendResult result = appendFormulaScript(script, text, tr, previousVisible);
                if (!result.accepted) {
                    accepted = false;
                    break;
                }
                previousVisible = result.previousVisible;
                hasLetter |= result.hasLetter;
                hasDigit |= result.hasDigit;
                hasOperator |= result.hasOperator;
                hasPositioned |= tr.subscript() || tr.superscript();
                if (color == null) color = tr.textColor();
                if (preferredBaseUnit == null && tr.fontSizeHwpunits() != null && tr.fontSizeHwpunits() > 0) {
                    preferredBaseUnit = tr.fontSizeHwpunits();
                }
                if (preferredFontFamily == null && tr.fontFamily() != null && !tr.fontFamily().isEmpty()) {
                    preferredFontFamily = tr.fontFamily();
                }
            }
            String hwpScript = accepted ? normalizeFormulaScript(script.toString()) : "";
            if (hwpScript.isEmpty() || hwpScript.length() > 128
                    || !hasLetter || !(hasDigit || hasOperator || hasPositioned)) {
                start = Math.max(start, end - 1);
                continue;
            }

            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                    new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(hwpScript, "CHEM_FORMULA");
            color = resolvedFormulaTextColor(items, s, e, color);
            if (color != null) eq.textColor(color);
            applyBodyTextEquationHints(eq, preferredBaseUnit, preferredFontFamily);
            for (int k = e - 1; k >= s; k--) {
                items.remove(k);
            }
            items.add(s, eq);
            start = s;
        }
    }

    private static boolean isWhitespaceOnlyRun(ASTInlineItem item) {
        if (!(item instanceof ASTTextRun)) return false;
        return isWhitespaceOnlyRun((ASTTextRun) item);
    }

    /** 구간 시작 직전 텍스트 런이 공백/경계 없이 영숫자로 끝나는가 (수식 연속 신호). */
    private static boolean formulaContinuesBefore(List<ASTInlineItem> items, int start) {
        if (start <= 0) return false;
        ASTInlineItem prev = items.get(start - 1);
        if (!(prev instanceof ASTTextRun)) return false;
        String text = ((ASTTextRun) prev).text();
        if (text == null || text.isEmpty()) return false;
        char last = text.charAt(text.length() - 1);
        return Character.isLetterOrDigit(last) && last < 0x2100;
    }

    /** 구간 끝 직후 텍스트 런이 공백/경계 없이 영숫자로 시작하는가 (수식 연속 신호). */
    private static boolean formulaContinuesAfter(List<ASTInlineItem> items, int endExclusive) {
        if (endExclusive >= items.size()) return false;
        ASTInlineItem next = items.get(endExclusive);
        if (!(next instanceof ASTTextRun)) return false;
        String text = ((ASTTextRun) next).text();
        if (text == null || text.isEmpty()) return false;
        char first = text.charAt(0);
        return Character.isLetterOrDigit(first) && first < 0x2100;
    }

    private static int collectTrailingEquationFontRuns(
            List<ASTInlineItem> newItems,
            List<ASTTextRun> out) {
        if (newItems == null || out == null) return 0;
        int count = 0;
        for (int j = newItems.size() - 1; j >= 0 && count < 24; j--) {
            ASTInlineItem it = newItems.get(j);
            if (!(it instanceof ASTTextRun)) break;
            ASTTextRun tr = (ASTTextRun) it;
            String text = tr.text();
            if (text == null || text.isEmpty()) break;
            if (text.trim().isEmpty()) {
                // 공백 런: 좌변 진행 중일 때만 통과 (선두 공백은 아래에서 잘림)
                out.add(0, tr);
                count++;
                continue;
            }
            boolean equationFont = mathTypeOf(tr) != null;
            boolean positioned = tr.subscript() || tr.superscript();
            if (!equationFont && !positioned) break;
            if (!isFormulaClusterText(text) || isFormulaBoundaryText(text)) break;
            out.add(0, tr);
            count++;
        }
        // 선두 공백 런 제거 (본문과 좌변 사이 공백은 본문에 남긴다)
        while (!out.isEmpty()) {
            String t = out.get(0).text();
            if (t != null && t.trim().isEmpty()) {
                out.remove(0);
                count--;
            } else {
                break;
            }
        }
        return Math.max(0, count);
    }

    /** 클러스터 스크립트가 화살표로 시작하는가 (좌변 미포함 신호). */
    private static boolean clusterScriptStartsWithArrow(
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation equation) {
        if (equation == null || equation.hwpScript() == null) return false;
        String script = equation.hwpScript().trim();
        if (script.startsWith("~")) script = script.substring(1).trim();
        return script.startsWith("rarrow");
    }

    /**
     * 대기 중인 수식 폰트 그룹(원본 ASTTextRun)을 클러스터 흡수용 스크립트로 만든다.
     * 그룹 구성원은 mathTypeOf 통과(BT/EH/NP 폰트)로 이미 폰트 증거가 있다.
     * 텍스트가 클러스터 문법에 안 맞거나 경계 텍스트면 null (기존 flush 로 폴백).
     */
    private static String buildFormulaScriptFromEquationFontRuns(List<ASTTextRun> runs) {
        if (runs == null || runs.isEmpty()) return null;
        StringBuilder script = new StringBuilder();
        char previousVisible = 0;
        for (ASTTextRun tr : runs) {
            if (tr == null) return null;
            String text = tr.text();
            if (text == null || text.isEmpty()) continue;
            if (!isFormulaClusterText(text)) return null;
            if (isFormulaBoundaryText(text)) return null;
            ScriptAppendResult result = appendFormulaScript(script, text, tr, previousVisible);
            if (!result.accepted) return null;
            previousVisible = result.previousVisible;
        }
        String out = normalizeFormulaScript(script.toString());
        return out.isEmpty() ? null : out;
    }

    private static FormulaCluster collectFormulaEquationCluster(List<ASTInlineItem> items, int start) {
        if (items == null || start < 0 || start >= items.size()) return null;
        StringBuilder script = new StringBuilder();
        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasOperator = false;
        boolean hasBox = false;
        boolean hasArrow = false;
        boolean hasPositioned = false;
        boolean hasChemicalSymbol = false;
        boolean hasFormulaFontEvidence = false;
        String color = null;
        Integer preferredBaseUnit = null;
        String preferredFontFamily = null;
        char previousVisible = 0;
        int end = start;

        for (int i = start; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) break;
            ASTTextRun tr = (ASTTextRun) item;
            applyPositionFromCharacterStyle(tr);
            String text = tr.text();
            if (text == null || text.isEmpty()) break;
            if (!isFormulaClusterText(text)) break;
            if (isFormulaBoundaryText(text)) {
                if (i == start) return null;
                break;
            }

            if (color == null) color = tr.textColor();
            if (preferredBaseUnit == null && tr.fontSizeHwpunits() != null && tr.fontSizeHwpunits() > 0) {
                preferredBaseUnit = tr.fontSizeHwpunits();
            }
            if (preferredFontFamily == null && tr.fontFamily() != null && !tr.fontFamily().isEmpty()) {
                preferredFontFamily = tr.fontFamily();
            }
            ScriptAppendResult result = appendFormulaScript(script, text, tr, previousVisible);
            if (!result.accepted) break;
            previousVisible = result.previousVisible;
            hasLetter |= result.hasLetter;
            hasDigit |= result.hasDigit;
            hasOperator |= result.hasOperator;
            hasBox |= result.hasBox;
            hasArrow |= result.hasArrow;
            hasPositioned |= hasFormulaScriptPositionEvidence(tr);
            hasChemicalSymbol |= result.hasChemicalSymbol;
            hasFormulaFontEvidence |= isFormulaFontEvidence(tr);
            end = i + 1;
        }

        if (end == start) return null;
        String hwpScript = normalizeFormulaScript(script.toString());
        if (hwpScript.isEmpty() || hwpScript.length() > 128) return null;
        boolean chemicalElementSequence = isChemicalFormulaElementSequence(hwpScript)
                && (hasFormulaFontEvidence || hasChemicalFormulaDelimiterContext(items, start, end));
        if (!hasFormulaFontEvidence
                && !chemicalElementSequence
                && !hasDigit
                && !hasBox
                && !hasArrow
                && !hasPositioned
                && !looksLikeMathContent(hwpScript)) {
            return null;
        }
        if (!hasFormulaFontEvidence
                && !chemicalElementSequence
                && hasLongLatinWord(hwpScript, 3)
                && !hasDigit
                && !hasBox
                && !hasArrow
                && !hasPositioned
                && !looksLikeMathContent(hwpScript)) {
            return null;
        }
        if (!hasLetter) return null;
        if (!hasChemicalSymbol && !hasBox && !hasArrow) return null;
        if (hasBox
                && !hasFormulaFontEvidence
                && !hasDigit
                && !hasOperator
                && !hasArrow
                && !hasPositioned) {
            return null;
        }
        if (!chemicalElementSequence
                && !hasDigit
                && !hasOperator
                && !hasBox
                && !hasArrow
                && !hasPositioned) {
            return null;
        }
        if (!isFormulaEquationMaterializationCandidate(
                hwpScript,
                chemicalElementSequence,
                hasFormulaFontEvidence,
                false,
                hasOperator,
                hasBox,
                hasArrow,
                hasPositioned,
                hasChemicalSymbol)) {
            return null;
        }

        if (hasArrow && hasChemicalSymbol) {
            String finalized = finalizeChemicalScript(hwpScript);
            if (finalized != null && !finalized.isEmpty()) hwpScript = finalized;
        }

        ASTEquation eq = new ASTEquation(hwpScript, "CHEM_FORMULA");
        color = resolvedFormulaTextColor(items, start, end, color);
        if (color != null) eq.textColor(color);
        applyBodyTextEquationHints(eq, preferredBaseUnit, preferredFontFamily);
        return new FormulaCluster(eq, end);
    }

    private static boolean hasChemicalFormulaDelimiterContext(List<ASTInlineItem> items, int start, int endExclusive) {
        if (items == null || start < 0 || endExclusive <= start) return false;
        Character before = previousVisibleChar(items, start - 1);
        Character after = nextVisibleChar(items, endExclusive);
        return isOpeningFormulaDelimiter(before) || isClosingFormulaDelimiter(after);
    }

    private static boolean canStartBodyTextChemicalReactionCluster(
            List<ASTInlineItem> items,
            int start,
            ASTTextRun run) {
        if (items == null || run == null || start < 0 || start >= items.size()) return false;
        String font = run.fontFamily();
        if (!BTFontGlyphMap.isBTBodyTextFont(font)) return false;
        String text = run.text();
        if (text == null || text.isEmpty() || !isFormulaClusterText(text) || isFormulaBoundaryText(text)) {
            return false;
        }

        boolean hasBodyFormulaText = false;
        boolean hasArrow = false;
        boolean hasChargeStyle = false;
        boolean hasOperator = false;
        for (int i = start; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) break;
            ASTTextRun tr = (ASTTextRun) item;
            applyPositionFromCharacterStyle(tr);
            String t = tr.text();
            if (t == null || t.isEmpty()) break;
            if (!isFormulaClusterText(t)) break;
            if (isFormulaBoundaryText(t)) {
                if (i == start) return false;
                break;
            }
            if (BTFontGlyphMap.isBTBodyTextFont(tr.fontFamily()) && containsChemicalElementText(t)) {
                hasBodyFormulaText = true;
            }
            if (t.indexOf('\u2192') >= 0) hasArrow = true;
            if (containsFormulaOperatorChar(t)) hasOperator = true;
            if ((tr.superscript() || charStyleNameScript(tr, true)) && containsIonChargeText(t)) {
                hasChargeStyle = true;
            }
        }
        return hasBodyFormulaText && hasArrow && hasOperator && hasChargeStyle;
    }

    private static boolean canStartExplicitBTFormulaCluster(String currentType, ASTTextRun run) {
        if (!"BT".equals(currentType) || run == null) return false;
        String font = run.fontFamily();
        return font != null
                && BTFontGlyphMap.isBTFontFamily(font)
                && !BTFontGlyphMap.isBTBodyTextFont(font);
    }

    private static boolean containsChemicalElementText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 'A' || c > 'Z') continue;
            String one = String.valueOf(c);
            String two = one;
            if (i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next >= 'a' && next <= 'z') two = one + next;
            }
            if (isChemicalElement(one) || isChemicalElement(two)) return true;
        }
        return false;
    }

    private static boolean containsFormulaOperatorChar(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '+' || c == '-' || c == '=' || c == '\u2192') return true;
        }
        return false;
    }

    private static boolean containsIonChargeText(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasCharge = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c) || isFormulaSpace(c)) continue;
            if (c == '+' || c == '-') {
                hasCharge = true;
                continue;
            }
            return false;
        }
        return hasCharge;
    }

    private static boolean isFormulaEquationMaterializationCandidate(
            String hwpScript,
            boolean chemicalElementSequence,
            boolean hasFormulaFontEvidence,
            boolean hasEquation,
            boolean hasOperator,
            boolean hasBox,
            boolean hasArrow,
            boolean hasPositioned,
            boolean hasChemicalSymbol) {
        return FormulaClassifier.shouldMaterializeChemicalEquation(
                hwpScript,
                chemicalElementSequence,
                hasFormulaFontEvidence,
                hasEquation,
                hasOperator,
                hasBox,
                hasArrow,
                hasPositioned,
                hasChemicalSymbol);
    }

    private static Character previousVisibleChar(List<ASTInlineItem> items, int index) {
        for (int i = index; i >= 0; i--) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            String text = ((ASTTextRun) item).text();
            if (text == null) continue;
            for (int j = text.length() - 1; j >= 0; j--) {
                char c = text.charAt(j);
                if (!isFormulaSpace(c)) return c;
            }
        }
        return null;
    }

    private static Character nextVisibleChar(List<ASTInlineItem> items, int index) {
        for (int i = index; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            String text = ((ASTTextRun) item).text();
            if (text == null) continue;
            for (int j = 0; j < text.length(); j++) {
                char c = text.charAt(j);
                if (!isFormulaSpace(c)) return c;
            }
        }
        return null;
    }

    private static boolean isOpeningFormulaDelimiter(Character c) {
        return c != null && (c == '(' || c == '[' || c == '{');
    }

    private static boolean isClosingFormulaDelimiter(Character c) {
        return c != null && (c == ')' || c == ']' || c == '}');
    }

    private static boolean isFormulaFontEvidence(ASTTextRun tr) {
        if (tr == null) return false;
        String ff = tr.fontFamily();
        return tr.grepMathFont()
                || isEHTextRun(tr)
                || (ff != null && (EHFontGlyphMap.isEHFontFamily(ff)
                || BTFontGlyphMap.isBTFontFamily(ff)
                || NPFontGlyphMap.isNPFont(ff)));
    }

    private static boolean isFormulaBoundaryText(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasBoundary = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)
                    || c == '\u2005' || c == '\u2007'
                    || c == '\u2009' || c == '\u200A') {
                continue;
            }
            if (c == '(' || c == ')' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == ',' || c == '.') {
                hasBoundary = true;
                continue;
            }
            return false;
        }
        return hasBoundary;
    }

    static void applyBodyTextEquationHints(ASTEquation equation, Integer preferredBaseUnit, String preferredFontFamily) {
        if (equation == null) return;
        if (preferredBaseUnit != null && preferredBaseUnit > 0) {
            equation.preferredBaseUnit(preferredBaseUnit);
        }
        if (preferredFontFamily != null && !preferredFontFamily.isEmpty()) {
            equation.preferredFontFamily(preferredFontFamily);
        }
    }

    private static class ScriptAppendResult {
        boolean accepted = true;
        boolean hasLetter;
        boolean hasDigit;
        boolean hasOperator;
        boolean hasBox;
        boolean hasArrow;
        boolean hasImplicitSubscript;
        boolean hasChemicalSymbol;
        char previousVisible;
    }

    private static ScriptAppendResult appendFormulaScript(
            StringBuilder out,
            String text,
            ASTTextRun run,
            char previousVisible) {
        ScriptAppendResult result = new ScriptAppendResult();
        result.previousVisible = previousVisible;
        boolean runSubscript = run.subscript() || charStyleNameScript(run, false);
        boolean runSuperscript = run.superscript() || charStyleNameScript(run, true);
        if ((runSubscript || runSuperscript) && isWholeFormulaScriptRun(text)) {
            String token = compactFormulaScriptRunText(text);
            if (token.isEmpty()) return result;
            if (runSubscript) {
                out.append("_{").append(token).append("}");
            } else {
                out.append("^{").append(token).append("}");
            }
            collectFormulaTokenSignals(token, result);
            result.hasImplicitSubscript = runSubscript;
            result.previousVisible = lastVisibleFormulaChar(token, previousVisible);
            return result;
        }

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isFormulaSpace(c)) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                continue;
            }
            if (c == '\uFFFC' || c == '\u25A1') {
                out.append('\u25A1');
                result.hasBox = true;
                result.previousVisible = '\u25A1';
                continue;
            }
            if (c == '\u2192') {
                out.append(" rarrow ");
                result.hasArrow = true;
                result.hasOperator = true;
                result.previousVisible = c;
                continue;
            }
            if (c == '+' || c == '-' || c == '=') {
                out.append(c);
                result.hasOperator = true;
                result.previousVisible = c;
                continue;
            }
            if (isAsciiLetter(c)) {
                out.append(c);
                result.hasLetter = true;
                result.hasChemicalSymbol |= isLikelyChemicalElementAt(text, i);
                result.previousVisible = c;
                continue;
            }
            if (Character.isDigit(c)) {
                result.hasDigit = true;
                boolean subscript = runSubscript;
                boolean superscript = runSuperscript;
                if (subscript) {
                    out.append("_{").append(c).append("}");
                } else if (superscript) {
                    out.append("^{").append(c).append("}");
                } else {
                    out.append(c);
                }
                result.previousVisible = c;
                continue;
            }
            result.accepted = false;
            return result;
        }
        return result;
    }

    private static boolean isWholeFormulaScriptRun(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean hasVisible = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isFormulaSpace(c)) continue;
            hasVisible = true;
            if (isAsciiLetter(c) || Character.isDigit(c)) continue;
            if (c == '+' || c == '-') continue;
            return false;
        }
        return hasVisible;
    }

    private static String compactFormulaScriptRunText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!isFormulaSpace(c)) out.append(c);
        }
        return out.toString();
    }

    private static void collectFormulaTokenSignals(String token, ScriptAppendResult result) {
        if (token == null || result == null) return;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (isAsciiLetter(c)) {
                result.hasLetter = true;
                result.hasChemicalSymbol |= isLikelyChemicalElementAt(token, i);
            } else if (Character.isDigit(c)) {
                result.hasDigit = true;
            } else if (c == '+' || c == '-' || c == '=') {
                result.hasOperator = true;
            }
        }
    }

    private static char lastVisibleFormulaChar(String text, char defaultValue) {
        if (text == null) return defaultValue;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (!isFormulaSpace(c)) return c;
        }
        return defaultValue;
    }

    // toChemicalTextRuns 가 낱글자로 풀면 raw 노출되는 다글자 HWP 수식 키워드.
    // 이들이 스크립트에 있으면 화학식(원소기호+숫자)이 아니므로 CHEM_FORMULA
    // 낱글자 렌더 대상에서 제외한다. HWP 수식 예약어(EHFontEquationConverter
    // mapUnicodeToHwp 가 방출하는 것들)만 나열해 화학 원소기호(He/Na 등)와 혼동을
    // 피한다. 단어 경계로 검사(angle 이 triangle 등에 부분매칭되지 않게).
    private static final String[] NON_CHEMICAL_FORMULA_KEYWORDS = {
            "angle", "DEG", "TIMES", "div", "sqrt", "over",
            "LEQ", "GEQ", "INF", "rarrow", "equiv", "notin", "SUBSET"
    };

    /**
     * SPEC-055 Phase B: 일반 본문 폰트 + 첨자 문자속성으로 조판된 화학식 세그먼트를
     * ASTEquation("CHEM_FORMULA")으로 변환한다.
     *
     * <p>수식 폰트(BT/EH/NP) 증거가 없는 본문 속 H₂O·CO₂ 는 수식 그룹핑에 진입하지
     * 못하고 첨자 텍스트런으로 남는다. 여기서는 <b>아래첨자 숫자 런</b>을 앵커로,
     * 직전 런 꼬리의 원소기호와 후속 조각을 묶어 수식화한다. 보수 가드:
     * <ul>
     *   <li>앵커는 1~2자리 숫자 아래첨자 + 직전 문자가 원소기호일 때만</li>
     *   <li>세그먼트의 모든 라틴 문자열이 원소기호 화이트리스트에 있어야 함</li>
     *   <li>원소 직전 문자가 라틴 소문자면 제외 (pH2 같은 영문 꼬리 보호)</li>
     *   <li>수식 폰트(BT/EH/NP) 런은 기존 수식 파이프라인 소관 — 건드리지 않음</li>
     * </ul>
     */
    static void convertSubscriptChemicalSegments(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.size() < 2) return;
        for (int i = 1; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun sub = (ASTTextRun) item;
            if (!isSubscriptEvidence(sub)) continue;
            String subText = sub.text();
            if (subText == null) continue;
            // 첨자 런 꼬리의 공백(어절 간 hairspace 등)은 수식 뒤로 보존한다.
            String subTrailing = "";
            java.util.regex.Matcher wsm = TRAILING_WHITESPACE.matcher(subText);
            if (wsm.find()) {
                subTrailing = wsm.group();
                subText = subText.substring(0, subText.length() - subTrailing.length());
            }
            if (!subText.matches("[0-9]{1,2}")) continue;
            // 직전의 빈 텍스트런(첨자 잔여물)은 건너뛰고 실제 base 를 찾는다.
            int baseIdx = i - 1;
            while (baseIdx >= 0 && items.get(baseIdx) instanceof ASTTextRun) {
                String bt = ((ASTTextRun) items.get(baseIdx)).text();
                if (bt != null && !bt.isEmpty()) break;
                baseIdx--;
            }
            if (baseIdx < 0 || !(items.get(baseIdx) instanceof ASTTextRun)) continue;
            ASTTextRun base = (ASTTextRun) items.get(baseIdx);
            if (base.subscript() || base.superscript()) continue;
            // 이탤릭 라틴은 수학 변수/기하 라벨(B_1 등) — 화학식으로 보지 않는다.
            if (base.fontStyle() != null && base.fontStyle().toLowerCase(java.util.Locale.ROOT).contains("italic")) continue;
            String baseText = base.text();
            if (baseText == null || baseText.isEmpty()) continue;

            // 직전 런 꼬리에서 원소 머리를 뗀다 ("물(H" → "H", "2H" → "2H").
            java.util.regex.Matcher m = TRAILING_CHEM_FRAGMENT.matcher(baseText);
            if (!m.find() || m.group().isEmpty()) continue;
            String head = m.group();
            if (head.endsWith("(")) head = head.substring(0, head.length() - 1);
            if (head.isEmpty() || !ChemicalFormulaPolicy.lettersAreKnownElements(head)) continue;
            String rest = baseText.substring(0, baseText.length() - m.group().length()
                    + (m.group().endsWith("(") ? 1 : 0));
            // 영문 단어 꼬리 보호: 원소 머리 직전이 라틴 문자면 화학식이 아니다.
            if (!rest.isEmpty()) {
                char before = rest.charAt(rest.length() - 1);
                if ((before >= 'a' && before <= 'z') || (before >= 'A' && before <= 'Z')) continue;
            }

            // 후속 조각 확장: 첨자 숫자 / 이온 전하 위첨자 / 원소·숫자 접두 일반 런.
            StringBuilder script = new StringBuilder(head);
            script.append("_{").append(subText).append('}');
            int end = i; // 마지막으로 흡수한 items 인덱스
            ASTTextRun tailPartialRun = null;
            String tailPartialRest = null;
            for (int j = i + 1; j < items.size(); j++) {
                if (!(items.get(j) instanceof ASTTextRun)) break;
                ASTTextRun r = (ASTTextRun) items.get(j);
                String t = r.text();
                if (t == null || t.isEmpty()) {
                    end = j;
                    continue;
                }
                if (isSubscriptEvidence(r)) {
                    java.util.regex.Matcher tw = TRAILING_WHITESPACE.matcher(t);
                    String tTrail = tw.find() ? tw.group() : "";
                    String tCore = t.substring(0, t.length() - tTrail.length());
                    if (tCore.matches("[0-9]{1,2}")) {
                        script.append("_{").append(tCore).append('}');
                        end = j;
                        if (!tTrail.isEmpty()) {
                            subTrailing = tTrail;
                            break;
                        }
                        continue;
                    }
                    break;
                }
                if (isSuperscriptEvidence(r) && t.matches("[0-9]{0,2}[+−-]")) {
                    script.append("^{").append(t.replace('−', '-')).append('}');
                    end = j;
                    continue;
                }
                if (r.subscript() || r.superscript()) break;
                // 일반 런: 원소/숫자 접두만 흡수 (괄호·화살표·+ 는 Phase B 범위 밖)
                java.util.regex.Matcher p = LEADING_CHEM_CONTINUATION.matcher(t);
                if (!p.find() || p.group().isEmpty()) break;
                String prefix = p.group();
                if (!ChemicalFormulaPolicy.lettersAreKnownElements(prefix)
                        && !prefix.matches("[0-9]{1,2}")) {
                    break;
                }
                if (prefix.length() == t.length()) {
                    script.append(prefix);
                    end = j;
                    continue;
                }
                script.append(prefix);
                tailPartialRun = r;
                tailPartialRest = t.substring(prefix.length());
                end = j;
                break;
            }

            String rebuilt = kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaClassifier
                    .inferChemicalSubscriptScript(script.toString());
            if (rebuilt == null || rebuilt.isEmpty()) continue;

            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                    new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(rebuilt, "CHEM_FORMULA");
            if (base.fontSizeHwpunits() != null && base.fontSizeHwpunits() > 0) {
                eq.preferredBaseUnit(base.fontSizeHwpunits());
            }
            if (base.textColor() != null && !base.textColor().isEmpty()) {
                eq.textColor(base.textColor());
            }

            // items 반영: [base(rest)] [EQ] [tailPartial(rest)] — 흡수분 제거.
            if (tailPartialRun != null) tailPartialRun.text(tailPartialRest);
            int removeTo = (tailPartialRun != null) ? end - 1 : end;
            for (int k = removeTo; k >= baseIdx + 1; k--) items.remove(k);
            int eqIdx;
            if (rest.isEmpty()) {
                items.set(baseIdx, eq);
                eqIdx = baseIdx;
            } else {
                base.text(rest);
                items.add(baseIdx + 1, eq);
                eqIdx = baseIdx + 1;
            }
            if (!subTrailing.isEmpty()) {
                ASTTextRun ws = base.copyWithText(subTrailing);
                ws.subscript(false);
                ws.superscript(false);
                ws.droppedResolvedScriptPosition(null);
                items.add(eqIdx + 1, ws);
            }
            i = eqIdx;
        }
    }

    /**
     * 아래첨자 증거: 문자속성 첨자, SPEC-045 가드로 폐기된 resolved 첨자 힌트,
     * 또는 첨자 charStyle 이름(하부자). 이름 증거는 SPEC-053 가드(도형 라벨)에
     * 막힌 진짜 화학식 첨자를 살린다 — 앵커의 "1~2자리 숫자 + 직전 원소기호"
     * 조건이 도형 라벨·계수 케이스를 차단하므로 여기선 이름만 본다.
     */
    private static boolean isSubscriptEvidence(ASTTextRun r) {
        if (r == null || r.superscript()) return false;
        return r.subscript()
                || "subscript".equals(r.droppedResolvedScriptPosition())
                || charStyleNameScript(r, false);
    }

    private static boolean isSuperscriptEvidence(ASTTextRun r) {
        if (r == null || r.subscript()) return false;
        return r.superscript()
                || "superscript".equals(r.droppedResolvedScriptPosition())
                || charStyleNameScript(r, true);
    }

    private static boolean hasFormulaScriptPositionEvidence(ASTTextRun r) {
        if (r == null) return false;
        return r.subscript()
                || r.superscript()
                || "subscript".equals(r.droppedResolvedScriptPosition())
                || "superscript".equals(r.droppedResolvedScriptPosition())
                || charStyleNameScript(r, false)
                || charStyleNameScript(r, true);
    }

    private static boolean charStyleNameScript(ASTTextRun r, boolean superscript) {
        String s = r.characterStyleRef();
        if (s == null) return false;
        s = s.toLowerCase(Locale.ROOT).replace("%3a", ":").replace("%25", "%");
        if (s.contains("정체") || s.contains("정자")) return false;
        if (superscript) {
            return s.contains("superscript") || s.contains("상부자") || s.contains("위첨자");
        }
        return s.contains("subscript") || s.contains("하부자") || s.contains("아래첨자");
    }

    private static final java.util.regex.Pattern TRAILING_WHITESPACE =
            java.util.regex.Pattern.compile("[\\s\u200A\u2028\u00A0]+$");

    private static final java.util.regex.Pattern LEADING_CHEM_CONTINUATION =
            java.util.regex.Pattern.compile("^(?:[0-9]{0,2}(?:[A-Z][a-z]?)+|[0-9]{1,2})");

    /** 스크립트가 화학식 연속부(첨자·계수·괄호)로 시작하는가 — 선행 원소가 잘려나간 표지. */
    private static boolean startsAsChemicalContinuation(String script) {
        if (script == null || script.isEmpty()) return false;
        char c = script.charAt(0);
        return Character.isDigit(c) || c == '_' || c == '(';
    }

    private static final java.util.regex.Pattern TRAILING_CHEM_FRAGMENT =
            java.util.regex.Pattern.compile(
                    "[0-9]{0,2}(?:[A-Z][a-z]?)+\\(?(?:\\+[0-9]{0,2}(?:[A-Z][a-z]?)+\\(?)*$");

    /** 직전 텍스트런 꼬리에서 화학식 선두 조각("H", "CH", "2Ca(")을 뗀다. 없으면 null. */
    private static String trailingChemicalFragment(String text) {
        if (text == null || text.isEmpty()) return null;
        java.util.regex.Matcher m = TRAILING_CHEM_FRAGMENT.matcher(text);
        if (!m.find()) return null;
        String frag = m.group();
        if (frag.isEmpty()) return null;
        if (!ChemicalFormulaPolicy.lettersAreKnownElements(frag)) return null;
        return frag;
    }

    /** 후행 런이 화학식 조각 문자만으로 이루어졌는가 (공백/숫자/연산/괄호/원소/화살표). */
    private static boolean isChemicalFragmentText(String text) {
        if (text == null) return false;
        // 빈/공백 런은 조각 사이 커넥터로 통과시킨다 (탭 정렬 반응식의 잔여 런).
        if (text.trim().isEmpty()) return true;
        boolean hasLetter = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isDigit(c)
                    || c == '+' || c == '(' || c == ')' || c == '·' || c == '→') {
                continue;
            }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                hasLetter = true;
                continue;
            }
            return false;
        }
        if (hasLetter && !ChemicalFormulaPolicy.lettersAreKnownElements(text)) return false;
        return true;
    }

    /** 이 조각이 흡수를 확정(commit)할 근거(원소기호 또는 화살표)를 갖는가. */
    private static boolean chemicalFragmentCommits(String text) {
        if (text == null) return false;
        return text.indexOf('→') >= 0 || ChemicalFormulaPolicy.containsElementText(text);
    }

    /** 화살표 치환 + 공백 정리("rarrow" 양쪽만 공백 유지) + 첨자 재추론. */
    private static String finalizeChemicalScript(String script) {
        if (script == null) return null;
        String s = script.replace("→", " rarrow ").replaceAll("\\s+", " ").trim();
        String[] parts = s.split("\\s*(?:~\\s*)?rarrow(?:\\s*~)?\\s*", -1);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            // HWP 수식 문법에서 일반 공백은 토큰 구분자일 뿐 렌더되지 않는다.
            // 화살표 양쪽의 가시 공백은 '~'(스페이스 토큰)로 표기해야 한다 (p20 실측).
            if (i > 0) joined.append(" ~ rarrow ~ ");
            joined.append(stripAllFormulaSpaces(parts[i]));
        }
        return kr.dogfoot.hwpxlib.tool.idmlconverter.formula.FormulaClassifier
                .inferChemicalSubscriptScript(joined.toString());
    }

    /**
     * SPEC-055: 화학식 수식 조각 봉합 + 스크립트 정규화.
     *
     * <p>수식 그룹핑은 수식 폰트/첨자 증거가 있는 런만 모으므로, 본문 폰트로 조판된
     * 화학식 선두 원소("CH₄"의 CH)나 화살표 뒤 조각이 텍스트런으로 남아 수식이
     * 쪼개진다("CH" + EQ["4+2O2 rarrow CO2+2H2O"]). 텍스트런 강등 시절에는 출력이
     * 이어 붙어 가려졌지만 hp:equation 방출(SPEC-055)에서는 조각이 그대로 보인다.
     * 인접 조각을 수식 스크립트로 흡수하고, 평문화된 첨자("N2")를 명시 토큰으로
     * 재추론한다. 흡수는 원소기호/화살표 근거가 있는 지점까지만 커밋한다
     * (화학식 뒤 무관한 숫자·괄호를 삼키지 않도록).
     */
    static void stitchChemicalFormulaFragments(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation)) continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                    (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) item;
            if (!"CHEM_FORMULA".equals(eq.sourceType())) continue;
            String script = eq.hwpScript() == null ? "" : eq.hwpScript();

            // (b) 후행 조각 — 원소/화살표 근거가 나오는 마지막 지점까지 수집
            int commitEnd = -1;
            java.util.List<String> tailTexts = new java.util.ArrayList<>();
            for (int j = i + 1; j < items.size(); j++) {
                ASTInlineItem cand = items.get(j);
                String candText;
                boolean commits;
                if (cand instanceof ASTTextRun) {
                    candText = ((ASTTextRun) cand).text();
                    if (!isChemicalFragmentText(candText)) break;
                    commits = chemicalFragmentCommits(candText);
                } else if (cand instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation
                        && "CHEM_FORMULA".equals(
                        ((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) cand).sourceType())) {
                    candText = ((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) cand).hwpScript();
                    commits = true;
                } else if (cand instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation
                        && isBareChemicalElementScript(
                        ((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) cand).hwpScript())) {
                    // 아래첨자와 다음 원소 사이의 hair space(U+200A)나 폰트크기 경계 때문에
                    // "H₂O" 의 "O" 가 별도 수식으로 떨어져 나오는 경우가 있다(rm H_{2} + O →
                    // 두 수식이 간격 두고 렌더). 단독 원소 수식이면 선행 화학식에 흡수한다.
                    candText = ((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) cand).hwpScript();
                    commits = true;
                } else {
                    break;
                }
                tailTexts.add(candText == null ? "" : candText);
                if (commits) {
                    commitEnd = j;
                } else if (commitEnd >= 0 && cand instanceof ASTTextRun
                        && candText != null && candText.trim().matches("[0-9]+")) {
                    // 앞서 원소가 확정된 뒤의 단독 숫자 = 마지막 원소의 아래첨자.
                    // (예 "rm N_{2}" + 수식 "H" + 텍스트 "4" = N₂H₄ 인데, 계수가 아닌
                    //  꼬리 첨자라 chemicalFragmentCommits 가 false 라 빠지던 것을 흡수한다.)
                    commitEnd = j;
                }
            }

            // (a) 선행 조각 체인 — 빈/공백 런은 건너뛰고 [원소 조각] → [계수] 순으로 흡수.
            //     "2" | "Cu+O" | EQ["2 rarrow 2CuO"] → "2Cu+O" 까지 복원한다.
            boolean arrowEvidence =
                    (script + " " + String.join(" ", tailTexts)).contains("rarrow")
                    || (script + String.join("", tailTexts)).indexOf('→') >= 0;
            String prevFragment = null;
            ASTTextRun prevPartialRun = null;
            String prevPartialRest = null;
            java.util.List<Integer> prevRemoveIdx = new java.util.ArrayList<>();
            int p = i - 1;
            while (p >= 0 && items.get(p) instanceof ASTTextRun) {
                String t = ((ASTTextRun) items.get(p)).text();
                if (t != null && !t.trim().isEmpty()) break;
                p--;
            }
            if (p >= 0 && items.get(p) instanceof ASTTextRun) {
                ASTTextRun r1 = (ASTTextRun) items.get(p);
                String t1 = r1.text();
                String head = script;
                int coefficientProbe = -1;
                if (startsAsChemicalContinuation(script)) {
                    // 스크립트가 숫자/첨자/괄호로 시작 = 선행 원소가 잘렸다 ("CH"+"4+…").
                    String frag = trailingChemicalFragment(t1);
                    if (frag != null) {
                        prevFragment = frag;
                        head = frag;
                        String rest = t1.substring(0, t1.length() - frag.length());
                        if (rest.trim().isEmpty()) {
                            prevRemoveIdx.add(p);
                            coefficientProbe = p - 1;
                        } else {
                            prevPartialRun = r1;
                            prevPartialRest = rest;
                        }
                    }
                } else {
                    coefficientProbe = p;
                }
                // 계수 흡수: 머리가 원소로 시작하는 반응식 앞의 단독 숫자 런.
                // 런 전체가 숫자일 때만 — "실험 2" 같은 서술 꼬리는 흡수하지 않는다.
                if (arrowEvidence && coefficientProbe >= 0
                        && !head.isEmpty() && head.charAt(0) >= 'A' && head.charAt(0) <= 'Z') {
                    int p2 = coefficientProbe;
                    while (p2 >= 0 && items.get(p2) instanceof ASTTextRun) {
                        String t2 = ((ASTTextRun) items.get(p2)).text();
                        if (t2 != null && !t2.trim().isEmpty()) break;
                        p2--;
                    }
                    if (p2 >= 0 && items.get(p2) instanceof ASTTextRun) {
                        String t2 = ((ASTTextRun) items.get(p2)).text();
                        if (t2 != null && t2.matches("[0-9]{1,2}")) {
                            prevFragment = t2 + (prevFragment == null ? "" : prevFragment);
                            prevRemoveIdx.add(p2);
                        }
                    }
                }
            }

            // 최종 스크립트를 먼저 만들고 검증 통과 시에만 items 를 변경한다.
            StringBuilder merged = new StringBuilder();
            if (prevFragment != null) merged.append(prevFragment);
            merged.append(script);
            int tailCount = commitEnd - i;
            for (int t = 0; t < tailCount; t++) merged.append(' ').append(tailTexts.get(t));
            String rebuilt = finalizeChemicalScript(merged.toString());
            if (rebuilt == null || rebuilt.isEmpty()
                    || containsNonChemicalFormulaKeyword(stripRarrowKeyword(rebuilt))) {
                continue;
            }

            // 후행 제거를 먼저 (i 이후 인덱스는 선행 제거의 영향을 받지 않도록)
            for (int t = 0; t < tailCount; t++) {
                items.remove(i + 1);
            }
            if (prevPartialRun != null && prevPartialRest != null) {
                prevPartialRun.text(prevPartialRest);
            }
            java.util.Collections.sort(prevRemoveIdx, java.util.Collections.reverseOrder());
            for (int idx : prevRemoveIdx) {
                items.remove(idx);
                i--;
            }
            eq.hwpScript(rebuilt);
        }
    }

    /**
     * 스크립트가 rm/첨자/연산자 없이 원소기호 1~3자만으로 된 "단독 원소 수식"인가.
     * (예 "O","H","Cl"). hair space/폰트크기 경계로 화학식에서 떨어져 나온 조각을
     * 선행 화학식에 흡수할지 판정하는 데 쓴다.
     */
    private static boolean isBareChemicalElementScript(String script) {
        if (script == null) return false;
        String s = script.trim();
        if (s.isEmpty() || s.length() > 3) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) return false;
        }
        return ChemicalFormulaPolicy.lettersAreKnownElements(s);
    }

    /**
     * SPEC-078: 파편화된 화학식 재조립.
     *
     * <p>hair space(U+200A)·폰트크기 경계·첨자 강등 때문에 "H₂O" 라벨이
     * {@code [수식 "H"][텍스트 "2"][수식 "O"]} 처럼 단독원소 수식과 숫자 텍스트로
     * 갈라지는 경우가 있다(한글에서 "H 2 O" 로 벌어져 보임). 단독원소 수식으로 시작하는
     * 연속 조각(단독원소 수식 + 숫자/원소 텍스트)을 모아 하나의 {@code rm H_{2}O} 화학식
     * 수식으로 재조립한다. 첨자는 {@code finalizeChemicalScript} 가 계수/첨자 위치로 추론한다.
     *
     * <p>과병합 방지: (1) 시퀀스는 단독원소 수식으로 시작, (2) 라틴 문자는 전부 원소기호,
     * (3) 알려진 원소 1개 이상, (4) 한글/비화학 텍스트를 만나면 즉시 종료.
     */
    static void reassembleFragmentedChemicalFormula(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.size() < 2) return;
        for (int i = 0; i < items.size(); i++) {
            if (!isBareChemicalElementEquation(items.get(i))) continue;
            int end = i;
            int bareEqCount = 0;
            String color = null;
            Integer baseUnit = null;
            String font = null;
            StringBuilder raw = new StringBuilder();
            for (int j = i; j < items.size(); j++) {
                ASTInlineItem it = items.get(j);
                String frag = chemicalFragmentRawText(it);
                if (frag == null) break;
                if (isBareChemicalElementEquation(it)) {
                    bareEqCount++;
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation e =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) it;
                    if (color == null) color = e.textColor();
                    if (font == null) font = e.preferredFontFamily();
                    if (e.preferredBaseUnit() != null
                            && (baseUnit == null || e.preferredBaseUnit() > baseUnit)) {
                        baseUnit = e.preferredBaseUnit();
                    }
                }
                raw.append(frag);
                end = j;
            }
            if (end <= i || bareEqCount < 1) continue;
            String rawStr = raw.toString().replaceAll("\\s+", "");
            if (rawStr.length() < 2) continue;
            if (!ChemicalFormulaPolicy.containsElementText(rawStr)
                    || !ChemicalFormulaPolicy.lettersAreKnownElements(rawStr)) continue;
            String script = finalizeChemicalScript(rawStr);
            if (script == null || script.isEmpty()) continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation merged =
                    new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(script, "CHEM_FORMULA");
            if (color != null) merged.textColor(color);
            if (baseUnit != null) merged.preferredBaseUnit(baseUnit);
            if (font != null) merged.preferredFontFamily(font);
            for (int k = end; k >= i; k--) items.remove(k);
            items.add(i, merged);
            // i 는 방금 삽입한 merged — 다음 반복에서 i+1 로 진행
        }
    }

    private static boolean isBareChemicalElementEquation(ASTInlineItem it) {
        return it instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation
                && isBareChemicalElementScript(
                ((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) it).hwpScript());
    }

    /**
     * 재조립 시퀀스에 포함될 조각의 raw 텍스트. 조각이 아니면 null, 공백 조각은 "".
     * 단독원소 수식(script)·숫자/원소/'+' 로만 된 짧은 텍스트런만 조각으로 인정한다.
     */
    private static String chemicalFragmentRawText(ASTInlineItem it) {
        if (it instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation e =
                    (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) it;
            if (isBareChemicalElementScript(e.hwpScript())) return e.hwpScript().trim();
            return null; // rm/첨자 포함 수식은 시퀀스 종료 (stitchChemicalFormulaFragments 가 처리)
        }
        if (it instanceof ASTTextRun) {
            String t = ((ASTTextRun) it).text();
            if (t == null) return "";
            String tr = t.trim();
            if (tr.isEmpty()) return "";
            boolean hasLatin = false;
            for (int i = 0; i < tr.length(); i++) {
                char c = tr.charAt(i);
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) hasLatin = true;
                else if (!((c >= '0' && c <= '9') || c == '+' || c == ' ')) return null;
            }
            if (hasLatin && !ChemicalFormulaPolicy.lettersAreKnownElements(tr)) return null;
            return tr;
        }
        return null;
    }

    /**
     * 화학식 스크립트에서 모든 공백류(일반 공백·hair space U+200A·NBSP 등 유니코드 공백)와
     * '~' 토큰을 제거한다. 화학식은 공백이 없어야 하며, Java {@code \\s} 는 U+200A 를 잡지
     * 못해 "H_{2} O" 처럼 hair space 가 새어들어 "H₂ O" 로 벌어지는 것을 막는다.
     */
    private static String stripAllFormulaSpaces(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '~' || Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            b.append(c);
        }
        return b.toString();
    }

    /**
     * SPEC-078(audit-A): 고립된 단일 라틴 문자 수식을 이탤릭 텍스트로 강등한다.
     *
     * <p>GREP 규칙(단일 라틴 문자 매칭)이 문단의 모든 단일 라틴 문자에 수식(BT수식) charStyle 을
     * 입히면(예 수식서체관련-태광 = BT수식H-편한글씨), 화학자 연표의 이름 이니셜
     * (A 아보가드로·J 돌턴·L 게이뤼삭 등)까지 단일문자 수식이 된다. 주변에 수식 근거(인접
     * 수식)가 없는 단독 단일 문자는 수식이 아니라 이탤릭 텍스트로 되돌린다.
     */
    static void demoteIsolatedSingleLetterMathEquation(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.isEmpty()) return;
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation)) continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                    (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) items.get(i);
            String letter = singleLatinLetterOfScript(eq.hwpScript());
            if (letter == null) continue;
            if (adjacentItemIsEquation(items, i, -1) || adjacentItemIsEquation(items, i, 1)) continue;
            ASTTextRun tr = new ASTTextRun();
            tr.text(letter);
            tr.fontStyle("Italic");
            if (eq.textColor() != null) tr.textColor(eq.textColor());
            if (eq.preferredBaseUnit() != null) tr.fontSizeHwpunits(eq.preferredBaseUnit());
            items.set(i, tr);
        }
    }

    /** 스크립트가 (rm 접두사 제외) 단일 라틴 문자면 그 문자를, 아니면 null. */
    private static String singleLatinLetterOfScript(String script) {
        if (script == null) return null;
        String s = script.trim();
        if (s.startsWith("rm ")) s = s.substring(3).trim();
        if (s.length() != 1) return null;
        char c = s.charAt(0);
        return ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) ? String.valueOf(c) : null;
    }

    /** idx 에서 dir(+1/-1) 방향으로 공백 텍스트런을 건너뛴 첫 비공백 항목이 수식인가. */
    private static boolean adjacentItemIsEquation(List<ASTInlineItem> items, int idx, int dir) {
        for (int j = idx + dir; j >= 0 && j < items.size(); j += dir) {
            ASTInlineItem it = items.get(j);
            if (it instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) return true;
            if (it instanceof ASTTextRun) {
                String t = ((ASTTextRun) it).text();
                if (t != null && !t.trim().isEmpty()) return false; // 비공백 텍스트 → 수식 근거 아님
                // 공백 런은 건너뜀
            } else {
                return false;
            }
        }
        return false;
    }

    /**
     * SPEC-078(B): 화살표 근거가 있는 반응식이 인접 조각(텍스트 좌변·별도 수식·orphan 첨자)으로
     * 갈라진 것을 하나의 반응식 수식으로 재조립한다.
     *
     * <p>예: {@code [T "2Mg"][T "+"][EQ "O_{2} rarrow 2MgO"]} → {@code rm 2Mg+O_{2} rarrow 2MgO},
     * {@code [EQ "rm H_{2}O_{2}"][EQ "rarrow 2H_{2}O+O_{2}"]} → 하나로,
     * {@code [EQ "H"][EQ "rm _{2}+O_{2} rarrow H_{2}O"]} → {@code rm H_{2}+O_{2} rarrow H_{2}O}.
     *
     * <p><b>over-merge 방지</b>: (1) 연속 조각(화학조각 텍스트 or 화학 수식)만, (2) 한글/비화학은
     * 즉시 종료, (3) <b>화살표(rarrow/→) 근거가 있어야만</b> 병합(완성된 단일 반응식은 조각 1개라
     * 미대상), (4) 병합 결과가 화학식으로 유효해야 함.
     */
    static void reassembleFragmentedChemicalReaction(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.size() < 2) return;
        for (int i = 0; i < items.size(); i++) {
            if (reactionFragmentRaw(items.get(i)) == null) continue;
            int end = i;
            boolean hasArrow = false;
            String color = null;
            Integer size = null;
            String font = null;
            StringBuilder raw = new StringBuilder();
            for (int j = i; j < items.size(); j++) {
                ASTInlineItem it = items.get(j);
                String frag = reactionFragmentRaw(it);
                if (frag == null) break;
                raw.append(frag);
                if (frag.indexOf('→') >= 0 || frag.contains("rarrow")) hasArrow = true;
                if (it instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation e =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) it;
                    if (color == null) color = e.textColor();
                    if (font == null) font = e.preferredFontFamily();
                    if (size == null || (e.preferredBaseUnit() != null && e.preferredBaseUnit() > size)) {
                        if (e.preferredBaseUnit() != null) size = e.preferredBaseUnit();
                    }
                }
                end = j;
            }
            if (end <= i || !hasArrow) continue; // 단일 항목이거나 화살표 없음 → 미병합
            String rawStr = raw.toString();
            if (!ChemicalFormulaPolicy.containsElementText(rawStr)) continue;
            String script = finalizeChemicalScript(rawStr);
            if (script == null || script.isEmpty()) continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation merged =
                    new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation(script, "CHEM_FORMULA");
            if (color != null) merged.textColor(color);
            if (size != null) merged.preferredBaseUnit(size);
            if (font != null) merged.preferredFontFamily(font);
            for (int k = end; k >= i; k--) items.remove(k);
            items.add(i, merged);
        }
    }

    /** 반응식 조각의 raw 문자열. 화학조각 텍스트 or 화학 수식이면 raw, 아니면 null(=시퀀스 종료). */
    private static String reactionFragmentRaw(ASTInlineItem it) {
        if (it instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) {
            String rawEq = equationToRaw(((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) it).hwpScript());
            if (rawEq.indexOf('→') >= 0 || ChemicalFormulaPolicy.containsElementText(rawEq)) return rawEq;
            return null; // 비화학 수식(이름 등) → 종료
        }
        if (it instanceof ASTTextRun) {
            String t = ((ASTTextRun) it).text();
            if (t == null || t.trim().isEmpty()) return "";
            if (!isChemicalFragmentText(t)) return null;
            return t;
        }
        return null;
    }

    /** HWP 수식 스크립트를 raw 화학식 문자열로: rm 제거, _{n}→n, rarrow→→, ~→공백. */
    private static String equationToRaw(String script) {
        if (script == null) return "";
        String s = script.trim();
        if (s.startsWith("rm ")) s = s.substring(3);
        else if (s.equals("rm")) return "";
        s = s.replace("rarrow", "→").replace("~", " ");
        s = s.replaceAll("_\\{([^}]*)\\}", "$1").replaceAll("\\^\\{([^}]*)\\}", "$1");
        s = s.replace("{", "").replace("}", "").replace("_", "").replace("^", "");
        return s;
    }

    private static String stripRarrowKeyword(String script) {
        return script == null ? null : script.replace("rarrow", " ");
    }

    private static boolean containsNonChemicalFormulaKeyword(String script) {
        if (script == null || script.isEmpty()) return false;
        for (String kw : NON_CHEMICAL_FORMULA_KEYWORDS) {
            int from = 0;
            while (true) {
                int idx = script.indexOf(kw, from);
                if (idx < 0) break;
                char before = idx > 0 ? script.charAt(idx - 1) : ' ';
                int after = idx + kw.length();
                char next = after < script.length() ? script.charAt(after) : ' ';
                // 앞뒤가 라틴 문자가 아니면(공백/연산자/숫자/경계) 독립 키워드로 인정
                if (!isAsciiLetter(before) && !isAsciiLetter(next)) {
                    return true;
                }
                from = idx + 1;
            }
        }
        return false;
    }

    private static boolean isFormulaClusterText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isFormulaSpace(c)) continue;
            if (isAsciiLetter(c) || Character.isDigit(c)) continue;
            if (c == '\uFFFC' || c == '\u25A1' || c == '\u2192') continue;
            if (c == '+' || c == '-' || c == '=') continue;
            return false;
        }
        return true;
    }

    private static boolean hasLongLatinWord(String text, int minLen) {
        if (text == null) return false;
        int streak = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isAsciiLetter(c)) {
                streak++;
                if (streak >= minLen) return true;
            } else {
                streak = 0;
            }
        }
        return false;
    }

    private static String normalizeFormulaScript(String script) {
        if (script == null) return "";
        String normalized = script.replaceAll("\\s+", " ").trim();
        normalized = normalized.replaceAll("\\s*\\+\\s*", "+");
        normalized = normalized.replaceAll("\\s*=\\s*", "=");
        normalized = normalized.replaceAll("(?i)\\s*RIGHT\\s*", " rarrow ");
        normalized = normalized.replaceAll("(?i)\\s*RARROW\\s*", " rarrow ");
        normalized = normalized.replaceAll("\\s*->\\s*", " rarrow ");
        if (isChemicalFormulaElementSequence(normalized)) {
            normalized = normalized.replace(" ", "");
            if (normalized.indexOf('_') < 0 && normalized.indexOf('^') < 0) {
                normalized = withImplicitChemicalSubscripts(normalized);
            }
        }
        return normalized.trim();
    }

    private static boolean isChemicalFormulaElementSequence(String script) {
        String token = chemicalFormulaToken(script);
        if (token.isEmpty()) return false;
        int i = 0;
        int elements = 0;
        boolean hasDigit = false;
        boolean hasLeadingCoefficient = false;
        while (i < token.length() && Character.isDigit(token.charAt(i))) {
            hasLeadingCoefficient = true;
            hasDigit = true;
            i++;
        }
        while (i < token.length()) {
            char c = token.charAt(i);
            if (c < 'A' || c > 'Z') return false;
            String symbol = String.valueOf(c);
            if (i + 1 < token.length()) {
                char next = token.charAt(i + 1);
                if (next >= 'a' && next <= 'z') {
                    String two = "" + c + next;
                    if (isChemicalElement(two)) {
                        symbol = two;
                        i += 2;
                    } else if (isChemicalElement(symbol)) {
                        i++;
                    } else {
                        return false;
                    }
                } else {
                    if (!isChemicalElement(symbol)) return false;
                    i++;
                }
            } else {
                if (!isChemicalElement(symbol)) return false;
                i++;
            }
            elements++;
            while (i < token.length() && Character.isDigit(token.charAt(i))) {
                hasDigit = true;
                i++;
            }
        }
        if (elements == 0) return false;
        return hasDigit || hasLeadingCoefficient || elements >= 2;
    }

    private static String chemicalFormulaToken(String script) {
        if (script == null || script.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (isFormulaSpace(c) || c == '_' || c == '^' || c == '{' || c == '}') continue;
            if (Character.isDigit(c) || isAsciiLetter(c)) {
                out.append(c);
                continue;
            }
            return "";
        }
        return out.toString();
    }

    private static boolean containsFormulaArrow(String script) {
        return script != null
                && (script.contains("->")
                || script.contains("RIGHT")
                || script.contains("RARROW")
                || script.contains("rarrow"));
    }

    private static boolean isFormulaSpace(char c) {
        return Character.isWhitespace(c)
                || c == '\u2005' || c == '\u2007' || c == '\u2009' || c == '\u200A';
    }

    private static boolean isLikelyChemicalElementAt(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) return false;
        char first = text.charAt(index);
        if (first < 'A' || first > 'Z') return false;
        String one = String.valueOf(first);
        String two = one;
        if (index + 1 < text.length()) {
            char second = text.charAt(index + 1);
            if (second >= 'a' && second <= 'z') {
                two = "" + first + second;
            }
        }
        return isChemicalElement(one) || isChemicalElement(two);
    }

    private static boolean isChemicalElement(String symbol) {
        if (symbol == null) return false;
        switch (symbol) {
            case "H": case "He": case "Li": case "Be": case "B": case "C":
            case "N": case "O": case "F": case "Ne": case "Na": case "Mg":
            case "Al": case "Si": case "P": case "S": case "Cl": case "Ar":
            case "K": case "Ca": case "Fe": case "Cu": case "Zn": case "Ag":
            case "I": case "Ba": case "Pt": case "Au": case "Hg": case "Pb":
                return true;
            default:
                return false;
        }
    }

    /**
     * 이 텍스트가 수식처럼 보이는가? (수식 구조 문자를 담고 있는가)
     *
     * <p>"BT수식H" 계열 폰트 런이 수식 그룹에 들어갈지 판단하는 데 쓴다. 그 폰트는
     * 본문 영문/숫자 조판용이라, 내용을 봐야 수식인지 알 수 있다:
     * <pre>
     *   "H", "O", "1.", "2", ":"   → 평범한 텍스트 (수식 아님)
     *   "x+1", "a^2", "n_1"        → 수식
     * </pre>
     */
    private static boolean looksLikeMathContent(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '+' || c == '=' || c == '<' || c == '>' || c == '^' || c == '_'
                    || c == '√'   // √
                    || c == '∑'   // ∑
                    || c == '∫'   // ∫
                    || c == 'π'   // π
                    || c == '∞'   // ∞
                    || c == '≤' || c == '≥'   // ≤ ≥
                    || c == '±'                     // ±
                    || c == '×' || c == '÷'   // × ÷
                    || c == '²' || c == '³') { // ² ³
                return true;
            }
        }
        return BTFontEquationConverter.containsGreekKeyword(text);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static IDMLCharacterRun mathRunFromTextRun(ASTTextRun tr, String fontFamily) {
        IDMLCharacterRun cr = new IDMLCharacterRun();
        cr.content(tr.text());
        cr.fontFamily(fontFamily);
        cr.appliedCharacterStyle(tr.characterStyleRef());
        cr.fontStyle(tr.fontStyle());
        if (tr.fontSizeHwpunits() != null) {
            cr.fontSize(tr.fontSizeHwpunits() / 100.0);
        }
        cr.fillColor(tr.textColor());
        if (tr.subscript()) {
            cr.position("Subscript");
        } else if (tr.superscript()) {
            cr.position("Superscript");
        }
        return cr;
    }

    private static String mathTypeOf(ASTTextRun tr) {
        if (tr == null) return null;
        String ff = tr.fontFamily();
        if (isEHTextRun(tr)) return "EH";
        if (ff != null && BTFontGlyphMap.isBTFontFamily(ff)) return "BT";
        if (ff != null && NPFontGlyphMap.isNPFont(ff)) return "NP";
        return null;
    }

    private static boolean isEHTextRun(ASTTextRun tr) {
        if (tr == null) return false;
        String ff = tr.fontFamily();
        if (ff != null && EHFontGlyphMap.isEHFontFamily(ff)) return true;
        return EHFontGlyphMap.isEHFontStyle(tr.characterStyleRef());
    }

    private static void applyPositionFromCharacterStyle(ASTTextRun tr) {
        if (tr == null || tr.characterStyleRef() == null) return;
        String style = tr.characterStyleRef().toLowerCase(Locale.ROOT)
                .replace("%3a", ":")
                .replace("%25", "%");
        // "정체"(正體=똑바로 선 글자, 정상 위치)는 EH상부자/하부자 폰트를 쓰되 첨자
        // 위치가 아니라는 조판 표기다. 스타일 이름의 "상부자/하부자"만 보고 첨자화하면
        // 본문 라틴 주석이 첨자로 깨진다(실측: 1단원 "상부자(정체)" 스타일의
        // Pythagoras·B.C.569?~475? 가 위첨자화). "정체"면 첨자로 만들지 않는다.
        if (style.contains("정체") || style.contains("정자")) return;
        boolean sup = style.contains("superscript") || style.contains("상부자") || style.contains("위첨자");
        boolean sub = style.contains("subscript") || style.contains("하부자") || style.contains("아래첨자");
        if (!sup && !sub) return;
        // SPEC-053: IDML/resolved 가 position 을 명시적으로 NORMAL(첨자 아님)로 보고했으면
        // charStyle 폰트명이 "상부자"여도 첨자화하지 않는다. 상부자 폰트로 조판한 도형
        // 라벨(원 O, □ABCD)이 위첨자로 깨지던 문제(실측: u5 p174).
        if (tr.explicitNormalPosition()) return;
        // SPEC-053: charStyle 이름의 "상부자/하부자"만으론 첨자화하지 않는다. 이 이름은
        // "EH상부자 폰트로 조판"을 뜻할 뿐 반드시 위/아래첨자 위치는 아니다. 도형 라벨
        // (원 O, □ABCD)이나 한글 캡션(확산하기)까지 폰트명만 보고 위첨자화하면 깨진다
        // (실측: u5 p174 원 O·□ABCD, "확산하기"·"사고"). 진짜 첨자는 화학식/지수처럼
        // 숫자·1~2 라틴 글자의 짧은 토큰이다. 그 외(한글·3+ 라틴·기호)는 첨자로 보지
        // 않는다. 실제 SUPERSCRIPT/SUBSCRIPT position 은 상류 applyPositionStyle 이 이미
        // 처리했으므로 이 이름-폴백 축소가 진짜 첨자를 놓치지 않는다.
        if (!isPlausibleScriptToken(tr.text())) return;
        if (sup) {
            tr.superscript(true);
            tr.subscript(false);
        } else {
            tr.subscript(true);
            tr.superscript(false);
        }
    }

    /** 첨자에 타당한 짧은 토큰(숫자·1~2 라틴 글자, 지수/화학식 계수)인가. */
    private static boolean isPlausibleScriptToken(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 2) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '+' || c == '-' || c == '(' || c == ')';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isSimplePositionedTextRun(ASTTextRun tr) {
        if (tr == null || (!tr.subscript() && !tr.superscript())) return false;
        String text = tr.text();
        if (text == null) return false;
        String cleaned = text.trim();
        if (cleaned.isEmpty() || cleaned.length() > 4) return false;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '+' || c == '-') continue;
            return false;
        }
        return true;
    }

    /**
     * SPEC-042: flush 후, 어댑터 변환으로 손실된 원본 런 스타일(크기·굵기·첨자)을
     * 결과 텍스트런에 백필한다. 어댑터(IDMLCharacterRun)에 직접 실으면 그룹핑
     * 하류 경로가 바뀌어 첨자 회귀가 나므로(SPEC-042 실패 기록), flush 산출물에만
     * 비어 있는 속성을 채운다. 매칭은 순서 보존 텍스트 일치(1:1)로, 분절·병합된
     * 산출물(수식 스크립트 등)은 건드리지 않는다.
     */
    private static void flushResolvedMathGroupWithBackfill(ResolvedBuildContext ctx, List<IDMLCharacterRun> group,
                                         List<ASTTextRun> sources, String type,
                                         List<ASTInlineItem> out, ASTParagraph ignoredPara) {
        if (group == null || group.isEmpty()) return;
        ASTParagraph tempPara = new ASTParagraph();
        if ("EH".equals(type)) {
            ASTMathGrouper.flushEHMathGroup(group, tempPara);
        } else if ("BT".equals(type)) {
            ASTMathGrouper.flushMathGroup(group, tempPara);
        } else if ("NP".equals(type)) {
            ASTMathGrouper.flushNPMathGroup(group, tempPara);
        }
        // SPEC-042: 폴백이 소스와 1:1 동일한 평문 런만 냈다면 손실 복제본 대신
        // 원본 런(크기·굵기·색·첨자 보유)을 재사용한다. 텍스트 정리·디코딩이
        // 일어난 경우(텍스트 불일치)나 수식·분절 산출이 있으면 폴백 산출을 쓴다.
        if (reuseOriginalRunsForPlainFallback(tempPara.items(), sources, out)) {
            return;
        }
        backfillFlushedTextRunStyles(tempPara.items(), sources);
        backfillChemicalEquationStyleHints(tempPara.items(), sources);
        splitBoundaryWrappedFormulaEquations(tempPara.items());
        out.addAll(tempPara.items());
    }

    private static boolean reuseOriginalRunsForPlainFallback(
            List<ASTInlineItem> emitted, List<ASTTextRun> sources, List<ASTInlineItem> out) {
        if (emitted == null || sources == null || emitted.isEmpty()) return false;
        if (emitted.size() != sources.size()) return false;
        for (int i = 0; i < emitted.size(); i++) {
            ASTInlineItem item = emitted.get(i);
            ASTTextRun src = sources.get(i);
            if (!(item instanceof ASTTextRun) || src == null) return false;
            ASTTextRun run = (ASTTextRun) item;
            if (!flushedTextMatches(src, run)) return false;
            String ff = run.fontFamily();
            if (ff != null && src.fontFamily() != null && !ff.equals(src.fontFamily())) return false;
        }
        out.addAll(sources);
        return true;
    }

    /**
     * SPEC-042: CHEM_FORMULA 수식(스크립트 재해석으로 텍스트런이 되는 화학식)의
     * 스타일 힌트(크기·색)를 원본 런에서 채운다. FormulaStyleResolver 가
     * preferredBaseUnit/textColor 를 읽어 FormulaRenderer 텍스트런에 적용한다.
     * 어댑터(IDMLCharacterRun)는 건드리지 않으므로 그룹핑 분기는 불변.
     */
    private static void backfillChemicalEquationStyleHints(
            List<ASTInlineItem> emitted, List<ASTTextRun> sources) {
        if (emitted == null || sources == null || sources.isEmpty()) return;
        Integer size = null;
        String color = null;
        for (ASTTextRun src : sources) {
            if (src == null) continue;
            if (size == null && src.fontSizeHwpunits() != null && src.fontSizeHwpunits() > 0) {
                size = src.fontSizeHwpunits();
            }
            if (color == null && src.textColor() != null && !src.textColor().isEmpty()) {
                color = src.textColor();
            }
            if (size != null && color != null) break;
        }
        if (size == null && color == null) return;
        for (ASTInlineItem item : emitted) {
            if (!(item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation)) continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                    (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) item;
            if (!"CHEM_FORMULA".equals(eq.sourceType())) continue;
            if (size != null && eq.preferredBaseUnit() == null) eq.preferredBaseUnit(size);
            if (color != null && (eq.textColor() == null || eq.textColor().isEmpty())) {
                eq.textColor(color);
            }
        }
    }

    private static void backfillFlushedTextRunStyles(List<ASTInlineItem> emitted, List<ASTTextRun> sources) {
        if (emitted == null || sources == null || sources.isEmpty()) return;
        int s = 0;
        for (ASTInlineItem item : emitted) {
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun outRun = (ASTTextRun) item;
            int k = s;
            while (k < sources.size() && !flushedTextMatches(sources.get(k), outRun)) k++;
            if (k >= sources.size()) continue;
            ASTTextRun src = sources.get(k);
            s = k + 1;
            // 짧은 숫자 런(첨자 후보 — H₂의 2)은 백필하지 않는다. 첨자 판정이
            // 위치 기반이라 숫자 런에 속성을 선주입하면 재그룹핑·재해석 시 첨자가
            // 유실된다 (SPEC-042 실패 기록 3·4·5 — 계수만 백필해도 같은 문단의
            // 첨자가 깨지는 양방향 상호작용 실측). 숫자 런은 전부 제외한다.
            String outText = outRun.text() == null ? "" : outRun.text().trim();
            if (outText.matches("\\d{1,2}")) {
                // SPEC-042: 첨자 속성 백필은 재그룹핑 회귀를 낳아 금지. 대신
                // SPEC-055 화학식 세그먼트 앵커용 힌트만 남긴다 (스타일·그룹핑 불변).
                if (src.subscript()) outRun.droppedResolvedScriptPosition("subscript");
                else if (src.superscript()) outRun.droppedResolvedScriptPosition("superscript");
                continue;
            }
            if (outRun.fontSizeHwpunits() == null && src.fontSizeHwpunits() != null) {
                outRun.fontSizeHwpunits(src.fontSizeHwpunits());
            }
            if (outRun.fontStyle() == null && src.fontStyle() != null) {
                outRun.fontStyle(src.fontStyle());
            }
            if (!outRun.subscript() && !outRun.superscript()) {
                if (src.subscript()) outRun.subscript(true);
                else if (src.superscript()) outRun.superscript(true);
            }
        }
    }

    private static boolean flushedTextMatches(ASTTextRun src, ASTTextRun out) {
        String a = src != null ? src.text() : null;
        String b = out != null ? out.text() : null;
        if (a == null || b == null) return false;
        String at = a.trim();
        return !at.isEmpty() && at.equals(b.trim());
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
        // SPEC-042: IDML FillColor 참조를 hex 로 푸는 리졸버 — 폴백 텍스트런 색 보존
        java.util.function.Function<String, String> colorToHex = ctx == null
                ? null
                : (color -> RunBuilder.resolveColorToHex(ctx, color));
        if (btGroup != null && !btGroup.isEmpty()) {
            String groupColor = firstGroupFillColorHex(ctx, btGroup);
            int before = para.items().size();
            ASTMathGrouper.flushMathGroup(btGroup, para, colorToHex);
            applyGroupColorHintToNewChemEquations(para, before, groupColor);
            btGroup.clear();
        }
        if (npGroup != null && !npGroup.isEmpty()) {
            ASTMathGrouper.flushNPMathGroup(npGroup, para);
            npGroup.clear();
        }
        if (ehGroup != null && !ehGroup.isEmpty()) {
            String groupColor = firstGroupFillColorHex(ctx, ehGroup);
            int before = para.items().size();
            ASTMathGrouper.flushEHMathGroup(ehGroup, para, colorToHex);
            applyGroupColorHintToNewChemEquations(para, before, groupColor);
            ehGroup.clear();
        }
    }

    /** SPEC-042: 그룹 첫 IDML FillColor 를 hex 로 (화학식 강조색 힌트용). */
    private static String firstGroupFillColorHex(ResolvedBuildContext ctx, List<IDMLCharacterRun> group) {
        if (ctx == null || group == null) return null;
        for (IDMLCharacterRun run : group) {
            if (run == null || run.fillColor() == null) continue;
            String hex = RunBuilder.resolveColorToHex(ctx, run.fillColor());
            if (hex != null && !hex.isEmpty()) return hex;
        }
        return null;
    }

    /**
     * SPEC-042: flush 가 새로 만든 CHEM_FORMULA 수식에 그룹 색 힌트를 주입.
     * 다이얼로그처럼 화살표 증거로 수식으로 직행하는 경우 폴백 텍스트런 색 주입이
     * 닿지 않으므로, FormulaRenderer 가 소비할 textColor 힌트를 여기서 채운다.
     */
    private static void applyGroupColorHintToNewChemEquations(ASTParagraph para, int fromIndex, String colorHex) {
        if (para == null || para.items() == null || colorHex == null || colorHex.isEmpty()) return;
        for (int i = Math.max(0, fromIndex); i < para.items().size(); i++) {
            ASTInlineItem item = para.items().get(i);
            if (!(item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation)) continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation eq =
                    (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) item;
            if (!"CHEM_FORMULA".equals(eq.sourceType())) continue;
            if (eq.textColor() == null || eq.textColor().isEmpty()) {
                eq.textColor(colorHex);
            }
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
                tr.shadeColor(templateRun.shadeColor());
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
