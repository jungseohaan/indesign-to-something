package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTToHwpxConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.IDMLNormalizer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedDataReader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper;

import java.io.File;
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

            // Phase 1.5: Resolved 데이터 조기 로딩 (Stage4 래스터화에서 사용)
            ResolvedData resolvedData = null;
            if (options.resolvedJsonPath() != null) {
                try {
                    reporter.reportProgress(3, 100, "resolved 데이터 로딩 중...");
                    resolvedData = ResolvedDataReader.read(options.resolvedJsonPath());
                } catch (Exception e) {
                    System.err.println("Warning: resolved.json 로드 실패 (무시): " + e.getMessage());
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

            // Phase 1.7a: 렌더링된 텍스트 프레임 교체 (짧은 텍스트 → 이미지)
            if (resolvedData != null && resolvedData.renderedTextFrameCount() > 0) {
                replaceRenderedTextFrames(idmlDoc, resolvedData, options);
            }

            // Phase 1.7b: 렌더링된 그룹 교체 — 하위 호환 (그룹 자식 → 단일 합성 이미지)
            if (resolvedData != null && resolvedData.renderedGroupCount() > 0) {
                replaceRenderedGroups(idmlDoc, resolvedData, options);
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
                }
            }

            // Phase 2.6: Resolved 오버레이 좌표 보강 (선택적)
            if (resolvedData != null) {
                try {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedOverlayEnricher.enrich(
                            astDoc, resolvedData);
                } catch (Exception e) {
                    System.err.println("Warning: resolved overlay 보강 실패 (무시): " + e.getMessage());
                }
            }

            // Phase 2.7: 플로팅 이미지 → 인라인 머지 (textWrap 자리차지)
            kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.FloatingImageMerger.merge(astDoc);

            // Phase 2.8: 인라인 앵커 텍스트 프레임 → 렌더 이미지 교체
            java.util.Set<String> inlineReplacedTexts = java.util.Collections.emptySet();
            if (resolvedData != null && resolvedData.renderedTextFrameCount() > 0) {
                inlineReplacedTexts = replaceInlineRenderedTextFrames(astDoc, resolvedData, options);
            }

            // 인라인 이미지로 교체된 텍스트와 동일한 플로팅 텍스트 프레임 블록 제거
            if (!inlineReplacedTexts.isEmpty()) {
                removeFloatingDuplicates(astDoc, inlineReplacedTexts);
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

            // Phase 2.8: 사용자 지정 폰트 매핑 로드 (선택적)
            Map<String, String> customFontMap = null;
            if (options.fontMapPath() != null) {
                customFontMap = FontMapper.loadFontMapFromJson(options.fontMapPath());
                if (customFontMap.isEmpty()) {
                    customFontMap = null;
                }
            }

            // Phase 3: AST -> HWPX (페이지별 진행률: 10~90)
            int totalPages = astDoc.sections().size();
            ConvertResult result = ASTToHwpxConverter.convert(astDoc, reporter, 10, totalPages, customFontMap);

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
            ConvertResult result = ASTToHwpxConverter.convert(astDoc, ProgressReporter.NONE, 0, 0);
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
    private static void replaceRenderedTextFrames(IDMLDocument idmlDoc,
                                                   ResolvedData resolvedData,
                                                   ConvertOptions options) {
        String resolvedDir = getResolvedDir(options);
        if (resolvedDir == null) return;

        // IDML 페이지 순서 → 스프레드 좌표 매핑 (pageIndex → page absolute top-left)
        java.util.List<double[]> pageOrigins = new java.util.ArrayList<>();
        for (IDMLSpread spread : idmlDoc.spreads()) {
            for (IDMLPage page : spread.pages()) {
                double[] gb = page.geometricBounds();
                double[] t = page.itemTransform();
                double pageLeft = (t != null && t.length >= 6) ? t[4] + gb[1] : gb[1];
                double pageTop = (t != null && t.length >= 6) ? t[5] + gb[0] : gb[0];
                pageOrigins.add(new double[]{pageTop, pageLeft});
            }
        }

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

            // 2단계: TextFrame 루프 — TextPath가 처리하는 파일은 건너뛰기
            java.util.Iterator<IDMLTextFrame> it = spread.textFrames().iterator();
            while (it.hasNext()) {
                IDMLTextFrame tf = it.next();
                RenderedGroup rendered = resolvedData.getRenderedTextFrameByIdmlId(tf.selfId());
                if (rendered == null) continue;

                // TextPath가 같은 파일을 처리하면 TextFrame 제거만 하고 이미지 생성 안 함
                if (textPathRenderedFiles.contains(rendered.file())) {
                    it.remove();
                    System.out.println("[RenderedTextFrame] " + tf.selfId()
                            + " → SKIP (TextPath가 " + rendered.file() + " 처리)");
                    continue;
                }

                double[] rb = rendered.bounds();
                if (rb == null || rb.length < 4) continue;

                int pgIdx = rendered.pageIndex();
                double offsetTop = 0, offsetLeft = 0;
                if (pgIdx >= 0 && pgIdx < pageOrigins.size()) {
                    offsetTop = pageOrigins.get(pgIdx)[0];
                    offsetLeft = pageOrigins.get(pgIdx)[1];
                }

                java.io.File pngFile = new java.io.File(resolvedDir, rendered.file());
                double spreadCenterY = offsetTop + (rb[0] + rb[2]) / 2.0;
                double spreadCenterX = offsetLeft + (rb[1] + rb[3]) / 2.0;
                double boundsW = rb[3] - rb[1];
                double boundsH = rb[2] - rb[0];

                try {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                    if (img != null && img.getWidth() > 0) {
                        double pngRatio = (double) img.getHeight() / img.getWidth();
                        boundsH = boundsW * pngRatio;
                    }
                } catch (Exception e) {
                    // fallback: resolved bounds 크기 그대로
                }

                double[] absBounds = new double[]{
                        spreadCenterY - boundsH / 2.0, spreadCenterX - boundsW / 2.0,
                        spreadCenterY + boundsH / 2.0, spreadCenterX + boundsW / 2.0
                };

                // TextFrame 전용 렌더: z+10000으로 페이지 장식 요소 위에 표시
                int zOrder = tf.zOrder() + 10000;
                it.remove();

                IDMLImageFrame syn = new IDMLImageFrame();
                syn.selfId(tf.selfId() + "_rendered");
                syn.geometricBounds(absBounds);
                syn.itemTransform(new double[]{1, 0, 0, 1, 0, 0});
                syn.zOrder(zOrder);
                syn.fromGroup(true);  // IN_FRONT_OF_TEXT로 배치

                String absPath = pngFile.getAbsolutePath();
                syn.linkResourceURI(absPath);
                syn.linkStoredState("Normal");
                syn.linkResourceFormat("PNG");

                spread.addImageFrame(syn);
                replacedCount++;

                System.out.println("[RenderedTextFrame] " + tf.selfId()
                        + " → " + rendered.file()
                        + " page=" + pgIdx + " z=" + zOrder
                        + " bounds=[" + String.format("%.1f,%.1f,%.1f,%.1f",
                            absBounds[0], absBounds[1], absBounds[2], absBounds[3]) + "]");
            }

            // 3단계: TextPath(VectorShape) 루프 — 그룹 z-order 보존
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

                double[] rb = rendered.bounds();
                if (rb == null || rb.length < 4) continue;

                int pgIdx = rendered.pageIndex();
                double offsetTop = 0, offsetLeft = 0;
                if (pgIdx >= 0 && pgIdx < pageOrigins.size()) {
                    offsetTop = pageOrigins.get(pgIdx)[0];
                    offsetLeft = pageOrigins.get(pgIdx)[1];
                }

                java.io.File pngFile = new java.io.File(resolvedDir, rendered.file());
                double spreadCenterY = offsetTop + (rb[0] + rb[2]) / 2.0;
                double spreadCenterX = offsetLeft + (rb[1] + rb[3]) / 2.0;
                double boundsW = rb[3] - rb[1];
                double boundsH = rb[2] - rb[0];

                try {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                    if (img != null && img.getWidth() > 0) {
                        double pngRatio = (double) img.getHeight() / img.getWidth();
                        boundsH = boundsW * pngRatio;
                    }
                } catch (Exception e) {}

                double[] absBounds = new double[]{
                        spreadCenterY - boundsH / 2.0, spreadCenterX - boundsW / 2.0,
                        spreadCenterY + boundsH / 2.0, spreadCenterX + boundsW / 2.0
                };

                // TextPath는 그룹 소속이면 원래 z-order (A/B/C 등과 정확한 stacking)
                int zOrder = (vs.parentGroupId() != null) ? vs.zOrder() : vs.zOrder() + 10000;
                vsIt.remove();

                IDMLImageFrame syn = new IDMLImageFrame();
                syn.selfId(vs.selfId() + "_rendered");
                syn.geometricBounds(absBounds);
                syn.itemTransform(new double[]{1, 0, 0, 1, 0, 0});
                syn.zOrder(zOrder);
                syn.fromGroup(true);  // IN_FRONT_OF_TEXT로 배치
                syn.linkResourceURI(pngFile.getAbsolutePath());
                syn.linkStoredState("Normal");
                syn.linkResourceFormat("PNG");
                spread.addImageFrame(syn);
                replacedCount++;

                System.out.println("[RenderedTextPath] " + vs.selfId()
                        + " → " + rendered.file()
                        + " page=" + pgIdx + " z=" + zOrder);
            }
        }

        if (replacedCount > 0) {
            System.out.println("[RenderedTextFrame] " + replacedCount + "개 텍스트 프레임을 이미지로 교체");
        }
    }

    /**
     * AST의 인라인 앵커 텍스트 프레임 중 렌더링 대상을 이미지로 교체한다.
     * spread 레벨이 아닌 스토리 내부에 앵커된 TextFrame (e.g. "e.g." 라벨)은
     * replaceRenderedTextFrames()에서 처리할 수 없으므로 AST 단계에서 교체한다.
     */
    private static java.util.Set<String> replaceInlineRenderedTextFrames(
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
                if (blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable tbl =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable) blk;
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableRow row : tbl.rows()) {
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableCell cell : row.cells()) {
                            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph para : cell.paragraphs()) {
                                int[] result = replaceInlineRenderedInParagraph(para, resolvedData, resolvedDir, replacedTexts);
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
    private static void removeFloatingDuplicates(ASTDocument astDoc, java.util.Set<String> replacedTexts) {
        int removed = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection sec : astDoc.sections()) {
            java.util.Iterator<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock> it = sec.blocks().iterator();
            while (it.hasNext()) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock blk = it.next();
                if (!(blk instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock)) continue;
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock tfb =
                        (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock) blk;
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
                if (!text.isEmpty() && replacedTexts.contains(text)) {
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
            if (rendered == null) continue;

            // 텍스트 내용 추출 (중복 감지용)
            String textContent = extractInlineTextContent(obj);

            if (textContent != null && replacedTexts.contains(textContent)) {
                // 동일 텍스트의 중복 매칭 → 제거 (0 크기 스페이서로 변환)
                obj.kind(kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject.ObjectKind.SPACER_RECT);
                obj.width(0);
                obj.height(0);
                obj.paragraphs(null);
                if (obj.inlineTables() != null) obj.inlineTables().clear();
                removed++;
                System.out.println("[InlineRendered] " + obj.sourceId() + " → 중복 제거 (\"" + textContent + "\")");
                continue;
            }

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

    /** resolved.json의 부모 디렉토리를 반환한다. */
    private static String getResolvedDir(ConvertOptions options) {
        if (options.resolvedJsonPath() == null) return null;
        java.io.File resolvedFile = new java.io.File(options.resolvedJsonPath());
        if (!resolvedFile.isAbsolute()) {
            resolvedFile = resolvedFile.getAbsoluteFile();
        }
        return resolvedFile.getParent();
    }

    /**
     * 렌더링된 그룹을 단일 합성 이미지 프레임으로 교체한다.
     * resolved.json의 renderedGroups 배열에 기록된 그룹의 자식 요소들을
     * 스프레드에서 제거하고, pre-rendered JPEG을 참조하는 단일 IDMLImageFrame으로 대체한다.
     */
    private static void replaceRenderedGroups(IDMLDocument idmlDoc,
                                               ResolvedData resolvedData,
                                               ConvertOptions options) {
        // resolved.json 기준 디렉토리 (group_renders/ 경로 해석용)
        String resolvedDir = null;
        if (options.resolvedJsonPath() != null) {
            File resolvedFile = new File(options.resolvedJsonPath());
            // 상대 경로일 때 절대 경로로 변환
            if (!resolvedFile.isAbsolute()) {
                resolvedFile = resolvedFile.getAbsoluteFile();
            }
            resolvedDir = resolvedFile.getParent();
        }
        if (resolvedDir == null) return;

        int replacedCount = 0;

        for (IDMLSpread spread : idmlDoc.spreads()) {
            for (IDMLGroup group : spread.groups()) {
                String groupIdmlId = group.selfId();  // e.g., "u1735"
                RenderedGroup rendered = resolvedData.getRenderedGroupByIdmlId(groupIdmlId);
                if (rendered == null) continue;

                // 1. 그룹 자식의 최소 z-order 확보 (제거 전)
                int groupZOrder = findMinZOrderOfGroup(spread, groupIdmlId);

                // 2. 그룹의 자식 요소들을 스프레드에서 제거 (보존 대상 TextFrame 제외)
                removeGroupChildren(spread, groupIdmlId, rendered);

                // 3. 합성 이미지 프레임 생성
                // PNG는 이미 회전/스케일 적용된 최종 이미지이므로
                // 절대 위치(spread 좌표)로 배치하고 identity transform 사용
                double[] origBounds = group.geometricBounds();
                double[] origTransform = group.itemTransform();
                double[] absBox = IDMLGeometry.getTransformedBoundingBox(origBounds, origTransform);
                // absBox: [minX, minY, maxX, maxY] → IDML bounds: [top, left, bottom, right]
                double[] absBounds = new double[]{absBox[1], absBox[0], absBox[3], absBox[2]};

                // visibleBounds 보정: PNG exportFile은 visibleBounds 영역을 내보내므로
                // geometricBounds와의 비율/오프셋으로 IDML bounds를 확장
                double[] ve = rendered.visibleExpansion();
                if (ve != null && ve.length >= 4) {
                    double idmlW = absBounds[3] - absBounds[1]; // right - left
                    double idmlH = absBounds[2] - absBounds[0]; // bottom - top
                    double centerX = (absBounds[1] + absBounds[3]) / 2.0;
                    double centerY = (absBounds[0] + absBounds[2]) / 2.0;
                    // ve: [widthRatio, heightRatio, offsetRatioX, offsetRatioY]
                    double newW = idmlW * ve[0];
                    double newH = idmlH * ve[1];
                    double newCenterX = centerX + idmlW * ve[2];
                    double newCenterY = centerY + idmlH * ve[3];
                    absBounds[0] = newCenterY - newH / 2.0;  // top
                    absBounds[1] = newCenterX - newW / 2.0;  // left
                    absBounds[2] = newCenterY + newH / 2.0;  // bottom
                    absBounds[3] = newCenterX + newW / 2.0;  // right
                }

                IDMLImageFrame syntheticFrame = new IDMLImageFrame();
                syntheticFrame.selfId(groupIdmlId + "_rendered");
                syntheticFrame.geometricBounds(absBounds);
                syntheticFrame.itemTransform(new double[]{1, 0, 0, 1, 0, 0});  // identity
                syntheticFrame.zOrder(groupZOrder);
                syntheticFrame.fromGroup(false);  // 합성된 이미지이므로 일반 이미지로 취급

                // 절대 경로로 이미지 참조
                String absPath = new File(resolvedDir, rendered.file()).getAbsolutePath();
                boolean pngExists = new File(absPath).exists();
                syntheticFrame.linkResourceURI(absPath);
                syntheticFrame.linkStoredState("Normal");
                syntheticFrame.linkResourceFormat("PNG");

                spread.addImageFrame(syntheticFrame);
                replacedCount++;

                int preservedCount = rendered.preservedTextFrameIdmlIds().size();
                System.out.println("[RenderedGroup] " + groupIdmlId
                        + " → " + rendered.file()
                        + (pngExists ? "" : " [PNG NOT FOUND: " + absPath + "]")
                        + " bounds=[" + String.format("%.1f,%.1f,%.1f,%.1f",
                            absBounds[0], absBounds[1], absBounds[2], absBounds[3]) + "]"
                        + (preservedCount > 0 ? " preserved=" + preservedCount : "")
                        + " z=" + groupZOrder + ")");
            }
        }

        if (replacedCount > 0) {
            System.out.println("[RenderedGroup] " + replacedCount + "개 그룹을 합성 이미지로 교체");
        }
    }

    /**
     * 특정 그룹에서 추출된 자식 요소들을 스프레드에서 제거한다.
     * 중첩 그룹의 자식도 모두 제거한다.
     * 보존 대상 TextFrame(긴 텍스트)은 제거하지 않고 글상자로 유지한다.
     */
    private static void removeGroupChildren(IDMLSpread spread, String groupIdmlId,
                                            RenderedGroup rendered) {
        // 중첩 그룹 포함 전체 그룹 ID 수집
        java.util.Set<String> allGroupIds = new java.util.HashSet<String>();
        allGroupIds.add(groupIdmlId);
        for (IDMLGroup grp : spread.groups()) {
            if (groupIdmlId.equals(grp.selfId())) {
                collectChildGroupIds(grp, allGroupIds);
                break;
            }
        }

        int removedTF = 0, removedIF = 0, removedVS = 0;

        java.util.Iterator<IDMLTextFrame> tfIter = spread.textFrames().iterator();
        while (tfIter.hasNext()) {
            IDMLTextFrame tf = tfIter.next();
            if (allGroupIds.contains(tf.parentGroupId())) {
                if (rendered.isPreservedTextFrame(tf.selfId())) {
                    continue;
                }
                tfIter.remove();
                removedTF++;
            }
        }
        java.util.Iterator<IDMLImageFrame> ifIter = spread.imageFrames().iterator();
        while (ifIter.hasNext()) {
            if (allGroupIds.contains(ifIter.next().parentGroupId())) {
                ifIter.remove();
                removedIF++;
            }
        }
        java.util.Iterator<IDMLVectorShape> vsIter = spread.vectorShapes().iterator();
        while (vsIter.hasNext()) {
            if (allGroupIds.contains(vsIter.next().parentGroupId())) {
                vsIter.remove();
                removedVS++;
            }
        }

        if (allGroupIds.size() > 1) {
            System.out.println("[RenderedGroup] " + groupIdmlId
                    + " nested groups: " + (allGroupIds.size() - 1)
                    + ", removed: TF=" + removedTF + " IF=" + removedIF + " VS=" + removedVS);
        }
    }

    /** 그룹의 모든 자식 그룹 ID를 재귀적으로 수집한다. */
    private static void collectChildGroupIds(IDMLGroup group, java.util.Set<String> ids) {
        for (IDMLGroup child : group.childGroups()) {
            ids.add(child.selfId());
            collectChildGroupIds(child, ids);
        }
    }

    /**
     * 그룹(중첩 포함)에 속한 자식 요소들의 최소 z-order를 반환한다.
     * 합성 이미지 프레임이 원본 그룹의 스태킹 위치를 유지하도록 사용.
     */
    private static int findMinZOrderOfGroup(IDMLSpread spread, String groupIdmlId) {
        java.util.Set<String> allGroupIds = new java.util.HashSet<String>();
        allGroupIds.add(groupIdmlId);
        for (IDMLGroup grp : spread.groups()) {
            if (groupIdmlId.equals(grp.selfId())) {
                collectChildGroupIds(grp, allGroupIds);
                break;
            }
        }
        int minZ = Integer.MAX_VALUE;
        for (IDMLImageFrame imgF : spread.imageFrames()) {
            if (allGroupIds.contains(imgF.parentGroupId()) && imgF.zOrder() < minZ) {
                minZ = imgF.zOrder();
            }
        }
        for (IDMLVectorShape vs : spread.vectorShapes()) {
            if (allGroupIds.contains(vs.parentGroupId()) && vs.zOrder() < minZ) {
                minZ = vs.zOrder();
            }
        }
        return minZ == Integer.MAX_VALUE ? 0 : minZ;
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
                System.out.println("Warnings:");
                for (String warning : result.warnings()) {
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
