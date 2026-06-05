package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

/**
 * Controls which pipeline owns visual rendering.
 *
 * Editable text remains HWPX text. Shape/table/text-frame visuals are expected
 * to come from InDesign-exported PNGs, so HWP-native decoration is disabled.
 */
public final class VisualSourcePolicy {
    private static final boolean HWP_NATIVE_TEXT_BOX_GRAPHICS = false;
    private static final boolean JAVA_SYNTHETIC_GRAPHIC_PNGS = false;

    private VisualSourcePolicy() {
    }

    public static boolean useHwpNativeTextBoxGraphics() {
        return HWP_NATIVE_TEXT_BOX_GRAPHICS;
    }

    public static boolean useJavaSyntheticGraphicPngs() {
        return JAVA_SYNTHETIC_GRAPHIC_PNGS;
    }
}
