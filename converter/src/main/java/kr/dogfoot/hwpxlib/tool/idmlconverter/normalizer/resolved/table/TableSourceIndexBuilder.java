package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.table;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLSpread;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Builds Stage 0 table source facts from IDML/resolved metadata only. */
public final class TableSourceIndexBuilder {
    private TableSourceIndexBuilder() {
    }

    public static TableSourceIndex build(ResolvedBuildContext ctx) {
        List<TableSourceRecord> records = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (ctx == null || ctx.resolvedData == null) {
            warnings.add(warning("TABLE_SOURCE_INDEX_INPUT_MISSING", "missing_resolved_context"));
            return new TableSourceIndex(records, warnings);
        }
        IDMLDocument doc = loadIdmlDocument(ctx, warnings);
        if (doc == null) return new TableSourceIndex(records, warnings);

        ResolvedData data = ctx.resolvedData;
        for (IDMLSpread spread : doc.spreads()) {
            if (spread == null || spread.textFrames() == null) continue;
            for (IDMLTextFrame frame : spread.textFrames()) {
                if (frame == null || blank(frame.parentStoryId())) continue;
                IDMLStory story = doc.getStory(frame.parentStoryId());
                if (story == null && ctx.loadIDMLStory != null) {
                    story = ctx.loadIDMLStory.apply(frame.parentStoryId());
                }
                if (story == null || story.tables() == null || story.tables().isEmpty()) {
                    continue;
                }
                appendRecordsForFrame(data, records, warnings, frame, story);
            }
        }
        return new TableSourceIndex(records, warnings);
    }

    private static IDMLDocument loadIdmlDocument(ResolvedBuildContext ctx, List<String> warnings) {
        if (ctx.idmlDocumentSupplier != null && ctx.idmlDocumentSupplier.get() != null) {
            return ctx.idmlDocumentSupplier.get();
        }
        if (ctx.ensureColorInfra != null) {
            ctx.ensureColorInfra.run();
        }
        IDMLDocument doc = ctx.idmlDocumentSupplier != null ? ctx.idmlDocumentSupplier.get() : null;
        if (doc == null) {
            warnings.add(warning("TABLE_SOURCE_INDEX_IDML_MISSING", "idml_document_not_available"));
        }
        return doc;
    }

    private static void appendRecordsForFrame(
            ResolvedData data,
            List<TableSourceRecord> records,
            List<String> warnings,
            IDMLTextFrame frame,
            IDMLStory story) {
        String carrierId = frame.selfId();
        int carrierDomId = idmlObjectDomId(carrierId);
        ResolvedPageItem item = carrierDomId >= 0 ? data.getPageItem(String.valueOf(carrierDomId)) : null;
        ResolvedTextFrame resolvedFrame = carrierDomId >= 0 ? data.getTextFrame(String.valueOf(carrierDomId)) : null;
        boolean visibleAncestry = visibleAncestry(data, item);
        String[] ancestry = ancestryIds(data, item);
        int rawPageIndex = item != null ? item.pageIndex()
                : (resolvedFrame != null ? resolvedFrame.pageIndex() : -1);
        int pageIndex = sourceSpreadStartPageIndex(data, item, resolvedFrame, rawPageIndex);
        double[] bounds = pageLocalPointBounds(data, item, resolvedFrame, pageIndex);
        int storyDomId = idmlObjectDomId(story.selfId() != null ? story.selfId() : frame.parentStoryId());
        boolean tableOnly = tableOnlyStory(story);
        for (IDMLTable table : story.tables()) {
            if (table == null) continue;
            int tableDomId = tableDomId(table.selfId());
            int[] styleSourceIds = tableStyleSourceIds(data, item, carrierDomId, storyDomId, tableDomId);
            String issue = null;
            if (carrierDomId < 0) issue = "invalid_carrier_id";
            else if (storyDomId < 0) issue = "invalid_story_id";
            else if (tableDomId < 0) issue = "invalid_table_id";
            else if (item == null && resolvedFrame == null) issue = "carrier_missing_resolved_page_item";
            else if (!visibleAncestry) issue = "hidden_ancestry";
            else if (pageIndex < 0) issue = "missing_page_index";
            else if (!validBounds(bounds)) issue = "missing_bounds";

            TableSourceRecord record = new TableSourceRecord(
                    carrierId,
                    carrierDomId,
                    story.selfId() != null ? story.selfId() : frame.parentStoryId(),
                    storyDomId,
                    table.selfId(),
                    tableDomId,
                    pageIndex,
                    visibleAncestry,
                    tableOnly,
                    ancestry,
                    styleSourceIds,
                    bounds,
                    issue);
            records.add(record);
            if (issue != null) {
                warnings.add(warning("TABLE_SOURCE_RECORD_NOT_EXECUTABLE",
                        "carrier=" + carrierId + " story=" + frame.parentStoryId()
                                + " table=" + table.selfId() + " issue=" + issue));
            }
        }
    }

