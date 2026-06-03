package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.legacy.IDMLNormalizer;
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

    static {
        // SPEC-031: DSL 규칙 엔진 초기화 — 클래스 로딩 시 1회 실행 (thread-safe)
        kr.dogfoot.hwpxlib.tool.idmlconverter.config.ConversionRulesKt.loadConversionRules();
    }

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
                    resolvedData.basePath(getResolvedDir(options));
                    applyCropManifest(resolvedData.basePath());
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

            // Phase 1.6b: 배지 그룹 인덱스 빌드 + ExtendScript 렌더 ID 통합 인덱스 빌드
            if (resolvedData != null) {
                resolvedData.buildBadgeGroupIndex();
                resolvedData.buildRenderedIdSet();
            }

            // Phase 1.7a: 렌더링된 텍스트 프레임 교체 (짧은 텍스트 → 이미지)
            if (resolvedData != null && resolvedData.renderedTextFrameCount() > 0) {
                replaceRenderedTextFrames(idmlDoc, resolvedData, options);
            }

            // Phase 2: AST 빌드
            reporter.reportProgress(5, 100, "AST 빌드 중...");
            ASTDocument astDoc;
            // IDML-Free 파이프라인: resolved.json에 renderedFloatingItems가 있으면 새 빌더 사용
            if (resolvedData != null && !resolvedData.allRenderedFloatingItems().isEmpty()) {
                astDoc = new kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ResolvedToASTBuilder(resolvedData, idmlDoc.tempDir(), options.config().pngExportResolution())
                        .debugAst(options.debugAst())
                        .tableQualityGate(options.config().tableQualityGate())
                        .build();
            } else {
                // 레거시: IDML 기반 4단계 정규화
                astDoc = IDMLNormalizer.normalize(idmlDoc, options, sourceFileName, resolvedData, reporter);
            }

            // Phase 2.5: Resolved 텍스트/스타일 보강 (레거시 파이프라인만)
            boolean isNewPipeline = resolvedData != null && !resolvedData.allRenderedFloatingItems().isEmpty();
            if (resolvedData != null && !isNewPipeline) {
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

            // Phase 2.6: Resolved 오버레이 좌표 보강 (레거시만)
            if (resolvedData != null && !isNewPipeline) {
                try {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedOverlayEnricher.enrich(
                            astDoc, resolvedData);
                } catch (Exception e) {
                    System.err.println("Warning: resolved overlay 보강 실패 (무시): " + e.getMessage());
                    earlyWarnings.add("[Resolved] overlay 보강 실패: " + e.getMessage());
                }
            }

            // Phase 2.7~2.8: 레거시 후처리 (새 파이프라인에서는 건너뜀)
            java.util.Set<String> inlineReplacedTexts = java.util.Collections.emptySet();
            if (!isNewPipeline) {
                // Phase 2.7: 플로팅 이미지 → 인라인 머지 (textWrap 자리차지)
                kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.legacy.FloatingImageMerger.merge(astDoc);

                // Phase 2.8: 인라인 앵커 텍스트 프레임 → 렌더 이미지 교체
                if (resolvedData != null && resolvedData.renderedTextFrameCount() > 0) {
                    inlineReplacedTexts = replaceInlineRenderedTextFrames(astDoc, resolvedData, options);
                }

                // 테이블 셀 인라인 텍스트와 동일한 플로팅 텍스트 프레임 블록 제거
                if (!inlineReplacedTexts.isEmpty()) {
                    removeFloatingDuplicates(astDoc, inlineReplacedTexts, resolvedData);
                }
            }

            // Phase 2.9~2.10: 레거시 후처리 (새 파이프라인에서는 건너뜀)
            if (!isNewPipeline) {
                // Phase 2.9: 플로팅 렌더 텍스트 프레임 → 이미지 교체
                if (resolvedData != null && resolvedData.renderedTextFrameCount() > 0) {
                    replaceFloatingRenderedTextFrames(astDoc, resolvedData, options);
                }
                // Phase 2.10: orphan 렌더 그래픽 주입
                if (resolvedData != null) {
                    injectOrphanRenderedGraphics(astDoc, resolvedData, options);
                }
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

            // Phase 2.8: 3계층 폰트 매퍼 초기화
            // --font-map 가 명시되지 않아도 jar/CWD 인근의 기본 font-mapping.json 을 자동 로드 →
            // FontStyle("30" 등 가변폰트 weight) 가 정적 fallback 에서 무시되는 문제 회피.
            FontMapper fontMapper = null;
            ConversionConfig config = options.config();
            String autoFontMapPath = options.fontMapPath();
            if (autoFontMapPath == null) {
                String[] candidates = {
                        System.getProperty("user.dir") + "/font-mapping.json",
                        new java.io.File(IDMLToHwpxConverter.class.getProtectionDomain()
                                .getCodeSource().getLocation().getPath()).getParent()
                                + "/../font-mapping.json"
                };
                for (String p : candidates) {
                    if (new java.io.File(p).exists()) { autoFontMapPath = p; break; }
                }
            }
            if (autoFontMapPath != null || !config.fontMappings().isEmpty()) {
                fontMapper = new FontMapper();
                // config 기반 초기화 (기본 폰트, 매핑, 메트릭)
                fontMapper.loadFromConfig(config);
                // font-mapping.json 로드 (명시 또는 자동 발견)
                if (autoFontMapPath != null) {
                    fontMapper.loadFontMapping(autoFontMapPath);
                }
                if (resolvedData != null) {
                    fontMapper.setIdmlMetrics(resolvedData.fontMetrics(), resolvedData.scaleFactor());
                }
            }

            // Phase 3: AST -> HWPX 직접 변환
            ConvertResult result = ASTToHwpxConverter.convert(astDoc, reporter, null, fontMapper, config);

            // 초기 단계 경고 + AST 정규화 경고를 결과에 병합
            for (String w : earlyWarnings) { result.addWarning(w); }
            for (String w : astDoc.warnings()) { result.addWarning(w); }

            // Phase 3.5: 시멘틱 레이어 추출 (SPEC-018 M3, 옵션)
            if (options.extractSemantics()) {
                try {
                    reporter.reportProgress(93, 100, "시멘틱 레이어 추출 중...");
                    extractSemanticLayer(astDoc, hwpxPath, options, result);
                } catch (Exception e) {
                    String msg = "[Semantic] 시멘틱 추출 실패 (HWPX 변환은 계속 진행): " + e.getMessage();
                    System.err.println(msg);
                    result.addWarning(msg);
                }
            }

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
            ConvertResult result = ASTToHwpxConverter.convert(astDoc);
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
                    System.err.println("[RenderedTextFrame] " + tf.selfId()
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

                // 배지 그룹 자식이면 TextPath 교체 로직 스킵 (배지 통째 렌더링에서 처리)
                if (rendered.isBadgeGroupChild() || rendered.isBadgeGroup()) continue;

                if (vs.isInline()) {
                    vsIt.remove();
                    System.err.println("[RenderedInlineVS] " + vs.selfId() + " → spread에서 제거 (AST에서 처리)");
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

                System.err.println("[RenderedTextPath] " + vs.selfId()
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
                        System.err.println("[RenderedTextPath] 형제 " + sib.selfId()
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
                            System.err.println("[RenderedTextPath] 형제 TF " + tf.selfId()
                                    + " 제거 (group=" + tf.parentGroupId() + ")");
                        }
                    }
                }
            }
        }

        if (replacedCount > 0) {
            System.err.println("[RenderedTextFrame] " + replacedCount + "개 텍스트 프레임을 이미지로 교체");
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
                    if (isBadgeFallback && bounds != null && bounds.length == 4) {
                        // 배지: 그룹 bounds(spread 좌표) → 페이지 상대 좌표로 변환
                        kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage badgePage =
                                resolvedData.getPage(rendered.pageIndex());
                        if (badgePage != null && badgePage.bounds() != null) {
                            double[] rel = badgePage.spreadBoundsToPageRelative(bounds);
                            figX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                    .pointsToHwpunits(rel[0]);
                            figY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                    .pointsToHwpunits(rel[1]);
                        } else {
                            figX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                    .pointsToHwpunits(bounds[1]);
                            figY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                    .pointsToHwpunits(bounds[0]);
                        }
                        figW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                .pointsToHwpunits(bounds[3] - bounds[1]);
                        figH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                .pointsToHwpunits(bounds[2] - bounds[0]);
                    } else if (bounds != null && bounds.length == 4) {
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
                        // bounds 없으면 AST 위치/크기 폴백
                        figX = tfb.x();
                        figW = tfb.width();
                        if (isBadgeFallback) {
                            // 배지: AST 높이 그대로 사용 (PNG 비율 적용하면 그룹 장식 영역 포함되어 높이 과대)
                            figH = tfb.height();
                            figY = tfb.y();
                        } else if (img.getWidth() > 0) {
                            figH = Math.round(figW * ((double) img.getHeight() / img.getWidth()));
                            figY = tfb.y() - (figH - tfb.height()) / 2;
                        } else {
                            figH = tfb.height();
                            figY = tfb.y();
                        }
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
                    System.err.println("[FloatingRendered] " + tfb.sourceId()
                            + " → " + rendered.file()
                            + " figPos=(" + figX + "," + figY + ")"
                            + " figSize=(" + figW + "," + figH + ")");
                } catch (Exception e) {
                    System.err.println("[FloatingRendered] " + tfb.sourceId()
                            + " 교체 실패: " + e.getMessage());
                }
            }
        }
        if (replaced > 0) {
            System.err.println("[FloatingRendered] " + replaced + "개 플로팅 텍스트 프레임을 이미지로 교체");
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
            System.err.println("[InlineRendered] " + replaced + "개 이미지 교체, " + removed + "개 중복 제거");
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
                    System.err.println("[FloatingDup] " + tfb.sourceId() + " → 제거 (\"" + text + "\")");
                }
            }
        }
        if (removed > 0) {
            System.err.println("[FloatingDup] " + removed + "개 플로팅 중복 블록 제거");
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
            // INLINE_TEXT_FRAME 또는 RENDERED_GROUP (인라인 배지 그룹 포함)
            if (obj.kind() != kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME
                    && obj.kind() != kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject.ObjectKind.RENDERED_GROUP)
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
                System.err.println("[InlineRendered] " + obj.sourceId() + " → " + rendered.file());
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
                System.err.println("[InlineRendered-Badge] " + obj.sourceId() + " → " + rendered.file());
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

        // 페이지별 기존 ASTFigure + ASTTable bounds 수집 (orphan 중복 주입 방지용)
        Map<Integer, java.util.List<long[]>> pageFigureBounds = new java.util.HashMap<>();
        for (int si = 0; si < sections.size(); si++) {
            java.util.List<long[]> bounds = new java.util.ArrayList<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk : sections.get(si).blocks()) {
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure fig =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure) blk;
                    bounds.add(new long[]{fig.x(), fig.y(), fig.x() + fig.width(), fig.y() + fig.height()});
                }
                // 테이블 영역도 수집: 테이블과 동일 위치의 그래픽 프레임 중복 주입 방지
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable tbl =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) blk;
                    bounds.add(new long[]{tbl.x(), tbl.y(), tbl.x() + tbl.width(), tbl.y() + tbl.height()});
                }
            }
            pageFigureBounds.put(si, bounds);
        }

        // 배지 그룹을 OrphanGraphic 대상에 추가
        // (인라인 처리된 배지는 isConsumedRenderedGraphic으로 건너뜀)
        java.util.List<RenderedGroup> orphanTargets = new java.util.ArrayList<>(
                resolvedData.allRenderedGraphicFrames());
        // PDF 배치 프레임도 orphan 대상에 추가 (IDML에서 PDF 링크를 직접 변환하지 못하므로)
        orphanTargets.addAll(resolvedData.allRenderedPdfFrames());
        // renderedTextFrame: 배지 그룹 + 시각 효과(그림자, 투명도 등)로 렌더된 텍스트프레임
        // AST 파이프라인에서 isRenderedByExtendScript로 건너뛰므로 orphan으로 주입
        orphanTargets.addAll(resolvedData.allRenderedTextFrames());

        // 부모-자식 관계: childIds에 포함된 ID는 부모 렌더링에 이미 포함되므로 건너뜀
        // 단, 부모 자체가 렌더 가능한 파일을 가진 경우에만 (부모가 필터되면 자식 허용)
        java.util.Set<Integer> orphanChildIdSet = new java.util.HashSet<>();
        for (RenderedGroup rg : orphanTargets) {
            if (rg.childIds() != null && rg.file() != null) {
                for (int cid : rg.childIds()) {
                    orphanChildIdSet.add(cid);
                }
            }
        }

        Map<Integer, Integer> pageOrphanZCounter = new java.util.HashMap<>();
        // 페이지별 주입된 orphan bounds 추적 (자식 포함 관계 중복 방지)
        Map<Integer, java.util.List<long[]>> pageOrphanBounds = new java.util.HashMap<>();
        // 주입된 orphan의 childIds 집합 (배지 중복 방지)
        java.util.Set<Integer> placedOrphanChildIds = new java.util.HashSet<>();
        for (RenderedGroup rg : orphanTargets) {
            if (rg.file() == null || rg.bounds() == null) continue;

            // 부모 그룹의 childIds에 포함된 자식은 건너뜀 (부모가 전체 렌더링)
            if (orphanChildIdSet.contains(rg.id())) continue;

            // DOM ID → IDML hex ID 변환하여 이미 사용된 것인지 확인
            String idmlHexId = "u" + Integer.toHexString(rg.id());
            if (usedSourceIds.contains(idmlHexId)) continue;
            // BackgroundInjector가 page_obj_N 형식으로 이미 배치한 항목 스킵
            if (usedSourceIds.contains("page_obj_" + rg.id())) continue;

            // 배지 그룹 자식 도형 건너뜀 (배지 통째 렌더링에서 처리)
            if (resolvedData.isShapeInBadgeGroup(idmlHexId)) continue;

            // vectorShapes 처리 대상 및 클리핑 자식 건너뜀 (중복 주입 방지)
            if (astDoc.orphanExcludeIds().contains(idmlHexId)) continue;

            // 인라인 그래픽으로 이미 처리된 deco 건너뜀 (중복 주입 방지)
            if (resolvedData.isConsumedRenderedGraphic(String.valueOf(rg.id()))) continue;

            int pageIdx = rg.pageIndex();
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage resolvedPage =
                    resolvedData.getPage(pageIdx);

            // PNG 로드
            java.io.File pngFile = new java.io.File(resolvedDir, rg.file());
            if (!pngFile.exists()) {
                continue;
            }

            try {
                byte[] data = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null) continue;

                // 위치/크기 계산 (ExtendScript에서 이미 pageBounds를 뺀 페이지 상대 좌표)
                double[] bounds = rg.bounds();
                long figX, figY, figW, figH;
                figX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                        .pointsToHwpunits(bounds[1]);
                figY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                        .pointsToHwpunits(bounds[0]);
                figW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                        .pointsToHwpunits(bounds[3] - bounds[1]);
                figH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                        .pointsToHwpunits(bounds[2] - bounds[0]);
                // PNG 비율로 높이 보정
                if (img.getWidth() > 0) {
                    long geoBottom = figY + figH;
                    figH = Math.round(figW * ((double) img.getHeight() / img.getWidth()));
                    // 음수 Y (페이지 위 확장) 시 바닥 가장자리 기하학적 위치 유지
                    if (figY < 0) {
                        figY = geoBottom - figH;
                    }
                }

                // 기존 ASTFigure/ASTTable과 겹치는 도형 건너뜀 (중복 방지)
                // 양방향 겹침: orphan과 기존 figure 모두 50% 이상 겹쳐야 중복으로 판단
                // (배경 도형은 기존 figure보다 훨씬 크므로 단방향이면 잘못 필터됨)
                boolean overlapsExisting = false;
                long figArea = figW * figH;
                java.util.List<long[]> existingBounds = pageFigureBounds.get(pageIdx);
                if (existingBounds != null && figArea > 0) {
                    for (long[] eb : existingBounds) {
                        long overlapLeft = Math.max(figX, eb[0]);
                        long overlapTop = Math.max(figY, eb[1]);
                        long overlapRight = Math.min(figX + figW, eb[2]);
                        long overlapBottom = Math.min(figY + figH, eb[3]);
                        if (overlapLeft < overlapRight && overlapTop < overlapBottom) {
                            long overlapArea = (overlapRight - overlapLeft) * (overlapBottom - overlapTop);
                            double orphanRatio = (double) overlapArea / figArea;
                            long ebArea = (eb[2] - eb[0]) * (eb[3] - eb[1]);
                            double existingRatio = ebArea > 0 ? (double) overlapArea / ebArea : 0;
                            // 양쪽 모두 50% 이상 겹쳐야 중복 (같은 크기/위치의 객체)
                            // 단, 한쪽이 다른 쪽을 거의 완전히 포함(>90%)하면
                            // 배경/컨테이너 관계이므로 중복 아님
                            boolean isContainment = (existingRatio > 0.9 && orphanRatio < 0.9)
                                    || (orphanRatio > 0.9 && existingRatio < 0.9);
                            if (orphanRatio > 0.7 && existingRatio > 0.7 && !isContainment) {
                                overlapsExisting = true;
                                break;
                            }
                        }
                    }
                }
                if (overlapsExisting) continue;

                // 같은 페이지에 이미 주입된 orphan에 포함되는 자식 건너뜀
                // (부모 그룹의 렌더 이미지가 자식을 포함하므로 자식은 중복)
                // 단, 배지 그룹이 이미 배치된 orphan의 자식이 아니면 독립 배치 허용
                // (배경 사각형 위에 배지가 있는 경우: 배경은 배지를 포함하지 않음)
                boolean containedByOrphan = false;
                boolean isBadge = rg.isBadgeGroup();
                if (isBadge && placedOrphanChildIds.contains(rg.id())) {
                    // 이미 배치된 orphan이 이 배지를 자식으로 포함 → 중복이므로 건너뜀
                    containedByOrphan = true;
                }
                java.util.List<long[]> existingOrphans = pageOrphanBounds.get(pageIdx);
                if (!isBadge && existingOrphans != null && figArea > 0) {
                    for (long[] ob : existingOrphans) {
                        long obW = ob[2] - ob[0];
                        long obH = ob[3] - ob[1];
                        long obArea = obW * obH;
                        // ob가 현재 orphan을 80% 이상 포함하면 건너뜀
                        long oLeft = Math.max(figX, ob[0]);
                        long oTop = Math.max(figY, ob[1]);
                        long oRight = Math.min(figX + figW, ob[2]);
                        long oBottom = Math.min(figY + figH, ob[3]);
                        if (oLeft < oRight && oTop < oBottom) {
                            long oArea = (oRight - oLeft) * (oBottom - oTop);
                            double containRatio = (double) oArea / figArea;
                            if (containRatio > 0.8) {
                                // 크기가 비슷하면 동일 객체 중복 → 건너뜀
                                // 크기가 크게 다르면 배경-컨텐츠 레이어 관계 → 유지
                                long minArea = Math.min(figArea, obArea);
                                long maxArea = Math.max(figArea, obArea);
                                double sizeRatio = maxArea > 0 ? (double) minArea / maxArea : 0;
                                if (sizeRatio > 0.9) {
                                    // 면적 비 90% 이상 → 거의 같은 크기 → 중복
                                    containedByOrphan = true;
                                    break;
                                }
                                // 면적 비 90% 미만 → 배경 위 컨텐츠 레이어 → 유지
                            }
                        }
                    }
                }
                if (containedByOrphan) continue;

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
                if (cropLeft > 0 || cropTop > 0) {
                    fig.cropLeftFraction(cropLeft);
                    fig.cropTopFraction(cropTop);
                }

                // z-order: IDML 스프레드 파싱 시 할당된 원본 z-order 사용
                // (AST 텍스트 프레임과 동일한 스케일 → inFrontBlocks 정렬에서 올바른 스태킹)
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection section = sections.get(pageIdx);
                java.util.Map<String, Integer> zMap = astDoc.idmlZOrders();
                Integer idmlZ = (zMap != null) ? zMap.get(idmlHexId) : null;
                if (idmlZ == null) {
                    // 폴백: 순차 카운터
                    Integer orphanZ = pageOrphanZCounter.get(pageIdx);
                    if (orphanZ == null) orphanZ = 0;
                    idmlZ = orphanZ;
                    pageOrphanZCounter.put(pageIdx, orphanZ + 1);
                }
                fig.zOrder(idmlZ);
                // renderedGraphicFrame(벡터 도형)은 텍스트/배지보다 앞에 표시
                // IDML z-order 없으면 높은 값으로 설정하여 배경 위에 배치
                if (idmlZ <= 0 && resolvedData.getRenderedGraphicFrameByIdmlId(idmlHexId) != null) {
                    fig.zOrder(9000 + pageOrphanZCounter.getOrDefault(pageIdx, 0));
                }

                // 배경 vs 전경 판단: 이미지 면적이 페이지의 50% 이상이면 배경(BEHIND_TEXT)
                boolean isBackground = false;
                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage bgPage =
                        (resolvedData != null) ? resolvedData.getPage(pageIdx) : null;
                if (bgPage != null && bgPage.bounds() != null) {
                    double[] pb = bgPage.bounds();
                    double pageArea = (pb[2] - pb[0]) * (pb[3] - pb[1]);
                    double orphanArea = (double) figW * figH / (100.0 * 100.0); // hwpunit → pt
                    if (pageArea > 0 && orphanArea / pageArea > 0.5) {
                        isBackground = true;
                    }
                }
                if (isBackground) {
                    fig.zOrder(0);  // 배경은 최하위 z-order
                }
                fig.fromGroup(!isBackground);  // 배경이면 BEHIND_TEXT, 아니면 IN_FRONT_OF_TEXT

                section.addBlock(fig);

                // 주입된 orphan bounds 추적
                java.util.List<long[]> orphanList = pageOrphanBounds.get(pageIdx);
                if (orphanList == null) {
                    orphanList = new java.util.ArrayList<>();
                    pageOrphanBounds.put(pageIdx, orphanList);
                }
                orphanList.add(new long[]{figX, figY, figX + figW, figY + figH});
                // 주입된 orphan의 자식 ID 기록 (자식 배지 중복 방지)
                if (rg.childIds() != null) {
                    for (int cid : rg.childIds()) {
                        placedOrphanChildIds.add(cid);
                    }
                }

                // === 스프레드 걸침 감지: 그래픽이 인접 페이지까지 확장되면 추가 배치 ===
                if (resolvedPage != null && resolvedPage.bounds() != null) {
                    double pageRight = resolvedPage.bounds()[3];  // 현재 페이지 오른쪽 끝 (spread coords, pt)
                    double graphicRight = bounds[3];              // 그래픽 오른쪽 끝
                    int adjPageIdx = pageIdx + 1;
                    // 그래픽이 현재 페이지를 넘어서고 다음 페이지가 존재하면
                    if (graphicRight > pageRight + 10 && adjPageIdx < sections.size()) {
                        kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage adjPage =
                                resolvedData.getPage(adjPageIdx);
                        if (adjPage != null && adjPage.bounds() != null) {
                            double adjPageLeft = adjPage.bounds()[1];
                            double graphicLeft = bounds[1];
                            double totalW = graphicRight - graphicLeft;  // 전체 그래픽 폭
                            double totalH = bounds[2] - bounds[0];
                            if (totalW > 0 && totalH > 0) {
                                // 인접 페이지 상대 좌표
                                double adjRelX = graphicLeft - adjPageLeft;  // 음수 (페이지 왼쪽 바깥)
                                double adjRelY = bounds[0] - adjPage.bounds()[0];
                                long adjFigX = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                        .pointsToHwpunits(adjRelX);
                                long adjFigY = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                        .pointsToHwpunits(adjRelY);
                                long adjFigW = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                        .pointsToHwpunits(totalW);
                                long adjFigH = kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter
                                        .pointsToHwpunits(totalH);
                                // PNG 비율 보정
                                if (img.getWidth() > 0) {
                                    adjFigH = Math.round(adjFigW * ((double) img.getHeight() / img.getWidth()));
                                }
                                // crop fraction (왼쪽 부분 = 이전 페이지 영역)
                                double adjCropLeft = 0, adjCropTop = 0;
                                if (adjFigX < 0) {
                                    adjCropLeft = (double) (-adjFigX) / adjFigW;
                                }
                                if (adjFigY < 0) {
                                    adjCropTop = (double) (-adjFigY) / adjFigH;
                                }

                                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure adjFig =
                                        new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure();
                                adjFig.x(adjFigX);
                                adjFig.y(adjFigY);
                                adjFig.width(adjFigW);
                                adjFig.height(adjFigH);
                                adjFig.imageData(data);
                                adjFig.imageFormat("png");
                                adjFig.pixelWidth(img.getWidth());
                                adjFig.pixelHeight(img.getHeight());
                                adjFig.sourceId(idmlHexId + "_adj");
                                adjFig.fromGroup(true);
                                if (adjCropLeft > 0 || adjCropTop > 0) {
                                    adjFig.cropLeftFraction(adjCropLeft);
                                    adjFig.cropTopFraction(adjCropTop);
                                }
                                adjFig.zOrder(idmlZ);
                                sections.get(adjPageIdx).addBlock(adjFig);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // skip
            }
        }

        // === 렌더링된 이미지 프레임 orphan 주입 ===
        // renderedGraphicFrame이 이미 같은 영역을 커버하면 이미지 프레임 스킵
        // (graphic 렌더는 배경 이미지까지 포함하므로 image 렌더가 중복됨)
        Map<Integer, java.util.List<double[]>> graphicBoundsPerPage = new java.util.HashMap<>();
        for (RenderedGroup gfx : resolvedData.allRenderedGraphicFrames()) {
            if (gfx.bounds() != null && gfx.pageIndex() >= 0) {
                graphicBoundsPerPage.computeIfAbsent(gfx.pageIndex(), k -> new java.util.ArrayList<>())
                        .add(gfx.bounds());
            }
        }
        // TextFrame 자식 없는 배지 그룹(TextPath 전용)도 graphic bounds에 추가
        for (RenderedGroup rg2 : resolvedData.allRenderedTextFrames()) {
            if (rg2.isBadgeGroup() && (rg2.childTextFrameIds() == null || rg2.childTextFrameIds().length == 0)
                    && rg2.bounds() != null && rg2.pageIndex() >= 0) {
                graphicBoundsPerPage.computeIfAbsent(rg2.pageIndex(), k -> new java.util.ArrayList<>())
                        .add(rg2.bounds());
            }
        }
        java.util.Set<Integer> coveredChildIds = new java.util.HashSet<>();
        // 자식 수 내림차순 정렬 → 큰 그룹 먼저 처리
        java.util.List<RenderedGroup> sortedImgFrames = new java.util.ArrayList<>(
                resolvedData.allRenderedImageFrames());
        sortedImgFrames.sort((a, b) -> {
            int aLen = a.childImageIds() != null ? a.childImageIds().length : 0;
            int bLen = b.childImageIds() != null ? b.childImageIds().length : 0;
            return Integer.compare(bLen, aLen);
        });

        for (RenderedGroup rg : sortedImgFrames) {
            if (rg.file() == null || rg.bounds() == null) continue;

            String idmlHexId = "u" + Integer.toHexString(rg.id());
            if (usedSourceIds.contains(idmlHexId)) continue;
            // BackgroundInjector가 page_obj_N 형식으로 이미 배치한 항목 스킵
            if (usedSourceIds.contains("page_obj_" + rg.id())) continue;

            // 자식 이미지가 이미 사용되었거나 상위 그룹에 의해 커버됨
            if (rg.childImageIds() != null) {
                boolean anyChildUsed = false;
                for (int childId : rg.childImageIds()) {
                    String childHex = "u" + Integer.toHexString(childId);
                    if (usedSourceIds.contains(childHex) || coveredChildIds.contains(childId)) {
                        anyChildUsed = true;
                        break;
                    }
                }
                if (anyChildUsed) continue;
            }

            // 이 그룹 자체가 상위 그룹에 의해 이미 커버된 경우
            if (coveredChildIds.contains(rg.id())) continue;

            int pageIdx = rg.pageIndex();
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            // 같은 페이지의 renderedGraphicFrame이 이 이미지와 대부분 겹치면 스킵
            // (graphic 렌더는 배경 이미지까지 포함하므로 image 렌더가 중복됨)
            if (rg.bounds() != null) {
                java.util.List<double[]> gfxBounds = graphicBoundsPerPage.get(pageIdx);
                if (gfxBounds != null) {
                    boolean coveredByGraphic = false;
                    double[] ib = rg.bounds(); // [top, left, bottom, right]
                    double imgArea = (ib[2] - ib[0]) * (ib[3] - ib[1]);
                    if (imgArea > 0) {
                        for (double[] gb : gfxBounds) {
                            // overlap 영역 계산
                            double oTop = Math.max(gb[0], ib[0]);
                            double oLeft = Math.max(gb[1], ib[1]);
                            double oBottom = Math.min(gb[2], ib[2]);
                            double oRight = Math.min(gb[3], ib[3]);
                            if (oBottom > oTop && oRight > oLeft) {
                                double overlapArea = (oBottom - oTop) * (oRight - oLeft);
                                if (overlapArea / imgArea > 0.7) {
                                    coveredByGraphic = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (coveredByGraphic) {
                        continue;
                    }
                }
            }

            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage resolvedPage =
                    resolvedData.getPage(pageIdx);

            java.io.File pngFile = new java.io.File(resolvedDir, rg.file());
            if (!pngFile.exists()) continue;

            try {
                byte[] data = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null) continue;

                double[] bounds = rg.bounds();
                long figX, figY, figW, figH;
                if (resolvedPage != null && resolvedPage.bounds() != null) {
                    double[] rel = resolvedPage.spreadBoundsToPageRelative(bounds);
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

                // 음수 좌표 클램프: HWPX는 음수 오프셋을 지원하지 않음
                // 크기는 유지하여 페이지 전체를 덮도록 함 (bleed 영역은 페이지 밖으로 확장)
                if (figX < 0) figX = 0;
                if (figY < 0) figY = 0;

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
                // 이 그룹의 자식 ID를 커버 목록에 추가 (하위 그룹 중복 방지)
                if (rg.childImageIds() != null) {
                    for (int childId : rg.childImageIds()) {
                        coveredChildIds.add(childId);
                    }
                }
            } catch (Exception e) {
                // skip
            }
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

    // SPEC-030 B.1: _crop_manifest.json이 있으면 배치 PNG에서 개별 배지 PNG를 크롭한다.
    // 데스크탑 앱(Rust)이 크롭을 적용하지 못한 경우(CLI 직접 변환 등) 여기서 보정.
    // 구 JSX 버그: visibleBounds가 mm 단위일 때 dpi/72 계수를 사용해 좌표가 작게 계산됨.
    // 보정: 배치 PNG 너비와 매니페스트 max(x+w)의 비율로 스케일을 자동 감지.
    private static void applyCropManifest(String baseDir) {
        if (baseDir == null) return;
        java.io.File manifest = new java.io.File(baseDir, "_crop_manifest.json");
        if (!manifest.exists()) return;
        try {
            String json = new String(java.nio.file.Files.readAllBytes(manifest.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
            java.util.regex.Pattern objPat = java.util.regex.Pattern.compile("\\{([^}]+)\\}");
            java.util.regex.Pattern strPat = java.util.regex.Pattern.compile("\"(src|dst)\"\\s*:\\s*\"([^\"]+)\"");
            java.util.regex.Pattern numPat = java.util.regex.Pattern.compile("\"([xywh])\"\\s*:\\s*(-?\\d+)");
            // 1패스: 전체 파싱
            java.util.List<java.util.Map<String, Object>> entries = new java.util.ArrayList<>();
            java.util.regex.Matcher objM = objPat.matcher(json);
            while (objM.find()) {
                String obj = objM.group(1);
                java.util.regex.Matcher sm = strPat.matcher(obj);
                java.util.Map<String, String> strs = new java.util.HashMap<>();
                while (sm.find()) strs.put(sm.group(1), sm.group(2));
                java.util.regex.Matcher nm = numPat.matcher(obj);
                java.util.Map<String, Integer> nums = new java.util.HashMap<>();
                while (nm.find()) nums.put(nm.group(1), Integer.parseInt(nm.group(2)));
                if (strs.get("src") == null || strs.get("dst") == null) continue;
                java.util.Map<String, Object> e = new java.util.HashMap<>();
                e.put("src", strs.get("src")); e.put("dst", strs.get("dst"));
                e.put("x", nums.getOrDefault("x", 0)); e.put("y", nums.getOrDefault("y", 0));
                e.put("w", nums.getOrDefault("w", 0)); e.put("h", nums.getOrDefault("h", 0));
                entries.add(e);
            }
            // 2패스: src별로 배치 PNG 로드 + 스케일 자동 감지 + 크롭
            java.util.Map<String, java.awt.image.BufferedImage> batches = new java.util.HashMap<>();
            java.util.Map<String, Double> scales = new java.util.HashMap<>();
            java.util.Set<String> srcFiles = new java.util.HashSet<>();
            int count = 0;
            // 스케일 계산: src별 max(x+w)를 배치 너비와 비교
            for (java.util.Map<String, Object> e : entries) {
                String src = (String) e.get("src");
                if (!batches.containsKey(src)) {
                    java.io.File sf = new java.io.File(baseDir, src);
                    if (!sf.exists()) continue;
                    java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(sf);
                    if (b != null) { batches.put(src, b); scales.put(src, 1.0); }
                }
                if (!batches.containsKey(src)) continue;
                int xw = (Integer) e.get("x") + (Integer) e.get("w");
                if (xw > 0) {
                    double ratio = (double) batches.get(src).getWidth() / xw;
                    // 구 JSX 버그로 인해 ratio ≈ 2.83 (72/25.4) 이면 mm 단위 보정 필요
                    if (ratio > scales.get(src)) scales.put(src, ratio);
                }
            }
            // 스케일이 1.3~4.0 범위면 보정 적용, 그 외는 1.0 (신 JSX 또는 pt 문서)
            for (String src : scales.keySet()) {
                double r = scales.get(src);
                if (r < 1.3 || r > 4.0) scales.put(src, 1.0);
            }
            // 크롭
            for (java.util.Map<String, Object> e : entries) {
                String src = (String) e.get("src"), dst = (String) e.get("dst");
                java.awt.image.BufferedImage batch = batches.get(src);
                if (batch == null) continue;
                java.io.File dstFile = new java.io.File(baseDir, dst);
                if (dstFile.exists()) continue;
                double sc = scales.getOrDefault(src, 1.0);
                int x = (int) Math.round((Integer) e.get("x") * sc);
                int y = (int) Math.round((Integer) e.get("y") * sc);
                int w = (int) Math.round((Integer) e.get("w") * sc);
                int h = (int) Math.round((Integer) e.get("h") * sc);
                if (w <= 0 || h <= 0) continue;
                x = Math.max(0, Math.min(x, batch.getWidth() - 1));
                y = Math.max(0, Math.min(y, batch.getHeight() - 1));
                w = Math.min(w, batch.getWidth() - x);
                h = Math.min(h, batch.getHeight() - y);
                if (w <= 0 || h <= 0) continue;
                try {
                    java.awt.image.BufferedImage crop = batch.getSubimage(x, y, w, h);
                    javax.imageio.ImageIO.write(crop, "PNG", dstFile);
                    srcFiles.add(src);
                    count++;
                } catch (Exception ex) { /* skip */ }
            }
            if (count > 0) System.err.println("[IDMLToHwpxConverter] crop_manifest: " + count + " badge PNG(s) cropped");
            for (String s : srcFiles) { new java.io.File(baseDir, s).delete(); }
            manifest.delete();
        } catch (Exception ex) { /* skip manifest failures */ }
    }

    /**
     * SPEC-018 M3: 시멘틱 레이어 추출 + JSON 저장.
     *
     * <p>{@link ConvertOptions#extractSemantics()} 가 true 일 때만 호출.
     * 실패해도 변환 자체는 계속 진행 (호출 측에서 try/catch).</p>
     *
     * <p>스키마 해석 우선순위:</p>
     * <ol>
     *   <li>options.semanticSchema() 가 절대/상대 파일 경로로 존재하면 그 파일</li>
     *   <li>그 외에는 classpath 리소스 {@code semantic-schemas/<value>.schema.json}</li>
     *   <li>null/빈값이면 기본 스키마 {@code common}</li>
     * </ol>
     */
    private static void extractSemanticLayer(
            ASTDocument astDoc,
            String hwpxPath,
            ConvertOptions options,
            ConvertResult result) throws java.io.IOException {
        kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io.SchemaLoader loader =
                new kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io.SchemaLoader();

        String schemaSpec = options.semanticSchema();
        kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticSchema schema;
        if (schemaSpec == null || schemaSpec.isEmpty()) {
            schema = loader.loadResource("semantic-schemas/common.schema.json");
        } else {
            java.io.File f = new java.io.File(schemaSpec);
            if (f.isFile()) {
                schema = loader.loadFromFile(f.toPath());
            } else {
                // 단순 ID 또는 classpath 경로 둘 다 시도
                String resourcePath = schemaSpec.contains("/")
                        ? schemaSpec
                        : ("semantic-schemas/" + schemaSpec + ".schema.json");
                schema = loader.loadResource(resourcePath);
            }
        }

        kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticLayer layer =
                kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticExtractor.extract(astDoc, schema);

        String outputPath = options.semanticOutput();
        if (outputPath == null || outputPath.isEmpty()) {
            outputPath = defaultSemanticOutputPath(hwpxPath);
        }

        java.nio.file.Path outPath = java.nio.file.Paths.get(outputPath);
        java.nio.file.Path parent = outPath.getParent();
        if (parent != null && !java.nio.file.Files.exists(parent)) {
            java.nio.file.Files.createDirectories(parent);
        }
        kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io.SemanticLayerWriter.write(layer, outPath);

        int classified = 0;
        int unknown = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticNode n : layer.nodes) {
            if (kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticTypes.LABEL_UNKNOWN.equals(n.label)) {
                unknown++;
            } else {
                classified++;
            }
        }
        System.out.println("[Semantic] " + layer.nodes.size() + " nodes ("
                + classified + " classified, " + unknown + " unknown), "
                + layer.relations.size() + " relations → " + outputPath);
        result.addWarning("[Semantic] 추출 완료: " + layer.nodes.size() + " 노드, "
                + classified + " 분류 → " + outputPath);
    }

    /** hwpx 경로 → 같은 디렉토리의 .semantic.json. */
    private static String defaultSemanticOutputPath(String hwpxPath) {
        if (hwpxPath == null) return "out.semantic.json";
        int dot = hwpxPath.lastIndexOf('.');
        if (dot < 0) return hwpxPath + ".semantic.json";
        return hwpxPath.substring(0, dot) + ".semantic.json";
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
