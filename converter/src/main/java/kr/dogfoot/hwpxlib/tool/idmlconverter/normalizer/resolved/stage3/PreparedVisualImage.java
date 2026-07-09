package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

/**
 * Stage 3 visual image payload after decode/crop/image mutation.
 *
 * <p>This is intentionally mutable while the legacy Phase 6 executor is being split.
 * Once crop preparation moves into {@code VisualCropper}, instances should be returned
 * as finalized results instead of being mutated by the executor.</p>
 */
public final class PreparedVisualImage {
    public byte[] imageData;
    public int pixelW;
    public int pixelH;
    public boolean pageAnchoredStripCrop;

    public PreparedVisualImage(byte[] imageData) {
        this.imageData = imageData;
    }

}
