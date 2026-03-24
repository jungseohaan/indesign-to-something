/**
 * 페이지별 배경 PDF 내보내기 (고해상도).
 * extract_indd.jsx 완료 후 별도로 실행.
 *
 * 인수: [inddPath, outputDir, startPage, endPage]
 * - inddPath: .indd 파일 경로
 * - outputDir: 추출 디렉토리 (rendered_frames 하위에 PDF 생성)
 * - startPage: 시작 페이지 (1-based)
 * - endPage: 끝 페이지 (1-based)
 */

// 인수를 _pdf_bg_args.json 파일에서 읽기
// (with arguments로 실행하면 InDesign이 PDF export 설정을 무시하므로 파일 기반 전달)
var inddPath, outputDir, startPage, endPage;

// 열린 문서에서 outputDir 추론 (rendered_frames 디렉토리에 _pdf_bg_args.json이 있는 경우)
var argsLoaded = false;
try {
    // 최근 수정된 indd-extract 디렉토리 검색
    var tmpFolder = Folder("/var/folders");
    // 직접 검색 대신: 열린 문서의 경로에서 추출 디렉토리 찾기
    // 방법: arguments가 있으면 사용, 없으면 현재 열린 문서의 추출 디렉토리 검색
    if (typeof arguments !== "undefined" && arguments.length >= 4) {
        inddPath = String(arguments[0]);
        outputDir = String(arguments[1]);
        startPage = parseInt(String(arguments[2]), 10);
        endPage = parseInt(String(arguments[3]), 10);
        argsLoaded = true;
    }
} catch (e) {}

if (!argsLoaded) {
    // _pdf_bg_args.json 파일 검색 — /tmp의 최근 indd-extract 디렉토리에서
    try {
        var tmpBase = Folder(Folder.temp.fsName);
        var candidates = tmpBase.getFiles("indd-extract-*");
        var latestDir = null;
        var latestTime = 0;
        for (var ci = 0; ci < candidates.length; ci++) {
            if (candidates[ci] instanceof Folder) {
                var af = File(candidates[ci].fsName + "/_pdf_bg_args.json");
                if (af.exists) {
                    var mtime = af.modified ? af.modified.getTime() : 0;
                    if (mtime > latestTime) {
                        latestTime = mtime;
                        latestDir = candidates[ci];
                    }
                }
            }
        }
        if (latestDir) {
            var af2 = File(latestDir.fsName + "/_pdf_bg_args.json");
            af2.open("r");
            var jsonStr = af2.read();
            af2.close();
            $.writeln("[export_pdf_bg] args file: " + af2.fsName);
            $.writeln("[export_pdf_bg] content: " + jsonStr);
            var parsed = eval("(" + jsonStr + ")");
            inddPath = parsed.inddPath;
            outputDir = parsed.outputDir;
            startPage = parsed.startPage;
            endPage = parsed.endPage;
            argsLoaded = true;
        } else {
            $.writeln("[export_pdf_bg] No _pdf_bg_args.json found in " + candidates.length + " dirs");
        }
    } catch (searchErr) {
        $.writeln("[export_pdf_bg] args search error: " + searchErr.message);
    }
}

