package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGeometry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLPage;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-frame 텍스트 감싸기 시뮬레이션 + 여백 가이드라인.
 * Stage4_BuildAST에서 분리됨.
 */
public class ASTTextWrapSimulator {

    /**
     * 텍스트 감싸기 존 — 페이지 상의 특정 영역에서 다른 텍스트 프레임의 콘텐츠를 밀어내는 영역.
     * InDesign의 TextWrapPreference를 시뮬레이션하기 위한 데이터.
     */
    private static class TextWrapZone {
        ASTBlock sourceBlock;       // 이 존을 생성한 블록 (자기 자신 제외용)
        int sourceZOrder;           // 존을 생성한 블록의 z-order
        long x, y, width, height;   // 페이지 상대 HWPUNIT
        long marginTop, marginLeft, marginBottom, marginRight; // 감싸기 여백
        String mode;                // "BoundingBoxTextWrap", "JumpObjectTextWrap" 등
    }

    /**
     * 섹션 내 cross-frame 텍스트 감싸기 시뮬레이션.
     *
     * InDesign에서는 텍스트 감싸기(TextWrap)가 설정된 객체가 다른 텍스트 프레임과
     * 겹칠 때, 해당 프레임의 텍스트가 객체 아래/옆으로 밀려난다.
     * HWPX에서는 페이지 레벨 텍스트 프레임이 IN_FRONT_OF_TEXT로 독립 배치되어
     * 이 효과가 없으므로, insetTop을 추가하여 텍스트를 아래로 밀어내는 방식으로 시뮬레이션한다.
     */
    static void simulateTextWrap(ASTSection section) {
        List<ASTBlock> blocks = section.blocks();

        // 1단계: 텍스트 감싸기 존 수집
        List<TextWrapZone> zones = new ArrayList<>();

        // (A) 텍스트 프레임 내 인라인 객체의 텍스트 감싸기
        for (ASTBlock block : blocks) {
            if (block.blockType() != ASTBlock.BlockType.TEXT_FRAME_BLOCK) continue;
            ASTTextFrameBlock tfb = (ASTTextFrameBlock) block;
            collectInlineTextWrapZones(tfb, zones);
        }

        // (B) 페이지 레벨 이미지(ASTFigure)의 텍스트 감싸기
        for (ASTBlock block : blocks) {
            if (block.blockType() != ASTBlock.BlockType.FIGURE) continue;
            ASTFigure fig = (ASTFigure) block;
            String mode = fig.textWrapMode();
            if (mode == null || "None".equals(mode)) continue;

            TextWrapZone zone = new TextWrapZone();
            zone.sourceBlock = fig;
            zone.sourceZOrder = fig.zOrder();
            zone.x = fig.x();
            zone.y = fig.y();
            zone.width = fig.width();
            zone.height = fig.height();
            zone.marginTop = fig.textWrapTop();
            zone.marginLeft = fig.textWrapLeft();
            zone.marginBottom = fig.textWrapBottom();
            zone.marginRight = fig.textWrapRight();
            zone.mode = mode;
            zones.add(zone);
        }

        if (zones.isEmpty()) return;

        // 2단계: 각 텍스트 프레임 블록에 대해 겹침 검사 및 insetTop 조정
        for (ASTBlock block : blocks) {
            if (block.blockType() != ASTBlock.BlockType.TEXT_FRAME_BLOCK) continue;
            ASTTextFrameBlock tfb = (ASTTextFrameBlock) block;

            for (TextWrapZone zone : zones) {
                if (zone.sourceBlock == block) continue; // 자기 자신 건너뜀
                // z-order가 존 출처보다 높은 프레임은 텍스트 감싸기 영향 없음
                // (InDesign "Text Wrap Only Affects Text Beneath" 동작)
                if (tfb.zOrder() > zone.sourceZOrder) continue;

                long pushDown = computeTextWrapPushDown(tfb, zone);
                if (pushDown > 0 && pushDown > tfb.insetTop()) {
                    System.err.println("[TextWrap] frame " + tfb.sourceId()
                            + " z=" + tfb.zOrder()
                            + " insetTop " + tfb.insetTop() + " → " + pushDown
                            + " by zone src=" + (zone.sourceBlock != null ? zone.sourceBlock.sourceId() : "?")
                            + " y=" + zone.y + " h=" + zone.height);
                    tfb.insetTop(pushDown);
                }
            }
        }
    }

