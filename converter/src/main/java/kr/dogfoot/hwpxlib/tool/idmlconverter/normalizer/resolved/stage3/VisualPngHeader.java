package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import java.io.File;
import java.io.FileInputStream;

/**
 * Tiny PNG header reader for Stage 3 image preparation.
 *
 * <p>Reads width/height without decoding pixel data.</p>
 */
public final class VisualPngHeader {
    private VisualPngHeader() {
    }

    public static int[] readDimensions(File pngFile) {
        try (FileInputStream fis = new FileInputStream(pngFile)) {
            byte[] header = new byte[24];
            if (fis.read(header) < 24) return null;
            return readDimensions(header);
        } catch (Exception e) {
            return null;
        }
    }

    public static int[] readDimensions(byte[] pngData) {
        if (pngData == null || pngData.length < 24) return null;
        try {
            if ((pngData[0] & 0xFF) != 0x89 || pngData[1] != 'P') return null;
            int w = ((pngData[16] & 0xFF) << 24) | ((pngData[17] & 0xFF) << 16)
                  | ((pngData[18] & 0xFF) << 8)  |  (pngData[19] & 0xFF);
            int h = ((pngData[20] & 0xFF) << 24) | ((pngData[21] & 0xFF) << 16)
                  | ((pngData[22] & 0xFF) << 8)  |  (pngData[23] & 0xFF);
            return new int[]{w, h};
        } catch (Exception e) {
            return null;
        }
    }
}
