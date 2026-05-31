package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;

import java.util.List;
import java.util.Set;

/**
 * 문자런 변환 + FFFC 기반 인라인 항목 인터리빙.
 * ASTStoryConverter에서 분리됨.
 */
public class ASTRunConverter {

    private static final java.util.regex.Pattern R_N_PAR = java.util.regex.Pattern.compile("r(\\d+)par");

    /**
     * 원문자(r1par → ①) 변환.
     * 수식 그룹화 전에 호출해야 한다 (원문자는 수식이 아닌 일반 텍스트로 처리).
     */
    public static void convertCircledNumberRuns(List<IDMLCharacterRun> runs) {
        for (IDMLCharacterRun run : runs) {
            String text = run.content();
            if (text == null || !text.contains("par")) continue;
            if (!run.isBTFont() && !run.grepMathFont()) continue;

            java.util.regex.Matcher m = R_N_PAR.matcher(text);
            if (!m.find()) continue;

            StringBuffer sb = new StringBuffer();
            m.reset();
            while (m.find()) {
                int num = Integer.parseInt(m.group(1));
                String repl = (num >= 1 && num <= 20)
                        ? String.valueOf((char) (0x2460 + num - 1))
                        : "(" + num + ")";
                m.appendReplacement(sb, repl);
            }
            m.appendTail(sb);
            run.content(sb.toString());

            // 원문자만 남았으면 수식폰트 해제 (라틴 문자/숫자가 없으면)
            String newText = sb.toString();
            boolean hasLatinOrDigit = false;
            for (int i = 0; i < newText.length(); i++) {
                char c = newText.charAt(i);
                if (Character.isLetterOrDigit(c)) { hasLatinOrDigit = true; break; }
            }
            if (!hasLatinOrDigit) {
                run.grepMathFont(false);
            }
        }
    }

    /**
     * IDMLCharacterRun → ASTTextRun + ASTInlineObject + ASTBreak 변환.
     * 인라인 앵커(InlineAnchor)를 사용하여 TextFrame과 InlineGraphic을
     * 문서 순서(FFFC 위치)에 맞게 인터리빙한다.
     */
    static void convertCharacterRun(IDMLCharacterRun run, IDMLParagraph parentPara,
                                     ASTParagraph para,
                                     FlattenedObjectPool pool,
                                     IDMLDocument idmlDoc,
                                     ColorResolver colorResolver,
                                     ASTImageLoader imageLoader,
                                     ResolvedData resolvedData) {
        String text = run.content();
        List<IDMLCharacterRun.InlineAnchor> anchors = run.inlineAnchors();
        boolean useAnchors = !anchors.isEmpty();

        List<IDMLTextFrame> frames = run.inlineFrames();
        int frameIdx = 0;   // legacy mode: frame-only FFFC 인덱스
        int anchorIdx = 0;  // anchor mode: 통합 FFFC 인덱스
        Set<Integer> processedGraphicIndices = useAnchors ? new java.util.HashSet<Integer>() : null;

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
                // FFFC 위치에서 텍스트를 분할하여 인라인 항목과 인터리빙
                int start = 0;
                for (int j = 0; j <= seg.length(); j++) {
                    if (j == seg.length() || seg.charAt(j) == '\uFFFC') {
                        // FFFC 이전 (또는 세그먼트 끝) 텍스트 추가
                        if (j > start) {
                            String part = seg.substring(start, j);
                            ASTTextRun textRun = createTextRun(run, part, parentPara, idmlDoc, colorResolver);
                            // 장식 선(GraphicLine)의 stroke 색상 → underline으로 전파
                            if (para.pendingUnderlineColor() != null && !textRun.underline()) {
                                textRun.underline(true);
                                textRun.underlineColor(para.pendingUnderlineColor());
                            }
                            para.addItem(textRun);
                        }
                        // FFFC 위치에 인라인 항목 삽입 (앵커 모드 또는 레거시 모드)
                        if (j < seg.length()) {
                            if (useAnchors && anchorIdx < anchors.size()) {
                                IDMLCharacterRun.InlineAnchor anchor = anchors.get(anchorIdx++);
                                insertAnchoredItem(anchor, run, para, idmlDoc, colorResolver,
                                        imageLoader, processedGraphicIndices, resolvedData);
                            } else if (!useAnchors && frameIdx < frames.size()) {
                                IDMLTextFrame inlineTf = frames.get(frameIdx++);
                                if (!ASTPageProcessor.shouldDeferInlineFrame(inlineTf)) {
                                    addInlineFrame(inlineTf, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                                }
                            } else {
                                // \uFFFC without inline object = forced line break (ACE 8)
                                para.addItem(new ASTBreak(ASTBreak.BreakType.LINE));
                            }
                        }
                        start = j + 1;
                    }
                }
            }
        }

        // 텍스트에서 소비되지 않은 나머지 앵커 / 프레임
        if (useAnchors) {
            for (int i = anchorIdx; i < anchors.size(); i++) {
                insertAnchoredItem(anchors.get(i), run, para, idmlDoc, colorResolver,
                        imageLoader, processedGraphicIndices, resolvedData);
            }
        } else {
            for (int i = frameIdx; i < frames.size(); i++) {
                IDMLTextFrame inlineTf = frames.get(i);
                if (ASTPageProcessor.shouldDeferInlineFrame(inlineTf)) continue;
                addInlineFrame(inlineTf, para, idmlDoc, colorResolver, imageLoader, resolvedData);
            }
        }

