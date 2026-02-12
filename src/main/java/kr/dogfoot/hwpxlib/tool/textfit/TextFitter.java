package kr.dogfoot.hwpxlib.tool.textfit;

import java.util.ArrayList;
import java.util.List;

/**
 * 연결된 텍스트 프레임 체인에 텍스트를 분배한다.
 * 각 프레임의 크기와 기본 폰트 크기/줄간격을 기반으로
 * 텍스트가 각 프레임에 들어갈 분량을 계산한다.
 */
public class TextFitter {

    /**
     * 프레임 크기 정보.
     */
    public static class FrameInfo {
        private final double widthPoints;
        private final double heightPoints;

        private FrameInfo(double widthPoints, double heightPoints) {
            this.widthPoints = widthPoints;
            this.heightPoints = heightPoints;
        }

        public static FrameInfo fromPoints(double widthPoints, double heightPoints) {
            return new FrameInfo(widthPoints, heightPoints);
        }

        public double widthPoints() { return widthPoints; }
        public double heightPoints() { return heightPoints; }

        /**
         * 프레임에 들어갈 수 있는 대략적인 글자 수를 추정한다.
         *
         * @param fontSizePt      폰트 크기 (points)
         * @param lineSpacingRatio 줄간격 배율 (예: 1.6)
         * @return 추정 글자 수
         */
        public int estimateCapacity(double fontSizePt, double lineSpacingRatio) {
            if (widthPoints <= 0 || heightPoints <= 0 || fontSizePt <= 0) {
                return 0;
            }
            // 한 줄에 들어가는 글자 수 (한글 기준 폰트 크기 ≈ 글자 폭)
            double charsPerLine = widthPoints / (fontSizePt * 0.9);
            // 줄 수
            double lineHeight = fontSizePt * lineSpacingRatio;
            double lines = heightPoints / lineHeight;

            return Math.max(1, (int) (charsPerLine * lines));
        }
    }

    /**
     * 텍스트를 프레임 크기에 맞게 분할한다.
     *
     * @param fullText         전체 텍스트
     * @param frameInfos       프레임 크기 정보 목록
     * @param defaultFontSize  기본 폰트 크기 (points)
     * @param lineSpacingRatio 줄간격 배율
     * @return 각 프레임에 할당된 텍스트 목록
     */
    public static List<String> fitText(String fullText, List<FrameInfo> frameInfos,
                                        double defaultFontSize, double lineSpacingRatio) {
        List<String> result = new ArrayList<String>();

        if (frameInfos == null || frameInfos.isEmpty()) {
            result.add(fullText != null ? fullText : "");
            return result;
        }

        if (fullText == null || fullText.isEmpty()) {
            for (int i = 0; i < frameInfos.size(); i++) {
                result.add("");
            }
            return result;
        }

        // 각 프레임의 용량 추정
        int[] capacities = new int[frameInfos.size()];
        int totalCapacity = 0;
        for (int i = 0; i < frameInfos.size(); i++) {
            capacities[i] = frameInfos.get(i).estimateCapacity(defaultFontSize, lineSpacingRatio);
            totalCapacity += capacities[i];
        }

        // 텍스트 분배
        int textLen = fullText.length();
        int offset = 0;

        for (int i = 0; i < frameInfos.size(); i++) {
            if (i == frameInfos.size() - 1) {
                // 마지막 프레임은 나머지 전부
                result.add(offset < textLen ? fullText.substring(offset) : "");
            } else {
                int capacity = capacities[i];

                // 총 용량 대비 비율로 실제 분배량 조정
                if (totalCapacity > 0 && textLen > totalCapacity) {
                    capacity = (int) ((double) capacity / totalCapacity * textLen);
                }

                if (offset + capacity >= textLen) {
                    result.add(fullText.substring(offset));
                    offset = textLen;
                } else {
                    // 단어/줄 경계에서 자르기 시도
                    int cutPoint = findCutPoint(fullText, offset, offset + capacity);
                    result.add(fullText.substring(offset, cutPoint));
                    offset = cutPoint;
                }
            }
        }

        return result;
    }

    /**
     * 적절한 잘라내기 지점을 찾는다 (줄바꿈 > 공백 > 지정 위치 순으로 시도).
     */
    private static int findCutPoint(String text, int start, int preferredEnd) {
        int end = Math.min(preferredEnd, text.length());

        // 1. preferredEnd 근처의 줄바꿈 찾기
        int searchStart = Math.max(start, end - 20);
        int searchEnd = Math.min(text.length(), end + 20);
        for (int i = end; i < searchEnd; i++) {
            if (text.charAt(i) == '\n') {
                return i + 1;
            }
        }
        for (int i = end - 1; i >= searchStart; i--) {
            if (text.charAt(i) == '\n') {
                return i + 1;
            }
        }

        // 2. 공백 경계 찾기
        for (int i = end; i < searchEnd; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        for (int i = end - 1; i >= searchStart; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }

        // 3. 그대로 자르기
        return end;
    }
}
