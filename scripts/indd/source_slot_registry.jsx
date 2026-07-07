/*
 * Source-slot registry for extract_indd.jsx.
 *
 * This module summarizes legacy render candidates by source-slot identity and
 * keeps the temporary Stage 1 canonical execution filter close to that registry
 * decision. It must not inspect rendered pixels, page text, or executor output.
 */

function _canonicalizeSourceSlotSubsumedCandidatesWithDiagnostics(candidates, sourceItems) {
    var diagnostics = {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "source-slot-canonicalization-filter",
        inputCount: candidates ? candidates.length : 0,
        outputCount: 0,
        droppedCount: 0,
        suppressedCount: 0,
        reasonCounts: {},
        suppressedCandidates: []
    };
    if (!candidates || candidates.length === 0) {
        diagnostics.outputCount = 0;
        return { candidates: candidates || [], diagnostics: diagnostics };
    }
    var sourceInfoById = {};
    var childIdsByParentId = {};
    if (sourceItems && typeof _buildSourceItemIndexes === "function") {
        try {
            var indexes = _buildSourceItemIndexes(sourceItems);
            sourceInfoById = indexes.sourceInfoById || {};
            childIdsByParentId = indexes.childIdsByParentId || {};
        } catch (eCanonicalSourceIndexes) {
            sourceInfoById = {};
            childIdsByParentId = {};
        }
    }
    var shellOwnersByPage = {};
    var visualOnlyCompositeOwnersByPage = {};
    var nativeShellOwnersByPage = {};
    var exactVisibleOwnersBySlotKey = {};
    var exactContentOwnersByPageAndVisibleKey = {};
    var visibleCandidateSourceIdsCache = {};
    var exactVisibleSlotKeyCache = {};
    var paintLeavesByCandidateKey = {};
    var sourceLayerNameByCandidateKey = {};
    function candidateCacheKey(candidate) {
        if (!candidate) return "missing";
        var id = candidate.candidateId || candidate.bundleId || "";
        if (id) return String(id);
        return String(candidate.passId || "UNKNOWN") + "|"
                + String(candidate.pageIndex) + "|"
                + _sourceSetKey(candidate.sourceObjectIds || []) + "|"
                + _sourceSetKey(candidate.visualSourceObjectIds || []) + "|"
                + _sourceSetKey(candidate.exportSourceObjectIds || []);
    }
    function containsId(ids, id) {
        if (!ids || id === null || id === undefined) return false;
        return _sourceSetMembership(ids)[String(id)] === true;
    }
    function containsAllIds(ownerIds, candidateIds) {
        return _sourceSetContainsAll(ownerIds, candidateIds);
    }
    function properSubset(ownerIds, candidateIds) {
        if (!containsAllIds(ownerIds, candidateIds)) return false;
        return _sourceSetKey(ownerIds || []) !== _sourceSetKey(candidateIds || []);
    }
    function visibleCandidateSourceIds(candidate) {
        if (!candidate) return [];
        var cacheKey = candidateCacheKey(candidate);
        if (visibleCandidateSourceIdsCache.hasOwnProperty(cacheKey)) {
            return visibleCandidateSourceIdsCache[cacheKey];
        }
        var ids = [];
        if (candidate.visualSourceObjectIds && candidate.visualSourceObjectIds.length > 0) {
            ids = _sortedNumericIds(candidate.visualSourceObjectIds);
        } else if (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) {
            ids = _sortedNumericIds(candidate.exportSourceObjectIds);
        } else {
            ids = _sortedNumericIds(candidate.sourceObjectIds || []);
        }
        visibleCandidateSourceIdsCache[cacheKey] = ids;
        return ids;
    }
    function candidateOwnershipSlot(candidate) {
        if (!candidate) return "UNKNOWN_SLOT";
        if (candidate.ownershipSlot) {
            if (candidate.ownershipSlot === "TEXTLESS_GROUP_VISUAL_SLOT") return "CONTENT_VISUAL_SLOT";
            return candidate.ownershipSlot;
        }
        if (candidate.visualAction === "PLACE_TEXT_SHELL") return "SHELL_SLOT";
        return "CONTENT_VISUAL_SLOT";
    }
    function exactVisibleSlotKey(candidate) {
        if (!candidate) return null;
        var cacheKey = candidateCacheKey(candidate);
        if (exactVisibleSlotKeyCache.hasOwnProperty(cacheKey)) {
            return exactVisibleSlotKeyCache[cacheKey];
        }
        var ids = visibleCandidateSourceIds(candidate);
        if (!ids || ids.length === 0) return null;
        var key = String(candidate.pageIndex) + "|"
                + candidateOwnershipSlot(candidate) + "|"
                + _sourceSetKey(ids);
        exactVisibleSlotKeyCache[cacheKey] = key;
        return key;
    }
    function exactContentOwnerScore(candidate) {
        if (!candidate) return 0;
        var score = 0;
        if (candidate.disabled !== true) score += 100;
        if (candidate.passId === "pass.image_placed_frames") score += 80;
        else if (candidate.passId === "pass.image_textless_groups") score += 70;
        else if (candidate.passId === "pass.page_textless_graphic_groups") score += 60;
        else if (candidate.passId === "pass.complex_graphic_frames") score += 50;
        else if (candidate.passId === "pass.vector_shape_frames") score += 40;
        else if (candidate.passId === "pass.decoration_groups") score += 10;
        if (candidate.candidatePurpose === "CONTENT_CANDIDATE") score += 5;
        return score;
    }
    function exactVisibleOwnerScore(candidate) {
        if (!candidate) return 0;
        var score = exactContentOwnerScore(candidate);
        if (candidate.slotRole === "background_shell_slot") score += 35;
        if (candidate.visualLayer === "PAGE_BACKGROUND") score += 30;
        if (candidate.slotRole === "page_textless_graphic_group") score += 25;
        if (candidate.slotRole === "textless_group_visual_slot") score += 20;
        if (candidate.slotRole === "shell_slot_only") score += 10;
        if (candidate.visualAction === "PLACE_FLOATING_PNG") score += 4;
        if (candidate.visualAction === "PLACE_TEXT_SHELL") score += 3;
        return score;
    }
    function incrementReason(reason) {
        reason = reason || "UNKNOWN";
        if (!diagnostics.reasonCounts[reason]) diagnostics.reasonCounts[reason] = 0;
        diagnostics.reasonCounts[reason]++;
    }
    function recordSuppressedCandidate(candidate, reason, ownerCandidate) {
        if (!candidate) return;
        incrementReason(reason);
        diagnostics.suppressedCandidates.push({
            candidateId: candidate ? candidate.candidateId || null : null,
            passId: candidate ? candidate.passId || null : null,
            pageIndex: candidate ? candidate.pageIndex : null,
            reason: reason,
            sourceObjectIds: candidate ? _sortedNumericIds(candidate.sourceObjectIds || []) : [],
            visibleSourceObjectIds: visibleCandidateSourceIds(candidate),
            ownerCandidateId: ownerCandidate ? ownerCandidate.candidateId || null : null,
            ownerPassId: ownerCandidate ? ownerCandidate.passId || null : null,
            ownerSourceObjectIds: ownerCandidate ? _sortedNumericIds(ownerCandidate.sourceObjectIds || []) : [],
            ownerVisibleSourceObjectIds: visibleCandidateSourceIds(ownerCandidate)
        });
    }
    function sourceInfo(id) {
        return sourceInfoById ? sourceInfoById[String(id)] : null;
    }
    function boundsAlmostEqual(a, b) {
        if (!a || !b || a.length !== 4 || b.length !== 4) return false;
        for (var i = 0; i < 4; i++) {
            if (Math.abs(Number(a[i]) - Number(b[i])) > 0.25) return false;
        }
        return true;
    }
    function sourceLayerNameForCandidate(candidate) {
        var cacheKey = candidateCacheKey(candidate);
        if (sourceLayerNameByCandidateKey.hasOwnProperty(cacheKey)) {
            return sourceLayerNameByCandidateKey[cacheKey];
        }
        var ids = candidate ? (candidate.sourceObjectIds || []) : [];
        for (var i = 0; i < ids.length; i++) {
            var src = sourceInfo(ids[i]);
            if (src && src.layerName) {
                sourceLayerNameByCandidateKey[cacheKey] = String(src.layerName);
                return sourceLayerNameByCandidateKey[cacheKey];
            }
        }
        sourceLayerNameByCandidateKey[cacheKey] = "";
        return "";
    }
    function collectPaintLeaves(sourceId, out, seen) {
        if (sourceId === null || sourceId === undefined) return;
        var key = String(sourceId);
        if (seen[key]) return;
        seen[key] = true;
        var src = sourceInfo(sourceId);
        if (!src) return;
        var children = childIdsByParentId[key] || [];
        if (children.length > 0) {
            for (var ci = 0; ci < children.length; ci++) collectPaintLeaves(children[ci], out, seen);
            return;
        }
        if (src.hasPlacedVisual === true) {
            out.push(src);
            return;
        }
        if (src.hasVisibleFill === true || src.hasVisibleStroke === true) {
            out.push(src);
            return;
        }
    }
    function paintLeavesForCandidate(candidate) {
        var cacheKey = candidateCacheKey(candidate);
        if (paintLeavesByCandidateKey.hasOwnProperty(cacheKey)) {
            return paintLeavesByCandidateKey[cacheKey];
        }
        var ids = visibleCandidateSourceIds(candidate);
        var out = [];
        var seen = {};
        for (var i = 0; i < ids.length; i++) collectPaintLeaves(ids[i], out, seen);
        paintLeavesByCandidateKey[cacheKey] = out;
        return out;
    }
    function sourceFillIsPaper(src) {
        var fill = String(src && (src.fillColorName || src.fillColor) || "").toLowerCase();
        return fill === "paper" || fill === "[paper]";
    }
    function candidateIsPaperOnlyInlineProxy(candidate) {
        if (!candidate || candidate.passId !== "pass.inline_objects") return false;
        if (candidate.ownershipSlot && candidate.ownershipSlot !== "CONTENT_VISUAL_SLOT") return false;
        if (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0) return false;
        if (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0) return false;
        if (candidate.ownedTextFrameIds && candidate.ownedTextFrameIds.length > 0) return false;
        var leaves = paintLeavesForCandidate(candidate);
        if (!leaves || leaves.length === 0) return false;
        for (var i = 0; i < leaves.length; i++) {
            var leaf = leaves[i];
            if (leaf.hasPlacedVisual === true) return false;
            if (leaf.hasVisibleStroke === true) return false;
            if (leaf.hasVisibleFill !== true || !sourceFillIsPaper(leaf)) return false;
        }
        return true;
    }
    function candidateHasNonPaperPaint(candidate) {
        var leaves = paintLeavesForCandidate(candidate);
        if (!leaves || leaves.length === 0) return false;
        for (var i = 0; i < leaves.length; i++) {
            var leaf = leaves[i];
            if (leaf.hasPlacedVisual === true) return true;
            if (leaf.hasVisibleStroke === true) return true;
            if (leaf.hasVisibleFill === true && !sourceFillIsPaper(leaf)) return true;
        }
        return false;
    }
    function findNativeShellOwnerForPaperInlineProxy(candidate) {
        if (!candidateIsPaperOnlyInlineProxy(candidate)) return null;
        var owners = nativeShellOwnersByPage[String(candidate.pageIndex)] || [];
        var candidateLayer = sourceLayerNameForCandidate(candidate);
        for (var oi = 0; oi < owners.length; oi++) {
            var owner = owners[oi];
            if (!owner || owner === candidate) continue;
            if (!boundsAlmostEqual(owner.bounds, candidate.bounds)) continue;
            if (candidateLayer && sourceLayerNameForCandidate(owner) !== candidateLayer) continue;
            if (!candidateHasNonPaperPaint(owner)) continue;
            return owner;
        }
        return null;
    }
    for (var i = 0; i < candidates.length; i++) {
        var owner = candidates[i];
        if (!owner || owner.passId !== "pass.decoration_groups") continue;
        if (owner.candidatePurpose !== "SHELL_CANDIDATE") continue;
        if (owner.materialization === "HWPX_TEXT" || owner.visualAction === "DROP_VISUAL") continue;
        var pageKey = String(owner.pageIndex);
        if (!shellOwnersByPage[pageKey]) shellOwnersByPage[pageKey] = [];
        shellOwnersByPage[pageKey].push(owner);
        if (!nativeShellOwnersByPage[pageKey]) nativeShellOwnersByPage[pageKey] = [];
        nativeShellOwnersByPage[pageKey].push(owner);
    }
    for (var vi = 0; vi < candidates.length; vi++) {
        var visualOwner = candidates[vi];
        if (visualOwner && visualOwner.visualAction !== "DROP_VISUAL") {
            var exactVisibleKey = exactVisibleSlotKey(visualOwner);
            if (exactVisibleKey) {
                var currentVisible = exactVisibleOwnersBySlotKey[exactVisibleKey];
                if (!currentVisible
                        || exactVisibleOwnerScore(visualOwner) > exactVisibleOwnerScore(currentVisible)) {
                    exactVisibleOwnersBySlotKey[exactVisibleKey] = visualOwner;
                }
            }
        }
        if (visualOwner
                && candidateOwnershipSlot(visualOwner) === "CONTENT_VISUAL_SLOT"
                && visualOwner.visualAction !== "DROP_VISUAL") {
            var exactKey = exactVisibleSlotKey(visualOwner);
            if (exactKey) {
                var currentExact = exactContentOwnersByPageAndVisibleKey[exactKey];
                if (!currentExact
                        || exactContentOwnerScore(visualOwner) > exactContentOwnerScore(currentExact)) {
                    exactContentOwnersByPageAndVisibleKey[exactKey] = visualOwner;
                }
            }
        }
        if (!visualOwner || (visualOwner.passId !== "pass.image_textless_groups"
                    && visualOwner.passId !== "pass.page_textless_graphic_groups")) continue;
        if (visualOwner.ownershipSlot !== "CONTENT_VISUAL_SLOT") continue;
        if (visualOwner.visualAction === "DROP_VISUAL") continue;
        if (visualOwner.ownedTextFrameIds && visualOwner.ownedTextFrameIds.length > 0) continue;
        var visualOwnerIds = visibleCandidateSourceIds(visualOwner);
        if (!visualOwnerIds || visualOwnerIds.length < 2) continue;
        var visualPageKey = String(visualOwner.pageIndex);
        if (!visualOnlyCompositeOwnersByPage[visualPageKey]) {
            visualOnlyCompositeOwnersByPage[visualPageKey] = [];
        }
        visualOnlyCompositeOwnersByPage[visualPageKey].push({
            candidate: visualOwner,
            sourceIds: visualOwnerIds
        });
    }
    var filtered = [];
    for (var ci = 0; ci < candidates.length; ci++) {
        var candidate = candidates[ci];
        var paperInlineOwner = findNativeShellOwnerForPaperInlineProxy(candidate);
        if (paperInlineOwner) {
            recordSuppressedCandidate(candidate, "PAPER_INLINE_PROXY_OWNED_BY_NATIVE_SHELL", paperInlineOwner);
            var layoutOnlyCandidate = {};
            for (var pk in candidate) {
                if (candidate.hasOwnProperty(pk)) layoutOnlyCandidate[pk] = candidate[pk];
            }
            layoutOnlyCandidate.visualAction = "DROP_VISUAL";
            layoutOnlyCandidate.materialization = "HWPX_TEXT";
            layoutOnlyCandidate.layoutOnlyInlineSlot = true;
            layoutOnlyCandidate.reason = "paper_inline_proxy_layout_slot_owned_by_native_shell";
            layoutOnlyCandidate.ownedByNativeShellSourceObjectIds = _sortedNumericIds(
                    paperInlineOwner.sourceObjectIds || []);
            filtered.push(layoutOnlyCandidate);
            continue;
        }
        var compositeOwners = visualOnlyCompositeOwnersByPage[String(candidate && candidate.pageIndex)] || [];
        var candidateVisibleIds = visibleCandidateSourceIds(candidate);
        var exactVisibleOwner = exactVisibleOwnersBySlotKey[exactVisibleSlotKey(candidate)];
        if (exactVisibleOwner && exactVisibleOwner !== candidate) {
            recordSuppressedCandidate(candidate, "SUBSUMED_BY_EXACT_VISIBLE_SLOT_OWNER", exactVisibleOwner);
            continue;
        }
        if (candidate
                && candidate.passId === "pass.decoration_groups"
                && candidateOwnershipSlot(candidate) === "CONTENT_VISUAL_SLOT") {
            var exactOwner = exactContentOwnersByPageAndVisibleKey[exactVisibleSlotKey(candidate)];
            if (exactOwner && exactOwner !== candidate) {
                recordSuppressedCandidate(candidate, "SUBSUMED_BY_EXACT_CONTENT_VISUAL_OWNER", exactOwner);
                continue;
            }
        }
        var subsumedByVisualOnlyComposite = false;
        var subsumingComposite = null;
        for (var coi = 0; coi < compositeOwners.length; coi++) {
            var compositeOwner = compositeOwners[coi];
            if (!compositeOwner || compositeOwner.candidate === candidate) continue;
            if (properSubset(compositeOwner.sourceIds, candidateVisibleIds)) {
                subsumedByVisualOnlyComposite = true;
                subsumingComposite = compositeOwner.candidate;
                break;
            }
        }
        if (subsumedByVisualOnlyComposite) {
            recordSuppressedCandidate(candidate, "SUBSUMED_BY_VISUAL_ONLY_COMPOSITE", subsumingComposite);
            continue;
        }
        if (candidate && candidate.passId === "pass.editable_textframe_visual_shells"
                && candidate.sourceObjectIds && candidate.sourceObjectIds.length === 1) {
            var textFrameId = candidate.sourceObjectIds[0];
            var owners = shellOwnersByPage[String(candidate.pageIndex)] || [];
            var subsumed = false;
            var subsumingShell = null;
            for (var oi = 0; oi < owners.length; oi++) {
                var shell = owners[oi];
                var shellVisiblyOwnsTextFrameStyle =
                        containsId(shell.exportSourceObjectIds, textFrameId)
                        || containsId(shell.visualSourceObjectIds, textFrameId)
                        || containsId(shell.styleSourceObjectIds, textFrameId);
                if (shellVisiblyOwnsTextFrameStyle) {
                    subsumed = true;
                    subsumingShell = shell;
                    break;
                }
            }
            if (subsumed) {
                recordSuppressedCandidate(candidate, "SUBSUMED_BY_DECORATION_SHELL_OWNER", subsumingShell);
                continue;
            }
        }
        filtered.push(candidate);
    }
    diagnostics.outputCount = filtered.length;
    diagnostics.suppressedCount = diagnostics.suppressedCandidates.length;
    diagnostics.droppedCount = diagnostics.inputCount - diagnostics.outputCount;
    diagnostics.summary = {
        inputCount: diagnostics.inputCount,
        outputCount: diagnostics.outputCount,
        droppedCount: diagnostics.droppedCount,
        suppressedCount: diagnostics.suppressedCount,
        reasonCounts: diagnostics.reasonCounts
    };
    return { candidates: filtered, diagnostics: diagnostics };
}

