package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * ASTDocument를 번들 디렉토리로 저장.
 *
 * 출력 구조:
 *   output-dir/
 *     ast.json
 *     images/
 *       fig_001.png
 *       inline_001.png
 *       bg_001.png
 */
public class ASTBundleWriter {

    public static void write(ASTDocument doc, File outputDir) throws IOException {
        File imagesDir = new File(outputDir, "images");
        imagesDir.mkdirs();

        int figCounter = 0;
        int inlineCounter = 0;
        int bgCounter = 0;

        // (1) 섹션 내 블록 순회
        for (ASTSection sec : doc.sections()) {
            for (ASTBlock block : sec.blocks()) {
                if (block instanceof ASTFigure) {
                    ASTFigure fig = (ASTFigure) block;
                    if (fig.imageData() != null) {
                        String ext = extensionFor(fig.imageFormat());
                        String name = String.format("fig_%03d.%s", ++figCounter, ext);
                        Files.write(new File(imagesDir, name).toPath(), fig.imageData());
                        fig.bundlePath("images/" + name);
                    }
                } else if (block instanceof ASTTextFrameBlock) {
                    inlineCounter = saveTextFrameInlineImages(
                            (ASTTextFrameBlock) block, imagesDir, inlineCounter);
                } else if (block instanceof ASTTable) {
                    inlineCounter = saveTableInlineImages(
                            (ASTTable) block, imagesDir, inlineCounter);
                }
            }
        }

        // (2) 페이지 배경 이미지 저장
        for (ASTPageBackground bg : doc.backgrounds()) {
            if (bg.pngData() != null) {
                String name = String.format("bg_%03d.png", ++bgCounter);
                Files.write(new File(imagesDir, name).toPath(), bg.pngData());
                bg.bundlePath("images/" + name);
            }
        }

        // (3) JSON 직렬화 (bundlePath가 설정된 상태)
        String json = ASTSerializer.toJson(doc);
        Files.write(new File(outputDir, "ast.json").toPath(),
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static int saveTextFrameInlineImages(ASTTextFrameBlock tf,
                                                  File imagesDir, int counter) throws IOException {
        for (ASTParagraph para : tf.paragraphs()) {
            counter = saveParagraphInlineImages(para, imagesDir, counter);
        }
        return counter;
    }

    private static int saveTableInlineImages(ASTTable table,
                                              File imagesDir, int counter) throws IOException {
        if (table.rows() == null) return counter;
        for (ASTTableRow row : table.rows()) {
            if (row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell.paragraphs() == null) continue;
                for (ASTParagraph para : cell.paragraphs()) {
                    counter = saveParagraphInlineImages(para, imagesDir, counter);
                }
            }
        }
        return counter;
    }

    private static int saveParagraphInlineImages(ASTParagraph para,
                                                  File imagesDir, int counter) throws IOException {
        if (para.items() == null) return counter;
        for (ASTInlineItem item : para.items()) {
            if (item instanceof ASTInlineObject) {
                counter = saveInlineObjectImage((ASTInlineObject) item, imagesDir, counter);
            }
        }
        return counter;
    }

    private static int saveInlineObjectImage(ASTInlineObject obj,
                                              File imagesDir, int counter) throws IOException {
        // 이미지 데이터 저장
        if (obj.imageData() != null) {
            String ext = extensionFor(obj.imageFormat());
            String name = String.format("inline_%03d.%s", ++counter, ext);
            Files.write(new File(imagesDir, name).toPath(), obj.imageData());
            obj.bundlePath("images/" + name);
        }

        // 오버레이 프레임 재귀 순회
        if (obj.overlayFrames() != null) {
            for (ASTInlineObject overlay : obj.overlayFrames()) {
                counter = saveInlineObjectImage(overlay, imagesDir, counter);
            }
        }

        // 인라인 텍스트 프레임 내 단락 순회
        if (obj.paragraphs() != null) {
            for (ASTParagraph para : obj.paragraphs()) {
                counter = saveParagraphInlineImages(para, imagesDir, counter);
            }
        }

        // 인라인 테이블 순회
        if (obj.inlineTables() != null) {
            for (ASTTable table : obj.inlineTables()) {
                counter = saveTableInlineImages(table, imagesDir, counter);
            }
        }

        return counter;
    }

    private static String extensionFor(String imageFormat) {
        if (imageFormat == null) return "png";
        switch (imageFormat.toLowerCase()) {
            case "jpeg":
            case "jpg":
                return "jpg";
            case "gif":
                return "gif";
            case "tiff":
            case "tif":
                return "tif";
            case "bmp":
                return "bmp";
            case "svg":
                return "svg";
            default:
                return "png";
        }
    }
}
