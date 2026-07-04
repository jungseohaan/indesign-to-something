/**
 * Optional PDF preview export.
 *
 * This module only writes preview.pdf after extraction. It must not affect
 * source ownership, plan construction, or render materialization.
 */

// PDF 프리뷰를 내보낸다. ctx.skipPdf=true이면 아무것도 하지 않는다.
function _exportPdf(doc, ctx) {
    if (ctx.skipPdf) return;
    _marker(ctx.outputDir, "12_pdf_export");
    try {
        app.pdfExportPreferences.exportReaderSpreads = ctx.spreadMode;
        // SPEC-030 A.3: 페이지 범위가 지정된 경우 PDF도 해당 범위만 출력.
        // InDesign PDF pageRange에서 bare "31-33"은 섹션 페이지 라벨로 해석될 수 있다.
        // 추출 파이프라인은 ctx.startPage/endPage를 물리 페이지로 정규화하므로 PDF도 "+31-+33"
        // 형식의 absolute page range를 사용해 resolved/results와 같은 페이지를 내보낸다.
        if (ctx.startPage === 1 && ctx.endPage === ctx.pageCount) {
            app.pdfExportPreferences.pageRange = PageRange.ALL_PAGES;
        } else {
            app.pdfExportPreferences.pageRange = "+" + ctx.startPage + "-+" + ctx.endPage;
        }
        app.pdfExportPreferences.colorBitmapSampling           = Sampling.BICUBIC_DOWNSAMPLE;
        app.pdfExportPreferences.colorBitmapSamplingDPI        = 300;
        app.pdfExportPreferences.colorBitmapCompression        = BitmapCompression.JPEG;
        app.pdfExportPreferences.colorBitmapQuality            = CompressionQuality.HIGH;
        app.pdfExportPreferences.grayscaleBitmapSampling       = Sampling.BICUBIC_DOWNSAMPLE;
        app.pdfExportPreferences.grayscaleBitmapSamplingDPI    = 300;
        app.pdfExportPreferences.grayscaleBitmapCompression    = BitmapCompression.JPEG;
        app.pdfExportPreferences.grayscaleBitmapQuality        = CompressionQuality.HIGH;
        app.pdfExportPreferences.monochromeBitmapSampling      = Sampling.BICUBIC_DOWNSAMPLE;
        app.pdfExportPreferences.monochromeBitmapSamplingDPI   = 1200;
        app.pdfExportPreferences.cropImagesToFrames            = true;
        app.pdfExportPreferences.compressTextAndLineArt        = true;
        app.pdfExportPreferences.acrobatCompatibility          = AcrobatCompatibility.ACROBAT_7;
        app.pdfExportPreferences.subsetFontsBelow              = 100;
        app.pdfExportPreferences.optimizePDF                   = true;
        doc.exportFile(ExportFormat.PDF_TYPE, File(ctx.outputDir + "/preview.pdf"));
    } catch (e) {}
}
