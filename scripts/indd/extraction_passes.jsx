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
        { id: "pass.image_placed_frames", phase: "06_imgFrames", executor: "exportImagePlacedFrames", unit: "ITEM", mode: "ORIGINAL_VISUAL", candidatePurpose: "CONTENT_CANDIDATE", mayHideText: false, outputDir: "rendered_frames" },
        { id: "pass.image_textless_groups", phase: "06_imgFrames", executor: "exportImagePlacedFrames", unit: "GROUP", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "CONTENT_CANDIDATE", mayHideText: true, outputDir: "rendered_frames" },
        { id: "pass.decoration_groups", phase: "07_decoGroups", executor: "exportDecorationGroups", unit: "GROUP_OR_ITEM", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "SHELL_CANDIDATE", mayHideText: true, outputDir: "rendered_frames" },
        { id: "pass.complex_graphic_frames", phase: "08_complexFrames", executor: "exportComplexGraphicFrames", unit: "GROUP", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "CONTENT_CANDIDATE", mayHideText: true, outputDir: "rendered_frames" },
        { id: "pass.vector_shape_frames", phase: "09_shapeFrames", executor: "exportDecorationGroups", unit: "ITEM", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "VECTOR_CANDIDATE", mayHideText: false, outputDir: "rendered_frames" },
        { id: "pass.master_page_graphics", phase: "09b_masterGraphics", executor: "exportMasterPageGraphics", unit: "MASTER_ITEM", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "MASTER_CANDIDATE", mayHideText: true, outputDir: "rendered_frames" },
        { id: "pass.editable_textframe_visual_shells", phase: "09c_editableTFVisualShells", executor: "exportEditableTextFrameVisualShells", unit: "TEXT_FRAME", mode: "TEXTLESS_CANDIDATE", candidatePurpose: "SHELL_CANDIDATE", mayHideText: true, outputDir: "rendered_frames" }
    ];
}