function _buildSourceSlotRegistryDiagnostics(plannerBundles, objectPlanDiagnostics, sourceItems) {
    var bundles = plannerBundles && plannerBundles.bundles ? plannerBundles.bundles : [];
    var plans = objectPlanDiagnostics && objectPlanDiagnostics.objectPlans
            ? objectPlanDiagnostics.objectPlans
            : [];
    var sourceInfoById = {};
    if (sourceItems && typeof _buildSourceItemIndexes === "function") {
        try {
            sourceInfoById = _buildSourceItemIndexes(sourceItems).sourceInfoById || {};
        } catch (eSourceSlotRegistryIndexes) {
            sourceInfoById = {};
        }
    }
    var planByBundleId = {};
    for (var pi = 0; pi < plans.length; pi++) {
        var plan = plans[pi];
        if (plan && plan.bundleId) planByBundleId[String(plan.bundleId)] = plan;
    }

    var slotsByKey = {};
    var clusterSlotsByKey = {};
    var orderedKeys = [];
    var orderedClusterKeys = [];
    var summary = {
        candidateCount: plannerBundles && plannerBundles.summary
                ? plannerBundles.summary.candidateCount || 0
                : bundles.length,
        bundleCount: bundles.length,
        executableBundleCount: 0,
        registrySlotCount: 0,
        executableRegistrySlotCount: 0,
        clusterRegistrySlotCount: 0,
        competingClusterRegistrySlotCount: 0,
        alternateChannelCount: 0,
        clusterAlternateChannelCount: 0,
        potentialExportSkipCount: 0,
        clusterPotentialExportSkipCount: 0,
        registrySlotCounts: {},
        clusterRegistrySlotCounts: {},
        passIdCounts: {},
        alternatePassIdCounts: {},
        clusterAlternatePassIdCounts: {},
        contractStatusCounts: {},
        pngAvoidanceStatusCounts: {},
        pngAvoidancePassIdCounts: {},
        nativeShapeEligibleCount: 0,
        textFrameStyleEligibleCount: 0,
        pngRequiredCount: 0,
        nonPngMaterializationCount: 0,
        issueCodeCounts: objectPlanDiagnostics && objectPlanDiagnostics.summary
                ? objectPlanDiagnostics.summary.issueCodeCounts || {}
                : {}
    };

    for (var bi = 0; bi < bundles.length; bi++) {
        var bundle = bundles[bi];
        if (!bundle) continue;
        if (bundle.executable === true) summary.executableBundleCount++;
        _incrementSourceSlotRegistrySummary(summary.passIdCounts, bundle.passId || "UNKNOWN");

        var slotKey = _sourceSlotRegistryKey(bundle);
        if (!slotsByKey[slotKey]) {
            slotsByKey[slotKey] = {
                registrySlotKey: slotKey,
                pageIndex: bundle.pageIndex,
                placement: _sourceSlotRegistryPlacementFromBundle(bundle),
                coordinateSpace: _sourceSlotRegistryCoordinateSpaceFromBundle(bundle),
                ownershipSlot: bundle.ownershipSlot || "UNKNOWN_SLOT",
                sourceObjectIds: _sortedNumericIds(bundle.sourceObjectIds || []),
                visualSourceObjectIds: _sortedNumericIds(bundle.visualSourceObjectIds || []),
                styleSourceObjectIds: _sortedNumericIds(bundle.styleSourceObjectIds || []),
                ownedTextFrameIds: _sortedNumericIds(bundle.ownedTextFrameIds || []),
                exportSourceObjectIds: _sortedNumericIds(bundle.exportSourceObjectIds || []),
                hiddenVisualSourceObjectIds: _sortedNumericIds(bundle.hiddenVisualSourceObjectIds || []),
                candidateCount: 0,
                executableCandidateCount: 0,
                canonicalBundleId: null,
                canonicalCandidateId: null,
                canonicalPassId: null,
                canonicalContractStatus: null,
                canonicalMigrationStatus: null,
                canonicalBlocker: null,
                alternateCandidateCount: 0,
                alternateChannels: []
            };
            orderedKeys.push(slotKey);
        }

        var slot = slotsByKey[slotKey];
        slot.candidateCount++;
        if (bundle.executable === true) slot.executableCandidateCount++;

        var planForBundle = bundle.bundleId ? planByBundleId[String(bundle.bundleId)] : null;
        var pngAvoidance = _sourceSlotRegistryPngAvoidance(bundle, sourceInfoById);
        var candidateEntry = {
            bundleId: bundle.bundleId || null,
            candidateId: bundle.candidateId || null,
            passId: bundle.passId || null,
            executable: bundle.executable === true,
            materialization: bundle.materialization || null,
            policyLayer: bundle.policyLayer || null,
            clusterRelation: bundle.clusterRelation || null,
            contractStatus: planForBundle ? planForBundle.contractStatus || null : null,
            migrationStatus: planForBundle ? planForBundle.migrationStatus || null : null,
            migrationBlocker: planForBundle ? planForBundle.migrationBlocker || null : null,
            pngAvoidanceStatus: pngAvoidance.status,
            pngAvoidanceReason: pngAvoidance.reason,
            pngAvoidanceSourceKind: pngAvoidance.sourceKind || null
        };
        var clusterSlotKey = _sourceSlotRegistryClusterKey(bundle);
        if (!clusterSlotsByKey[clusterSlotKey]) {
            clusterSlotsByKey[clusterSlotKey] = {
                clusterRegistrySlotKey: clusterSlotKey,
                pageIndex: bundle.pageIndex,
                placement: _sourceSlotRegistryPlacementFromBundle(bundle),
                ownershipSlot: bundle.ownershipSlot || "UNKNOWN_SLOT",
                sourceRootObjectIds: _sortedNumericIds(bundle.sourceRootObjectIds || []),
                clusterSourceObjectIds: _sortedNumericIds(bundle.clusterSourceObjectIds || []),
                candidateCount: 0,
                executableCandidateCount: 0,
                canonicalBundleId: null,
                canonicalCandidateId: null,
                canonicalPassId: null,
                alternateCandidateCount: 0,
                alternateChannels: []
            };
            orderedClusterKeys.push(clusterSlotKey);
        }
        var clusterSlot = clusterSlotsByKey[clusterSlotKey];
        clusterSlot.candidateCount++;
        if (bundle.executable === true) clusterSlot.executableCandidateCount++;
        if (_sourceSlotRegistryIsBetterCanonical(candidateEntry, clusterSlot)) {
            if (clusterSlot.canonicalBundleId !== null) {
                clusterSlot.alternateChannels.push({
                    bundleId: clusterSlot.canonicalBundleId,
                    candidateId: clusterSlot.canonicalCandidateId,
                    passId: clusterSlot.canonicalPassId,
                    displacedBy: candidateEntry.bundleId
                });
            }
            clusterSlot.canonicalBundleId = candidateEntry.bundleId;
            clusterSlot.canonicalCandidateId = candidateEntry.candidateId;
            clusterSlot.canonicalPassId = candidateEntry.passId;
        } else {
            clusterSlot.alternateChannels.push(candidateEntry);
        }

        if (_sourceSlotRegistryIsBetterCanonical(candidateEntry, slot)) {
            if (slot.canonicalBundleId !== null) {
                slot.alternateChannels.push({
                    bundleId: slot.canonicalBundleId,
                    candidateId: slot.canonicalCandidateId,
                    passId: slot.canonicalPassId,
                    contractStatus: slot.canonicalContractStatus,
                    migrationStatus: slot.canonicalMigrationStatus,
                    migrationBlocker: slot.canonicalBlocker,
                    displacedBy: candidateEntry.bundleId
                });
            }
            slot.canonicalBundleId = candidateEntry.bundleId;
            slot.canonicalCandidateId = candidateEntry.candidateId;
            slot.canonicalPassId = candidateEntry.passId;
            slot.canonicalContractStatus = candidateEntry.contractStatus;
            slot.canonicalMigrationStatus = candidateEntry.migrationStatus;
            slot.canonicalBlocker = candidateEntry.migrationBlocker;
            slot.canonicalPngAvoidanceStatus = candidateEntry.pngAvoidanceStatus;
            slot.canonicalPngAvoidanceReason = candidateEntry.pngAvoidanceReason;
            slot.canonicalPngAvoidanceSourceKind = candidateEntry.pngAvoidanceSourceKind;
        } else {
            slot.alternateChannels.push(candidateEntry);
        }
    }

    var registryEntries = [];
    for (var oi = 0; oi < orderedKeys.length; oi++) {
        var entry = slotsByKey[orderedKeys[oi]];
        entry.alternateCandidateCount = entry.alternateChannels.length;
        summary.registrySlotCount++;
        if (entry.executableCandidateCount > 0) summary.executableRegistrySlotCount++;
        if (entry.alternateCandidateCount > 0) {
            summary.alternateChannelCount += entry.alternateCandidateCount;
            summary.potentialExportSkipCount += entry.alternateCandidateCount;
            for (var ai = 0; ai < entry.alternateChannels.length; ai++) {
                _incrementSourceSlotRegistrySummary(
                        summary.alternatePassIdCounts,
                        entry.alternateChannels[ai].passId || "UNKNOWN");
            }
        }
        _incrementSourceSlotRegistrySummary(summary.registrySlotCounts, entry.ownershipSlot);
        _incrementSourceSlotRegistrySummary(summary.contractStatusCounts,
                entry.canonicalContractStatus || "UNKNOWN");
        _incrementSourceSlotRegistrySummary(summary.pngAvoidanceStatusCounts,
                entry.canonicalPngAvoidanceStatus || "UNKNOWN");
        _incrementSourceSlotRegistrySummary(summary.pngAvoidancePassIdCounts,
                (entry.canonicalPngAvoidanceStatus || "UNKNOWN") + "|"
                        + (entry.canonicalPassId || "UNKNOWN"));
        if (entry.canonicalPngAvoidanceStatus === "NATIVE_SHAPE_ELIGIBLE") {
            summary.nativeShapeEligibleCount++;
        } else if (entry.canonicalPngAvoidanceStatus === "TEXT_FRAME_STYLE_ELIGIBLE") {
            summary.textFrameStyleEligibleCount++;
        } else if (entry.canonicalPngAvoidanceStatus === "NON_PNG_MATERIALIZATION") {
            summary.nonPngMaterializationCount++;
        } else if (entry.canonicalPngAvoidanceStatus
                && String(entry.canonicalPngAvoidanceStatus).indexOf("PNG_REQUIRED") === 0) {
            summary.pngRequiredCount++;
        }
        registryEntries.push(entry);
    }
    var clusterRegistryEntries = [];
    for (var ci = 0; ci < orderedClusterKeys.length; ci++) {
        var clusterEntry = clusterSlotsByKey[orderedClusterKeys[ci]];
        clusterEntry.alternateCandidateCount = clusterEntry.alternateChannels.length;
        summary.clusterRegistrySlotCount++;
        if (clusterEntry.alternateCandidateCount > 0) {
            summary.competingClusterRegistrySlotCount++;
            summary.clusterAlternateChannelCount += clusterEntry.alternateCandidateCount;
            summary.clusterPotentialExportSkipCount += clusterEntry.alternateCandidateCount;
            for (var cai = 0; cai < clusterEntry.alternateChannels.length; cai++) {
                _incrementSourceSlotRegistrySummary(
                        summary.clusterAlternatePassIdCounts,
                        clusterEntry.alternateChannels[cai].passId || "UNKNOWN");
            }
        }
        _incrementSourceSlotRegistrySummary(summary.clusterRegistrySlotCounts, clusterEntry.ownershipSlot);
        clusterRegistryEntries.push(clusterEntry);
    }

    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "source-slot-registry-diagnostics",
        summary: summary,
        registryEntries: registryEntries,
        clusterRegistryEntries: clusterRegistryEntries
    };
}

