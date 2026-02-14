package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;

import java.util.List;

/**
 * Stage 1: 컨테이너 평탄화.
 * Spread → Page → Frame → Group 계층을 제거하고 모든 객체를 플랫 풀에 등록.
 */
public class Stage1_Flatten {

    public static FlattenedObjectPool flatten(IDMLDocument idmlDoc) {
        FlattenedObjectPool pool = new FlattenedObjectPool();
        int zOrderCounter = 0;

        for (IDMLSpread spread : idmlDoc.spreads()) {
            List<IDMLPage> pages = spread.pages();

            // TextFrames
            for (IDMLTextFrame tf : spread.textFrames()) {
                if (tf.geometricBounds() == null || tf.itemTransform() == null) continue;

                FlatObject fo = new FlatObject();
                fo.selfId(tf.selfId());
                fo.contentType(FlatObject.ContentType.TEXT_FRAME);
                fo.storyId(tf.parentStoryId());
                fo.geometricBounds(tf.geometricBounds());
                fo.itemTransform(tf.itemTransform());
                fo.absoluteBbox(IDMLGeometry.getTransformedBoundingBox(
                        tf.geometricBounds(), tf.itemTransform()));
                fo.sourceObject(tf);
                fo.zOrder(zOrderCounter++);
                fo.pageNumber(assignPage(tf.geometricBounds(), tf.itemTransform(), pages));
                if (tf.parentGroupId() != null) {
                    fo.fromGroup(true);
                    fo.parentGroupId(tf.parentGroupId());
                }
                pool.add(fo);
            }

            // ImageFrames
            for (IDMLImageFrame img : spread.imageFrames()) {
                if (img.geometricBounds() == null || img.itemTransform() == null) continue;

                FlatObject fo = new FlatObject();
                fo.selfId(img.selfId());
                fo.contentType(FlatObject.ContentType.IMAGE_FRAME);
                fo.geometricBounds(img.geometricBounds());
                fo.itemTransform(img.itemTransform());
                fo.absoluteBbox(IDMLGeometry.getTransformedBoundingBox(
                        img.geometricBounds(), img.itemTransform()));
                fo.sourceObject(img);
                fo.zOrder(zOrderCounter++);
                fo.pageNumber(assignPage(img.geometricBounds(), img.itemTransform(), pages));
                fo.fromGroup(img.fromGroup());
                pool.add(fo);
            }

            // VectorShapes
            for (IDMLVectorShape vs : spread.vectorShapes()) {
                if (vs.geometricBounds() == null || vs.itemTransform() == null) continue;

                FlatObject fo = new FlatObject();
                fo.selfId(vs.selfId());
                fo.contentType(FlatObject.ContentType.VECTOR_SHAPE);
                fo.geometricBounds(vs.geometricBounds());
                fo.itemTransform(vs.itemTransform());
                fo.absoluteBbox(IDMLGeometry.getTransformedBoundingBox(
                        vs.geometricBounds(), vs.itemTransform()));
                fo.sourceObject(vs);
                fo.zOrder(zOrderCounter++);
                fo.pageNumber(assignPage(vs.geometricBounds(), vs.itemTransform(), pages));
                fo.fromGroup(vs.fromGroup());

                // 이미 인라인으로 표시된 벡터 (기존 extractInlineGraphicsFromStories에서 설정)
                if (vs.isInline()) {
                    fo.isInline(true);
                    fo.parentStoryId(vs.parentStoryId());
                }

                pool.add(fo);
            }

            // Groups (그룹 자체를 등록 — PNG 렌더링용)
            for (IDMLGroup grp : spread.groups()) {
                flattenGroup(grp, pool, pages, zOrderCounter++, null);
            }
        }

        System.err.println("[Stage1_Flatten] " + pool.summary());
        return pool;
    }

    /**
     * 그룹을 풀에 등록 (재귀).
     * 그룹 자체와 자식 그룹도 각각 등록.
     */
    private static void flattenGroup(IDMLGroup group, FlattenedObjectPool pool,
                                      List<IDMLPage> pages, int zOrder,
                                      String parentGroupId) {
        if (group.geometricBounds() == null || group.itemTransform() == null) return;

        FlatObject fo = new FlatObject();
        fo.selfId(group.selfId());
        fo.contentType(FlatObject.ContentType.GROUP);
        fo.geometricBounds(group.geometricBounds());
        fo.itemTransform(group.itemTransform());
        fo.absoluteBbox(IDMLGeometry.getTransformedBoundingBox(
                group.geometricBounds(), group.itemTransform()));
        fo.sourceObject(group);
        fo.zOrder(zOrder);
        fo.pageNumber(assignPage(group.geometricBounds(), group.itemTransform(), pages));
        if (parentGroupId != null) {
            fo.fromGroup(true);
            fo.parentGroupId(parentGroupId);
        }
        pool.add(fo);

        // 중첩 그룹 재귀
        for (IDMLGroup child : group.childGroups()) {
            flattenGroup(child, pool, pages, zOrder, group.selfId());
        }
    }

    /**
     * 객체의 중심점이 속하는 페이지 번호 반환.
     * 어느 페이지에도 속하지 않으면 첫 페이지 번호 반환.
     */
    private static int assignPage(double[] bounds, double[] transform, List<IDMLPage> pages) {
        for (IDMLPage page : pages) {
            if (page.geometricBounds() == null || page.itemTransform() == null) continue;
            if (IDMLGeometry.isFrameOnPage(bounds, transform,
                    page.geometricBounds(), page.itemTransform())) {
                return page.pageNumber();
            }
        }
        // 폴백: 첫 페이지
        return pages.isEmpty() ? 1 : pages.get(0).pageNumber();
    }
}
