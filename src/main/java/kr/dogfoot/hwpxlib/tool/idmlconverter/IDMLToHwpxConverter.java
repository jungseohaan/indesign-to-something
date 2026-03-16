package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.flat.ASTToFlatConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.flat.FlatDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.flat.FlatToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.IDMLNormalizer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedDataReader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * IDML -> HWPX 변환 메인 파사드.
 *
 * AST 파이프라인:
 * IDMLLoader -> IDMLNormalizer (4단계) -> ASTDocument -> ASTToHwpxConverter -> HWPX
 *
 * 사용 예:
 * <pre>{@code
 * // 직접 변환
 * ConvertResult result = IDMLToHwpxConverter.convert("input.idml", "output.hwpx",
 *                                                     ConvertOptions.defaults());
 *
 * // IDML -> AST JSON
 * String json = IDMLToHwpxConverter.toJson("input.idml", ConvertOptions.defaults());
 * }</pre>
 *
 * CLI:
 * <pre>
 * java IDMLToHwpxConverter input.idml output.hwpx [startPage] [endPage]
 * </pre>
 */
public class IDMLToHwpxConverter {

    /**
     * IDML 파일을 HWPX 파일로 변환한다.
     *
     * @param idmlPath IDML 파일 경로
     * @param hwpxPath 출력 HWPX 파일 경로
     * @param options  변환 옵션
     * @return 변환 결과 (경고, 통계)
     */
    public static ConvertResult convert(String idmlPath, String hwpxPath,
                                         ConvertOptions options) throws ConvertException {
        return convert(idmlPath, hwpxPath, options, ProgressReporter.NONE);
    }