function _sourceSlotRegistryKey(bundle) {
    if (!bundle) return "unknown";
    var placement = _sourceSlotRegistryPlacementFromBundle(bundle);
    var slot = bundle.ownershipSlot || "UNKNOWN_SLOT";
    var pageIndex = bundle.pageIndex !== null && bundle.pageIndex !== undefined
            ? String(bundle.pageIndex)
            : "none";
    var visibleIds = _sourceSlotRegistryVisibleIds(bundle);
    var visibleKey = _sourceSetKey(visibleIds || []);
    if (!visibleKey) visibleKey = _sourceSetKey(bundle.sourceObjectIds || []);
    if (!visibleKey) visibleKey = "synthetic:" + String(bundle.bundleId || bundle.candidateId || "unknown");
    return pageIndex + "|" + placement + "|" + slot + "|" + visibleKey;
}

function _sourceSlotRegistryClusterKey(bundle) {
    if (!bundle) return "unknown";
    var placement = _sourceSlotRegistryPlacementFromBundle(bundle);
    var slot = bundle.ownershipSlot || "UNKNOWN_SLOT";
    var pageIndex = bundle.pageIndex !== null && bundle.pageIndex !== undefined
            ? String(bundle.pageIndex)
            : "none";
    var clusterIds = bundle.clusterSourceObjectIds && bundle.clusterSourceObjectIds.length > 0
            ? bundle.clusterSourceObjectIds
            : (bundle.sourceRootObjectIds && bundle.sourceRootObjectIds.length > 0
                    ? bundle.sourceRootObjectIds
                    : bundle.sourceObjectIds);
    var clusterKey = _sourceSetKey(clusterIds || []);
    if (!clusterKey) clusterKey = "synthetic:" + String(bundle.bundleId || bundle.candidateId || "unknown");
    return pageIndex + "|" + placement + "|" + slot + "|" + clusterKey;
}