    /**
     * 텍스트 프레임 블록 내 인라인 객체에서 텍스트 감싸기 존을 수집.
     * 인라인 객체의 페이지 상 절대 위치를 추정하여 존으로 등록한다.
     */
    private static void collectInlineTextWrapZones(ASTTextFrameBlock tfb,
                                                     List<TextWrapZone> zones) {
        // 프레임 내 Y 오프셋 추적 (인라인 객체의 페이지 절대 Y 위치 추정)
        long yOffset = tfb.insetTop();

        for (ASTParagraph para : tfb.paragraphs()) {
            // 문단 전 간격
            if (para.spaceBefore() != null) {
                yOffset += para.spaceBefore();
            }

            long maxItemHeight = 0;
            for (ASTInlineItem item : para.items()) {
                if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) {
                    ASTInlineObject obj = (ASTInlineObject) item;
                    long objH = obj.containerHeight() > 0 ? obj.containerHeight() : obj.height();
                    long objW = obj.containerWidth() > 0 ? obj.containerWidth() : obj.width();

                    String mode = obj.textWrapMode();
                    if (mode != null && !"None".equals(mode) && objH > 1000) {
                        TextWrapZone zone = new TextWrapZone();
                        zone.sourceBlock = tfb;
                        zone.sourceZOrder = tfb.zOrder();
                        zone.x = tfb.x() + tfb.insetLeft();
                        zone.y = tfb.y() + yOffset;
                        zone.width = objW;
                        zone.height = objH;
                        zone.marginTop = obj.textWrapTop();
                        zone.marginLeft = obj.textWrapLeft();
                        zone.marginBottom = obj.textWrapBottom();
                        zone.marginRight = obj.textWrapRight();
                        zone.mode = mode;
                        zones.add(zone);
                    }

                    maxItemHeight = Math.max(maxItemHeight, objH);
                } else if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                    ASTTextRun run = (ASTTextRun) item;
                    long lineH = 1400; // 기본 14pt 줄 높이
                    if (run.fontSizeHwpunits() != null && run.fontSizeHwpunits() > 0) {
                        lineH = (long) (run.fontSizeHwpunits() * 1.4);
                    }
                    maxItemHeight = Math.max(maxItemHeight, lineH);
                }
            }

            yOffset += maxItemHeight;

