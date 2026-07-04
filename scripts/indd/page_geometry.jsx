/**
 * Shared page geometry helpers for extraction planning and rendering.
 *
 * These helpers only resolve DOM geometry/coordinate-space metadata. They must
 * not decide ownership, materialization, or placement policy.
 */

// item의 parentPage를 반환한다.
// parentPage가 null인 인라인/스프레드 경계 아이템은 visibleBounds 중심점으로 doc.pages를 탐색한다.
function _resolveParentPage(item, doc) {
    var pg = null;
    try { pg = item.parentPage; } catch (e) {}
    if (pg) return pg;
    try {
        var vb = null;
        try { vb = item.visibleBounds; } catch (e) {}
        if (!vb) vb = item.geometricBounds;
        var cy = (vb[0] + vb[2]) / 2, cx = (vb[1] + vb[3]) / 2;
        for (var i = 0; i < doc.pages.length; i++) {
            var pb = doc.pages[i].bounds;
            if (cy >= pb[0] && cy <= pb[2] && cx >= pb[1] && cx <= pb[3]) return doc.pages[i];
        }
    } catch (e) {}
    return null;
}

// bounds 배열 [top, left, bottom, right]을 page 기준 상대 좌표로 in-place 변환한다.
function _toPageRelativeBounds(bounds, page) {
    var pb = page.bounds;
    bounds[0] -= pb[0]; bounds[1] -= pb[1];
    bounds[2] -= pb[0]; bounds[3] -= pb[1];
}

function _pageRelativeBoundsCopy(bounds, page) {
    if (!bounds || !page) return null;
    var copy = arrCopy(bounds);
    _toPageRelativeBounds(copy, page);
    return copy;
}
