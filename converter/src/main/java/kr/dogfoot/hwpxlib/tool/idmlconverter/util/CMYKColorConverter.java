package kr.dogfoot.hwpxlib.tool.idmlconverter.util;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.InputStream;

/**
 * CMYK → sRGB 변환 유틸리티.
 * <p>
 * ICC 프로파일 기반 변환을 우선 사용하고, 프로파일 로드 실패 시
 * 단순 수식 폴백으로 동작한다.
 * <p>
 * 프로파일 탐색 순서:
 * <ol>
 *   <li>클래스패스 {@code /icc/GenericCMYK.icc}</li>
 *   <li>macOS {@code /System/Library/ColorSync/Profiles/Generic CMYK Profile.icc}</li>
 *   <li>Adobe 공통 경로 (Windows/Linux)</li>
 * </ol>
 * CMYK 입력값 범위: 0.0 ~ 1.0
 */
public class CMYKColorConverter {

    private static final ICC_ColorSpace CMYK_CS;
    private static final boolean ICC_AVAILABLE;

    static {
        ICC_ColorSpace loaded = null;
        try {
            ICC_Profile profile = loadCMYKProfile();
            if (profile != null) {
                loaded = new ICC_ColorSpace(profile);
                // 검증: 변환이 정상 동작하는지 확인
                loaded.toRGB(new float[]{0f, 0f, 0f, 1f});
            }
        } catch (Exception e) {
            System.err.println("[CMYKColorConverter] ICC profile load failed, using fallback: " + e.getMessage());
            loaded = null;
        }
        CMYK_CS = loaded;
        ICC_AVAILABLE = loaded != null;
    }

    /**
     * CMYK → HEX 문자열 (예: "#FF8000").
     *
     * @param c Cyan    0.0–1.0
     * @param m Magenta 0.0–1.0
     * @param y Yellow  0.0–1.0
     * @param k Key     0.0–1.0
     */
    public static String cmykToHex(double c, double m, double y, double k) {
        float[] rgb = toRGB(c, m, y, k);
        int r = clamp8(Math.round(rgb[0] * 255));
        int g = clamp8(Math.round(rgb[1] * 255));
        int b = clamp8(Math.round(rgb[2] * 255));
        return String.format("#%02X%02X%02X", r, g, b);
    }

    /**
     * CMYK → java.awt.Color.
     *
     * @param c Cyan    0.0–1.0
     * @param m Magenta 0.0–1.0
     * @param y Yellow  0.0–1.0
     * @param k Key     0.0–1.0
     */
    public static Color cmykToColor(double c, double m, double y, double k) {
        float[] rgb = toRGB(c, m, y, k);
        return new Color(
                clampF(rgb[0]),
                clampF(rgb[1]),
                clampF(rgb[2]));
    }

    /**
     * ICC 프로파일이 로드되었는지 여부.
     */
    public static boolean isIccAvailable() {
        return ICC_AVAILABLE;
    }

    // ── 내부 구현 ──

    private static float[] toRGB(double c, double m, double y, double k) {
        if (ICC_AVAILABLE) {
            return iccToRGB(c, m, y, k);
        }
        return fallbackToRGB(c, m, y, k);
    }

    /**
     * ICC 프로파일을 통한 정확한 CMYK → sRGB 변환.
     * CMYK 색공간 → CIEXYZ → sRGB 경로로 변환한다.
     */
    private static float[] iccToRGB(double c, double m, double y, double k) {
        float[] cmyk = {(float) c, (float) m, (float) y, (float) k};
        try {
            return CMYK_CS.toRGB(cmyk);
        } catch (Exception e) {
            return fallbackToRGB(c, m, y, k);
        }
    }

    /**
     * ICC 프로파일 없이 단순 수식으로 변환 (폴백).
     */
    private static float[] fallbackToRGB(double c, double m, double y, double k) {
        float r = (float) ((1 - c) * (1 - k));
        float g = (float) ((1 - m) * (1 - k));
        float b = (float) ((1 - y) * (1 - k));
        return new float[]{r, g, b};
    }

    // ── ICC 프로파일 로드 ──

    private static ICC_Profile loadCMYKProfile() {
        // 1) 클래스패스 리소스
        ICC_Profile profile = loadFromClasspath("/icc/GenericCMYK.icc");
        if (profile != null) return profile;

        // 2) macOS 시스템 프로파일
        profile = loadFromFile("/System/Library/ColorSync/Profiles/Generic CMYK Profile.icc");
        if (profile != null) return profile;

        // 3) Adobe 공통 경로 (Windows)
        profile = loadFromFile("C:\\Windows\\System32\\spool\\drivers\\color\\USWebCoatedSWOP.icc");
        if (profile != null) return profile;

        // 4) Linux 공통 경로
        profile = loadFromFile("/usr/share/color/icc/ghostscript/default_cmyk.icc");
        if (profile != null) return profile;

        return null;
    }

    private static ICC_Profile loadFromClasspath(String path) {
        try (InputStream is = CMYKColorConverter.class.getResourceAsStream(path)) {
            if (is != null) {
                ICC_Profile profile = ICC_Profile.getInstance(is);
                if (profile.getColorSpaceType() == ColorSpace.TYPE_CMYK) {
                    return profile;
                }
            }
        } catch (Exception e) {
            // 개별 프로파일 로드 실패는 다음 경로 시도
        }
        return null;
    }

    private static ICC_Profile loadFromFile(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (f.isFile()) {
                ICC_Profile profile = ICC_Profile.getInstance(path);
                if (profile.getColorSpaceType() == ColorSpace.TYPE_CMYK) {
                    return profile;
                }
            }
        } catch (Exception e) {
            // 개별 프로파일 로드 실패는 다음 경로 시도
        }
        return null;
    }

    private static int clamp8(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static float clampF(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
