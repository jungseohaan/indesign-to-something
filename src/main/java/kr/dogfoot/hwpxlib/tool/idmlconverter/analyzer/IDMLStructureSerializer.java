package kr.dogfoot.hwpxlib.tool.idmlconverter.analyzer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.analyzer.IDMLStructure.*;

/**
 * IDMLStructure를 JSON으로 직렬화.
 * 외부 라이브러리 없이 간단한 JSON 생성.
 */
public class IDMLStructureSerializer {

    public static String toJson(IDMLStructure structure) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        // spreads
        sb.append("  \"spreads\": [\n");
        boolean firstSpread = true;
        for (SpreadInfo spread : structure.getSpreads()) {
            if (!firstSpread) sb.append(",\n");
            firstSpread = false;
            sb.append(spreadToJson(spread, "    "));
        }
        sb.append("\n  ],\n");

        // totals
        sb.append("  \"total_text_frames\": ").append(structure.getTotalTextFrames()).append(",\n");
        sb.append("  \"total_image_frames\": ").append(structure.getTotalImageFrames()).append(",\n");
        sb.append("  \"total_vector_shapes\": ").append(structure.getTotalVectorShapes()).append(",\n");
        sb.append("  \"total_tables\": ").append(structure.getTotalTables()).append("\n");

        sb.append("}");
        return sb.toString();
    }

    private static String spreadToJson(SpreadInfo spread, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"id\": ").append(jsonString(spread.getId())).append(",\n");
        sb.append(indent).append("  \"page_count\": ").append(spread.getPageCount()).append(",\n");
        sb.append(indent).append("  \"text_frame_count\": ").append(spread.getTextFrameCount()).append(",\n");
        sb.append(indent).append("  \"image_frame_count\": ").append(spread.getImageFrameCount()).append(",\n");
        sb.append(indent).append("  \"vector_count\": ").append(spread.getVectorCount()).append(",\n");

        // 스프레드 바운드 정보
        sb.append(indent).append("  \"bounds_top\": ").append(spread.getBoundsTop()).append(",\n");
        sb.append(indent).append("  \"bounds_left\": ").append(spread.getBoundsLeft()).append(",\n");
        sb.append(indent).append("  \"bounds_bottom\": ").append(spread.getBoundsBottom()).append(",\n");
        sb.append(indent).append("  \"bounds_right\": ").append(spread.getBoundsRight()).append(",\n");
        sb.append(indent).append("  \"total_width\": ").append(spread.getTotalWidth()).append(",\n");
        sb.append(indent).append("  \"total_height\": ").append(spread.getTotalHeight()).append(",\n");

        // pages
        sb.append(indent).append("  \"pages\": [\n");
        boolean firstPage = true;
        for (PageInfo page : spread.getPages()) {
            if (!firstPage) sb.append(",\n");
            firstPage = false;
            sb.append(pageToJson(page, indent + "    "));
        }
        sb.append("\n").append(indent).append("  ]\n");

        sb.append(indent).append("}");
        return sb.toString();
    }

    private static String pageToJson(PageInfo page, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"id\": ").append(jsonString(page.getId())).append(",\n");
        sb.append(indent).append("  \"name\": ").append(jsonString(page.getName())).append(",\n");
        sb.append(indent).append("  \"page_number\": ").append(page.getPageNumber()).append(",\n");
        sb.append(indent).append("  \"width\": ").append(page.getWidth()).append(",\n");
        sb.append(indent).append("  \"height\": ").append(page.getHeight()).append(",\n");

        // 마진 정보
        sb.append(indent).append("  \"margin_top\": ").append(page.getMarginTop()).append(",\n");
        sb.append(indent).append("  \"margin_bottom\": ").append(page.getMarginBottom()).append(",\n");
        sb.append(indent).append("  \"margin_left\": ").append(page.getMarginLeft()).append(",\n");
        sb.append(indent).append("  \"margin_right\": ").append(page.getMarginRight()).append(",\n");
        sb.append(indent).append("  \"column_count\": ").append(page.getColumnCount()).append(",\n");
        sb.append(indent).append("  \"master_spread\": ").append(jsonString(page.getMasterSpread())).append(",\n");

        // geometric bounds
        sb.append(indent).append("  \"geometric_bounds\": ");
        if (page.getGeometricBounds() != null) {
            sb.append(doubleArrayToJson(page.getGeometricBounds()));
        } else {
            sb.append("null");
        }
        sb.append(",\n");

        // item transform
        sb.append(indent).append("  \"item_transform\": ");
        if (page.getItemTransform() != null) {
            sb.append(doubleArrayToJson(page.getItemTransform()));
        } else {
            sb.append("null");
        }
        sb.append(",\n");

        // frames
        sb.append(indent).append("  \"frames\": [\n");
        boolean firstFrame = true;
        for (FrameInfo frame : page.getFrames()) {
            if (!firstFrame) sb.append(",\n");
            firstFrame = false;
            sb.append(frameToJson(frame, indent + "    "));
        }
        sb.append("\n").append(indent).append("  ]\n");

        sb.append(indent).append("}");
        return sb.toString();
    }

    private static String doubleArrayToJson(double[] arr) {
        if (arr == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String frameToJson(FrameInfo frame, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"id\": ").append(jsonString(frame.getId())).append(",\n");
        sb.append(indent).append("  \"type\": ").append(jsonString(frame.getType())).append(",\n");
        sb.append(indent).append("  \"label\": ").append(jsonString(frame.getLabel())).append(",\n");
        sb.append(indent).append("  \"x\": ").append(frame.getX()).append(",\n");
        sb.append(indent).append("  \"y\": ").append(frame.getY()).append(",\n");
        sb.append(indent).append("  \"width\": ").append(frame.getWidth()).append(",\n");
        sb.append(indent).append("  \"height\": ").append(frame.getHeight());

        // 이미지 타입에만 추가 필드 포함
        if ("image".equals(frame.getType())) {
            sb.append(",\n");
            sb.append(indent).append("  \"link_path\": ").append(jsonString(frame.getLinkPath())).append(",\n");
            sb.append(indent).append("  \"needs_preview\": ").append(frame.isNeedsPreview());
        }

        sb.append("\n").append(indent).append("}");
        return sb.toString();
    }

    /**
     * 문자열을 JSON 문자열로 이스케이프.
     */
    private static String jsonString(String value) {
        if (value == null) return "null";

        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
