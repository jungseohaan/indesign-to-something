/*
 * Static extraction pass contracts.
 *
 * This module only declares pass metadata consumed by the extraction
 * orchestrator and render matchers. It must not inspect source objects or make
 * ownership decisions.
 */

function _buildExtractionPasses() {
    return [
        { id: "pass.page_backgrounds", phase: "04_pageRendering", executor: "exportPageBackgrounds", unit: "PAGE", mode: "NO_OP", candidatePurpose: "NO_VISIBLE_CANDIDATE", mayHideText: false, outputDir: "rendered_frames" },
        { id: "pass.inline_objects", phase: "04_pageRendering", executor: "exportInlineObjects", unit: "INLINE_OBJECT", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "INLINE_CANDIDATE", mayHideText: true, outputDir: "rendered_frames" },
        { id: "pass.image_placed_frames", phase: "04_pageRendering", executor: "exportInlineObjects", unit: "PAGE_OBJECT", mode: "ORIGINAL_VISUAL", candidatePurpose: "CONTENT_CANDIDATE", mayHideText: false, outputDir: "rendered_frames" },
        // 배치 이미지가 Group 으로 묶인 경우(동영상 미리보기 사진 등). plan 빌더와
        // 소유권 로직(planner_bundles/object_plans/source_slot_registry)이 이 pass 이름에
        // 의존하지만 렌더 실행기가 등록된 적이 없어 PNG 가 안 만들어지고 file=null 로
        // 유실됐다(p13 대화 사진). image_placed_frames 와 같은 exportInlineObjects 로 렌더한다.
        { id: "pass.image_textless_groups", phase: "04_pageRendering", executor: "exportInlineObjects", unit: "PAGE_OBJECT", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "CONTENT_CANDIDATE", mayHideText: false, outputDir: "rendered_frames" },
        { id: "pass.decoration_groups", phase: "04_pageRendering", executor: "exportInlineObjects", unit: "PAGE_OBJECT", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "SHELL_CANDIDATE", mayHideText: true, outputDir: "rendered_frames" },
        { id: "pass.master_page_graphics", phase: "04_pageRendering", executor: "exportInlineObjects", unit: "MASTER_ITEM", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "MASTER_CANDIDATE", mayHideText: true, outputDir: "rendered_frames" },
        { id: "pass.page_textless_graphic_groups", phase: "06b_pageTextlessGroups", executor: "exportSingleTextlessPagePlanes", unit: "PAGE", mode: "TEXTLESS_PAGE_PLANE", candidatePurpose: "PAGE_PLANE", mayHideText: true, outputDir: "rendered_frames" }
    ];
}