if (!argsLoaded) {
    $.writeln("[export_pdf_bg] No arguments found — skipping PDF export");
} else {
// ── argsLoaded 가드: 이하 모든 코드는 이 블록 안에서 실행 ──

$.writeln("[export_pdf_bg] indd=" + inddPath + " out=" + outputDir + " pages=" + startPage + "-" + endPage);

// 문서 열기 (이미 열려있으면 재사용)
var doc = null;
for (var di = 0; di < app.documents.length; di++) {
    try {
        if (app.documents[di].fullName.fsName === inddPath ||
            app.documents[di].fullName.absoluteURI === File(inddPath).absoluteURI) {
            doc = app.documents[di];
            break;
        }
    } catch (e) {}
}
if (!doc) {
    doc = app.open(File(inddPath));
}

// rendered_frames 폴더
var renderDir = Folder(outputDir + "/rendered_frames");
if (!renderDir.exists) renderDir.create();

// PDF 내보내기 설정 — 다운샘플링 비활성화, 원본 해상도 보존
app.pdfExportPreferences.exportReaderSpreads = false;
app.pdfExportPreferences.colorBitmapSampling = Sampling.NONE;
app.pdfExportPreferences.colorBitmapCompression = BitmapCompression.NONE;
app.pdfExportPreferences.grayscaleBitmapSampling = Sampling.NONE;
app.pdfExportPreferences.grayscaleBitmapCompression = BitmapCompression.NONE;
app.pdfExportPreferences.monochromeBitmapSampling = Sampling.NONE;
app.pdfExportPreferences.monochromeBitmapCompression = BitmapCompression.NONE;

// editable TextFrame 판별 함수 (extract_indd.jsx와 동일 로직)
function isInlineItem(item) {
    try {
        var p = item.parent;
        var pName = p.constructor.name;
        return (pName === "Character" || pName === "InsertionPoint" ||
                pName === "Story" || pName === "Text" || pName === "Word" ||
                pName === "Line" || pName === "Paragraph");
    } catch (e) { return false; }
}

function isRenderableTextFrame(tf) {
    try {
        var txt = tf.parentStory.contents;
        if (txt.length > 30) return false;
        // 짧은 텍스트 = 장식/배지 → 렌더 대상 (숨기지 않음)
        return true;
    } catch (e) { return false; }
}

function isOnHiddenLayer(item) {
    try { return !item.itemLayer.visible; } catch (e) { return false; }
}

// 편집 가능 TextFrame 수집
var editableFrames = [];
var allItems = doc.allPageItems;
for (var i = 0; i < allItems.length; i++) {
    var item = allItems[i];
    if (item.constructor.name !== "TextFrame") continue;
    if (isOnHiddenLayer(item)) continue;
    try { if (item.nonprinting) continue; } catch (e) {}
    if (isInlineItem(item)) continue;
    if (isRenderableTextFrame(item)) continue;
    try {
        if (item.parentStory && item.parentStory.tables.length > 0) continue;
    } catch (e) {}
    editableFrames.push(item);
}

$.writeln("[export_pdf_bg] editableFrames=" + editableFrames.length);

// startPage/endPage=0 → 전체 페이지
if (!startPage || startPage <= 0) startPage = 1;
if (!endPage || endPage <= 0) endPage = doc.pages.length;

$.writeln("[export_pdf_bg] pages " + startPage + "-" + endPage + " (total " + doc.pages.length + ")");

// 페이지별 PDF 내보내기
var exported = 0;
for (var pi = 0; pi < doc.pages.length; pi++) {
    var page = doc.pages[pi];
    var pgIdx = page.documentOffset + 1;
    if (pgIdx < startPage || pgIdx > endPage) continue;

    // 해당 페이지의 편집 TextFrame 숨기기
    var hidden = [];
    for (var hi = 0; hi < editableFrames.length; hi++) {
        var tf = editableFrames[hi];
        try {
            if (tf.parentPage === page && tf.visible) {
                tf.visible = false;
                hidden.push(tf);
            }
        } catch (e) {
            try {
                var tfb = tf.visibleBounds;
                var pb = page.bounds;
                var cy = (tfb[0] + tfb[2]) / 2;
                var cx = (tfb[1] + tfb[3]) / 2;
                if (cy >= pb[0] && cy <= pb[2] && cx >= pb[1] && cx <= pb[3]) {
                    if (tf.visible) {
                        tf.visible = false;
                        hidden.push(tf);
                    }
                }
            } catch (e2) {}
        }
    }

    // PDF 내보내기
    var pdfFile = File(renderDir + "/page_bg_" + pi + ".pdf");
    try {
        app.pdfExportPreferences.pageRange = page.name;
        doc.exportFile(ExportFormat.PDF_TYPE, pdfFile);
        exported++;
    } catch (e) {
        $.writeln("[export_pdf_bg] Page " + pgIdx + " export failed: " + e.message);
    }

    // 숨긴 TextFrame 복원
    for (var ri = 0; ri < hidden.length; ri++) {
        try { hidden[ri].visible = true; } catch (e) {}
    }
}

$.writeln("[export_pdf_bg] Done: " + exported + " pages exported");

} // argsLoaded 가드 닫기
