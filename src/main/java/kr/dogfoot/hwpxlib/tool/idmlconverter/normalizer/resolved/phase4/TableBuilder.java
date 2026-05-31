package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.ASTTableConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * SPEC-013 Phase 4 + SPEC-017 v2: 테이블 포함 TextFrame → ASTTable / ASTFigure 변환.
 *
 * <p>분기 정책 (SPEC-017):</p>
 * <ul>
 *   <li><b>중첩 테이블</b> + {@code nestedTableForcesPng} → 표 전체 PNG fallback</li>
 *   <li><b>cell-level 모드</b>(기본) → ASTTable로 변환 후 트리거 셀의 인라인 객체만
 *       개별 floating ASTFigure로 추출. 본문 셀은 ASTTable로 유지되어 검색/편집 가능</li>
 *   <li><b>preferCellLevel = false</b>(레거시) → 인라인 감지 시 표 전체 PNG fallback</li>
 *   <li>PNG fallback이 필요한데 PNG 못 찾으면 ASTTable로 폴백 + "배지 중복 위험" 카운트</li>
 * </ul>
 *
 * <p>의존: ctx.idmlDir, resolvedData, scaleFactor, basePath, toSectionIndex, loadIDMLStory,
 * ensureIdmlInfra + idmlDocumentSupplier/colorResolverSupplier/imageLoaderSupplier,
 * tableQualityGate, debugAst.</p>
 */
public final class TableBuilder {

    private TableBuilder() {}

    /** 변환 종료 시 stderr에 출력할 분기 카운터. */
    private static final class Phase4Report {
        int asTableCleanCount;          // ASTTable, 인라인 추출 0건
        int asTableWithExtraction;      // ASTTable + 셀 인라인 추출 1건 이상
        int totalCellsExtracted;        // 추출된 셀 수
        int totalInlinesExtracted;      // 추출된 인라인 객체 수
        int wholeTablePngRendered;      // 표 전체 PNG (rendered_frames에서 발견)
        int wholeTablePngForced;        // 중첩 테이블 등 정책상 강제 PNG
        int pngMissingFallback;         // 인라인 있지만 PNG 없어서 ASTTable로 떨어진 케이스 (배지 중복 위험)
        int total;
    }

    public static void placeTablesFromIDML(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.idmlDir == null) return;
        ConversionConfig.TableQualityGateConfig policy = ctx.tableQualityGate != null
                ? ctx.tableQualityGate
                : new ConversionConfig.TableQualityGateConfig();
        Phase4Report report = new Phase4Report();
        // 같은 Table이 연결 글상자 체인의 복수 TF에서 중복 처리되는 것을 방지
        Set<String> processedTableIds = new HashSet<>();

        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            String storyId = tf.storyId();
            if (storyId == null) continue;
            IDMLStory idmlStory = ctx.loadIDMLStory.apply(storyId);
            if (idmlStory == null || !idmlStory.hasTables()) continue;

            if (tf.isInline() && ctx.resolvedData.isEditableTextFrame(tf.id())) continue;
            if (!tf.isInline() && !ctx.resolvedData.isEditableTextFrame(tf.id())) continue;

