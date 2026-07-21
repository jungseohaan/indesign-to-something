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
        { id: "pass.decoration_groups", phase: "04_pageRendering", executor: "exportInlineObjects", unit: "PAGE_OBJECT", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "SHELL_CANDIDATE", mayHideText: true, outputDir: "rendered_frames" },
        { id: "pass.page_textless_graphic_groups", phase: "06b_pageTextlessGroups", executor: "exportSingleTextlessPagePlanes", unit: "PAGE", mode: "TEXTLESS_PAGE_PLANE", candidatePurpose: "PAGE_PLANE", mayHideText: true, outputDir: "rendered_frames" }
    ];
}