        // 앵커로 처리되지 않은 나머지 인라인 그래픽 (앵커 없는 레거시 런, GREP 분할 런 등)
        for (int i = 0; i < run.inlineGraphics().size(); i++) {
            if (processedGraphicIndices != null && processedGraphicIndices.contains(i)) continue;
            processInlineGraphic(run.inlineGraphics().get(i), para, idmlDoc, colorResolver, imageLoader, resolvedData);
        }
    }

    /**
     * 앵커 타입에 따라 인라인 프레임 또는 인라인 그래픽을 단락에 삽입.
     */
    private static void insertAnchoredItem(IDMLCharacterRun.InlineAnchor anchor,
                                            IDMLCharacterRun run,
                                            ASTParagraph para,
                                            IDMLDocument idmlDoc,
                                            ColorResolver colorResolver,
                                            ASTImageLoader imageLoader,
                                            Set<Integer> processedGraphicIndices,
                                            ResolvedData resolvedData) {
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
            if (anchor.index() < run.inlineFrames().size()) {
                IDMLTextFrame inlineTf = run.inlineFrames().get(anchor.index());
                if (!ASTPageProcessor.shouldDeferInlineFrame(inlineTf)) {
                    addInlineFrame(inlineTf, para, idmlDoc, colorResolver, imageLoader, resolvedData);
                }
            }
        } else {
            if (anchor.index() < run.inlineGraphics().size()) {
                processInlineGraphic(run.inlineGraphics().get(anchor.index()),
                        para, idmlDoc, colorResolver, imageLoader, resolvedData);
                if (processedGraphicIndices != null) {
                    processedGraphicIndices.add(anchor.index());
                }
            }
        }
    }

    /**
     * 인라인 그래픽을 처리하여 단락에 추가.
     */
    static void processInlineGraphic(IDMLCharacterRun.InlineGraphic ig,
                                              ASTParagraph para,
                                              IDMLDocument idmlDoc,
                                              ColorResolver colorResolver,
                                              ASTImageLoader imageLoader,
                                              ResolvedData resolvedData) {
        // AnchoredPosition="Anchored" + TextWrapMode="None" Group → 인라인 삽입 건너뜀.
        // (Phase 3 후처리가 BEHIND_TEXT floating ASTFigure 로 배치 — 텍스트 겹침)
        if ("Anchored".equals(ig.anchoredPosition()) && "None".equals(ig.textWrapMode())) {
            return;
        }
        // 다중 박스(예: ㅍ ㅎ ㅂ ㅅ 자모 배지) 인라인 Group → 각 TF 를 박스 INLINE_TEXT_FRAME 으로 분해.
        // ASTRunConverter 는 ResolvedBuildContext 가 없으므로 임시 ctx 를 만들어 호출.
        if (resolvedData != null && ig.selfId() != null) {
            int boxDomId = -1;
            try { boxDomId = Integer.parseInt(ig.selfId().startsWith("u") ? ig.selfId().substring(1) : ig.selfId(), 16); } catch (Exception e) {}
            if (boxDomId > 0) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext tmpCtx =
                        new kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext();
                tmpCtx.resolvedData = resolvedData;
                tmpCtx.scaleFactor = resolvedData.scaleFactor();
                java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject> boxList =
                        kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler
                                .tryInlineGroupAsBoxList(tmpCtx, boxDomId);
                if (boxList != null && !boxList.isEmpty()) {
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject box : boxList) {
                        para.addItem(box);
                    }
                    return;
                }
                // 단일 배지 (배경 도형 + TF 1개): INLINE_TEXT_FRAME으로 변환
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject singleBadge =
                        kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler
                                .tryInlineGroupAsSingleBadge(tmpCtx, boxDomId);
                if (singleBadge != null) {
                    para.addItem(singleBadge);
                    return;
                }
            }
        }
        // DOM id 파싱 (이하 badge_group / inline_object 판별에 공통 사용)
        int domId = -1;
        if (ig.selfId() != null) {
            try { domId = Integer.parseInt(ig.selfId().startsWith("u") ? ig.selfId().substring(1) : ig.selfId(), 16); } catch (Exception e) {}
        }

        // 1순위: badge_group PNG (텍스트 포함, 항상 inline_object보다 우선)
        if (resolvedData != null && domId > 0) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup badgeRg =
                    resolvedData.getBadgeGroupByDomId(domId);
            // 중첩 Group(외부 래퍼 → 내부 배지) 폴백
            if (badgeRg == null && ig.childGraphics() != null) {
                for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
                    if (child.selfId() == null) continue;
                    int childDomId = -1;
                    try { childDomId = Integer.parseInt(child.selfId().startsWith("u") ? child.selfId().substring(1) : child.selfId(), 16); } catch (Exception e) {}
                    if (childDomId > 0) {
                        badgeRg = resolvedData.getBadgeGroupByDomId(childDomId);
                        if (badgeRg != null) break;
                    }
                }
            }
            // textHiddenBeforeExport=true → 배지 PNG에 텍스트가 없음 (신 파이프라인 InlineFrameHandler가
            // 처리해야 하지만, 테이블 셀은 ASTTableConverter 경유로 여기 도달.
            // 이 경우 badge PNG 대신 inline_object PNG(텍스트 포함)를 사용하도록 스킵.
            if (badgeRg != null && badgeRg.file() != null && !badgeRg.isTextHiddenBeforeExport()) {
                ASTInlineObject badgeObj = loadBadgeImage(ig, badgeRg, resolvedData);
                if (badgeObj != null) {
                    para.addItem(badgeObj);
                    return;
                }
            }
        }
        // 2순위: inline_object PNG (badge_group 없는 경우, imageLoader 불필요)
        if (resolvedData != null && domId > 0) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rg : resolvedData.allRenderedFloatingItems()) {
                if (rg.id() == domId && "inline_object".equals(rg.itemType()) && rg.file() != null) {
                    // 자손 TextFrame이 플로팅 텍스트박스로 배치되면 PNG와 글상자 중복됨 → 스킵
                    if (inlineObjectContainsFloatingTextFrame(domId, resolvedData)) {
                        return;
                    }
                    ASTInlineObject inlineImg = loadRenderedGroupAsInlineImage(ig, rg, null, resolvedData);
                    if (inlineImg != null) {
                        para.addItem(inlineImg);
                        return;
                    }
                    break;
                }
            }
        }
        // 그룹 자체가 renderedGraphicFrame으로 통째 렌더링된 경우 → 인라인 이미지로 직접 사용
        if (resolvedData != null && ig.selfId() != null && imageLoader != null) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup groupRg =
                    resolvedData.getRenderedGraphicFrameByIdmlId(ig.selfId());
            if (groupRg != null && groupRg.file() != null) {
                ASTInlineObject groupImg = loadRenderedGroupAsInlineImage(ig, groupRg, imageLoader, resolvedData);
                if (groupImg != null) {
                    para.addItem(groupImg);
                    return;
                }
            }
        }
        // 그룹 자체 또는 자손이 renderedGraphicFrame(deco 등)으로 렌더링된 경우
        // orphan 주입에서 올바른 위치로 배치되므로 인라인 이미지 생성 생략
        if (resolvedData != null && hasRenderedGraphicDescendant(ig, resolvedData)) {
            return;
        }

        // ObjectStyle에서 stroke/fill 속성 상속 (인라인 도형에 직접 속성이 없는 경우)
        applyObjectStyleDefaults(ig, idmlDoc);
        ASTInlineObject inlineObj = ASTInlineObjectBuilder.createInlineObjectFromGraphic(ig, imageLoader, colorResolver, idmlDoc);
        if (inlineObj != null) {
            // 인라인 수평 GraphicLine → 언더라인 탭으로 변환 (빈칸 밑줄선)
            if (tryConvertGraphicLineToUnderlineTab(ig, inlineObj, para, colorResolver, idmlDoc)) {
                return;
            }
            // 자식 도형이 모두 renderedGraphicFrame으로 렌더링되는 그룹은 합성 이미지 생략
            // (orphan 주입에서 개별 렌더 PNG가 플로팅 이미지로 배치되므로 중복 방지)
            if (inlineObj.kind() == ASTInlineObject.ObjectKind.IMAGE
                    && !ig.hasVectorShape() && !ig.childGraphics().isEmpty()
                    && resolvedData != null
                    && allChildrenRenderedGraphic(ig, resolvedData)) {
                return;
            }
            // 크기 0인 RENDERED_GROUP 래퍼는 추가하지 않음 (배경 사각형+텍스트프레임 구조의 Group)
            boolean isEmptyWrapper = inlineObj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP
                    && inlineObj.width() <= 0 && inlineObj.height() <= 0
                    && (inlineObj.imageData() == null || inlineObj.imageData().length == 0);
            if (!isEmptyWrapper) {
                para.addItem(inlineObj);
            } else if (ig.hasVectorShape()
                    && ig.vectorShape().shapeType() == IDMLVectorShape.ShapeType.GRAPHIC_LINE) {
                // 수평 장식 선 → stroke 색상을 후속 텍스트 런에 underline으로 전파
                IDMLVectorShape shape = ig.vectorShape();
                String strokeHex = ASTInlineObjectBuilder.resolveColorHex(
                        shape.strokeColor(), colorResolver);
                if (strokeHex != null) {
                    double tint = shape.strokeTint() / 100.0;
                    String blended = ASTInlineObjectBuilder.blendColorWithWhite(strokeHex, tint);
                    para.pendingUnderlineColor(blended);
                }
            }
            // IMAGE로 처리된 그래픽: 자식 텍스트프레임에 실제 텍스트가 있는 경우만 오버레이 처리
            // (장식용 도형만 가진 빈 TextFrame은 오버레이 불필요 — 체크박스 등)
            if (inlineObj.kind() == ASTInlineObject.ObjectKind.IMAGE
                    && !hasChildTextFramesWithContent(ig, idmlDoc)) {
                return;
            }
        }
        // 부모 Group의 배경 사각형에서 전체 스타일 추출 (fill, stroke, cornerRadius)
        ASTInlineObjectBuilder.GroupBackground bg = ASTInlineObjectBuilder.extractGroupBackground(ig, colorResolver);
        // IDML 직접 탐색으로 못 찾은 경우 → resolved 데이터에서 fill 조회 (깊은 중첩 Group 대응)
        if (bg == null && resolvedData != null) {
            bg = ASTInlineObjectBuilder.extractGroupBackgroundFromResolved(ig, resolvedData);
            if (bg != null) {
                System.err.println("[BG-WRAPPER] resolved fallback: fill=" + bg.fillHex
                        + " id=" + ig.selfId());
            }
        }
        // 인라인 그래픽 내부의 자식 텍스트프레임 처리 (중첩 Group 포함, 재귀)
        // IMAGE 그룹인 경우: 자식 텍스트프레임을 이미지 위 오버레이로 배치
        boolean isImageGroup = inlineObj != null
                && inlineObj.kind() == ASTInlineObject.ObjectKind.IMAGE
                && ASTInlineObjectBuilder.hasChildTextFramesRecursive(ig);
        if (isImageGroup) {
            ASTInlineObjectBuilder.collectOverlayFrames(
                    ig, inlineObj, idmlDoc, colorResolver, imageLoader, bg, resolvedData);
        } else {
            // 배경 있는 그룹 + 자식 텍스트프레임 → 단일 래퍼 글상자로 변환
            // (벡터 화살표 등은 소실되지만 텍스트+배경 스타일은 보존)
            if (bg != null && ASTInlineObjectBuilder.hasChildTextFramesRecursive(ig)) {
                ASTInlineObject wrapper = ASTOverlayBuilder.createBackgroundGroupWrapper(
                        ig, bg, idmlDoc, colorResolver, imageLoader);
                if (wrapper != null) {
                    System.err.println("[BG-WRAPPER] SUCCESS: fill=" + bg.fillHex
                            + " paras=" + wrapper.paragraphs().size()
                            + " w=" + wrapper.width() + " h=" + wrapper.height());
                    // 이전에 추가한 RENDERED_GROUP 래퍼를 제거하고 래퍼 글상자로 교체
                    if (inlineObj != null) {
                        para.items().remove(inlineObj);
                    }
                    para.addItem(wrapper);
                    return;
                }
            }
            // 그리드 테이블 감지 시도 (2×2 이상의 TextFrame 그리드 → ASTTable)
            ASTTable gridTable = ASTTableConverter.tryBuildGridTable(
                    ig, idmlDoc, colorResolver, imageLoader, bg);
            if (gridTable != null) {
                if (inlineObj == null) {
                    inlineObj = new ASTInlineObject();
                    inlineObj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
                }
                // 래퍼 크기를 그리드 테이블 크기로 설정
                if (inlineObj.width() <= 0) inlineObj.width(gridTable.width());
                if (inlineObj.height() <= 0) inlineObj.height(gridTable.height());
                inlineObj.addInlineTable(gridTable);
                if (!para.items().contains(inlineObj)) {
                    para.addItem(inlineObj);
                }
            } else {
                ASTInlineObjectBuilder.collectChildTextFrames(ig, para, idmlDoc, colorResolver, imageLoader, bg, resolvedData);
            }
        }
    }

    /**
     * 인라인 도형에 직접 stroke/fill 속성이 없으면 ObjectStyle에서 상속.
     * (예: Polygon이 GradientStroke만 사용하고 StrokeColor 속성이 없는 경우)
     */
    private static void applyObjectStyleDefaults(IDMLCharacterRun.InlineGraphic ig,
                                                   IDMLDocument idmlDoc) {
        if (!ig.hasVectorShape() || ig.appliedObjectStyle() == null || idmlDoc == null) return;
        IDMLVectorShape vs = ig.vectorShape();
        if (vs.strokeColor() != null) return; // 이미 직접 설정된 경우 스킵
        String[] osProps = idmlDoc.getObjectStyle(ig.appliedObjectStyle());
        if (osProps == null || osProps[0] == null) return;
        vs.strokeColor(osProps[0]);
        if (osProps[1] != null) {
            try { vs.strokeWeight(Double.parseDouble(osProps[1])); } catch (NumberFormatException ignored) {}
        }
        if (osProps[2] != null) {
            try {
                double tint = Double.parseDouble(osProps[2]);
                if (tint >= 0) vs.strokeTint(tint); // -1은 IDML 기본값 (100%)
            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     * 인라인 그래픽의 자손(자기 자신 제외) 중 renderedGraphicFrame(deco 등)으로 등록된 것이 있는지 확인.
     * true이면 자손 deco가 orphan 주입에서 배치되므로 인라인 이미지 생성을 생략해야 함.
     * 자기 자신이 deco인 경우는 텍스트 흐름에 인라인으로 남아야 하므로 차단하지 않음.
     */
    /**
     * inline_object PNG 대상 ID의 자손 TextFrame 중에 텍스트를 가지면서
     * FramePlacer에서 플로팅 텍스트박스로 배치될 것이 있는지 확인한다.
     * true이면 inline_object PNG를 스킵해야 이미지+글상자 중복을 피할 수 있다.
     */
    private static boolean inlineObjectContainsFloatingTextFrame(int anchorDomId,
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData resolvedData) {
        String anchorStr = String.valueOf(anchorDomId);
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame childTf : resolvedData.textFrames()) {
            // childTf가 anchor의 자손인지 확인
            String curId = childTf.id();
            boolean isDescendant = false;
            for (int depth = 0; depth < 8 && curId != null; depth++) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem pi = resolvedData.getPageItem(curId);
                if (pi == null) break;
                String parentId = pi.parentId();
                if (parentId == null) break;
                if (anchorStr.equals(parentId)) { isDescendant = true; break; }
                curId = parentId;
            }
            if (!isDescendant) continue;
            String vt = childTf.frameVisibleText();
            boolean hasText = vt != null
                    && vt.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim().length() > 1;
            if (!hasText) continue;
            // SPEC-020: 빈 컨테이너(fill=None, stroke=None)는 PNG 안의 시각적 배경 위에
            // 텍스트를 오버레이하는 입력란이므로 PNG 폐기 대상이 아님.
            String fc = childTf.fillColor();
            String sc = childTf.strokeColor();
            boolean emptyContainer = (fc == null || fc.isEmpty() || "None".equals(fc) || fc.contains("[None]"))
                    && (sc == null || sc.isEmpty() || "None".equals(sc) || sc.contains("[None]"));
            if (emptyContainer) continue;
            if (resolvedData.isEditableTextFrame(childTf.id()) || childTf.isInline()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRenderedGraphicDescendant(IDMLCharacterRun.InlineGraphic ig,
                                                         kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData resolvedData) {
        // 자기 자신은 체크하지 않음 — 자손만 확인
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (hasRenderedGraphicDescendantIncludingSelf(child, resolvedData)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRenderedGraphicDescendantIncludingSelf(IDMLCharacterRun.InlineGraphic ig,
                                                         kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData resolvedData) {
        if (ig.selfId() != null
                && resolvedData.getRenderedGraphicFrameByIdmlId(ig.selfId()) != null) {
            return true;
        }
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (hasRenderedGraphicDescendantIncludingSelf(child, resolvedData)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 인라인 그래픽의 자식 텍스트프레임 중 실제 텍스트 콘텐츠가 있는 것이 있는지 확인.
     * 장식용 도형(체크마크 PathGeometry 등)만 포함하는 빈 TextFrame은 false.
     */
    /**
     * 그룹의 벡터 도형 자식이 모두 renderedGraphicFrame으로 등록되어 있는지 확인.
     * true이면 orphan 주입에서 개별 렌더 PNG가 배치되므로 합성 이미지는 중복.
     */
    private static boolean allChildrenRenderedGraphic(IDMLCharacterRun.InlineGraphic ig,
                                                       kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData resolvedData) {
        boolean hasAny = false;
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasVectorShape()) {
                hasAny = true;
                if (child.selfId() == null
                        || resolvedData.getRenderedGraphicFrameByIdmlId(child.selfId()) == null) {
                    return false;
                }
            }
            // 재귀: 하위 그룹의 자식도 확인
            if (!child.childGraphics().isEmpty()) {
                if (!allChildrenRenderedGraphic(child, resolvedData)) {
                    return false;
                }
                hasAny = true;
            }
        }
        return hasAny;
    }

    private static boolean hasChildTextFramesWithContent(IDMLCharacterRun.InlineGraphic ig,
                                                          IDMLDocument idmlDoc) {
        if (idmlDoc == null) return ASTInlineObjectBuilder.hasChildTextFramesRecursive(ig);
        return hasChildTextFramesWithContentRecursive(ig, idmlDoc);
    }

    private static boolean hasChildTextFramesWithContentRecursive(IDMLCharacterRun.InlineGraphic ig,
                                                                    IDMLDocument idmlDoc) {
        if (ig.childTextFrames() != null) {
            for (IDMLTextFrame tf : ig.childTextFrames()) {
                String storyId = tf.parentStoryId();
                if (storyId == null) continue;
                IDMLStory story = idmlDoc.getStory(storyId);
                if (story == null) continue;
                if (story.hasTables()) return true;
                for (IDMLParagraph p : story.paragraphs()) {
                    for (IDMLCharacterRun run : p.characterRuns()) {
                        String c = run.content();
                        if (c != null && !c.trim().isEmpty()) return true;
                    }
                }
            }
        }
        if (ig.childGraphics() != null) {
            for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
                if (hasChildTextFramesWithContentRecursive(child, idmlDoc)) return true;
            }
        }
        return false;
    }

    private static boolean tryConvertGraphicLineToUnderlineTab(
            IDMLCharacterRun.InlineGraphic ig, ASTInlineObject inlineObj,
            ASTParagraph para, ColorResolver colorResolver, IDMLDocument idmlDoc) {
        if (!ig.hasVectorShape()) return false;
        if (ig.vectorShape().shapeType() != IDMLVectorShape.ShapeType.GRAPHIC_LINE) return false;
        if (inlineObj.height() > 200 || inlineObj.width() <= 0) return false;

        IDMLVectorShape shape = ig.vectorShape();
        String strokeHex = ASTInlineObjectBuilder.resolveColorHex(shape.strokeColor(), colorResolver);
        double tint = shape.strokeTint();

        // 직접 속성 없으면 AppliedObjectStyle에서 stroke 색상 조회
        if (strokeHex == null && ig.appliedObjectStyle() != null && idmlDoc != null) {
            String[] objStyle = idmlDoc.getObjectStyle(ig.appliedObjectStyle());
            if (objStyle != null) {
                strokeHex = ASTInlineObjectBuilder.resolveColorHex(objStyle[0], colorResolver);
                if (objStyle[2] != null) {
                    try { tint = Double.parseDouble(objStyle[2]); } catch (NumberFormatException e) {
                        System.err.println("[ASTRunConverter] StrokeTint 파싱 실패: " + objStyle[2]);
                    }
                }
            }
        }
        if (strokeHex == null) return false;

        // IDML tint: -1 = 기본값(100%), 0~100 = 퍼센트
        double tintRatio = (tint < 0) ? 1.0 : tint / 100.0;
        String blended = ASTInlineObjectBuilder.blendColorWithWhite(strokeHex, tintRatio);

        // stroke 유형 힌트 → underline shape ("DOT", "DASH", null=SOLID)
        String ulShape = null;
        if (shape.strokeTypeHint() != null) {
            if ("dot".equals(shape.strokeTypeHint())) ulShape = "DOT";
            else if ("dash".equals(shape.strokeTypeHint())) ulShape = "DASH";
        }

        // 선행 텍스트 길이로 변환 방식 결정
        int textCharsBefore = 0;
        int lastFontSize = 1350; // default 13.5pt
        String lastFontFamily = null;
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                ASTTextRun tr = (ASTTextRun) item;
                if (tr.text() != null) textCharsBefore += tr.text().length();
                if (tr.fontSizeHwpunits() != null) lastFontSize = tr.fontSizeHwpunits();
                if (tr.fontFamily() != null) lastFontFamily = tr.fontFamily();
            }
        }

        if (textCharsBefore < 3) {
            // 탭+리더 방식: 선행 텍스트가 매우 짧은 경우 (e.g., "1. \t[___]")
            long lastTabPos = 0;
            if (para.hasTabStops()) {
                for (ASTTabStop ts : para.tabStops()) {
                    if (ts.position() > lastTabPos) lastTabPos = ts.position();
                }
            }
            // 안전 마진 200 HWPUNIT(2pt): 탭 리더가 셀 경계에 닿으면 한글이 줄바꿈하므로
            long tabPos = lastTabPos + inlineObj.width() - 200;
            if (tabPos < lastTabPos + 100) tabPos = lastTabPos + 100;
            para.addTabStop(new ASTTabStop(tabPos, "left", "_"));

            ASTTextRun tabRun = new ASTTextRun();
            tabRun.text("\t");
            // 탭 리더("_" → SOLID)가 밑줄 역할을 하므로 문자 밑줄은 설정하지 않는다.
            // 둘 다 설정하면 이중 밑줄이 나타남.
            tabRun.textColor(blended);
            para.addItem(tabRun);
        } else {
            // 고정폭 공백 방식: 본문 중간의 빈칸 밑줄 (e.g., "I like [___] the most")
            // DOT 스타일: 가운뎃점(·) 문자열로 변환 (중간 점선은 밑줄이 아닌 중간 높이)
            if ("DOT".equals(ulShape)) {
                int dotWidth = lastFontSize / 2;
                if (dotWidth <= 0) dotWidth = 675;
                int count = Math.max(1, (int) Math.round((double) inlineObj.width() / dotWidth));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) sb.append('\u00B7'); // middle dot
                ASTTextRun dotRun = new ASTTextRun();
                dotRun.text(sb.toString());
                dotRun.textColor(blended);
                dotRun.fontSizeHwpunits(lastFontSize);
                if (lastFontFamily != null) dotRun.fontFamily(lastFontFamily);
                para.addItem(dotRun);
            } else {
                // en-space (U+2002) = 0.5em 폭으로 정밀한 너비 제어
                int enSpaceWidth = lastFontSize / 2;
                if (enSpaceWidth <= 0) enSpaceWidth = 675;
                int count = Math.max(1, (int) Math.round((double) inlineObj.width() / enSpaceWidth));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) sb.append('\u2002');

                ASTTextRun spaceRun = new ASTTextRun();
                spaceRun.text(sb.toString());
                spaceRun.underline(true);
                spaceRun.underlineColor(blended);
                spaceRun.underlineShape(ulShape);
                spaceRun.textColor(blended);
                spaceRun.fontSizeHwpunits(lastFontSize);
                if (lastFontFamily != null) spaceRun.fontFamily(lastFontFamily);
                para.addItem(spaceRun);
            }
        }
        return true;
    }

    /**
     * 인라인 TextFrame을 분수 수식 또는 일반 인라인 오브젝트로 변환하여 단락에 추가.
     */
    private static void addInlineFrame(IDMLTextFrame inlineTf, ASTParagraph para,
                                        IDMLDocument idmlDoc, ColorResolver colorResolver,
                                        ASTImageLoader imageLoader, ResolvedData resolvedData) {
        ASTEquation fractionEq = ASTStoryConverter.tryConvertFractionTextFrame(inlineTf, idmlDoc);
        if (fractionEq != null) {
            para.addItem(fractionEq);
            return;
        }
        ASTInlineObject inlineObj = ASTStoryConverter.createInlineObjectFromTextFrame(inlineTf, idmlDoc, colorResolver, imageLoader, resolvedData);
        if (inlineObj == null) {
            // 분수 스타일이지만 내용 없는 빈 답안 상자 → 테두리 있는 빈 사각형
            inlineObj = createAnswerBox(inlineTf, colorResolver);
        }
        if (inlineObj == null) {
            // 채색된 도형 (불릿 아이콘 등) → 색칠된 인라인 사각형
            inlineObj = createColoredShape(inlineTf, colorResolver);
        }
        if (inlineObj != null) {
            para.addItem(inlineObj);
        }
    }

    /**
     * 빈 답안 상자(분수 스타일 TextFrame, 내용 없음) → 테두리 있는 인라인 사각형.
     */
    private static ASTInlineObject createAnswerBox(IDMLTextFrame tf, ColorResolver colorResolver) {
        String objStyle = tf.appliedObjectStyle();
        if (objStyle == null || !objStyle.contains("분수")) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.SPACER_RECT);
        obj.sourceId(tf.selfId());

        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));

        if (tf.strokeColor() != null) {
            obj.strokeColor(colorResolver.resolve(tf.strokeColor()));
        }
        obj.strokeWeight(tf.strokeWeight());
        if (tf.fillColor() != null) {
            obj.fillColor(colorResolver.resolve(tf.fillColor()));
        }
        obj.cornerRadius(tf.cornerRadius());

        return obj;
    }

    /**
     * 채색된 인라인 도형 (불릿 아이콘, 장식 도형 등) → 색칠된 인라인 사각형.
     * FillColor가 있고 스토리 내용이 없는 TextFrame을 변환.
     */
    private static ASTInlineObject createColoredShape(IDMLTextFrame tf, ColorResolver colorResolver) {
        if (tf.fillColor() == null) return null;
        // "Swatch/None" 또는 "Color/Paper"이면 실질적 색상 없음
        String fc = tf.fillColor();
        if (fc.contains("None") || fc.contains("Paper")) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.SPACER_RECT);
        obj.sourceId(tf.selfId());

        double w = IDMLGeometry.transformedWidth(tf.geometricBounds(), tf.itemTransform());
        double h = IDMLGeometry.transformedHeight(tf.geometricBounds(), tf.itemTransform());
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));

        obj.fillColor(colorResolver.resolve(tf.fillColor()));
        if (tf.fillTint() > 0) {
            obj.fillTint(tf.fillTint());
        }
        if (tf.strokeColor() != null && !tf.strokeColor().contains("None")) {
            obj.strokeColor(colorResolver.resolve(tf.strokeColor()));
        }
        obj.strokeWeight(tf.strokeWeight());

        return obj;
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
        textRun.text(ASTPageProcessor.stripACEPlaceholders(text));

        String charStyleRef = run.appliedCharacterStyle();
        if (charStyleRef != null) {
            textRun.characterStyleRef(ASTStoryConverter.cleanStyleRef(charStyleRef));
        }

        // 스타일 상속 해결: 런 → CharacterStyle → ParagraphStyle
        String fontFamily = run.fontFamily();
        Double fontSize = run.fontSize();
        String fillColor = run.fillColor();
        String fontStyle = run.fontStyle();
        Double tracking = run.tracking();
        Boolean underline = run.underline();
        String underlineType = run.underlineType();
        Boolean strikeThrough = run.strikeThrough();
        Double baselineShift = run.baselineShift();
        Double horizontalScale = run.horizontalScale();
        String capitalization = run.capitalization();

        // CharacterStyle에서 빈 속성 채우기
        if (charStyleRef != null) {
            IDMLStyleDef charStyle = ASTStoryConverter.resolveStyle(charStyleRef, idmlDoc.charStyles());
            if (charStyle != null) {
                if (fontFamily == null) fontFamily = charStyle.fontFamily();
                if (fontSize == null) fontSize = charStyle.fontSize();
                if (fillColor == null) fillColor = charStyle.fillColor();
                if (fontStyle == null) fontStyle = charStyle.fontStyle();
                if (tracking == null) tracking = charStyle.tracking();
                if (underline == null) underline = charStyle.underline();
                if (underlineType == null) underlineType = charStyle.underlineType();
                if (strikeThrough == null) strikeThrough = charStyle.strikeThrough();
                if (baselineShift == null) baselineShift = charStyle.baselineShift();
                if (horizontalScale == null) horizontalScale = charStyle.horizontalScale();
                if (capitalization == null) capitalization = charStyle.capitalization();
            }
        }

        // ParagraphStyleRange 인라인 오버라이드에서 빈 속성 채우기
        if (parentPara != null) {
            if (tracking == null) tracking = parentPara.tracking();
        }

        // ParagraphStyle에서 빈 속성 채우기
        String paraStyleRef = parentPara != null ? parentPara.appliedParagraphStyle() : null;
        if (paraStyleRef != null) {
            IDMLStyleDef paraStyle = ASTStoryConverter.resolveStyle(paraStyleRef, idmlDoc.paraStyles());
            if (paraStyle != null) {
                if (fontFamily == null) fontFamily = paraStyle.fontFamily();
                if (fontSize == null) fontSize = paraStyle.fontSize();
                if (fillColor == null) fillColor = paraStyle.fillColor();
                if (fontStyle == null) fontStyle = paraStyle.fontStyle();
                if (tracking == null) tracking = paraStyle.tracking();
                if (underline == null) underline = paraStyle.underline();
                if (underlineType == null) underlineType = paraStyle.underlineType();
                if (strikeThrough == null) strikeThrough = paraStyle.strikeThrough();
                if (baselineShift == null) baselineShift = paraStyle.baselineShift();
                if (horizontalScale == null) horizontalScale = paraStyle.horizontalScale();
                if (capitalization == null) capitalization = paraStyle.capitalization();
            }
        }

        // GREP 일반 문자 스타일 오버라이드 (FillColor 등)
        if (run.grepAppliedCharStyle() != null) {
            IDMLStyleDef grepCharStyle = ASTStoryConverter.resolveStyle(
                    run.grepAppliedCharStyle(), idmlDoc.charStyles());
            if (grepCharStyle != null) {
                if (grepCharStyle.fillColor() != null) fillColor = grepCharStyle.fillColor();
                if (grepCharStyle.fontFamily() != null) fontFamily = grepCharStyle.fontFamily();
                if (grepCharStyle.fontSize() != null) fontSize = grepCharStyle.fontSize();
                if (grepCharStyle.fontStyle() != null) fontStyle = grepCharStyle.fontStyle();
                textRun.grepStyleApplied(true);
                // EH상부자/하부자 GREP 적용 시 ASCII 글리프 매핑 (예: '_' → '×')
                if (grepCharStyle.fontFamily() != null
                        && kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap
                                .isEHFontFamily(grepCharStyle.fontFamily())) {
                    String mapped = kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap
                            .applyEHGrepAsciiGlyphMap(textRun.text());
                    textRun.text(mapped);
                }
            }
        }

        // GREP 수식 폰트가 적용된 런: 스타일 상속 대신 BT수식M Italic 적용
        if (run.grepMathFont() && !ASTMathGrouper.isPlainAlphanumericRun(run)
                && (fontFamily == null || !fontFamily.contains("BT수식"))) {
            fontFamily = "BT수식M";
            fontStyle = "Italic";
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
        textRun.underline(Boolean.TRUE.equals(underline));
        // 밑줄 타입 매핑 (IDML "StrokeStyle/$ID/Wavy" → AST "WAVE")
        if (Boolean.TRUE.equals(underline) && underlineType != null) {
            String lower = underlineType.toLowerCase();
            if (lower.contains("wavy") || lower.contains("wave")) {
                textRun.underlineShape("WAVE");
            } else if (lower.contains("dashed") || lower.contains("dash")) {
                textRun.underlineShape("DASH");
            } else if (lower.contains("dotted") || lower.contains("dot")) {
                textRun.underlineShape("DOT");
            }
        }
        // 밑줄 틴트가 있으면 Black을 틴트 비율로 흰색과 블렌딩하여 밑줄 색상 계산
        if (Boolean.TRUE.equals(underline) && run.underlineTint() != null) {
            double tint = run.underlineTint() / 100.0;
            int gray = (int) Math.round(255 * (1.0 - tint));
            textRun.underlineColor(String.format("#%02X%02X%02X", gray, gray, gray));
        }
        textRun.strikeThrough(Boolean.TRUE.equals(strikeThrough));

        // 기준선 이동 (points → HWPX %, 폰트 크기 기준)
        if (baselineShift != null && baselineShift != 0) {
            // IDML: points 단위. HWPX: 폰트 크기 대비 % (양수=위)
            double fSize = fontSize != null ? fontSize : 10.0;
            short shiftPercent = (short) Math.round(baselineShift / fSize * 100);
            textRun.baselineShift(shiftPercent);
        }

        // 장평 (%, 100=normal)
        if (horizontalScale != null && horizontalScale != 100.0) {
            textRun.horizontalScale((short) Math.round(horizontalScale));
        }

        // Capitalization → 텍스트 변환 (SmallCaps는 HWPX 미지원이므로 AllCaps만 처리)
        if ("AllCaps".equals(capitalization) && textRun.text() != null) {
            textRun.text(textRun.text().toUpperCase());
        }

        return textRun;
    }

    /**
     * 배지 그룹의 PNG 이미지를 로드하여 ASTInlineObject(IMAGE)로 변환.
     */
    private static ASTInlineObject loadBadgeImage(
            IDMLCharacterRun.InlineGraphic ig,
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup badgeRg,
            ResolvedData resolvedData) {
        String basePath = resolvedData.basePath();
        if (basePath == null) return null;

        java.io.File pngFile = new java.io.File(basePath, badgeRg.file());
        if (!pngFile.exists()) return null;

        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
            if (img == null) return null;

            ASTInlineObject obj = new ASTInlineObject();
            obj.sourceId(ig.selfId());
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);
            obj.imageData(imageData);
            obj.imageFormat("png");
            obj.pixelWidth(img.getWidth());
            obj.pixelHeight(img.getHeight());

            // 인라인 크기: resolved bounds 사용 (normalizeToPoints 후 points 단위)
            double[] bounds = badgeRg.bounds();
            if (bounds != null && bounds.length >= 4) {
                double wPt = bounds[3] - bounds[1];
                double hPt = bounds[2] - bounds[0];
                obj.width(CoordinateConverter.pointsToHwpunits(wPt));
                obj.height(CoordinateConverter.pointsToHwpunits(hPt));
            } else {
                obj.width(CoordinateConverter.pointsToHwpunits(ig.widthPoints()));
                obj.height(CoordinateConverter.pointsToHwpunits(ig.heightPoints()));
            }

            // 앵커/래핑 속성 복사
            obj.anchoredPosition(ig.anchoredPosition());
            obj.textWrapMode(ig.textWrapMode());
            obj.keepInline(true); // 테이블 셀에서 floating 추출 금지 (inline에 유지)

            System.out.println("[InlineBadge] " + ig.selfId() + " → " + badgeRg.file());
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 그룹 자체가 renderedGraphicFrame으로 통째 렌더링된 경우,
     * 해당 PNG를 인라인 이미지로 로드하여 반환.
     */
    private static ASTInlineObject loadRenderedGroupAsInlineImage(
            IDMLCharacterRun.InlineGraphic ig,
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rg,
            ASTImageLoader imageLoader,
            ResolvedData resolvedData) {
        String basePath = resolvedData.basePath();
        if (basePath == null) return null;

        java.io.File pngFile = new java.io.File(basePath, rg.file());
        if (!pngFile.exists()) return null;

        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
            if (img == null) return null;

            ASTInlineObject obj = new ASTInlineObject();
            obj.sourceId(ig.selfId());
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);
            obj.imageData(imageData);
            obj.imageFormat("png");
            obj.pixelWidth(img.getWidth());
            obj.pixelHeight(img.getHeight());

            // resolved bounds [top, left, bottom, right] 에서 실제 크기 계산
            double widthPt = ig.widthPoints();
            double heightPt = ig.heightPoints();
            if (rg.bounds() != null && rg.bounds().length >= 4) {
                double bw = rg.bounds()[3] - rg.bounds()[1];
                double bh = rg.bounds()[2] - rg.bounds()[0];
                if (bw > 0 && bh > 0) {
                    // bounds는 문서 단위(mm) → pt 변환 필요
                    widthPt = bw * 2.8346;
                    heightPt = bh * 2.8346;
                }
            }
            obj.width(CoordinateConverter.pointsToHwpunits(widthPt));
            obj.height(CoordinateConverter.pointsToHwpunits(heightPt));

            // rendered bounds X 좌표 저장 (인라인 객체 정렬용)
            if (rg.bounds() != null && rg.bounds().length >= 4) {
                obj.boundsX(rg.bounds()[1]);
                // SPEC-020: 페이지 절대 좌표 기록 — 같은 셀에 여러 인라인이 있을 때
                // cellX/cellY fallback 으로 겹치는 문제 방지.
                double scale = resolvedData != null ? resolvedData.scaleFactor() : 1.0;
                obj.resolvedPageX(CoordinateConverter.pointsToHwpunits(rg.bounds()[1] * scale));
                obj.resolvedPageY(CoordinateConverter.pointsToHwpunits(rg.bounds()[0] * scale));
            }

            obj.anchoredPosition(ig.anchoredPosition());
            obj.textWrapMode(ig.textWrapMode());
            obj.keepInline(true); // IDML AnchoredPosition=InlineOrAbove → floating 추출 금지

            // 그룹 자체 + 자식 ID를 consumed로 마킹 → orphan 주입에서 제외
            resolvedData.markConsumedRenderedGraphic(String.valueOf(rg.id()));
            if (rg.childIds() != null) {
                for (int childId : rg.childIds()) {
                    resolvedData.markConsumedRenderedGraphic(String.valueOf(childId));
                }
            }

            System.err.println("[InlineRenderedGroup] " + ig.selfId() + " → " + rg.file()
                    + " (children consumed: " + (rg.childIds() != null ? rg.childIds().length : 0) + ")");
            return obj;
        } catch (Exception e) {
            return null;
        }
    }
}