function _sourceSlotRegistryVisibleIds(bundle) {
    if (!bundle) return [];
    if (bundle.ownershipSlot === "TABLE_STYLE_SLOT"
            && bundle.styleSourceObjectIds
            && bundle.styleSourceObjectIds.length > 0) {
        return _sortedNumericIds(bundle.styleSourceObjectIds);
    }
    if (bundle.ownershipSlot === "TEXT_SLOT"
            && bundle.ownedTextFrameIds
            && bundle.ownedTextFrameIds.length > 0) {
        return _sortedNumericIds(bundle.ownedTextFrameIds);
    }
    if (bundle.visualSourceObjectIds && bundle.visualSourceObjectIds.length > 0) {
        return _sortedNumericIds(bundle.visualSourceObjectIds);
    }
    if (bundle.exportSourceObjectIds && bundle.exportSourceObjectIds.length > 0) {
        return _sortedNumericIds(bundle.exportSourceObjectIds);
    }
    return _sortedNumericIds(bundle.sourceObjectIds || []);
}

function _sourceSlotRegistryPlacementFromBundle(bundle) {
    if (!bundle) return "FLOATING";
    return bundle.passId === "pass.inline_objects" ? "INLINE" : "FLOATING";
}

function _sourceSlotRegistryCoordinateSpaceFromBundle(bundle) {
    return _sourceSlotRegistryPlacementFromBundle(bundle) === "INLINE"
            ? "STORY_FLOW"
            : "PAGE";
}