    /**
     * IDML 파일을 HWPX 파일로 변환한다 (진행률 보고 포함).
     *
     * @param idmlPath IDML 파일 경로
     * @param hwpxPath 출력 HWPX 파일 경로
     * @param options  변환 옵션
     * @param reporter 진행률 보고기
     * @return 변환 결과 (경고, 통계)
     */
    public static ConvertResult convert(String idmlPath, String hwpxPath,
                                         ConvertOptions options,
                                         ProgressReporter reporter) throws ConvertException {
        // Phase 1: IDML 로드
        reporter.reportProgress(0, 100, "IDML 파일 로딩 중...");
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        try {
            String sourceFileName = new File(idmlPath).getName();

            // 초기 단계 경고 수집 (result 생성 전이므로 리스트에 보관)
            List<String> earlyWarnings = new ArrayList<>();

            // Phase 1.5: Resolved 데이터 조기 로딩 (Stage4 래스터화에서 사용)
            ResolvedData resolvedData = null;
            if (options.resolvedJsonPath() != null) {
                try {
                    reporter.reportProgress(3, 100, "resolved 데이터 로딩 중...");
                    resolvedData = ResolvedDataReader.read(options.resolvedJsonPath());
                } catch (Exception e) {
                    System.err.println("Warning: resolved.json 로드 실패 (무시): " + e.getMessage());
                    earlyWarnings.add("[Resolved] resolved.json 로드 실패: " + e.getMessage());
                }
            }

            // Phase 1.6: resolved 좌표 단위 정규화 (mm/in → pt)
            // InDesign DOM은 문서 측정 단위로 좌표를 반환하므로,
            // IDML 페이지 치수(항상 pt)와 비교하여 스케일 팩터를 계산한다.
            if (resolvedData != null) {
                List<IDMLPage> idmlPages = idmlDoc.getAllPages();
                if (!idmlPages.isEmpty()) {
                    resolvedData.normalizeToPoints(idmlPages.get(0).widthPoints());
                }
            }

            // Phase 1.6b: 배지 그룹 인덱스 빌드 (배지 소속 도형을 개별 래스터화에서 제외하기 위해)
            if (resolvedData != null) {
                resolvedData.buildBadgeGroupIndex();
            }

            // Phase 1.7a: 렌더링된 텍스트 프레임 교체 (짧은 텍스트 → 이미지)
            if (resolvedData != null && resolvedData.renderedTextFrameCount() > 0) {
                replaceRenderedTextFrames(idmlDoc, resolvedData, options);
            }

            // Phase 2: IDML -> ASTDocument (4단계 정규화, resolved 좌표 활용)
            reporter.reportProgress(5, 100, "IDML 정규화 중...");
            ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName, resolvedData, reporter);

            // Phase 2.5: Resolved 텍스트/스타일 보강 (선택적)
            if (resolvedData != null) {
                try {
                    reporter.reportProgress(8, 100, "resolved 데이터 병합 중...");
                    kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedMerger.enrich(
                            astDoc, resolvedData);
                    kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedFrameDistributor.distribute(
                            astDoc, resolvedData);
                } catch (Exception e) {
                    System.err.println("Warning: resolved.json 병합 실패 (무시): " + e.getMessage());
                    earlyWarnings.add("[Resolved] resolved.json 병합 실패: " + e.getMessage());
                }
            }

            // Phase 2.6: Resolved 오버레이 좌표 보강 (선택적)
            if (resolvedData != null) {
                try {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedOverlayEnricher.enrich(
                            astDoc, resolvedData);
                } catch (Exception e) {
                    System.err.println("Warning: resolved overlay 보강 실패 (무시): " + e.getMessage());
                    earlyWarnings.add("[Resolved] overlay 보강 실패: " + e.getMessage());
                }
            }

            // Phase 2.7: 플로팅 이미지 → 인라인 머지 (textWrap 자리차지)
            kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.FloatingImageMerger.merge(astDoc);

            // Phase 2.8: 인라인 앵커 텍스트 프레임 → 렌더 이미지 교체
            java.util.Set<String> inlineReplacedTexts = java.util.Collections.emptySet();
            if (resolvedData != null && resolvedData.renderedTextFrameCount() > 0) {
                inlineReplacedTexts = replaceInlineRenderedTextFrames(astDoc, resolvedData, options);
            }

            // 테이블 셀 인라인 텍스트와 동일한 플로팅 텍스트 프레임 블록 제거
            // (플로팅 교체 전에 실행해야 ASTFigure로 변환되기 전에 매칭 가능)
            if (!inlineReplacedTexts.isEmpty()) {
                removeFloatingDuplicates(astDoc, inlineReplacedTexts, resolvedData);
            }

            // Phase 2.9: 플로팅 렌더 텍스트 프레임 → 이미지 교체 (AST 좌표 기반)
            if (resolvedData != null && resolvedData.renderedTextFrameCount() > 0) {
                replaceFloatingRenderedTextFrames(astDoc, resolvedData, options);
            }

            // Phase 2.10: VectorShape로 등록되지 않은 렌더 그래픽 프레임 주입
            if (resolvedData != null) {
                injectOrphanRenderedGraphics(astDoc, resolvedData, options);
            }

            // 정규화 완료 summary
            {
                int tfCount = 0, imgCount = 0, tblCount = 0;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection sec : astDoc.sections()) {
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : sec.blocks()) {
                        if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) tfCount++;
                        else if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) imgCount++;
                        else if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) tblCount++;
                    }
                }
                reporter.reportProgress(9, 100,
                        "정규화 완료 — " + astDoc.sections().size() + "p, "
                                + tfCount + " 텍스트프레임, " + imgCount + " 이미지, " + tblCount + " 테이블");
            }

            // Phase 2.8: 3계층 폰트 매퍼 초기화 (선택적)
            FontMapper fontMapper = null;
            if (options.fontMapPath() != null) {
                fontMapper = new FontMapper();
                fontMapper.loadFontMapping(options.fontMapPath());
                if (resolvedData != null) {
                    fontMapper.setIdmlMetrics(resolvedData.fontMetrics());
                }
            }

            // Phase 3: AST -> Flat -> HWPX (페이지별 진행률: 10~90)
            FlatDocument flatDoc = ASTToFlatConverter.convert(astDoc);
            ConvertResult result = FlatToHwpxConverter.convert(flatDoc, reporter, null, fontMapper);

            // 초기 단계 경고 + AST 정규화 경고를 결과에 병합
            for (String w : earlyWarnings) { result.addWarning(w); }
            for (String w : astDoc.warnings()) { result.addWarning(w); }

            // Phase 4: HWPX 파일 저장
            reporter.reportProgress(95, 100, "HWPX 파일 저장 중...");
            try {
                HWPXWriter.toFilepath(result.hwpxFile(), hwpxPath);
            } catch (Exception e) {
                throw new ConvertException(ConvertException.Phase.HWPX_GENERATION,
                        "Failed to write HWPX file: " + e.getMessage(), e);
            }

            // 변환 완료 보고
            reporter.reportComplete(result);

            return result;
        } finally {
            idmlDoc.cleanup();
        }
    }

    /**
     * IDML 파일을 HWPXFile 객체로 변환한다 (파일 저장 없이).
     *
     * @param idmlPath IDML 파일 경로
     * @param options  변환 옵션
     * @return HWPXFile 객체
     */
    public static HWPXFile convertToHwpxFile(String idmlPath,
                                              ConvertOptions options) throws ConvertException {
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        try {
            String sourceFileName = new File(idmlPath).getName();
            ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName);
            FlatDocument flatDoc = ASTToFlatConverter.convert(astDoc);
            ConvertResult result = FlatToHwpxConverter.convert(flatDoc);
            return result.hwpxFile();
        } finally {
            idmlDoc.cleanup();
        }
    }

    /**
     * IDML 파일을 AST JSON 문자열로 변환한다.
     *
     * @param idmlPath IDML 파일 경로
     * @param options  변환 옵션
     * @return JSON 문자열
     */
    public static String toJson(String idmlPath, ConvertOptions options) throws ConvertException {
        IDMLDocument idmlDoc = IDMLLoader.load(idmlPath);
        try {
            String sourceFileName = new File(idmlPath).getName();
            ASTDocument astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName);
            return ASTSerializer.toJson(astDoc);
        } finally {
            idmlDoc.cleanup();
        }
    }

    /**
     * 렌더링된 텍스트 프레임을 합성 이미지로 교체한다.
     * 20자 미만의 장식 텍스트를 PNG 이미지로 대체.
     */
    static void replaceRenderedTextFrames(IDMLDocument idmlDoc,
                                                   ResolvedData resolvedData,
                                                   ConvertOptions options) {
        String resolvedDir = getResolvedDir(options);
        if (resolvedDir == null) return;

        int replacedCount = 0;

        for (IDMLSpread spread : idmlDoc.spreads()) {
            // 1단계: TextPath(VectorShape)가 처리할 렌더 파일 목록 미리 수집
            //   같은 PNG가 TextFrame + TextPath 양쪽에서 매칭되면
            //   TextPath 버전만 사용 (그룹 z-order가 정확)
            java.util.Set<String> textPathRenderedFiles = new java.util.HashSet<>();
            for (IDMLVectorShape vs : spread.vectorShapes()) {
                if (vs.isInline()) continue;
                RenderedGroup rg = resolvedData.getRenderedTextFrameByIdmlId(vs.selfId());
                if (rg != null && rg.file() != null) {
                    textPathRenderedFiles.add(rg.file());
                }
            }

            // 2단계: TextPath와 중복되는 TextFrame만 제거 (이미지 교체는 AST 단계에서)
            java.util.Iterator<IDMLTextFrame> it = spread.textFrames().iterator();
            while (it.hasNext()) {
                IDMLTextFrame tf = it.next();
                RenderedGroup rendered = resolvedData.getRenderedTextFrameByIdmlId(tf.selfId());
                if (rendered == null) continue;
                if (textPathRenderedFiles.contains(rendered.file())) {
                    it.remove();
                    System.out.println("[RenderedTextFrame] " + tf.selfId()
                            + " → SKIP (TextPath가 " + rendered.file() + " 처리)");
                }
                // TextPath와 중복되지 않는 렌더 TextFrame은 AST에서 처리
            }

            // 3단계: TextPath(VectorShape) 교체
            java.util.Set<String> replacedGroupIds = new java.util.HashSet<>();

            java.util.Iterator<IDMLVectorShape> vsIt = spread.vectorShapes().iterator();
            while (vsIt.hasNext()) {
                IDMLVectorShape vs = vsIt.next();
                RenderedGroup rendered = resolvedData.getRenderedTextFrameByIdmlId(vs.selfId());
                if (rendered == null) continue;

                if (vs.isInline()) {
                    vsIt.remove();
                    System.out.println("[RenderedInlineVS] " + vs.selfId() + " → spread에서 제거 (AST에서 처리)");
                    continue;
                }

                java.io.File pngFile = new java.io.File(resolvedDir, rendered.file());

                double[] vsBounds = vs.geometricBounds();
                double[] vsTransform = vs.itemTransform();

                // 렌더 PNG는 이미 올바른 방향(회전/반전 포함)으로 출력되었으므로
                // 변환 행렬의 회전/반전을 제거하여 이미지가 이중 변환되지 않게 한다.
                if (vsTransform != null && vsTransform.length >= 6) {
                    double a = vsTransform[0], b = vsTransform[1];
                    double c = vsTransform[2], d = vsTransform[3];
                    boolean hasRotation = (Math.abs(b) > 0.01 || Math.abs(c) > 0.01);
                    boolean flipX = (a < 0 && !hasRotation);
                    boolean flipY = (d < 0 && !hasRotation);

                    if (hasRotation || flipX || flipY) {
                        // resolved bounds → spread 좌표로 변환
                        double[] resolvedBounds = rendered.bounds();
                        boolean usedResolved = false;
                        if (resolvedBounds != null && resolvedBounds.length >= 4) {
                            int renderPageIdx = rendered.pageIndex();
                            IDMLPage targetPage = findPageByIndex(idmlDoc, renderPageIdx);
                            if (targetPage != null && targetPage.itemTransform() != null) {
                                double pageTx = targetPage.itemTransform()[4];
                                double pageTy = targetPage.itemTransform()[5];
                                double rTop = resolvedBounds[0], rLeft = resolvedBounds[1];
                                double rBottom = resolvedBounds[2], rRight = resolvedBounds[3];
                                vsBounds = new double[]{rTop + pageTy, rLeft + pageTx,
                                        rBottom + pageTy, rRight + pageTx};
                                usedResolved = true;
                            }
                        }
                        if (!usedResolved) {
                            double[] aabb = IDMLGeometry.getTransformedBoundingBox(vsBounds, vsTransform);
                            vsBounds = new double[]{aabb[1], aabb[0], aabb[3], aabb[2]};
                        }
                        // PNG 실제 비율로 bounds 보정 (visibleBounds ≠ PNG export 영역일 수 있음)
                        try {
                            java.awt.image.BufferedImage pngImg = javax.imageio.ImageIO.read(pngFile);
                            if (pngImg != null) {
                                double pngAspect = (double) pngImg.getWidth() / pngImg.getHeight();
                                double bW = vsBounds[3] - vsBounds[1]; // right - left
                                double bH = vsBounds[2] - vsBounds[0]; // bottom - top
                                double boundsAspect = bW / bH;
                                if (Math.abs(pngAspect - boundsAspect) > 0.05) {
                                    // 너비 기준으로 높이 조정
                                    double centerY = (vsBounds[0] + vsBounds[2]) / 2.0;
                                    double newH = bW / pngAspect;
                                    vsBounds[0] = centerY - newH / 2.0;
                                    vsBounds[2] = centerY + newH / 2.0;
                                }
                            }
                        } catch (Exception imgErr) {
                            // PNG 읽기 실패 시 무시
                        }
                        vsTransform = new double[]{1, 0, 0, 1, 0, 0};
                    }
                }

                // TextPath는 그룹 소속이면 원래 z-order (A/B/C 등과 정확한 stacking)
                int zOrder = (vs.parentGroupId() != null) ? vs.zOrder() : vs.zOrder() + 10000;
                if (vs.parentGroupId() != null) {
                    replacedGroupIds.add(vs.parentGroupId());
                }
                vsIt.remove();

                IDMLImageFrame syn = new IDMLImageFrame();
                syn.selfId(vs.selfId() + "_rendered");
                syn.geometricBounds(vsBounds);
                syn.itemTransform(vsTransform);
                syn.zOrder(zOrder);
                syn.fromGroup(true);  // IN_FRONT_OF_TEXT로 배치
                if (vs.parentGroupId() != null) {
                    syn.parentGroupId(vs.parentGroupId());
                }
                syn.linkResourceURI(pngFile.getAbsolutePath());
                syn.linkStoredState("Normal");
                syn.linkResourceFormat("PNG");
                spread.addImageFrame(syn);
                replacedCount++;

                System.out.println("[RenderedTextPath] " + vs.selfId()
                        + " → " + rendered.file()
                        + " z=" + zOrder
                        + (vs.parentGroupId() != null ? " group=" + vs.parentGroupId() : ""));
            }

            // 4단계: 교체된 그룹의 형제 VectorShape 제거
            // (같은 그룹의 다른 폴리곤 — 예: 스트로크 경로 — 이 사각형 박스로 남는 것을 방지)
            if (!replacedGroupIds.isEmpty()) {
                java.util.Iterator<IDMLVectorShape> sibIt = spread.vectorShapes().iterator();
                while (sibIt.hasNext()) {
                    IDMLVectorShape sib = sibIt.next();
                    if (sib.parentGroupId() != null && replacedGroupIds.contains(sib.parentGroupId())) {
                        sibIt.remove();
                        System.out.println("[RenderedTextPath] 형제 " + sib.selfId()
                                + " 제거 (group=" + sib.parentGroupId() + ")");
                    }
                }
                // 같은 그룹의 TextFrame 중 렌더링된 것만 제거
                // (콘텐츠 텍스트 프레임은 유지)
                java.util.Iterator<IDMLTextFrame> tfSibIt = spread.textFrames().iterator();
                while (tfSibIt.hasNext()) {
                    IDMLTextFrame tf = tfSibIt.next();
                    if (tf.parentGroupId() != null && replacedGroupIds.contains(tf.parentGroupId())) {
                        RenderedGroup tfRendered = resolvedData.getRenderedTextFrameByIdmlId(tf.selfId());
                        if (tfRendered != null) {
                            tfSibIt.remove();
                            System.out.println("[RenderedTextPath] 형제 TF " + tf.selfId()
                                    + " 제거 (group=" + tf.parentGroupId() + ")");
                        }
                    }
                }
            }
        }

        if (replacedCount > 0) {
            System.out.println("[RenderedTextFrame] " + replacedCount + "개 텍스트 프레임을 이미지로 교체");
        }
    }

    /**
     * AST의 플로팅 텍스트 프레임 블록 중 렌더링 대상을 이미지(ASTFigure)로 교체한다.
     * AST 좌표(페이지 상대, HWPUNIT)를 직접 사용하므로 좌표 변환 오류가 없다.
     */
    static void replaceFloatingRenderedTextFrames(
            ASTDocument astDoc, ResolvedData resolvedData, ConvertOptions options) {
        String resolvedDir = getResolvedDir(options);
        if (resolvedDir == null) return;

        int replaced = 0;
        // 배지 그룹 중복 교체 방지 + 최적 자식 선택:
        // 같은 배지 그룹의 자식 TextFrame이 여러 개일 때 가장 넓은 자식의 위치를 기준으로 교체
        java.util.Set<String> replacedBadgeFiles = new java.util.HashSet<>();
        // badge file → 가장 넓은 자식 TextFrame (위치 기준으로 사용)
        java.util.Map<String, kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock> badgeBestChild =
                new java.util.HashMap<>();
        // 1차 패스: 배지 그룹별 가장 넓은 자식 TextFrame 찾기
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection sec : astDoc.sections()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : sec.blocks()) {
                if (!(blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock))
                    continue;
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock tfb =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) blk;
                if (tfb.sourceId() == null) continue;
                RenderedGroup rg = resolvedData.getBadgeGroupByChildTextFrameIdmlId(tfb.sourceId());
                if (rg == null) continue;
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock prev = badgeBestChild.get(rg.file());
                if (prev == null || tfb.width() * tfb.height() > prev.width() * prev.height()) {
                    badgeBestChild.put(rg.file(), tfb);
                }
            }
        }

        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection sec : astDoc.sections()) {
            java.util.ListIterator<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock> it =
                    sec.blocks().listIterator();
            while (it.hasNext()) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk = it.next();
                if (!(blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock))
                    continue;

                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock tfb =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) blk;
                if (tfb.sourceId() == null) continue;

                RenderedGroup rendered = resolvedData.getRenderedTextFrameByIdmlId(tfb.sourceId());
                boolean isBadgeFallback = false;
                if (rendered == null) {
                    rendered = resolvedData.getBadgeGroupByChildTextFrameIdmlId(tfb.sourceId());
                    isBadgeFallback = (rendered != null);
                }
                if (rendered == null) continue;

                // 같은 배지 그룹에서 이미 교체한 경우 중복 제거
                if (isBadgeFallback && replacedBadgeFiles.contains(rendered.file())) {
                    it.remove();
                    continue;
                }

                // 배지 그룹: 가장 넓은 자식의 위치를 기준으로 사용
                if (isBadgeFallback) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock best =
                            badgeBestChild.get(rendered.file());
                    if (best != null && !best.sourceId().equals(tfb.sourceId())) {
                        // 현재 TextFrame이 최적 자식이 아니면 제거 (나중에 최적 자식 처리 시 교체)
                        it.remove();
                        continue;
                    }
                }

                java.io.File pngFile = new java.io.File(resolvedDir, rendered.file());
                if (!pngFile.exists()) continue;

                try {
                    byte[] data = java.nio.file.Files.readAllBytes(pngFile.toPath());
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                    if (img == null) continue;

                    // 크기: bounds 폭 + PNG 비율로 높이 산출
                    // InDesign visibleBounds의 높이는 PNG 렌더링 영역보다 작아
                    // bounds 높이를 그대로 쓰면 글자가 납작해지므로 PNG 비율로 보정
                    // 위치: AST 중심점 기반 (pageRelativeCenter, 페이지 좌표 항상 정확)
                    long figX, figY, figW, figH;
                    double[] bounds = rendered.bounds();
                    if (bounds != null && bounds.length == 4) {
                        double rW = bounds[3] - bounds[1];
                        figW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                .pointsToHwpunits(rW);
                        // PNG 비율로 높이 산출 (visibleBounds 높이 대신)
                        if (img.getWidth() > 0) {
                            figH = Math.round(figW * ((double) img.getHeight() / img.getWidth()));
                        } else {
                            double rH = bounds[2] - bounds[0];
                            figH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                    .pointsToHwpunits(rH);
                        }
                        // AST center = pageRelativeCenter (항상 정확)
                        long centerX = tfb.x() + tfb.width() / 2;
                        long centerY = tfb.y() + tfb.height() / 2;
                        figX = centerX - figW / 2;
                        figY = centerY - figH / 2;
                    } else {
                        // bounds 없으면 AST + PNG 비율 폴백
                        figX = tfb.x();
                        figW = tfb.width();
                        if (img.getWidth() > 0) {
                            figH = Math.round(figW * ((double) img.getHeight() / img.getWidth()));
                        } else {
                            figH = tfb.height();
                        }
                        figY = tfb.y() - (figH - tfb.height()) / 2;
                    }

                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure fig =
                            new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure();
                    fig.x(figX);
                    fig.y(figY);
                    fig.width(figW);
                    fig.height(figH);
                    fig.zOrder(tfb.zOrder());
                    fig.imageData(data);
                    fig.imageFormat("png");
                    fig.pixelWidth(img.getWidth());
                    fig.pixelHeight(img.getHeight());
                    fig.fromGroup(true); // IN_FRONT_OF_TEXT
                    fig.imagePath(pngFile.getAbsolutePath());
                    fig.sourceId(tfb.sourceId());

                    it.set(fig);
                    replaced++;
                    if (isBadgeFallback) {
                        replacedBadgeFiles.add(rendered.file());
                    }
                    System.out.println("[FloatingRendered] " + tfb.sourceId()
                            + " → " + rendered.file()
                            + " astPos=(" + tfb.x() + "," + tfb.y() + ")"
                            + " astSize=(" + tfb.width() + "," + tfb.height() + ")"
                            + " figPos=(" + figX + "," + figY + ")"
                            + " figSize=(" + figW + "," + figH + ")"
                            + (Math.abs(tfb.rotationAngle()) > 0.5 ? " rot=" + tfb.rotationAngle() : ""));
                } catch (Exception e) {
                    System.err.println("[FloatingRendered] " + tfb.sourceId()
                            + " 교체 실패: " + e.getMessage());
                }
            }
        }
        if (replaced > 0) {
            System.out.println("[FloatingRendered] " + replaced + "개 플로팅 텍스트 프레임을 이미지로 교체");
        }
    }

    /**
     * AST의 인라인 앵커 텍스트 프레임 중 렌더링 대상을 이미지로 교체한다.
     * spread 레벨이 아닌 스토리 내부에 앵커된 TextFrame (e.g. "e.g." 라벨)은
     * replaceRenderedTextFrames()에서 처리할 수 없으므로 AST 단계에서 교체한다.
     */
    static java.util.Set<String> replaceInlineRenderedTextFrames(
            ASTDocument astDoc, ResolvedData resolvedData, ConvertOptions options) {
        java.util.Set<String> replacedTexts = new java.util.HashSet<>();
        String resolvedDir = getResolvedDir(options);
        if (resolvedDir == null) return replacedTexts;

        // 동일 텍스트 내용의 중복 매칭 추적 (첫 매칭만 이미지 교체, 나머지 제거)
        // InDesign에서 같은 장식 텍스트가 여러 곳에 중복 배치될 수 있음
        int replaced = 0;
        int removed = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection sec : astDoc.sections()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : sec.blocks()) {
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock tfb =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) blk;
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph para : tfb.paragraphs()) {
                        int[] result = replaceInlineRenderedInParagraph(para, resolvedData, resolvedDir, replacedTexts);
                        replaced += result[0];
                        removed += result[1];
                    }
                }
                // 테이블 셀 내부: 배지 자식 인라인 TextFrame은 이미지로 교체,
                // 직접 렌더 매치(콘텐츠 텍스트)는 글상자로 유지
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable tbl =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) blk;
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableRow row : tbl.rows()) {
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableCell cell : row.cells()) {
                            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph para : cell.paragraphs()) {
                                int[] result = replaceInlineRenderedInParagraph_badgeOnly(
                                        para, resolvedData, resolvedDir, replacedTexts);
                                replaced += result[0];
                                removed += result[1];
                            }
                        }
                    }
                }
            }
        }
        if (replaced > 0 || removed > 0) {
            System.out.println("[InlineRendered] " + replaced + "개 이미지 교체, " + removed + "개 중복 제거");
        }
        return replacedTexts;
    }

    /**
     * 인라인 이미지로 교체된 텍스트와 동일한 내용을 가진 플로팅 텍스트 프레임 블록을 제거한다.
     * InDesign에서 앵커된 TextFrame의 스토리가 독립 텍스트 프레임으로도 처리되어
     * 동일 텍스트가 중복 출현하는 문제를 해결한다.
     */
    static void removeFloatingDuplicates(ASTDocument astDoc, java.util.Set<String> replacedTexts,
                                         ResolvedData resolvedData) {
        int removed = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection sec : astDoc.sections()) {
            java.util.Iterator<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock> it = sec.blocks().iterator();
            while (it.hasNext()) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk = it.next();
                if (!(blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock)) continue;
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock tfb =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) blk;
                // 배지 그룹 자식 TextFrame은 제거하지 않음 (배지 이미지로 교체 대상)
                if (resolvedData != null && tfb.sourceId() != null
                        && resolvedData.getBadgeGroupByChildTextFrameIdmlId(tfb.sourceId()) != null) {
                    continue;
                }
                // 텍스트 내용 추출
                StringBuilder sb = new StringBuilder();
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph p : tfb.paragraphs()) {
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem item : p.items()) {
                        if (item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) {
                            sb.append(((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) item).text());
                        }
                    }
                }
                String text = sb.toString().trim();
                // 3글자 이하 텍스트는 오매칭 위험이 높으므로 중복 제거 건너뛰기
                if (text.length() > 3 && replacedTexts.contains(text)) {
                    it.remove();
                    removed++;
                    System.out.println("[FloatingDup] " + tfb.sourceId() + " → 제거 (\"" + text + "\")");
                }
            }
        }
        if (removed > 0) {
            System.out.println("[FloatingDup] " + removed + "개 플로팅 중복 블록 제거");
        }
    }

    private static int[] replaceInlineRenderedInParagraph(
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph para,
            ResolvedData resolvedData, String resolvedDir,
            java.util.Set<String> replacedTexts) {
        int replaced = 0;
        int removed = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem item : para.items()) {
            if (item.itemType() != kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem.ItemType.INLINE_OBJECT)
                continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject obj =
                    (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject) item;
            if (obj.kind() != kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME)
                continue;
            if (obj.sourceId() == null) continue;

            RenderedGroup rendered = resolvedData.getRenderedTextFrameByIdmlId(obj.sourceId());
            // 배지 자식 TextFrame → 배지 그룹 렌더 PNG 폴백
            if (rendered == null) {
                rendered = resolvedData.getBadgeGroupByChildTextFrameIdmlId(obj.sourceId());
            }
            if (rendered == null) continue;

            // 텍스트 내용 추출 (중복 감지용)
            String textContent = extractInlineTextContent(obj);

            // PNG 파일 읽기
            java.io.File pngFile = new java.io.File(resolvedDir, rendered.file());
            if (!pngFile.exists()) continue;
            try {
                byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null) continue;

                // 이미지로 교체: kind, imageData, 크기 설정
                obj.kind(kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject.ObjectKind.IMAGE);
                obj.imageData(imageData);
                obj.imageFormat("png");
                obj.pixelWidth(img.getWidth());
                obj.pixelHeight(img.getHeight());
                // paragraphs 제거 (이미지이므로 불필요)
                obj.paragraphs(null);
                if (obj.inlineTables() != null) obj.inlineTables().clear();

                if (textContent != null) replacedTexts.add(textContent);
                replaced++;
                System.out.println("[InlineRendered] " + obj.sourceId() + " → " + rendered.file());
            } catch (Exception e) {
                // 읽기 실패 — 텍스트 프레임 유지
            }
        }
        return new int[]{replaced, removed};
    }

    /**
     * 테이블 셀 전용: 배지 자식 인라인 TextFrame만 이미지로 교체.
     * 직접 렌더 매치(콘텐츠 텍스트)는 건너뛰어 글상자로 유지.
     */
    private static int[] replaceInlineRenderedInParagraph_badgeOnly(
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph para,
            ResolvedData resolvedData, String resolvedDir,
            java.util.Set<String> replacedTexts) {
        int replaced = 0;
        int removed = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem item : para.items()) {
            if (item.itemType() != kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem.ItemType.INLINE_OBJECT)
                continue;
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject obj =
                    (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject) item;
            if (obj.kind() != kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME)
                continue;
            if (obj.sourceId() == null) continue;

            // 직접 렌더 매치는 건너뛰기 (테이블 셀 콘텐츠 텍스트 유지)
            if (resolvedData.getRenderedTextFrameByIdmlId(obj.sourceId()) != null) continue;

            // 배지 자식만 교체
            RenderedGroup rendered = resolvedData.getBadgeGroupByChildTextFrameIdmlId(obj.sourceId());
            if (rendered == null) continue;

            String textContent = extractInlineTextContent(obj);
            java.io.File pngFile = new java.io.File(resolvedDir, rendered.file());
            if (!pngFile.exists()) continue;
            try {
                byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null) continue;

                obj.kind(kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject.ObjectKind.IMAGE);
                obj.imageData(imageData);
                obj.imageFormat("png");
                obj.pixelWidth(img.getWidth());
                obj.pixelHeight(img.getHeight());
                obj.paragraphs(null);
                if (obj.inlineTables() != null) obj.inlineTables().clear();

                if (textContent != null) replacedTexts.add(textContent);
                replaced++;
                System.out.println("[InlineRendered-Badge] " + obj.sourceId() + " → " + rendered.file());
            } catch (Exception e) {
                // 읽기 실패 — 텍스트 프레임 유지
            }
        }
        return new int[]{replaced, removed};
    }

    /** INLINE_TEXT_FRAME의 텍스트 내용을 추출한다 (중복 감지용). */
    private static String extractInlineTextContent(
            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject obj) {
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph p : obj.paragraphs()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem it : p.items()) {
                if (it instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) {
                    sb.append(((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) it).text());
                }
            }
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? null : text;
    }


    private static void collectInlineSourceIds(
            java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph> paragraphs,
            java.util.Set<String> usedSourceIds) {
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph para : paragraphs) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem item : para.items()) {
                if (item.itemType() == kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem.ItemType.INLINE_OBJECT) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject obj =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject) item;
                    if (obj.sourceId() != null) {
                        usedSourceIds.add(obj.sourceId());
                    }
                }
            }
        }
    }

    /**
     * VectorShape로 등록되지 않은 렌더 그래픽 프레임을 AST에 주입한다.
     * 깊이 중첩된 IDML 구조(TextFrame 내부 Oval 등)는 VectorShape 풀에 등록되지 않아
     * ASTFigureBuilder에서 처리되지 못한다. 이들 중 렌더링된 PNG가 있으면 직접 주입한다.
     */
    static void injectOrphanRenderedGraphics(
            ASTDocument astDoc, ResolvedData resolvedData, ConvertOptions options) {
        String resolvedDir = getResolvedDir(options);
        if (resolvedDir == null) return;

        // 이미 AST에 사용된 rendered graphic sourceId 수집
        java.util.Set<String> usedSourceIds = new java.util.HashSet<>();
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection sec : astDoc.sections()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : sec.blocks()) {
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure
                        && blk.sourceId() != null) {
                    usedSourceIds.add(blk.sourceId());
                }
                // TextFrameBlock 내 인라인 객체 sourceId 수집
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock tfb =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) blk;
                    collectInlineSourceIds(tfb.paragraphs(), usedSourceIds);
                }
                // 테이블 셀 내 인라인 객체 sourceId 수집
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable tbl =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) blk;
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableRow row : tbl.rows()) {
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableCell cell : row.cells()) {
                            collectInlineSourceIds(cell.paragraphs(), usedSourceIds);
                        }
                    }
                }
            }
        }

        // 페이지 bounds 캐시 (pageIndex → resolvedPage)
        List<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection> sections = astDoc.sections();

        // 페이지별 기존 ASTFigure bounds 수집 (orphan 중복 주입 방지용)
        Map<Integer, java.util.List<long[]>> pageFigureBounds = new java.util.HashMap<>();
        for (int si = 0; si < sections.size(); si++) {
            java.util.List<long[]> bounds = new java.util.ArrayList<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : sections.get(si).blocks()) {
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure fig =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) blk;
                    bounds.add(new long[]{fig.x(), fig.y(), fig.x() + fig.width(), fig.y() + fig.height()});
                }
            }
            pageFigureBounds.put(si, bounds);
        }

        int injected = 0;
        for (RenderedGroup rg : resolvedData.allRenderedGraphicFrames()) {
            if (rg.file() == null || rg.bounds() == null) continue;

            // DOM ID → IDML hex ID 변환하여 이미 사용된 것인지 확인
            String idmlHexId = "u" + Integer.toHexString(rg.id());
            if (usedSourceIds.contains(idmlHexId)) continue;

            // 배지 그룹 자식 도형 건너뜀 (배지 통째 렌더링에서 처리)
            if (resolvedData.isShapeInBadgeGroup(idmlHexId)) continue;

            // 배경 프레임 필터: 페이지의 80% 이상이면 건너뜀
            int pageIdx = rg.pageIndex();
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage resolvedPage =
                    resolvedData.getPage(pageIdx);
            if (resolvedPage != null && resolvedPage.bounds() != null) {
                double[] rpb = resolvedPage.bounds();
                double pageW = rpb[3] - rpb[1];
                double pageH = rpb[2] - rpb[0];
                double[] rb = rg.bounds();
                double rW = rb[3] - rb[1];
                double rH = rb[2] - rb[0];
                if (rW > pageW * 0.8 && rH > pageH * 0.8) continue;
            }

            // PNG 로드
            java.io.File pngFile = new java.io.File(resolvedDir, rg.file());
            if (!pngFile.exists()) continue;

            try {
                byte[] data = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null) continue;

                // 위치/크기 계산 (resolved bounds, 페이지 상대 좌표)
                double[] bounds = rg.bounds();
                long figX, figY, figW, figH;
                if (resolvedPage != null && resolvedPage.bounds() != null) {
                    double[] rel = resolvedPage.toPageRelative(bounds);
                    figX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(rel[0]);
                    figY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(rel[1]);
                    figW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[3] - bounds[1]);
                    figH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[2] - bounds[0]);
                } else {
                    figX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[1]);
                    figY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[0]);
                    figW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[3] - bounds[1]);
                    figH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[2] - bounds[0]);
                }
                // PNG 비율로 높이 보정
                if (img.getWidth() > 0) {
                    figH = Math.round(figW * ((double) img.getHeight() / img.getWidth()));
                }

                // 기존 ASTFigure에 완전히 포함되는 도형 건너뜀 (인라인 자식 도형 중복 방지)
                // 단, 면적비가 매우 작은 경우 (< 5%) 는 독립 도형으로 간주하여 통과
                boolean containedInExisting = false;
                long figArea = figW * figH;
                java.util.List<long[]> existingBounds = pageFigureBounds.get(pageIdx);
                if (existingBounds != null) {
                    for (long[] eb : existingBounds) {
                        if (figX >= eb[0] && figY >= eb[1]
                                && figX + figW <= eb[2] && figY + figH <= eb[3]) {
                            long containerArea = (eb[2] - eb[0]) * (eb[3] - eb[1]);
                            if (containerArea > 0 && (double) figArea / containerArea > 0.05) {
                                containedInExisting = true;
                                break;
                            }
                        }
                    }
                }
                if (containedInExisting) continue;

                // 페이지 밖 음수 좌표 → crop fraction 설정
                // HwpxImageBuilder가 hasCrop일 때 x<0, y<0을 자동 클램핑하므로
                // 원래 크기를 유지하고 fraction만 설정
                double cropLeft = 0, cropTop = 0;
                if (figX < 0) {
                    cropLeft = (double) (-figX) / figW;
                }
                if (figY < 0) {
                    cropTop = (double) (-figY) / figH;
                }

                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure fig =
                        new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure();
                fig.x(figX);
                fig.y(figY);
                fig.width(figW);
                fig.height(figH);
                fig.imageData(data);
                fig.imageFormat("png");
                fig.pixelWidth(img.getWidth());
                fig.pixelHeight(img.getHeight());
                fig.sourceId(idmlHexId);
                fig.fromGroup(true);  // inFrontBlocks로 분류되도록
                if (cropLeft > 0 || cropTop > 0) {
                    fig.cropLeftFraction(cropLeft);
                    fig.cropTopFraction(cropTop);
                }

                // 겹치는 도형의 z-order 탐색
                // 자기보다 작은 겹치는 도형이 있으면 그 아래에, 없으면 겹치는 최대 z-order 위에
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection section = sections.get(pageIdx);
                int minSmallerZ = Integer.MAX_VALUE;  // 자기보다 작은 겹치는 도형의 최소 z-order
                int maxOverlapZ = 0;                   // 겹치는 모든 도형의 최대 z-order
                boolean hasSmallerOverlap = false;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : section.blocks()) {
                    if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) {
                        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure existing =
                                (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) blk;
                        long ew = existing.width(), eh = existing.height();
                        // 바운딩 박스 교차 확인
                        long ox1 = Math.max(existing.x(), figX);
                        long oy1 = Math.max(existing.y(), figY);
                        long ox2 = Math.min(existing.x() + ew, figX + figW);
                        long oy2 = Math.min(existing.y() + eh, figY + figH);
                        if (ox2 > ox1 && oy2 > oy1) {
                            if (existing.zOrder() > maxOverlapZ) {
                                maxOverlapZ = existing.zOrder();
                            }
                            long existArea = ew * eh;
                            if (existArea < figArea) {
                                hasSmallerOverlap = true;
                                if (existing.zOrder() < minSmallerZ) {
                                    minSmallerZ = existing.zOrder();
                                }
                            }
                        }
                    }
                }
                // 자기보다 작은 도형이 겹치면 그 아래에 (큰 도형 = 배경)
                // 아니면 겹치는 최대 위에
                if (hasSmallerOverlap) {
                    fig.zOrder(Math.max(0, minSmallerZ - 1));
                } else {
                    fig.zOrder(maxOverlapZ + 1);
                }

                section.addBlock(fig);
                injected++;
                System.out.println("[OrphanGraphic] " + idmlHexId + " (DOM " + rg.id()
                        + ") → page " + (pageIdx + 1) + " " + rg.file()
                        + " pos=(" + figX + "," + figY + ") size=(" + figW + "x" + figH + ")"
                        + " bounds=[" + bounds[0] + "," + bounds[1] + "," + bounds[2] + "," + bounds[3] + "]"
                        + " px=" + img.getWidth() + "x" + img.getHeight());
            } catch (Exception e) {
                System.err.println("[OrphanGraphic] Failed: " + rg.file() + " — " + e.getMessage());
            }
        }
        if (injected > 0) {
            System.out.println("[OrphanGraphic] " + injected + "개 렌더 그래픽 프레임 주입 완료");
        }

        // === 렌더링된 이미지 프레임 orphan 주입 ===
        int imgInjected = 0;
        for (RenderedGroup rg : resolvedData.allRenderedImageFrames()) {
            if (rg.file() == null || rg.bounds() == null) continue;

            String idmlHexId = "u" + Integer.toHexString(rg.id());
            if (usedSourceIds.contains(idmlHexId)) continue;

            // 그룹 렌더의 자식 ID도 usedSourceIds에 있는지 확인
            if (rg.childImageIds() != null) {
                boolean anyChildUsed = false;
                for (int childId : rg.childImageIds()) {
                    String childHex = "u" + Integer.toHexString(childId);
                    if (usedSourceIds.contains(childHex)) {
                        anyChildUsed = true;
                        break;
                    }
                }
                if (anyChildUsed) continue;
            }

            int pageIdx = rg.pageIndex();
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            // 배경 프레임 필터: 페이지의 80% 이상이면 건너뜀
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage resolvedPage =
                    resolvedData.getPage(pageIdx);
            if (resolvedPage != null && resolvedPage.bounds() != null) {
                double[] rpb = resolvedPage.bounds();
                double pageW = rpb[3] - rpb[1];
                double pageH = rpb[2] - rpb[0];
                double[] rb = rg.bounds();
                double rW = rb[3] - rb[1];
                double rH = rb[2] - rb[0];
                if (rW > pageW * 0.8 && rH > pageH * 0.8) continue;
            }

            java.io.File pngFile = new java.io.File(resolvedDir, rg.file());
            if (!pngFile.exists()) continue;

            try {
                byte[] data = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null) continue;

                double[] bounds = rg.bounds();
                long figX, figY, figW, figH;
                if (resolvedPage != null && resolvedPage.bounds() != null) {
                    double[] rel = resolvedPage.toPageRelative(bounds);
                    figX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(rel[0]);
                    figY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(rel[1]);
                    figW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[3] - bounds[1]);
                    figH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[2] - bounds[0]);
                } else {
                    figX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[1]);
                    figY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[0]);
                    figW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[3] - bounds[1]);
                    figH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                            .pointsToHwpunits(bounds[2] - bounds[0]);
                }
                if (img.getWidth() > 0) {
                    figH = Math.round(figW * ((double) img.getHeight() / img.getWidth()));
                }

                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection section = sections.get(pageIdx);

                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure fig =
                        new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure();
                fig.x(figX);
                fig.y(figY);
                fig.width(figW);
                fig.height(figH);
                fig.imageData(data);
                fig.imageFormat("png");
                fig.pixelWidth(img.getWidth());
                fig.pixelHeight(img.getHeight());
                fig.sourceId(idmlHexId);
                fig.fromGroup(true);

                // z-order: 겹치는 도형 기준
                long figArea = figW * figH;
                int minSmallerZ = Integer.MAX_VALUE;
                int maxOverlapZ = 0;
                boolean hasSmallerOverlap = false;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : section.blocks()) {
                    if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) {
                        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure existing =
                                (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) blk;
                        long ew = existing.width(), eh = existing.height();
                        long ox1 = Math.max(existing.x(), figX);
                        long oy1 = Math.max(existing.y(), figY);
                        long ox2 = Math.min(existing.x() + ew, figX + figW);
                        long oy2 = Math.min(existing.y() + eh, figY + figH);
                        if (ox2 > ox1 && oy2 > oy1) {
                            if (existing.zOrder() > maxOverlapZ) {
                                maxOverlapZ = existing.zOrder();
                            }
                            long existArea = ew * eh;
                            if (existArea < figArea) {
                                hasSmallerOverlap = true;
                                if (existing.zOrder() < minSmallerZ) {
                                    minSmallerZ = existing.zOrder();
                                }
                            }
                        }
                    }
                }
                if (hasSmallerOverlap) {
                    fig.zOrder(Math.max(0, minSmallerZ - 1));
                } else {
                    fig.zOrder(maxOverlapZ + 1);
                }

                section.addBlock(fig);
                imgInjected++;
                System.out.println("[OrphanImage] " + idmlHexId + " (DOM " + rg.id()
                        + ") → page " + (pageIdx + 1) + " " + rg.file()
                        + " pos=(" + figX + "," + figY + ") size=(" + figW + "x" + figH + ")"
                        + " px=" + img.getWidth() + "x" + img.getHeight());
            } catch (Exception e) {
                System.err.println("[OrphanImage] Failed: " + rg.file() + " — " + e.getMessage());
            }
        }
        if (imgInjected > 0) {
            System.out.println("[OrphanImage] " + imgInjected + "개 렌더 이미지 프레임 주입 완료");
        }
    }

    /** 문서 전체 페이지 인덱스(0-based)로 IDMLPage를 찾는다. */
    private static IDMLPage findPageByIndex(IDMLDocument doc, int pageIndex) {
        int idx = 0;
        for (IDMLSpread sp : doc.spreads()) {
            for (IDMLPage pg : sp.pages()) {
                if (idx == pageIndex) return pg;
                idx++;
            }
        }
        return null;
    }

    /** resolved.json의 부모 디렉토리를 반환한다. */
    static String getResolvedDir(ConvertOptions options) {
        if (options.resolvedJsonPath() == null) return null;
        java.io.File resolvedFile = new java.io.File(options.resolvedJsonPath());
        if (!resolvedFile.isAbsolute()) {
            resolvedFile = resolvedFile.getAbsoluteFile();
        }
        return resolvedFile.getParent();
    }

    /**
     * CLI 메인 메서드.
     *
     * 사용법: java IDMLToHwpxConverter input.idml output.hwpx [startPage] [endPage]
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: IDMLToHwpxConverter <input.idml> <output.hwpx> [startPage] [endPage]");
            System.err.println("  startPage, endPage: 1-based page numbers (0 = no limit)");
            System.exit(1);
            return;
        }

        String idmlPath = args[0];
        String hwpxPath = args[1];
        int startPage = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int endPage = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        ConvertOptions options = ConvertOptions.defaults()
                .startPage(startPage)
                .endPage(endPage);

        try {
            System.out.println("Converting: " + idmlPath + " -> " + hwpxPath);
            if (startPage > 0 || endPage > 0) {
                System.out.println("Page range: " + (startPage > 0 ? startPage : "start")
                        + " ~ " + (endPage > 0 ? endPage : "end"));
            }

            ConvertResult result = convert(idmlPath, hwpxPath, options);

            System.out.println("Conversion completed: " + result.summary());
            if (result.hasWarnings()) {
                System.out.println("Warnings (" + result.warnings().size() + "):");
                for (String warning : result.summarizedWarnings()) {
                    System.out.println("  - " + warning);
                }
            }
        } catch (ConvertException e) {
            System.err.println("Conversion failed [" + e.phase() + "]: " + e.getMessage());
            if (e.getCause() != null) {
                e.getCause().printStackTrace(System.err);
            }
            System.exit(2);
        }
    }
}