    private static int sourceSpreadStartPageIndex(
            ResolvedData data,
            ResolvedPageItem item,
            ResolvedTextFrame frame,
            int rawPageIndex) {
        if (data == null || rawPageIndex < 0) return rawPageIndex;
        ResolvedPage page = sourcePage(data, rawPageIndex);
        if (page == null || !validBounds(page.bounds())) return rawPageIndex;
        double[] gb = item != null && validBounds(item.geometricBounds())
                ? item.geometricBounds()
                : (frame != null && validBounds(frame.geometricBounds()) ? frame.geometricBounds() : null);
        if (!validBounds(gb)) return rawPageIndex;

        double pageLeft = page.bounds()[1];
        boolean assignedToRightPage = pageLeft > 1.0;
        boolean startsOnPreviousPage = gb[1] < pageLeft && gb[3] > pageLeft;
        if (assignedToRightPage && startsOnPreviousPage && sourcePage(data, rawPageIndex - 1) != null) {
            return rawPageIndex - 1;
        }
        return rawPageIndex;
    }

    private static int[] tableStyleSourceIds(
            ResolvedData data,
            ResolvedPageItem carrier,
            int carrierDomId,
            int storyDomId,
            int tableDomId) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        add(ids, carrierDomId);
        add(ids, storyDomId);
        add(ids, tableDomId);
        ResolvedPageItem parent = carrier != null && !blank(carrier.parentId())
                ? data.getPageItem(carrier.parentId())
                : null;
        if (parent != null) {
            collectDirectTableStyleSources(data, parent, carrier, carrierDomId, ids);
        }
        collectAncestryTableStyleSources(data, carrier, ids);
        collectSiblingTableStyleSources(data, carrier, ids);
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id;
        return out;
    }

    private static void collectDirectTableStyleSources(
            ResolvedData data,
            ResolvedPageItem parent,
            ResolvedPageItem carrier,
            int carrierDomId,
            LinkedHashSet<Integer> ids) {
        if (data == null || parent == null || carrier == null || parent.childIds() == null) return;
        double[] carrierBounds = carrier.geometricBounds();
        if (!validBounds(carrierBounds)) return;
        String parentId = parent.id();
        for (int childId : parent.childIds()) {
            if (childId == carrierDomId) continue;
            ResolvedPageItem child = data.getPageItem(String.valueOf(childId));
            if (child == null || !sameId(parentId, child.parentId())) continue;
            if (isTableStyleSource(child)
                    && !subtreeContainsTextFrame(data, child, 0)
                    && boundsNearOrInside(carrierBounds, child.geometricBounds(), 10.0)) {
                add(ids, childId);
            }
        }
    }

    private static void collectAncestryTableStyleSources(
            ResolvedData data,
            ResolvedPageItem carrier,
            LinkedHashSet<Integer> ids) {
        if (data == null || carrier == null || ids == null) return;
        double[] carrierBounds = carrier.geometricBounds();
        String childOnPathId = carrier.id();
        String parentId = carrier.parentId();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        while (!blank(parentId) && seen.add(parentId)) {
            ResolvedPageItem parent = data.getPageItem(parentId);
            if (parent == null) return;
            int parentSourceId = parseInt(parent.id());
            if (isTableStyleSource(parent)
                    && overlaps(carrierBounds, parent.geometricBounds())
                    && !subtreeContainsOtherTextFrame(data, parent, carrier.id(), 0)) {
                add(ids, parentSourceId);
            }
            if (parent.childIds() != null) {
                for (int childId : parent.childIds()) {
                    if (sameId(String.valueOf(childId), childOnPathId)) continue;
                    ResolvedPageItem child = data.getPageItem(String.valueOf(childId));
                    if (child == null || !sameId(parent.id(), child.parentId())) continue;
                    if (isTableStyleSource(child)
                            && !subtreeContainsTextFrame(data, child, 0)
                            && boundsNearOrInside(carrierBounds, child.geometricBounds(), 10.0)) {
                        add(ids, childId);
                    }
                }
            }
            childOnPathId = parent.id();
            parentId = parent.parentId();
        }
    }

    /**
     * Table style absorption can only claim textless visual sources that share
     * the table carrier's geometry. Sources that contain their own TextFrame are
     * separate text/shell bundles, even when they sit in the same parent group.
     */
    private static void collectSiblingTableStyleSources(
            ResolvedData data,
            ResolvedPageItem carrier,
            LinkedHashSet<Integer> ids) {
        if (data == null || carrier == null || ids == null) return;
        double[] carrierBounds = carrier.geometricBounds();
        if (!validBounds(carrierBounds)) return;
        int carrierPage = carrier.pageIndex();
        for (ResolvedPageItem item : data.pageItems()) {
            if (item == null || !blank(item.parentId())) continue;
            if (item.pageIndex() != carrierPage) continue;
            if (sameId(item.id(), carrier.id())) continue;
            collectSiblingTableStyleSourceItem(data, carrier, carrierBounds, item, ids, 0);
        }
    }

    private static void collectSiblingTableStyleSourceItem(
            ResolvedData data,
            ResolvedPageItem carrier,
            double[] carrierBounds,
            ResolvedPageItem item,
            LinkedHashSet<Integer> ids,
            int depth) {
        if (item == null || depth > 6) return;
        if (!isTableStyleSource(item)) return;
        if (subtreeContainsTextFrame(data, item, 0)) return;
        if ("Group".equals(item.type())) {
            // 그룹 래퍼는 리프보다 몇 pt 크게 잡히므로(둥근 외곽 등) bounds
            // 게이트 없이 재귀하고, 소속 판정은 리프에서만 한다.
            if (item.childIds() == null) return;
            for (int childId : item.childIds()) {
                ResolvedPageItem child = data.getPageItem(String.valueOf(childId));
                if (child == null || !sameId(item.id(), child.parentId())) continue;
                collectSiblingTableStyleSourceItem(data, carrier, carrierBounds, child, ids, depth + 1);
            }
            return;
        }
        // 좌표는 pt 단위 spread 좌표 — 외곽 배경 rect 는 캐리어보다 최대 ~8pt
        // 크게 그려진다 (p28 실측 7.5pt). 10pt 까지 표 소속으로 본다.
        if (!boundsNearOrInside(carrierBounds, item.geometricBounds(), 10.0)) return;
        add(ids, parseInt(item.id()));
    }

    private static boolean subtreeContainsTextFrame(ResolvedData data, ResolvedPageItem item, int depth) {
        if (item == null || depth > 6) return false;
        if ("TextFrame".equals(item.type())) return true;
        if (item.childIds() == null) return false;
        for (int childId : item.childIds()) {
            ResolvedPageItem child = data.getPageItem(String.valueOf(childId));
            if (child == null) continue;
            if (subtreeContainsTextFrame(data, child, depth + 1)) return true;
        }
        return false;
    }

    private static boolean subtreeContainsOtherTextFrame(
            ResolvedData data,
            ResolvedPageItem item,
            String allowedTextFrameId,
            int depth) {
        if (item == null || depth > 6) return false;
        if ("TextFrame".equals(item.type())) {
            return !sameId(item.id(), allowedTextFrameId);
        }
        if (item.childIds() == null) return false;
        for (int childId : item.childIds()) {
            ResolvedPageItem child = data.getPageItem(String.valueOf(childId));
            if (child == null) continue;
            if (subtreeContainsOtherTextFrame(data, child, allowedTextFrameId, depth + 1)) return true;
        }
        return false;
    }

    private static boolean boundsNearOrInside(double[] container, double[] child, double tolerance) {
        if (!validBounds(container) || !validBounds(child)) return false;
        return child[0] >= container[0] - tolerance
                && child[1] >= container[1] - tolerance
                && child[2] <= container[2] + tolerance
                && child[3] <= container[3] + tolerance;
    }

    private static boolean isTableStyleSource(ResolvedPageItem item) {
        if (item == null || item.sourceHidden()) return false;
        if (item.isInline() || item.storyTextInlineSlot()) return false;
        return !"TextFrame".equals(item.type());
    }

    private static void add(LinkedHashSet<Integer> ids, int id) {
        if (id >= 0) ids.add(id);
    }

    private static boolean sameId(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    private static int parseInt(String value) {
        if (value == null) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean overlaps(double[] a, double[] b) {
        if (!validBounds(a) || !validBounds(b)) return false;
        return Math.max(a[0], b[0]) < Math.min(a[2], b[2])
                && Math.max(a[1], b[1]) < Math.min(a[3], b[3]);
    }

    private static boolean visibleAncestry(ResolvedData data, ResolvedPageItem item) {
        if (item == null || item.sourceHidden()) return false;
        String parentId = item.parentId();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        while (!blank(parentId) && seen.add(parentId)) {
            ResolvedPageItem parent = data.getPageItem(parentId);
            if (parent == null) return false;
            if (parent.sourceHidden()) return false;
            parentId = parent.parentId();
        }
        return true;
    }

    private static String[] ancestryIds(ResolvedData data, ResolvedPageItem item) {
        List<String> out = new ArrayList<>();
        String id = item != null ? item.id() : null;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        while (!blank(id) && seen.add(id)) {
            out.add(id);
            ResolvedPageItem current = data.getPageItem(id);
            id = current != null ? current.parentId() : null;
        }
        return out.toArray(new String[0]);
    }

    private static double[] pageLocalPointBounds(
            ResolvedData data,
            ResolvedPageItem item,
            ResolvedTextFrame frame,
            int pageIndex) {
        if (data == null || pageIndex < 0) return null;
        ResolvedPage page = sourcePage(data, pageIndex);
        if (page == null) return null;
        double[] gb = item != null && validBounds(item.geometricBounds())
                ? item.geometricBounds()
                : (frame != null && validBounds(frame.geometricBounds()) ? frame.geometricBounds() : null);
        if (!validBounds(gb)) return null;
        double[] xy = page.toPageRelative(gb);
        if (xy == null || xy.length < 2) return null;
        double height = gb[2] - gb[0];
        double width = gb[3] - gb[1];
        if (height <= 0 || width <= 0) return null;
        return new double[] { xy[1], xy[0], xy[1] + height, xy[0] + width };
    }

    private static ResolvedPage sourcePage(ResolvedData data, int pageIndex) {
        if (data == null || data.pages() == null) return null;
        for (ResolvedPage page : data.pages()) {
            if (page != null && page.index() == pageIndex) return page;
        }
        return null;
    }

    private static boolean tableOnlyStory(IDMLStory story) {
        if (story == null || story.paragraphs() == null) return false;
        StringBuilder sb = new StringBuilder();
        for (IDMLParagraph paragraph : story.paragraphs()) {
            if (paragraph == null) continue;
            String text = paragraph.getPlainText();
            if (text != null) sb.append(text);
        }
        String normalized = sb.toString()
                .replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\u0008", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        return normalized.isEmpty();
    }

    private static int idmlObjectDomId(String idmlId) {
        if (blank(idmlId)) return -1;
        String value = idmlId;
        if (value.charAt(0) == 'u' || value.charAt(0) == 'U') {
            value = value.substring(1);
        }
        int cut = value.indexOf('i');
        if (cut >= 0) value = value.substring(0, cut);
        return parseHex(value);
    }

    private static int tableDomId(String tableId) {
        if (blank(tableId)) return -1;
        int i = tableId.indexOf('i');
        if (i >= 0 && i + 1 < tableId.length()) {
            return parseHex(tableId.substring(i + 1));
        }
        return idmlObjectDomId(tableId);
    }

    private static int parseHex(String hex) {
        if (blank(hex)) return -1;
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean validBounds(double[] bounds) {
        return bounds != null && bounds.length >= 4
                && Double.isFinite(bounds[0])
                && Double.isFinite(bounds[1])
                && Double.isFinite(bounds[2])
                && Double.isFinite(bounds[3])
                && bounds[2] > bounds[0]
                && bounds[3] > bounds[1];
    }

    private static boolean blank(String s) {
        return s == null || s.isEmpty();
    }

    private static String warning(String code, String detail) {
        return "{\"code\":\"" + TableSourceRecord.escape(code)
                + "\",\"stage\":\"stage0.tableSourceIndex\",\"detail\":\""
                + TableSourceRecord.escape(detail) + "\"}";
    }
}