            // 문단 후 간격
            if (para.spaceAfter() != null) {
                yOffset += para.spaceAfter();
            }
        }
    }

    /**
     * 텍스트 프레임이 텍스트 감싸기 존과 겹칠 때 필요한 밀어내기 양(insetTop) 계산.
     *
     * @return 필요한 insetTop (HWPUNIT), 겹침 없으면 0
     */
    private static long computeTextWrapPushDown(ASTTextFrameBlock tfb, TextWrapZone zone) {
        // 존의 확장 영역 (감싸기 여백 포함)
        long zoneLeft = zone.x - zone.marginLeft;
        long zoneRight = zone.x + zone.width + zone.marginRight;
        long zoneTop = zone.y - zone.marginTop;
        long zoneBottom = zone.y + zone.height + zone.marginBottom;

        // 프레임 영역
        long frameLeft = tfb.x();
        long frameRight = tfb.x() + tfb.width();
        long frameTop = tfb.y();
        long frameBottom = tfb.y() + tfb.height();

        // 수평 겹침 확인
        boolean hOverlap = frameLeft < zoneRight && frameRight > zoneLeft;
        if (!hOverlap) return 0;

        // 수직 겹침 확인
        boolean vOverlap = frameTop < zoneBottom && frameBottom > zoneTop;
        if (!vOverlap) return 0;

        // JumpObjectTextWrap 또는 존이 프레임 폭의 50% 이상을 덮는 경우 → 텍스트 전체 아래로 밀기
        long overlapLeft = Math.max(frameLeft, zoneLeft);
        long overlapRight = Math.min(frameRight, zoneRight);
        long overlapWidth = overlapRight - overlapLeft;
        boolean isWideOverlap = "JumpObjectTextWrap".equals(zone.mode)
                || overlapWidth * 2 > tfb.width();

        if (isWideOverlap) {
            // 프레임 상단부터 존 하단까지의 거리 = 필요한 insetTop
            long pushDown = zoneBottom - frameTop;
            return pushDown > 0 ? pushDown : 0;
        }

        // 좁은 겹침 (BoundingBoxTextWrap + 프레임 폭의 50% 미만)
        // → 현재는 처리하지 않음 (사이드 래핑은 HWPX에서 지원 불가)
        return 0;
    }

    /**
     * 페이지 여백 가이드라인을 0.1pt 회색 선으로 래스터화하여 ASTFigure 생성.
     * z-order -5000으로 마스터 콘텐츠 위, 본문 콘텐츠 아래에 렌더링된다.
     */
    static ASTFigure createMarginGuideFigure(IDMLPage page) {
        double marginTop = page.marginTop();
        double marginBottom = page.marginBottom();
        double marginLeft = page.marginLeft();
        double marginRight = page.marginRight();

        // 여백이 없으면 가이드 불필요
        if (marginTop <= 0 && marginBottom <= 0 && marginLeft <= 0 && marginRight <= 0) {
            return null;
        }

        double pageWPts = IDMLGeometry.width(page.geometricBounds());
        double pageHPts = IDMLGeometry.height(page.geometricBounds());

        // 여백 바운딩 박스 (페이지 상대 좌표, points)
        double guideX = marginLeft;
        double guideY = marginTop;
        double guideW = pageWPts - marginLeft - marginRight;
        double guideH = pageHPts - marginTop - marginBottom;
        if (guideW <= 0 || guideH <= 0) return null;

        // 래스터화 (144 DPI)
        int dpi = 144;
        double scale = dpi / 72.0;
        double strokePts = 0.5;
        double strokePad = strokePts * scale + 1;

        int pixW = (int) Math.ceil(guideW * scale + strokePad * 2);
        int pixH = (int) Math.ceil(guideH * scale + strokePad * 2);

        BufferedImage image = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(strokePad, strokePad);
        g.setColor(new Color(180, 180, 180)); // light gray
        g.setStroke(new BasicStroke((float) (strokePts * scale)));
        g.draw(new Rectangle2D.Double(0, 0, guideW * scale, guideH * scale));
        g.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] pngData = baos.toByteArray();

            long xHwp = CoordinateConverter.pointsToHwpunits(guideX);
            long yHwp = CoordinateConverter.pointsToHwpunits(guideY);
            long wHwp = CoordinateConverter.pointsToHwpunits(guideW);
            long hHwp = CoordinateConverter.pointsToHwpunits(guideH);

            ASTFigure figure = new ASTFigure();
            figure.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
            figure.x(xHwp);
            figure.y(yHwp);
            figure.width(wHwp);
            figure.height(hHwp);
            figure.zOrder(-5000); // 마스터(-10000) 위, 본문 콘텐츠(0+) 아래
            figure.imageData(pngData);
            figure.imageFormat("png");
            figure.pixelWidth(pixW);
            figure.pixelHeight(pixH);
            return figure;
        } catch (Exception e) {
            return null;
        }
    }
}
