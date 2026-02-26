package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 번들 디렉토리에서 ASTDocument 복원.
 *
 * 입력 구조:
 *   bundle-dir/
 *     ast.json
 *     images/
 *       fig_001.png
 *       inline_001.png
 *       bg_001.png
 */
public class ASTBundleReader {

    public static ASTDocument read(File bundleDir) throws IOException {
        File jsonFile = new File(bundleDir, "ast.json");
        String json = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
        ASTDocument doc = ASTDeserializer.fromJson(json);

        // bundlePath → imageData/pngData 복원
        for (ASTSection sec : doc.sections()) {
            for (ASTBlock block : sec.blocks()) {
                if (block instanceof ASTFigure) {
                    ASTFigure fig = (ASTFigure) block;
                    loadFigureImage(fig, bundleDir);
                } else if (block instanceof ASTTextFrameBlock) {
                    loadTextFrameInlineImages((ASTTextFrameBlock) block, bundleDir);
                } else if (block instanceof ASTTable) {
                    loadTableInlineImages((ASTTable) block, bundleDir);
                }
            }
        }

        for (ASTPageBackground bg : doc.backgrounds()) {
            if (bg.bundlePath() != null) {
                File imgFile = new File(bundleDir, bg.bundlePath());
                if (imgFile.exists()) {
                    bg.pngData(Files.readAllBytes(imgFile.toPath()));
                }
            }
        }

        return doc;
    }

    private static void loadFigureImage(ASTFigure fig, File bundleDir) throws IOException {
        if (fig.bundlePath() != null) {
            File imgFile = new File(bundleDir, fig.bundlePath());
            if (imgFile.exists()) {
                fig.imageData(Files.readAllBytes(imgFile.toPath()));
            }
        }
    }

    private static void loadTextFrameInlineImages(ASTTextFrameBlock tf,
                                                    File bundleDir) throws IOException {
        for (ASTParagraph para : tf.paragraphs()) {
            loadParagraphInlineImages(para, bundleDir);
        }
    }

    private static void loadTableInlineImages(ASTTable table,
                                               File bundleDir) throws IOException {
        if (table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell.paragraphs() == null) continue;
                for (ASTParagraph para : cell.paragraphs()) {
                    loadParagraphInlineImages(para, bundleDir);
                }
            }
        }
    }

    private static void loadParagraphInlineImages(ASTParagraph para,
                                                    File bundleDir) throws IOException {
        if (para.items() == null) return;
        for (ASTInlineItem item : para.items()) {
            if (item instanceof ASTInlineObject) {
                loadInlineObjectImage((ASTInlineObject) item, bundleDir);
            }
        }
    }

    private static void loadInlineObjectImage(ASTInlineObject obj,
                                               File bundleDir) throws IOException {
        if (obj.bundlePath() != null) {
            File imgFile = new File(bundleDir, obj.bundlePath());
            if (imgFile.exists()) {
                obj.imageData(Files.readAllBytes(imgFile.toPath()));
            }
        }

        // 오버레이 프레임 재귀
        if (obj.overlayFrames() != null) {
            for (ASTInlineObject overlay : obj.overlayFrames()) {
                loadInlineObjectImage(overlay, bundleDir);
            }
        }

        // 인라인 텍스트 프레임 내 단락
        if (obj.paragraphs() != null) {
            for (ASTParagraph para : obj.paragraphs()) {
                loadParagraphInlineImages(para, bundleDir);
            }
        }

        // 인라인 테이블
        if (obj.inlineTables() != null) {
            for (ASTTable table : obj.inlineTables()) {
                loadTableInlineImages(table, bundleDir);
            }
        }
    }
}