function _sourceSlotRegistryPngAvoidance(bundle, sourceInfoById) {
    if (!bundle) return { status: "UNKNOWN", reason: "missing bundle" };
    if (bundle.executable !== true) {
        return { status: "NON_EXECUTABLE", reason: "not selected for execution" };
    }
    if (bundle.materialization === "NATIVE_SOURCE_SHAPE"
            || bundle.materialization === "HWPX_TABLE_STYLE"
            || bundle.materialization === "HWPX_TEXT") {
        return {
            status: "NON_PNG_MATERIALIZATION",
            reason: "already represented without PNG"
        };
    }
    if (bundle.ownershipSlot === "TABLE_STYLE_SLOT") {
        return {
            status: "NON_PNG_MATERIALIZATION",
            reason: "table style slot is represented as HWPX table style"
        };
    }
    if (bundle.ownershipSlot !== "SHELL_SLOT" && bundle.ownershipSlot !== "CONTENT_VISUAL_SLOT") {
        return {
            status: "PNG_REQUIRED_NON_VISUAL_SLOT",
            reason: "not a visual shell/content slot"
        };
    }
    var visibleIds = bundle.visualSourceObjectIds && bundle.visualSourceObjectIds.length > 0
            ? bundle.visualSourceObjectIds
            : (bundle.exportSourceObjectIds && bundle.exportSourceObjectIds.length > 0
                    ? bundle.exportSourceObjectIds
                    : bundle.sourceObjectIds);
    visibleIds = _sortedNumericIds(visibleIds || []);
    if (visibleIds.length !== 1) {
        return {
            status: "PNG_REQUIRED_MULTI_SOURCE",
            reason: "multi-source shell/content must preserve source composition"
        };
    }
    var source = sourceInfoById ? sourceInfoById[String(visibleIds[0])] : null;
    if (!source) {
        return {
            status: "PNG_REQUIRED_NO_SOURCE_METADATA",
            reason: "source metadata unavailable"
        };
    }
    var kind = String(source.kind || "");
    if (kind === "Polygon") {
        return {
            status: "PNG_REQUIRED_POLYGON",
            reason: "native HWPX polygon materialization is not used",
            sourceKind: kind
        };
    }
    if (kind === "TextFrame") {
        if (_sourceSlotRegistrySourceHasPaint(source)) {
            return {
                status: "TEXT_FRAME_STYLE_ELIGIBLE",
                reason: "text frame fill/stroke can be absorbed as HWPX text box style",
                sourceKind: kind
            };
        }
        return {
            status: "PNG_REQUIRED_TEXT_FRAME_NO_STYLE",
            reason: "text frame has no visible shell style",
            sourceKind: kind
        };
    }
    if (kind === "Rectangle" || kind === "Oval" || kind === "GraphicLine") {
        var hiddenNonText = _sourceSlotRegistryNonTextSourceIds(
                bundle.hiddenVisualSourceObjectIds || [], sourceInfoById);
        if (hiddenNonText.length > 0) {
            return {
                status: "PNG_REQUIRED_HIDDEN_NON_TEXT_CHILDREN",
                reason: "textless export excludes non-text child visuals",
                sourceKind: kind
            };
        }
        if (source.hasChildren === true && !bundle.ownedTextFrameIds
                || (source.hasChildren === true && bundle.ownedTextFrameIds.length === 0)) {
            return {
                status: "PNG_REQUIRED_COMPLEX_SOURCE_TREE",
                reason: "source shape has children not represented as owned text",
                sourceKind: kind
            };
        }
        if (_sourceSlotRegistrySourceHasPaint(source)) {
            return {
                status: "NATIVE_SHAPE_ELIGIBLE",
                reason: "single simple painted source shape can be native materialization",
                sourceKind: kind
            };
        }
        return {
            status: "PNG_REQUIRED_UNPAINTED_SHAPE",
            reason: "shape has no visible fill/stroke metadata",
            sourceKind: kind
        };
    }
    return {
        status: "PNG_REQUIRED_UNSUPPORTED_SOURCE_KIND",
        reason: "source kind requires extracted visual materialization",
        sourceKind: kind || "UNKNOWN"
    };
}

