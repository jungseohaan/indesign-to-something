package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * textWrap이 있는 플로팅 이미지(ASTFigure)를 본문 글상자(ASTTextFrameBlock) 안의
 * 인라인 이미지(ASTInlineObject)로 변환한다.
 *
 * HWPX에서 플로팅 이미지의 textWrap은 동작하지 않으므로,
 * 이미지를 글상자 문단 안에 넣어 "자리차지" 효과를 구현한다.
 *
 * 호출 시점: Stage4_BuildAST 이후, HWPX 변환 이전.
 */
public class FloatingImageMerger {

    public static void merge(ASTDocument doc) {
        for (ASTSection section : doc.sections()) {
            mergeSection(section);
        }
    }

    private static void mergeSection(ASTSection section) {
        List<ASTFigure> wrappingFigures = new ArrayList<>();
        List<ASTTextFrameBlock> textFrames = new ArrayList<>();

        for (ASTBlock block : section.blocks()) {
            if (block instanceof ASTFigure) {
                ASTFigure fig = (ASTFigure) block;
                if (fig.textWrapMode() != null && !"None".equals(fig.textWrapMode())) {
                    wrappingFigures.add(fig);
                }
            } else if (block instanceof ASTTextFrameBlock) {
                textFrames.add((ASTTextFrameBlock) block);
            }
        }

        if (wrappingFigures.isEmpty() || textFrames.isEmpty()) return;

        // Y좌표 순 정렬
        wrappingFigures.sort(Comparator.comparingLong(ASTFigure::y));

        List<ASTFigure> merged = new ArrayList<>();
        for (ASTFigure fig : wrappingFigures) {
            ASTTextFrameBlock target = findTargetFrame(fig, textFrames);
            if (target == null) continue;

            ASTInlineObject inlineObj = convertToInline(fig);
            int insertIdx = findInsertionIndex(target, fig);

            // 이미지 전용 문단 생성 & 삽입
            ASTParagraph imgPara = new ASTParagraph();
            imgPara.addItem(inlineObj);
            target.paragraphs().add(insertIdx, imgPara);

            merged.add(fig);
        }

        // 머지된 figure를 섹션 블록에서 제거
        if (!merged.isEmpty()) {
            section.blocks().removeAll(merged);
            System.out.println("[FloatingImageMerger] Page " + section.pageNumber()
                    + ": merged " + merged.size() + " wrapping image(s) into text frames");
        }
    }

    /**
     * figure의 Y 범위와 겹치는 글상자를 찾는다.
     * 겹치는 프레임이 여러 개면 가장 넓은(본문) 프레임을 선택.
     * 겹치지 않으면 figure에 가장 가까운 프레임을 선택.
     */
    private static ASTTextFrameBlock findTargetFrame(ASTFigure fig, List<ASTTextFrameBlock> frames) {
        long figTop = fig.y();
        long figBottom = fig.y() + fig.height();

        ASTTextFrameBlock bestOverlap = null;
        long bestOverlapWidth = 0;

        ASTTextFrameBlock closest = null;
        long closestDist = Long.MAX_VALUE;

        for (ASTTextFrameBlock tf : frames) {
            // 텍스트가 없는 배경 전용 블록은 스킵
            if (tf.isBackgroundOnly()) continue;
            // 문단이 없는 빈 프레임도 스킵
            if (tf.paragraphs().isEmpty()) continue;

            long tfTop = tf.y();
            long tfBottom = tf.y() + tf.height();

            // Y 범위 겹침 확인
            if (figTop < tfBottom && figBottom > tfTop) {
                if (tf.width() > bestOverlapWidth) {
                    bestOverlap = tf;
                    bestOverlapWidth = tf.width();
                }
            }

            // 가장 가까운 프레임 추적
            long dist = Math.min(Math.abs(figTop - tfTop), Math.abs(figTop - tfBottom));
            dist = Math.min(dist, Math.abs(figBottom - tfTop));
            if (dist < closestDist) {
                closest = tf;
                closestDist = dist;
            }
        }

        return bestOverlap != null ? bestOverlap : closest;
    }

    /**
     * 글상자 내 문단 삽입 위치를 결정한다.
     * figure의 Y 좌표를 글상자 높이에 대한 비율로 환산하여
     * 문단 목록의 해당 비율 위치에 삽입.
     */
    private static int findInsertionIndex(ASTTextFrameBlock frame, ASTFigure fig) {
        if (frame.paragraphs().isEmpty()) return 0;
        if (frame.height() <= 0) return frame.paragraphs().size();

        double relY = (double) (fig.y() - frame.y()) / frame.height();
        relY = Math.max(0.0, Math.min(1.0, relY));

        int idx = (int) Math.round(relY * frame.paragraphs().size());
        return Math.max(0, Math.min(idx, frame.paragraphs().size()));
    }

    /**
     * ASTFigure → ASTInlineObject 변환.
     * 이미지 데이터와 textWrap 속성을 그대로 이전.
     */
    private static ASTInlineObject convertToInline(ASTFigure fig) {
        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.IMAGE);
        obj.width(fig.width());
        obj.height(fig.height());
        obj.imageData(fig.imageData());
        obj.imageFormat(fig.imageFormat());
        obj.imagePath(fig.imagePath());
        obj.pixelWidth(fig.pixelWidth());
        obj.pixelHeight(fig.pixelHeight());
        obj.bundlePath(fig.bundlePath());

        // 자리차지: Anchored 모드 + textWrap 속성
        obj.anchoredPosition("Anchored");
        obj.textWrapMode(fig.textWrapMode());
        obj.textWrapSide(fig.textWrapSide());
        obj.textWrapTop(fig.textWrapTop());
        obj.textWrapLeft(fig.textWrapLeft());
        obj.textWrapBottom(fig.textWrapBottom());
        obj.textWrapRight(fig.textWrapRight());

        return obj;
    }
}