            int pageIdx = ctx.toSectionIndex.applyAsInt(tf.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            double[] gb = tf.geometricBounds();
            if (gb == null || gb.length < 4) continue;
            ResolvedPage rPage = (pageIdx < ctx.resolvedData.pages().size())
                    ? ctx.resolvedData.pages().get(pageIdx) : null;
            double pageLeft = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[1] : 0;
            double pageTop = (rPage != null && rPage.bounds() != null) ? rPage.bounds()[0] : 0;
            boolean gbAlreadyPageRelative = (pageLeft > 0 && gb[1] < pageLeft);
            double x = gbAlreadyPageRelative ? gb[1] : (gb[1] - pageLeft);
            double y = gb[0] - pageTop;

            long hx = CoordinateConverter.pointsToHwpunits(x);
            long hy = CoordinateConverter.pointsToHwpunits(y);

            if (tf.insetSpacing() != null) {
                double[] inset = tf.insetSpacing();
                hy += CoordinateConverter.pointsToHwpunits(inset[0]);
                hx += CoordinateConverter.pointsToHwpunits(inset[1]);
            }

            // 중첩 테이블 부모 탐색 (selfId 접두사 매칭)
            List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> allTables = idmlStory.tables();
            Map<String, kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> tableById = new HashMap<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable t : allTables) {
                tableById.put(t.selfId(), t);
            }
            Map<String, kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable> parentTableMap = new HashMap<>();
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable t : allTables) {
                String sid = t.selfId();
                int lastSlash = sid.lastIndexOf('/');
                if (lastSlash > 0) {
                    String parentPart = sid.substring(0, lastSlash);
                    int prevSlash = parentPart.lastIndexOf('/');
                    if (prevSlash > 0) {
                        String candidateId = parentPart.substring(0, prevSlash);
                        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable parent = tableById.get(candidateId);
                        if (parent != null) parentTableMap.put(sid, parent);
                    }
                }
            }

            long tableYOffset = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable : allTables) {
                // 같은 Table이 TF 연결 체인에서 중복 배치되는 것을 방지
                if (!processedTableIds.add(idmlTable.selfId())) continue;

                long thisX = hx;
                long thisY = hy;

                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable parentTable = parentTableMap.get(idmlTable.selfId());
                boolean isNested = parentTable != null;

                if (isNested) {
                    String parentId = parentTable.selfId();
                    String remainder = idmlTable.selfId().substring(parentId.length());
                    int cellRowIdx = -1;
                    if (remainder.startsWith("i")) {
                        String cellPart = remainder.substring(1);
                        int nextI = cellPart.indexOf('i');
                        String rowStr = (nextI > 0) ? cellPart.substring(0, nextI) : cellPart;
                        try { cellRowIdx = Integer.parseInt(rowStr); } catch (NumberFormatException e) { /* ignore */ }
                    }
                    if (cellRowIdx >= 0) {
                        long rowHeightSum = 0;
                        int ri = 0;
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow pr : parentTable.rows()) {
                            if (ri >= cellRowIdx) break;
                            rowHeightSum += CoordinateConverter.pointsToHwpunits(pr.rowHeight());
                            ri++;
                        }
                        thisY = hy + rowHeightSum;
                    }
                } else {
                    int parasBefore = idmlTable.paragraphIndexBefore();
                    if (parasBefore > 0) {
                        double estLineHeight = 8.0 * ctx.scaleFactor;
                        tableYOffset = CoordinateConverter.pointsToHwpunits(parasBefore * estLineHeight);
                    }
                    thisY = hy + tableYOffset;
                }

                report.total++;
                ASTSection section = sections.get(pageIdx);

                // 분기 1: 중첩 테이블 + 정책상 강제 PNG
                if (isNested && policy.nestedTableForcesPng) {
                    ASTFigure fig = renderTableAsImage(ctx, idmlTable, tf, thisX, thisY, pageIdx);
                    if (fig == null && policy.fallbackToBackgroundCrop) {
                        fig = cropTableFromPageBackground(ctx, idmlTable, thisX, thisY, tf.zOrder(), pageIdx);
                    }
                    if (fig != null) {
                        if (ctx.debugAst) fig.debugOrNew().note("nested table forced to PNG");
                        section.addBlock(fig);
                        report.wholeTablePngForced++;
                        continue;
                    }
                    System.err.println("[Phase 4] 중첩 테이블이지만 PNG 없음 → ASTTable 폴백, tf=" + tf.id());
                }

                // 분기 2: 레거시 모드 (preferCellLevel=false) — 표 단위 게이트 + 표 전체 PNG
                if (!policy.preferCellLevel) {
                    if (idmlTableHasInlineWithShortText(idmlTable, policy.maxTextLengthWithInline)) {
                        ASTFigure fig = renderTableAsImage(ctx, idmlTable, tf, thisX, thisY, pageIdx);
                        if (fig == null && policy.fallbackToBackgroundCrop) {
                            fig = cropTableFromPageBackground(ctx, idmlTable, thisX, thisY, tf.zOrder(), pageIdx);
                            if (fig != null) {
                                section.addBlock(fig);
                                report.wholeTablePngRendered++; // crop도 같은 카운터 (rendered로 간주)
                                if (ctx.debugAst) fig.debugOrNew().note("background-cropped");
                                continue;
                            }
                        }
                        if (fig != null) {
                            section.addBlock(fig);
                            report.wholeTablePngRendered++;
                            continue;
                        }
                        report.pngMissingFallback++;
                        System.err.println("[Phase 4] (legacy) 인라인 감지했지만 PNG 없음 → ASTTable, tf="
                                + tf.id() + " — 배지 중복 위험");
                    }
                }

                // 분기 3 (기본): ASTTable로 변환
                ctx.ensureIdmlInfra.run();
                ASTTable astTable = ASTTableConverter.convertTableSimple(
                        idmlTable, thisX, thisY, tf.zOrder(),
                        ctx.idmlDocumentSupplier.get(), ctx.colorResolverSupplier.get(),
                        ctx.imageLoaderSupplier.get(), ctx.resolvedData);

                // 테이블 셀에 포함된 인라인 그룹 → 컬럼 분할 (예: 문장 성분의 종류 마인드맵)
                ASTTable expandedTable = tryExpandInlineGroupColumns(ctx, astTable, idmlTable);
                if (expandedTable != null) astTable = expandedTable;

                section.addBlock(astTable);

                // 분기 3a: cell-level 모드 — 트리거 셀의 인라인 객체를 floating으로 추출
                int extractedInThisTable = 0;
                int triggerCells = 0;
                if (policy.preferCellLevel) {
                    int[] result = extractInlinesFromCells(astTable, section, policy, ctx);
                    extractedInThisTable = result[0];
                    triggerCells = result[1];
                    report.totalInlinesExtracted += extractedInThisTable;
                    report.totalCellsExtracted += triggerCells;
                }
                if (extractedInThisTable > 0) {
                    report.asTableWithExtraction++;
                    if (ctx.debugAst) astTable.debugOrNew().note("cell-level extraction: " + triggerCells
                            + " cells, " + extractedInThisTable + " inlines");
                } else {
                    report.asTableCleanCount++;
                }
            }
        }

        printReport(report);
    }

    /**
     * 테이블 셀에 포함된 인라인 그룹(tryInlineGroupAsBoxList가 [leftITF, rightITF]를 반환)이
     * 있으면 해당 컬럼을 2개로 분할하여 3컬럼 테이블로 재구성한다.
     * 처리된 그룹 ID는 ctx.renderedTfPlacedAsText에 등록하여 Phase 7 중복 배치를 방지한다.
     *
     * @return 재구성된 ASTTable, 또는 확장 불필요 시 null
     */
    private static ASTTable tryExpandInlineGroupColumns(
            ResolvedBuildContext ctx,
            ASTTable original,
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable idmlTable) {

        if (ctx == null || ctx.resolvedData == null) return null;

        // (rowIdx_colIdx) → [leftITF, rightITF]
        Map<String, List<ASTInlineObject>> expansionMap = new HashMap<>();
        Set<Integer> expandColIndices = new HashSet<>();
        Set<Integer> suppressGroupIds = new HashSet<>();
        long firstLeftW = 0, firstRightW = 0;

        int rowIdx = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow idmlRow : idmlTable.rows()) {
            int colIdx = 0;
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell idmlCell : idmlRow.cells()) {
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph para : idmlCell.paragraphs()) {
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun run : para.characterRuns()) {
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun.InlineAnchor anchor : run.inlineAnchors()) {
                            if (anchor.type() != kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun.InlineAnchorType.GRAPHIC) continue;
                            java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun.InlineGraphic> igs = run.inlineGraphics();
                            if (anchor.index() >= igs.size()) continue;
                            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun.InlineGraphic ig = igs.get(anchor.index());
                            if (ig == null || ig.selfId() == null) continue;
                            String hexId = ig.selfId().startsWith("u") ? ig.selfId().substring(1) : ig.selfId();
                            int domId;
                            try { domId = Integer.parseInt(hexId, 16); } catch (NumberFormatException e) { continue; }

                            List<ASTInlineObject> boxList =
                                kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3.InlineFrameHandler.tryInlineGroupAsBoxList(ctx, domId);
                            if (boxList != null && boxList.size() >= 2) {
                                // 인라인 그룹이 텍스트 흐름 안에 박혀있는 경우 (앞뒤에 텍스트 있음)
                                // → 테이블 컬럼 분할 대상이 아님. 단독 셀 콘텐츠일 때만 확장.
                                boolean hasTextBesideAnchor = false;
                                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun otherRun : para.characterRuns()) {
                                    String ct = otherRun.content();
                                    if (ct != null && !ct.trim().isEmpty()) {
                                        hasTextBesideAnchor = true;
                                        break;
                                    }
                                }
                                if (hasTextBesideAnchor) continue;

                                String key = rowIdx + "_" + colIdx;
                                if (!expansionMap.containsKey(key)) {
                                    expansionMap.put(key, boxList);
                                    expandColIndices.add(colIdx);
                                    suppressGroupIds.add(domId);
                                    if (firstLeftW == 0) {
                                        firstLeftW = boxList.get(0).width();
                                        firstRightW = boxList.get(1).width();
                                    }
                                }
                            }
                        }
                    }
                }
                colIdx++;
            }
            rowIdx++;
        }

        if (expansionMap.isEmpty()) return null;

        // Phase 7 중복 배치 방지: 부모 그룹 ID + 같은 파일을 공유하는 TF ID + 자손 TF ID 모두 등록
        suppressAllDescendantsFromPhase7(ctx, suppressGroupIds);

        // 확장 컬럼을 2개로 분할한 새 컬럼 너비 목록
        List<Long> origColWidths = original.columnWidths();
        List<Long> newColWidths = new java.util.ArrayList<>();
        Map<Integer, Integer> oldToNewCol = new HashMap<>();
        int nc = 0;
        for (int c = 0; c < origColWidths.size(); c++) {
            oldToNewCol.put(c, nc);
            if (expandColIndices.contains(c)) {
                newColWidths.add(firstLeftW);
                newColWidths.add(firstRightW);
                nc += 2;
            } else {
                newColWidths.add(origColWidths.get(c));
                nc++;
            }
        }

        ASTTable newTable = new ASTTable();
        newTable.sourceId(original.sourceId());
        newTable.x(original.x());
        newTable.y(original.y());
        newTable.zOrder(original.zOrder());
        for (long cw : newColWidths) newTable.addColumnWidth(cw);
        newTable.colCount(newColWidths.size());

        int ri = 0;
        for (ASTTableRow origRow : original.rows()) {
            ASTTableRow newRow = new ASTTableRow();
            newRow.rowIndex(ri);
            newRow.rowHeight(origRow.rowHeight());
            newRow.autoGrow(origRow.autoGrow());

            for (ASTTableCell origCell : origRow.cells()) {
                int oldCol = origCell.columnIndex();
                int newBaseCol = oldToNewCol.getOrDefault(oldCol, oldCol);
                String key = ri + "_" + oldCol;
                List<ASTInlineObject> boxList = expansionMap.get(key);

                if (boxList != null) {
                    // 확장 셀: leftITF 셀 + rightITF 셀
                    ASTInlineObject leftITF = boxList.get(0);
                    leftITF.height(origRow.rowHeight());

                    ASTTableCell leftCell = cloneCellMeta(origCell);
                    leftCell.rowIndex(ri);
                    leftCell.columnIndex(newBaseCol);
                    leftCell.width(newColWidths.get(newBaseCol));
                    leftCell.height(origCell.height());
                    ASTParagraph leftPara = new ASTParagraph();
                    leftPara.addItem(leftITF);
                    leftCell.addParagraph(leftPara);
                    newRow.addCell(leftCell);

                    ASTInlineObject rightITF = boxList.get(1);
                    rightITF.height(origRow.rowHeight());

                    ASTTableCell rightCell = cloneCellMeta(origCell);
                    rightCell.rowIndex(ri);
                    rightCell.columnIndex(newBaseCol + 1);
                    rightCell.width(newColWidths.get(newBaseCol + 1));
                    rightCell.height(origCell.height());
                    ASTParagraph rightPara = new ASTParagraph();
                    rightPara.addItem(rightITF);
                    rightCell.addParagraph(rightPara);
                    newRow.addCell(rightCell);
                } else {
                    // 일반 셀: 복사 후 columnIndex 갱신
                    ASTTableCell newCell = cloneCellFull(origCell);
                    newCell.rowIndex(ri);
                    newCell.columnIndex(newBaseCol);
                    // 확장 컬럼이지만 이 행에 inline group 없음 → 두 분할 열을 colSpan=2로 병합
                    if (expandColIndices.contains(oldCol)) {
                        long mergedW = 0;
                        if (newBaseCol < newColWidths.size()) mergedW += newColWidths.get(newBaseCol);
                        if (newBaseCol + 1 < newColWidths.size()) mergedW += newColWidths.get(newBaseCol + 1);
                        newCell.width(mergedW);
                        newCell.columnSpan(2);
                    } else {
                        if (newBaseCol < newColWidths.size()) newCell.width(newColWidths.get(newBaseCol));
                    }
                    newRow.addCell(newCell);
                }
            }

            newTable.addRow(newRow);
            ri++;
        }
        newTable.rowCount(ri);

        long totalW = 0;
        for (long cw : newColWidths) totalW += cw;
        newTable.width(totalW);
        long totalH = 0;
        for (ASTTableRow row : newTable.rows()) totalH += row.rowHeight();
        newTable.height(totalH);

        return newTable;
    }

    /** 셀 메타데이터(테두리/여백/색상)만 복사, 내용(단락) 제외 */
    private static ASTTableCell cloneCellMeta(ASTTableCell src) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(src.rowIndex());
        cell.columnIndex(src.columnIndex());
        cell.rowSpan(src.rowSpan());
        cell.columnSpan(src.columnSpan());
        cell.fillColor(src.fillColor());
        cell.topBorder(src.topBorder());
        cell.bottomBorder(src.bottomBorder());
        cell.leftBorder(src.leftBorder());
        cell.rightBorder(src.rightBorder());
        cell.topLeftDiagonalLine(src.topLeftDiagonalLine());
        cell.topRightDiagonalLine(src.topRightDiagonalLine());
        cell.diagonalBorder(src.diagonalBorder());
        cell.marginTop(src.marginTop());
        cell.marginBottom(src.marginBottom());
        cell.marginLeft(src.marginLeft());
        cell.marginRight(src.marginRight());
        cell.verticalAlign(src.verticalAlign());
        cell.width(src.width());
        cell.height(src.height());
        return cell;
    }

    /** 셀 전체(메타+단락) 복사 */
    private static ASTTableCell cloneCellFull(ASTTableCell src) {
        ASTTableCell cell = cloneCellMeta(src);
        if (src.paragraphs() != null) {
            for (ASTParagraph p : src.paragraphs()) cell.addParagraph(p);
        }
        return cell;
    }

    /**
     * 그룹 ID 집합과 그 모든 자손을 Phase 7에서 처리하지 않도록 등록.
     *
     * 처리 순서:
     * 1) groupIds 자체를 renderedTfPlacedAsText에 추가
     * 2) groupIds가 사용하는 배지 PNG 파일 경로를 수집 →
     *    같은 파일을 공유하는 TF(형제 TF) 도 suppressed
     * 3) pageItems childIds BFS로 그룹 자손 전체 수집 → suppressed
     */
    private static void suppressAllDescendantsFromPhase7(
            ResolvedBuildContext ctx, Set<Integer> groupIds) {
        if (ctx.renderedTfPlacedAsText == null || ctx.resolvedData == null) return;

        // Step 1: 부모 그룹 직접 등록
        ctx.renderedTfPlacedAsText.addAll(groupIds);

        // Step 2: 배지 PNG 파일 공유 TF 억제
        Set<String> badgeFiles = new HashSet<>();
        for (RenderedGroup rt : ctx.resolvedData.allRenderedTextFrames()) {
            if (groupIds.contains(rt.id()) && rt.file() != null) {
                badgeFiles.add(rt.file());
            }
        }
        for (RenderedGroup rt : ctx.resolvedData.allRenderedFloatingItems()) {
            if (groupIds.contains(rt.id()) && rt.file() != null) {
                badgeFiles.add(rt.file());
            }
        }
        if (!badgeFiles.isEmpty()) {
            for (RenderedGroup rt : ctx.resolvedData.allRenderedTextFrames()) {
                if (rt.file() != null && badgeFiles.contains(rt.file())) {
                    ctx.renderedTfPlacedAsText.add(rt.id());
                }
            }
            for (RenderedGroup rt : ctx.resolvedData.allRenderedFloatingItems()) {
                if (rt.file() != null && badgeFiles.contains(rt.file())) {
                    ctx.renderedTfPlacedAsText.add(rt.id());
                }
            }
        }

        // Step 3: pageItems childIds BFS로 자손 전체 억제
        Map<Integer, int[]> childMap = new HashMap<>();
        for (ResolvedPageItem pi : ctx.resolvedData.pageItems()) {
            if (pi == null || pi.childIds() == null || pi.childIds().length == 0) continue;
            int pid;
            try { pid = Integer.parseInt(pi.id()); } catch (NumberFormatException e) { continue; }
            childMap.put(pid, pi.childIds());
        }
        Queue<Integer> queue = new LinkedList<>(groupIds);
        Set<Integer> visited = new HashSet<>(groupIds);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            int[] children = childMap.get(cur);
            if (children == null) continue;
            for (int child : children) {
                if (visited.add(child)) {
                    ctx.renderedTfPlacedAsText.add(child);
                    queue.add(child);
                }
            }
        }
    }

    /**
     * SPEC-017 Step D: ASTTable의 트리거 셀에서 인라인 객체를 floating ASTFigure로 추출.
     *
     * @return {@code [총 추출 인라인 수, 트리거 셀 수]}
     */
    private static int[] extractInlinesFromCells(ASTTable astTable, ASTSection section,
                                                   ConversionConfig.TableQualityGateConfig policy,
                                                   ResolvedBuildContext ctx) {
        int totalExtracted = 0;
        int triggerCells = 0;

        // 컬럼 X 누적 오프셋 (HWPUNIT)
        List<Long> colWidths = astTable.columnWidths();
        long[] colXOffset = new long[colWidths == null ? 1 : colWidths.size() + 1];
        for (int i = 1; i < colXOffset.length; i++) {
            colXOffset[i] = colXOffset[i - 1] + colWidths.get(i - 1);
        }

        long rowYAccum = 0;
        for (ASTTableRow row : astTable.rows()) {
            for (ASTTableCell cell : row.cells()) {
                if (!cellShouldExtract(cell, policy.maxTextLengthWithInline)) continue;
                int colIdx = cell.columnIndex();
                long cellX = astTable.x()
                        + (colIdx >= 0 && colIdx < colXOffset.length ? colXOffset[colIdx] : 0);
                long cellY = astTable.y() + rowYAccum;
                int extracted = extractCellInlines(cell, cellX, cellY, section, astTable.zOrder(), ctx);
                if (extracted > 0) {
                    triggerCells++;
                    totalExtracted += extracted;
                }
            }
            rowYAccum += row.rowHeight();
        }
        return new int[]{totalExtracted, triggerCells};
    }

    /** 셀이 게이트 조건(짧은 텍스트 + 인라인)을 만족하는지 — ASTTableCell 기반 검사. */
    private static boolean cellShouldExtract(ASTTableCell cell, int maxTextLen) {
        if (cell.paragraphs() == null) return false;
        boolean hasInline = false;
        int textLen = 0;
        for (ASTParagraph para : cell.paragraphs()) {
            if (para.items() == null) continue;
            for (ASTInlineItem item : para.items()) {
                if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                    ASTInlineObject io = (ASTInlineObject) item;
                    if (io.imageData() != null) hasInline = true;
                } else if (item instanceof ASTTextRun) {
                    String t = ((ASTTextRun) item).text();
                    if (t != null) textLen += t.replace("\uFFFC", "").trim().length();
                }
            }
        }
        return hasInline && textLen < maxTextLen;
    }

    /**
     * 셀 안의 모든 imageData를 가진 인라인 객체를 floating ASTFigure로 추출.
     * 추출된 인라인은 cell.paragraphs.items에서 제거된다.
     */
    private static int extractCellInlines(ASTTableCell cell, long cellX, long cellY,
                                            ASTSection section, int tableZOrder, ResolvedBuildContext ctx) {
        if (cell.paragraphs() == null) return 0;
        int extracted = 0;
        for (ASTParagraph para : cell.paragraphs()) {
            if (para.items() == null) continue;
            Iterator<ASTInlineItem> it = para.items().iterator();
            while (it.hasNext()) {
                ASTInlineItem item = it.next();
                if (!(item instanceof ASTInlineObject)) continue;
                ASTInlineObject inline = (ASTInlineObject) item;
                if (inline.imageData() == null) continue;
                if (inline.keepInline()) continue; // IDML inline_object — floating 추출 금지

                ASTFigure fig = inlineToFigure(inline, cellX, cellY, tableZOrder);
                if (fig == null) continue;
                if (ctx.debugAst) {
                    fig.debugOrNew().createdAt = "Phase4.cellInlineExtraction";
                    fig.debug().note("from cell r=" + cell.rowIndex() + " c=" + cell.columnIndex());
                }
                section.addBlock(fig);
                it.remove();
                extracted++;
            }
        }
        return extracted;
    }

    /** ASTInlineObject → ASTFigure (page-level floating image). */
    private static ASTFigure inlineToFigure(ASTInlineObject inline, long cellX, long cellY, int tableZOrder) {
        ASTFigure fig = new ASTFigure();
        // 위치: resolved 절대 좌표 우선, 없으면 셀 좌상단
        long figX = (inline.resolvedPageX() >= 0) ? inline.resolvedPageX() : cellX;
        long figY = (inline.resolvedPageY() >= 0) ? inline.resolvedPageY() : cellY;
        fig.x(figX);
        fig.y(figY);
        // 크기: resolved width/height 우선, 없으면 inline 자체 크기
        long figW = (inline.resolvedWidth() > 0) ? inline.resolvedWidth() : inline.width();
        long figH = (inline.resolvedHeight() > 0) ? inline.resolvedHeight() : inline.height();
        if (figW <= 0 || figH <= 0) return null;
        fig.width(figW);
        fig.height(figH);
        fig.zOrder(tableZOrder + 1); // 표보다 위
        fig.imageData(inline.imageData());
        fig.imageFormat(inline.imageFormat() != null ? inline.imageFormat() : "png");
        fig.pixelWidth(inline.pixelWidth());
        fig.pixelHeight(inline.pixelHeight());
        fig.sourceId(inline.sourceId());
        return fig;
    }

    /** 레거시(preferCellLevel=false) 경로용 — IDML 단계에서 표 전체 게이트 검사. */
    private static boolean idmlTableHasInlineWithShortText(
            kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table, int threshold) {
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow row : table.rows()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell cell : row.cells()) {
                boolean hasInline = false;
                int textLen = 0;
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph para : cell.paragraphs()) {
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun run : para.characterRuns()) {
                        if (run.inlineGraphics() != null && !run.inlineGraphics().isEmpty()) hasInline = true;
                        if (run.inlineFrames() != null && !run.inlineFrames().isEmpty()) hasInline = true;
                        if (run.content() != null) textLen += run.content().replace("\uFFFC", "").trim().length();
                    }
                }
                if (hasInline && textLen < threshold) return true;
            }
        }
        return false;
    }

    /**
     * 인라인 객체가 포함된 테이블 전체를 rendered PNG로 변환.
     * renderedFloatingItems에서 type="table_inline"인 항목을 찾아 사용.
     */
    private static ASTFigure renderTableAsImage(ResolvedBuildContext ctx,
                                                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table,
                                                ResolvedTextFrame tf, long x, long y, int pageIdx) {
        if (ctx.basePath == null || ctx.resolvedData == null) return null;

        String tfDomId = tf.id();
        int domId;
        try { domId = Integer.parseInt(tfDomId); } catch (NumberFormatException e) { return null; }

        File directFile = new File(ctx.basePath, "rendered_frames/table_" + domId + ".png");
        File pngFile = null;
        double[] rgBounds = null;
        if (directFile.exists()) {
            pngFile = directFile;
        }
        if (pngFile == null) {
            for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                if (rg.id() == domId && rg.file() != null) {
                    File f = new File(ctx.basePath, rg.file());
                    if (f.exists()) {
                        pngFile = f;
                        rgBounds = rg.bounds();
                        break;
                    }
                }
            }
        }
        if (pngFile == null) return null;

        try {
            byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
            if (img == null) return null;

            ASTFigure fig = new ASTFigure();
            fig.sourceId("tbl_" + table.selfId());
            fig.x(x);
            fig.y(y);
            fig.zOrder(tf.zOrder());
            fig.imageData(imageData);
            fig.imageFormat("png");
            fig.pixelWidth(img.getWidth());
            fig.pixelHeight(img.getHeight());

            if (rgBounds != null && rgBounds.length >= 4) {
                double bw = Math.abs(rgBounds[3] - rgBounds[1]) * ctx.scaleFactor;
                double bh = Math.abs(rgBounds[2] - rgBounds[0]) * ctx.scaleFactor;
                fig.width(CoordinateConverter.pointsToHwpunits(bw));
                fig.height(CoordinateConverter.pointsToHwpunits(bh));
            } else {
                long tw = 0, th = 0;
                for (double cw : table.columnWidths()) tw += CoordinateConverter.pointsToHwpunits(cw);
                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow r : table.rows())
                    th += CoordinateConverter.pointsToHwpunits(r.rowHeight());
                fig.width(tw);
                fig.height(th);
            }
            return fig;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SPEC-017 Step E: 페이지 배경 PNG에서 테이블 영역만 crop하여 ASTFigure 생성.
     * renderedFloatingItems에 단독 표 PNG가 없을 때 fallback.
     *
     * <p>좌표 변환: 표는 thisX/thisY/tw/th(HWPUNIT, 페이지 기준). 페이지 배경 PNG는
     * 픽셀 크기 + bounds(points). 픽셀 스케일 = pageImg.width / pageWidthHwpunit.</p>
     */
    private static ASTFigure cropTableFromPageBackground(ResolvedBuildContext ctx,
                                                          kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable table,
                                                          long tableX, long tableY, int zOrder, int pageIdx) {
        if (ctx.basePath == null || ctx.resolvedData == null) return null;

        // 1. 표 크기 (HWPUNIT) — column widths 합 + row heights 합
        long tableW = 0, tableH = 0;
        for (double cw : table.columnWidths()) tableW += CoordinateConverter.pointsToHwpunits(cw);
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow r : table.rows())
            tableH += CoordinateConverter.pointsToHwpunits(r.rowHeight());
        if (tableW <= 0 || tableH <= 0) return null;

        // 2. 해당 페이지의 page_background RG 검색
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (!"page_background".equals(rg.itemType())) continue;
            int rgPageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
            if (rgPageIdx != pageIdx) continue;
            if (rg.file() == null) continue;

            File pngFile = new File(ctx.basePath, rg.file());
            if (!pngFile.exists()) continue;

            try {
                java.awt.image.BufferedImage pageImg = javax.imageio.ImageIO.read(pngFile);
                if (pageImg == null) continue;

                double[] bounds = rg.bounds();
                if (bounds == null || bounds.length < 4) continue;

                long pageWHwp = CoordinateConverter.pointsToHwpunits((bounds[3] - bounds[1]) * ctx.scaleFactor);
                long pageHHwp = CoordinateConverter.pointsToHwpunits((bounds[2] - bounds[0]) * ctx.scaleFactor);
                if (pageWHwp <= 0 || pageHHwp <= 0) continue;

                double scaleX = (double) pageImg.getWidth() / pageWHwp;
                double scaleY = (double) pageImg.getHeight() / pageHHwp;

                int px = Math.max(0, (int) Math.round(tableX * scaleX));
                int py = Math.max(0, (int) Math.round(tableY * scaleY));
                int pw = (int) Math.round(tableW * scaleX);
                int ph = (int) Math.round(tableH * scaleY);

                if (px + pw > pageImg.getWidth()) pw = pageImg.getWidth() - px;
                if (py + ph > pageImg.getHeight()) ph = pageImg.getHeight() - py;
                if (pw <= 8 || ph <= 8) return null; // 너무 작으면 거부 (화질 우려)

                java.awt.image.BufferedImage cropped = pageImg.getSubimage(px, py, pw, ph);
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(cropped, "png", baos);
                byte[] data = baos.toByteArray();

                ASTFigure fig = new ASTFigure();
                fig.sourceId("tbl_crop_" + table.selfId());
                fig.x(tableX);
                fig.y(tableY);
                fig.width(tableW);
                fig.height(tableH);
                fig.zOrder(zOrder);
                fig.imageData(data);
                fig.imageFormat("png");
                fig.pixelWidth(pw);
                fig.pixelHeight(ph);
                return fig;
            } catch (Exception e) {
                System.err.println("[Phase 4] background crop 실패: " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private static void printReport(Phase4Report r) {
        if (r.total == 0) return;
        System.err.println("[Phase 4] Tables: " + r.total + " total");
        if (r.asTableCleanCount > 0) {
            System.err.println("  · " + r.asTableCleanCount + " → ASTTable (clean)");
        }
        if (r.asTableWithExtraction > 0) {
            System.err.println("  · " + r.asTableWithExtraction + " → ASTTable + "
                    + r.totalInlinesExtracted + " inline figures extracted (cells: "
                    + r.totalCellsExtracted + ")");
        }
        if (r.wholeTablePngRendered > 0) {
            System.err.println("  · " + r.wholeTablePngRendered + " → whole-table PNG fallback (rendered)");
        }
        if (r.wholeTablePngForced > 0) {
            System.err.println("  · " + r.wholeTablePngForced + " → whole-table PNG fallback (nested forced)");
        }
        if (r.pngMissingFallback > 0) {
            System.err.println("  · WARNING: " + r.pngMissingFallback
                    + " → ASTTable forced (PNG missing, badge duplication risk!)");
        }
    }
}