function _sourceSlotRegistrySourceHasPaint(source) {
    if (!source) return false;
    var fillName = String(source.fillColorName || source.fillColor || "").toLowerCase();
    var strokeName = String(source.strokeColorName || source.strokeColor || "").toLowerCase();
    var strokeWeight = Number(source.strokeWeight || 0);
    var hasFill = fillName && fillName !== "none" && fillName !== "n/a";
    var hasStroke = strokeWeight > 0 && strokeName && strokeName !== "none" && strokeName !== "n/a";
    return hasFill || hasStroke;
}

function _sourceSlotRegistryNonTextSourceIds(ids, sourceInfoById) {
    var out = [];
    for (var i = 0; ids && i < ids.length; i++) {
        var source = sourceInfoById ? sourceInfoById[String(ids[i])] : null;
        var kind = source ? String(source.kind || "") : "";
        if (kind !== "TextFrame" && kind !== "Story" && kind !== "Character"
                && kind !== "InsertionPoint" && kind !== "Cell") {
            out.push(ids[i]);
        }
    }
    return out;
}

function _sourceSlotRegistryIsBetterCanonical(candidateEntry, slot) {
    if (!slot || slot.canonicalBundleId === null) return true;
    var candidateScore = _sourceSlotRegistryCanonicalScore(candidateEntry);
    var currentScore = _sourceSlotRegistryCanonicalScore({
        passId: slot.canonicalPassId,
        contractStatus: slot.canonicalContractStatus,
        migrationStatus: slot.canonicalMigrationStatus,
        migrationBlocker: slot.canonicalBlocker,
        executable: true
    });
    return candidateScore > currentScore;
}

function _sourceSlotRegistryCanonicalScore(entry) {
    if (!entry) return 0;
    var score = 0;
    if (entry.executable === true) score += 100;
    if (entry.contractStatus === "READY_FOR_STAGE1_IMPORT") score += 50;
    if (entry.migrationStatus === "READY_EXACT_CLUSTER") score += 30;
    else if (entry.migrationStatus === "READY_SLOT_ONLY_CLUSTER_FRAGMENT") score += 25;
    else if (entry.migrationStatus === "READY_CLOSED_PLACED_CONTENT_FRAME") score += 25;
    if (entry.migrationBlocker === "NONE") score += 20;
    if (entry.passId === "pass.inline_objects") score += 5;
    return score;
}

function _incrementSourceSlotRegistrySummary(map, key) {
    key = key || "UNKNOWN";
    if (!map[key]) map[key] = 0;
    map[key]++;
}
