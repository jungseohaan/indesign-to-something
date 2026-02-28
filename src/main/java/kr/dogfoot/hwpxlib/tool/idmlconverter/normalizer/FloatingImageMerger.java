package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * textWrap이 있는 플로팅 이미지(ASTFigure)의 자리차지 효과를 구현한다.
 *
 * HWPX에서 플로팅 이미지의 textWrap은 동작하지 않으므로,
 * 글상자 안에 투명 스페이서를 삽입하여 공간만 확보하고
 * 원본 이미지는 BEHIND_TEXT 플로팅으로 그대로 유지한다.
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

        int count = 0;
        for (ASTFigure fig : wrappingFigures) {
            ASTTextFrameBlock target = findTargetFrame(fig, textFrames);
            if (target == null) continue;

            // 투명 스페이서로 공간만 확보
            ASTInlineObject spacer = createSpacer(fig, target.width());
            int insertIdx = findInsertionIndex(target, fig);
            ASTParagraph spacerPara = new ASTParagraph();
            spacerPara.addItem(spacer);
            target.paragraphs().add(insertIdx, spacerPara);

            // 원본 figure는 섹션 블록에 유지, textWrap만 클리어 → BEHIND_TEXT 플로팅
            fig.textWrapMode(null);
            count++;
        }

        if (count > 0) {
            System.err.println("[FloatingImageMerger] Page " + section.pageNumber()
                    + ": inserted " + count + " spacer(s), kept figures as floating");
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
     * resolved.json에서 전파된 단락별 Y좌표가 있으면 정확한 위치,
     * 없으면 글상자 높이 비율 기반 폴백.
     */
    private static int findInsertionIndex(ASTTextFrameBlock frame, ASTFigure fig) {
        List<ASTParagraph> paragraphs = frame.paragraphs();
        if (paragraphs.isEmpty()) return 0;

        // 이미지 Y를 글상자 로컬 좌표로 변환 (points)
        double figYInFrame = (fig.y() - frame.y()) / 100.0; // HWPUNIT → points

        // resolved Y좌표가 있는 단락이 있으면 정확한 위치 결정
        boolean hasYOffsets = false;
        for (ASTParagraph p : paragraphs) {
            if (p.yOffsetInFrame() >= 0) { hasYOffsets = true; break; }
        }

        if (hasYOffsets) {
            // 이미지 Y보다 뒤에 시작하는 첫 단락 앞에 삽입
            for (int i = 0; i < paragraphs.size(); i++) {
                double paraY = paragraphs.get(i).yOffsetInFrame();
                if (paraY >= 0 && paraY > figYInFrame) {
                    return i;
                }
            }
            return paragraphs.size();
        }

        // 폴백: 글상자 높이 비율 기반
        if (frame.height() <= 0) return paragraphs.size();
        double relY = (double) (fig.y() - frame.y()) / frame.height();
        relY = Math.max(0.0, Math.min(1.0, relY));
        int idx = (int) Math.round(relY * paragraphs.size());
        return Math.max(0, Math.min(idx, paragraphs.size()));
    }

    /**
     * 투명 스페이서 생성 — 글상자 내 공간 확보용.
     * 이미지 높이만큼의 빈 사각형을 삽입하여 텍스트를 밀어낸다.
     */
    private static ASTInlineObject createSpacer(ASTFigure fig, long frameWidth) {
        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.SPACER_RECT);
        obj.width(Math.min(fig.width(), frameWidth));
        obj.height(fig.height());
        // fillColor, strokeColor → null (투명)
        obj.anchoredPosition("Anchored");
        obj.textWrapMode(fig.textWrapMode());
        return obj;
    }
}
