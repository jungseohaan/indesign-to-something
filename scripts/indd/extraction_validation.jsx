/*
 * Extraction-plan validation helpers for extract_indd.jsx.
 *
 * This module validates that extraction output follows Stage 1 ownership
 * decisions. It must not create or reinterpret ownership.
 */

function _validateExtractionResults(plan, extractionResults) {
    var issues = [];
    var indexes = _buildExtractionValidationIndexes(plan, issues);
    var passById = indexes.passById;
    var candidateById = indexes.candidateById;
    _validateExecutionCandidateContract(plan, issues);
    _validateExtractionCandidatePassContracts(plan, passById, issues);
    _validateExtractionCandidateOwnershipSlots(plan, issues);
    _validateCompositeSourceSetCandidateContracts(plan, issues);
    var rows = extractionResults && extractionResults.results ? extractionResults.results : [];
    _validateExtractionResultRowDuplicates(rows, issues);
    _validateExtractionResultRenderUnitContracts(plan, rows, candidateById, issues);
    var counters = _validateExtractionResultRows(
            rows,
            passById, candidateById, issues);
    _validateExtractionCandidateResultCounts(candidateById, counters, issues);
    _validateRequiredExtractionCandidates(plan, counters.resultByCandidateId, issues);
    _validatePlannedTextShellCandidateResults(plan, passById, counters.resultByCandidateId, issues);
    return {
        status: issues.length ? "FAIL" : "OK",
        issueCount: issues.length,
        issues: issues
    };
}

function _validateObjectPlanGate(objectPlanDiagnostics) {
    var issues = [];
    if (!objectPlanDiagnostics) {
        issues.push({
            code: "missing_object_plan_diagnostics",
            severity: "ERROR"
        });
    } else if (!objectPlanDiagnostics.validation) {
        issues.push({
            code: "missing_object_plan_validation",
            severity: "ERROR"
        });
    } else if (objectPlanDiagnostics.validation.issueCount > 0) {
        var validationIssues = objectPlanDiagnostics.validation.issues || [];
        for (var i = 0; i < validationIssues.length; i++) {
            var issue = validationIssues[i] || {};
            issues.push({
                code: issue.code || "object_plan_validation_issue",
                severity: issue.severity || "ERROR",
                detail: issue.detail || {},
                plans: issue.plans || []
            });
        }
    }

    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "object-plan-validation-gate",
        status: issues.length ? "FAIL" : "OK",
        issueCount: issues.length,
        issueCodeCounts: _objectPlanGateIssueCodeCounts(issues),
        issues: issues
    };
}

function _validationIssueCountsBySeverity(issues) {
    var counts = { ERROR: 0, WARNING: 0 };
    for (var i = 0; issues && i < issues.length; i++) {
        var severity = String(issues[i] && issues[i].severity || "ERROR");
        if (severity === "WARNING") counts.WARNING++;
        else counts.ERROR++;
    }
    return counts;
}

function _assertObjectPlanGate(ctx, objectPlanDiagnostics) {
    var gate = _validateObjectPlanGate(objectPlanDiagnostics);
    if (ctx && ctx.outputDir) {
        writeJson(ctx.outputDir + "/object-plan-validation-gate.json", gate);
    }
    if (gate.status !== "OK") {
        throw new Error("ObjectPlan validation failed: "
                + gate.issueCount
                + " issue(s): "
                + _objectPlanGateIssueCodesForMessage(gate.issueCodeCounts));
    }
    return gate;
}

function _validateSourceOwnershipStageGate(
        sourceCoverageDiagnostics, sourceOwnershipModelDiagnostics, objectPlanDiagnostics) {
    var issues = [];
    var coverageSummary = sourceCoverageDiagnostics ? sourceCoverageDiagnostics.summary : null;
    var modelSummary = sourceOwnershipModelDiagnostics ? sourceOwnershipModelDiagnostics.summary : null;

    if (!coverageSummary) {
        issues.push({
            code: "missing_source_coverage_summary",
            severity: "ERROR"
        });
    } else {
        if (coverageSummary.unresolvedCount && coverageSummary.unresolvedCount > 0) {
            issues.push({
                code: "source_coverage_unresolved",
                severity: "WARNING",
                unresolvedCount: coverageSummary.unresolvedCount,
                visibleMaterialUnresolvedCount: coverageSummary.visibleMaterialUnresolvedCount || 0
            });
        }
        if (coverageSummary.visibleMaterialUnresolvedCount
                && coverageSummary.visibleMaterialUnresolvedCount > 0) {
            issues.push({
                code: "visible_source_material_unresolved",
                severity: "WARNING",
                visibleMaterialUnresolvedCount: coverageSummary.visibleMaterialUnresolvedCount
            });
        }
    }

    if (!modelSummary) {
        issues.push({
            code: "missing_source_ownership_model_summary",
            severity: "ERROR"
        });
    } else if (modelSummary.duplicateSlotOwnerCount
            && modelSummary.duplicateSlotOwnerCount > 0) {
        issues.push({
            code: "duplicate_source_slot_owner",
            severity: "ERROR",
            duplicateSlotOwnerCount: modelSummary.duplicateSlotOwnerCount,
            duplicateSlotKeys: modelSummary.duplicateSlotKeys || []
        });
    }

    _validateSourceOwnershipRenderUnits(
            sourceOwnershipModelDiagnostics, objectPlanDiagnostics, issues);

    var severityCounts = _validationIssueCountsBySeverity(issues);
    return {
        schemaVersion: 1,
        policy: "POLICY-source-ownership",
        mode: "source-ownership-stage-gate",
        status: severityCounts.ERROR > 0 ? "FAIL" : "OK",
        issueCount: issues.length,
        errorCount: severityCounts.ERROR,
        warningCount: severityCounts.WARNING,
        issueCodeCounts: _objectPlanGateIssueCodeCounts(issues),
        issues: issues
    };
}

function _assertSourceOwnershipStageGate(
        ctx, sourceCoverageDiagnostics, sourceOwnershipModelDiagnostics, objectPlanDiagnostics) {
    var gate = _validateSourceOwnershipStageGate(
            sourceCoverageDiagnostics, sourceOwnershipModelDiagnostics, objectPlanDiagnostics);
    if (ctx && ctx.outputDir) {
        writeJson(ctx.outputDir + "/source-ownership-stage-gate.json", gate);
    }
    if (gate.status !== "OK") {
        throw new Error("Source ownership validation failed: "
                + gate.issueCount
                + " issue(s): "
                + _objectPlanGateIssueCodesForMessage(gate.issueCodeCounts));
    }
    return gate;
}

function _validateSourceOwnershipRenderUnits(
        sourceOwnershipModelDiagnostics, objectPlanDiagnostics, issues) {
    if (!sourceOwnershipModelDiagnostics) return;
    var renderUnitsDoc = sourceOwnershipModelDiagnostics.renderUnits || null;
    if (!renderUnitsDoc) {
        issues.push({
            code: "missing_render_unit_diagnostics",
            severity: "ERROR"
        });
        return;
    }

    var objectPlanIds = {};
    var plans = objectPlanDiagnostics && objectPlanDiagnostics.objectPlans
            ? objectPlanDiagnostics.objectPlans
            : [];
    for (var p = 0; p < plans.length; p++) {
        var plan = plans[p];
        if (plan && plan.objectPlanId) objectPlanIds[String(plan.objectPlanId)] = true;
    }

    var renderUnits = renderUnitsDoc.renderUnits || [];
    for (var r = 0; r < renderUnits.length; r++) {
        var unit = renderUnits[r];
        if (!unit) continue;
        if (!unit.objectPlanId) {
            issues.push({
                code: "render_unit_missing_object_plan",
                severity: "ERROR",
                renderUnitId: unit.renderUnitId || null,
                slotId: unit.slotId || null
            });
        } else if (!objectPlanIds[String(unit.objectPlanId)]) {
            issues.push({
                code: "render_unit_unknown_object_plan",
                severity: "ERROR",
                renderUnitId: unit.renderUnitId || null,
                objectPlanId: unit.objectPlanId,
                slotId: unit.slotId || null
            });
        }
    }
}

function _objectPlanGateIssueCodeCounts(issues) {
    var counts = {};
    for (var i = 0; issues && i < issues.length; i++) {
        var key = issues[i] && issues[i].code ? issues[i].code : "UNKNOWN";
        if (!counts[key]) counts[key] = 0;
        counts[key]++;
    }
    return counts;
}

function _objectPlanGateIssueCodesForMessage(counts) {
    var parts = [];
    for (var key in counts) {
        if (!counts.hasOwnProperty(key)) continue;
        parts.push(key + "=" + counts[key]);
    }
    return parts.length > 0 ? parts.join(", ") : "UNKNOWN";
}

function _validateExecutionCandidateContract(plan, issues) {
    var summary = plan ? plan.executionCandidateContractSummary : null;
    if (!summary) {
        issues.push({
            code: "missing_execution_candidate_contract_summary",
            severity: "ERROR"
        });
        return;
    }
    if (summary.rowsWithMissingRequiredCount && summary.rowsWithMissingRequiredCount > 0) {
        issues.push({
            code: "execution_candidate_contract_missing_required_fields",
            severity: "ERROR",
            rowsWithMissingRequiredCount: summary.rowsWithMissingRequiredCount,
            missingRequiredCounts: summary.missingRequiredCounts || {}
        });
    }
}

function _validateExtractionResultRowDuplicates(rows, issues) {
    var seen = {};
    for (var i = 0; rows && i < rows.length; i++) {
        var row = rows[i];
        if (!row) continue;
        var key = _extractionResultRowDuplicateKey(row);
        if (seen[key]) {
            issues.push({
                code: "duplicate_extraction_result_row",
                severity: "ERROR",
                firstExportId: seen[key].exportId || null,
                duplicateExportId: row.exportId || null,
                candidateId: row.candidateId || null,
                planPassId: row.planPassId || null,
                pageIndex: row.pageIndex,
                id: row.id,
                file: row.file || null,
                sourceObjectIds: row.sourceObjectIds || []
            });
        } else {
            seen[key] = row;
        }
    }
}

function _extractionResultRowDuplicateKey(row) {
    function arrKey(arr) {
        if (!arr || arr.length === 0) return "";
        return _sortedNumericIds(arr).join(",");
    }
    function boundsKey(bounds) {
        if (!bounds || bounds.length < 4) return "";
        return [
            Math.round(bounds[0] * 100),
            Math.round(bounds[1] * 100),
            Math.round(bounds[2] * 100),
            Math.round(bounds[3] * 100)
        ].join(",");
    }
    return [
        row.candidateId || "",
        row.planPassId || "",
        row.pageIndex !== undefined ? row.pageIndex : "",
        row.id !== undefined ? row.id : "",
        row.type || "",
        row.file || "",
        row.reason || "",
        boundsKey(row.bounds),
        arrKey(row.sourceObjectIds)
    ].join("|");
}

function _validateExtractionResultRenderUnitContracts(plan, rows, candidateById, issues) {
    var renderUnits = plan && plan.renderUnits ? plan.renderUnits : [];
    var renderUnitById = {};
    var renderUnitByCandidateId = {};
    for (var ui = 0; ui < renderUnits.length; ui++) {
        var unit = renderUnits[ui];
        if (!unit) continue;
        if (unit.renderUnitId) renderUnitById[String(unit.renderUnitId)] = unit;
        if (unit.candidateId) renderUnitByCandidateId[String(unit.candidateId)] = unit;
    }
    if ((!renderUnits || renderUnits.length === 0) && rows && rows.length > 0) {
        issues.push({
            code: "rendered_result_without_render_unit_plan",
            severity: "ERROR",
            resultCount: rows.length
        });
        return;
    }
    for (var ri = 0; rows && ri < rows.length; ri++) {
        var row = rows[ri];
        if (!row) continue;
        var candidate = row.candidateId && candidateById ? candidateById[String(row.candidateId)] : null;
        var expectedUnit = candidate && candidate.renderUnitId
                ? renderUnitById[String(candidate.renderUnitId)]
                : (row.candidateId ? renderUnitByCandidateId[String(row.candidateId)] : null);
        if (!row.renderUnitId) {
            issues.push({
                code: "rendered_result_missing_render_unit",
                severity: "ERROR",
                exportId: row.exportId || null,
                candidateId: row.candidateId || null,
                planPassId: row.planPassId || null,
                expectedRenderUnitId: expectedUnit ? expectedUnit.renderUnitId || null : null
            });
            continue;
        }
        var rowUnit = renderUnitById[String(row.renderUnitId)];
        if (!rowUnit) {
            issues.push({
                code: "rendered_result_unknown_render_unit",
                severity: "ERROR",
                exportId: row.exportId || null,
                candidateId: row.candidateId || null,
                planPassId: row.planPassId || null,
                renderUnitId: row.renderUnitId
            });
            continue;
        }
        if (expectedUnit && rowUnit.renderUnitId !== expectedUnit.renderUnitId) {
            issues.push({
                code: "rendered_result_render_unit_mismatch",
                severity: "ERROR",
                exportId: row.exportId || null,
                candidateId: row.candidateId || null,
                renderUnitId: row.renderUnitId,
                expectedRenderUnitId: expectedUnit.renderUnitId || null
            });
        }
        var rowSlotIdentityKey = row.renderUnitSlotIdentityKey
                || (candidate ? candidate.renderUnitSlotIdentityKey : null);
        var rowUnitMatchesSlotIdentity = rowSlotIdentityKey
                && rowUnit.slotIdentityKey
                && String(rowSlotIdentityKey) === String(rowUnit.slotIdentityKey);
        if (row.candidateId && rowUnit.candidateId
                && String(row.candidateId) !== String(rowUnit.candidateId)
                && !rowUnitMatchesSlotIdentity) {
            issues.push({
                code: "rendered_result_render_unit_candidate_mismatch",
                severity: "ERROR",
                exportId: row.exportId || null,
                candidateId: row.candidateId,
                renderUnitId: row.renderUnitId,
                renderUnitCandidateId: rowUnit.candidateId
            });
        }
        if (row.planPassId && rowUnit.passId
                && String(row.planPassId) !== String(rowUnit.passId)) {
            issues.push({
                code: "rendered_result_render_unit_pass_mismatch",
                severity: "ERROR",
                exportId: row.exportId || null,
                candidateId: row.candidateId || null,
                renderUnitId: row.renderUnitId,
                resultPassId: row.planPassId,
                renderUnitPassId: rowUnit.passId
            });
        }
    }
}

function _buildExtractionValidationIndexes(plan, issues) {
    var passById = {};
    var candidateById = {};
    if (!plan || !plan.exportPasses) {
        issues.push({ code: "missing_extraction_plan", severity: "ERROR" });
    } else {
        for (var i = 0; i < plan.exportPasses.length; i++) {
            var p = plan.exportPasses[i];
            if (p && p.id && !p.disabled) passById[p.id] = p;
        }
    }
    if (!plan || !plan.candidates) {
        issues.push({ code: "missing_extraction_candidates", severity: "ERROR" });
    } else {
        for (var ci = 0; ci < plan.candidates.length; ci++) {
            var c = plan.candidates[ci];
            if (c && c.candidateId) candidateById[c.candidateId] = c;
        }
    }
    return { passById: passById, candidateById: candidateById };
}

function _validateExtractionCandidatePassContracts(plan, passById, issues) {
    if (!plan || !plan.candidates) return;
    for (var ci = 0; ci < plan.candidates.length; ci++) {
        var c = plan.candidates[ci];
        if (!c || !c.candidateId || !c.passId) continue;
        var cp = passById[c.passId];
        if (!cp) continue;
        var modeCompatible = c.mode && cp.mode && c.mode !== cp.mode
                && !(c.mode === "SLOT_ONLY" && cp.mode === "TEXTLESS_CANDIDATE");
        if (modeCompatible) {
            issues.push({
                code: "candidate_mode_mismatch_pass",
                severity: "ERROR",
                candidateId: c.candidateId,
                passId: c.passId,
                candidateMode: c.mode,
                passMode: cp.mode
            });
        }
        if (c.candidatePurpose && cp.candidatePurpose && c.candidatePurpose !== cp.candidatePurpose) {
            issues.push({
                code: "candidate_purpose_mismatch_pass",
                severity: "ERROR",
                candidateId: c.candidateId,
                passId: c.passId,
                candidatePurpose: c.candidatePurpose,
                passPurpose: cp.candidatePurpose
            });
        }
    }
}

function _isExtractionValidationVisibleCandidate(candidate) {
    if (!candidate || !candidate.passId) return false;
    var migratedPasses = _migratedExtractionPasses();
    if (!migratedPasses[candidate.passId]) return false;
    if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) return false;
    return true;
}

function _isExtractionValidationSlotOnlyShell(candidate) {
    if (!candidate) return false;
    if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
    if (candidate.passId !== "pass.decoration_groups"
            && candidate.passId !== "pass.editable_textframe_visual_shells") {
        return false;
    }
    if (candidate.slotRole !== "shell_slot_only" && candidate.mode !== "SLOT_ONLY") return false;
    return candidate.hiddenVisualSourceObjectIds
            && candidate.hiddenVisualSourceObjectIds.length > 0;
}

function _candidateValidationEffectiveVisualSourceIds(candidate) {
    if (!candidate) return [];
    if (candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0) {
        return candidate.exportSourceObjectIds;
    }
    if (_isExtractionValidationSlotOnlyShell(candidate)) return [];
    return candidate.sourceObjectIds || [];
}

function _isExtractionValidationDirectChildShellSlot(candidate) {
    if (!candidate) return false;
    if (candidate.passId !== "pass.decoration_groups") return false;
    if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
    if (candidate.slotRole !== "direct_child_shell_slot"
            && candidate.compositeRole !== "direct_child_shell_slot") return false;
    return candidate.exportSourceObjectIds && candidate.exportSourceObjectIds.length > 0;
}

function _isExtractionValidationPlannedTextShellCandidate(candidate) {
    if (!candidate) return false;
    if (candidate.passId !== "pass.decoration_groups"
            && candidate.passId !== "pass.editable_textframe_visual_shells") {
        return false;
    }
    if (candidate.candidatePurpose !== "SHELL_CANDIDATE") return false;
    if (candidate.materialization !== "EXTRACTED_PNG_VECTOR") return false;
    if (candidate.slotRole !== "direct_child_shell_slot"
            && candidate.slotRole !== "shell_slot_only"
            && candidate.compositeRole !== "direct_child_shell_slot"
            && candidate.compositeRole !== "shell_slot_only") {
        return false;
    }
    if (!candidate.exportSourceObjectIds || candidate.exportSourceObjectIds.length === 0) return false;
    return candidate.textOwner === "hwpx_tf"
            || (candidate.hiddenTextFrameIds && candidate.hiddenTextFrameIds.length > 0)
            || (candidate.editableTextFrameIds && candidate.editableTextFrameIds.length > 0);
}

function _shouldTrackCandidateForInlineShellConflict(candidate) {
    if (!candidate) return false;
    if (candidate.passId === "pass.inline_objects") return true;
    return _isExtractionValidationSlotOnlyShell(candidate)
            && candidate.exportSourceObjectIds
            && candidate.exportSourceObjectIds.length > 0;
}

function _isInlineShellConflict(a, b) {
    if (!a || !b) return false;
    if (a.passId === b.passId) return false;
    return a.passId === "pass.inline_objects" || b.passId === "pass.inline_objects";
}

function _validateExtractionCandidateOwnershipSlots(plan, issues) {
    if (!plan || !plan.candidates) return;
    var ownerByPageSource = {};
    for (var ci = 0; ci < plan.candidates.length; ci++) {
        var c = plan.candidates[ci];
        if (!_isExtractionValidationVisibleCandidate(c)) continue;
        if (_isExtractionValidationSlotOnlyShell(c)) {
            if (!c.exportSourceObjectIds || c.exportSourceObjectIds.length === 0) {
                issues.push({
                    code: "slot_only_shell_missing_export_sources",
                    severity: "ERROR",
                    candidateId: c.candidateId,
                    passId: c.passId,
                    pageIndex: c.pageIndex,
                    sourceObjectIds: c.sourceObjectIds || [],
                    hiddenVisualSourceObjectIds: c.hiddenVisualSourceObjectIds || []
                });
            } else {
                if (!_sourceSetContainsAll(c.sourceObjectIds || [], c.exportSourceObjectIds || [])) {
                    issues.push({
                        code: "slot_only_shell_export_not_in_source_set",
                        severity: "ERROR",
                        candidateId: c.candidateId,
                        passId: c.passId,
                        pageIndex: c.pageIndex,
                        sourceObjectIds: c.sourceObjectIds || [],
                        exportSourceObjectIds: c.exportSourceObjectIds || []
                    });
                }
                if (_sourceSetsIntersect(c.exportSourceObjectIds || [], c.hiddenVisualSourceObjectIds || [])) {
                    issues.push({
                        code: "slot_only_shell_export_intersects_hidden_sources",
                        severity: "ERROR",
                        candidateId: c.candidateId,
                        passId: c.passId,
                        pageIndex: c.pageIndex,
                        exportSourceObjectIds: c.exportSourceObjectIds || [],
                        hiddenVisualSourceObjectIds: c.hiddenVisualSourceObjectIds || []
                    });
                }
            }
        }
        if (c && c.passId === "pass.inline_objects"
                && c.editableTextFrameIds
                && c.editableTextFrameIds.length > 0) {
            if (c.visualAction === "DROP_VISUAL" && c.textOwner === "hwpx_tf") {
                // Stage 1 decided there is no visible inline PNG slot. The
                // editable child text remains owned by HWPX, so this is a
                // valid text-only/layout-only inline contract.
            } else if (c.requiresTextHidden === true) {
                if (c.textOwner !== "hwpx_tf") {
                    issues.push({
                        code: "inline_text_hidden_candidate_without_hwpx_text_owner",
                        severity: "ERROR",
                        candidateId: c.candidateId,
                        pageIndex: c.pageIndex,
                        editableTextFrameIds: c.editableTextFrameIds || [],
                        textOwner: c.textOwner || null
                    });
                }
            } else if (c.completePngTextAllowed === true) {
                if (c.textOwner !== "indesign_png") {
                    issues.push({
                        code: "inline_complete_text_candidate_without_png_text_owner",
                        severity: "ERROR",
                        candidateId: c.candidateId,
                        pageIndex: c.pageIndex,
                        editableTextFrameIds: c.editableTextFrameIds || [],
                        textOwner: c.textOwner || null
                    });
                }
            } else {
                issues.push({
                    code: "inline_editable_text_candidate_without_owner_contract",
                    severity: "ERROR",
                    candidateId: c.candidateId,
                    pageIndex: c.pageIndex,
                    editableTextFrameIds: c.editableTextFrameIds || [],
                    textOwner: c.textOwner || null
                });
            }
        }
        if (!_shouldTrackCandidateForInlineShellConflict(c)) continue;
        var effectiveSourceIds = _candidateValidationEffectiveVisualSourceIds(c);
        for (var si = 0; si < effectiveSourceIds.length; si++) {
            var key = String(c.pageIndex) + "|" + String(effectiveSourceIds[si]);
            var previous = ownerByPageSource[key];
            if (previous && previous.candidateId !== c.candidateId && _isInlineShellConflict(previous, c)) {
                issues.push({
                    code: "inline_source_also_owned_by_slot_shell",
                    severity: "ERROR",
                    pageIndex: c.pageIndex,
                    sourceObjectId: effectiveSourceIds[si],
                    firstCandidateId: previous.candidateId,
                    firstPassId: previous.passId,
                    secondCandidateId: c.candidateId,
                    secondPassId: c.passId
                });
            } else {
                ownerByPageSource[key] = c;
            }
        }
    }
}

function _sourceSetsEqualForExtractionValidation(a, b) {
    if (!a || !b) return false;
    if (a.length !== b.length) return false;
    return _sourceSetContainsAll(a, b) && _sourceSetContainsAll(b, a);
}

function _isCompositeGraphicSourceSetCandidate(candidate) {
    if (!candidate) return false;
    if (candidate.passId !== "pass.complex_graphic_frames") return false;
    if (candidate.candidatePurpose !== "CONTENT_CANDIDATE") return false;
    if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length <= 1) return false;
    return candidate.composite === true
            || candidate.compositeRole === "complex_graphic_source_set";
}

function _compositeGraphicSourceSetRoot(candidate) {
    if (!candidate) return null;
    if (candidate.primarySourceObjectId !== null && candidate.primarySourceObjectId !== undefined) {
        return candidate.primarySourceObjectId;
    }
    if (candidate.sourceObjectIds && candidate.sourceObjectIds.length > 0) {
        return candidate.sourceObjectIds[0];
    }
    return null;
}

function _validateCompositeSourceSetCandidateContracts(plan, issues) {
    if (!plan || !plan.candidates) return;
    for (var ci = 0; ci < plan.candidates.length; ci++) {
        var candidate = plan.candidates[ci];
        if (!_isCompositeGraphicSourceSetCandidate(candidate)) continue;
        var rootId = _compositeGraphicSourceSetRoot(candidate);
        if (candidate.exportTargetObjectId === null
                || candidate.exportTargetObjectId === undefined
                || String(candidate.exportTargetObjectId) !== String(rootId)) {
            issues.push({
                code: "composite_source_set_export_target_not_root",
                severity: "ERROR",
                candidateId: candidate.candidateId,
                passId: candidate.passId,
                pageIndex: candidate.pageIndex,
                primarySourceObjectId: candidate.primarySourceObjectId,
                exportTargetObjectId: candidate.exportTargetObjectId,
                sourceObjectIds: candidate.sourceObjectIds || []
            });
        }
        if (!_sourceSetsEqualForExtractionValidation(
                candidate.exportSourceObjectIds || [],
                candidate.sourceObjectIds || [])) {
            issues.push({
                code: "composite_source_set_export_sources_not_closed",
                severity: "ERROR",
                candidateId: candidate.candidateId,
                passId: candidate.passId,
                pageIndex: candidate.pageIndex,
                sourceObjectIds: candidate.sourceObjectIds || [],
                exportSourceObjectIds: candidate.exportSourceObjectIds || []
            });
        }
        if (!_sourceSetsEqualForExtractionValidation(
                candidate.visualSourceObjectIds || [],
                candidate.sourceObjectIds || [])) {
            issues.push({
                code: "composite_source_set_visual_sources_not_closed",
                severity: "ERROR",
                candidateId: candidate.candidateId,
                passId: candidate.passId,
                pageIndex: candidate.pageIndex,
                sourceObjectIds: candidate.sourceObjectIds || [],
                visualSourceObjectIds: candidate.visualSourceObjectIds || []
            });
        }
    }
}

function _validateCompositeSourceSetResultRow(candidate, row, issues) {
    if (!_isCompositeGraphicSourceSetCandidate(candidate)) return;
    var rootId = _compositeGraphicSourceSetRoot(candidate);
    if (rootId !== null && rootId !== undefined
            && row.id !== null && row.id !== undefined
            && String(row.id) !== String(rootId)) {
        issues.push({
            code: "composite_source_set_result_exported_non_root",
            severity: "ERROR",
            exportId: row.exportId || null,
            candidateId: candidate.candidateId,
            pageIndex: row.pageIndex,
            resultId: row.id,
            expectedRootId: rootId,
            sourceObjectIds: candidate.sourceObjectIds || []
        });
    }
    if (row.candidateMatchStrategy !== "candidate_source_set_direct") {
        issues.push({
            code: "composite_source_set_result_not_matched_by_source_set",
            severity: "ERROR",
            exportId: row.exportId || null,
            candidateId: candidate.candidateId,
            pageIndex: row.pageIndex,
            candidateMatchStrategy: row.candidateMatchStrategy || null,
            sourceObjectIds: candidate.sourceObjectIds || []
        });
    }
    if (!_sourceSetsEqualForExtractionValidation(
            row.sourceObjectIds || [],
            candidate.sourceObjectIds || [])) {
        issues.push({
            code: "composite_source_set_result_sources_not_closed",
            severity: "ERROR",
            exportId: row.exportId || null,
            candidateId: candidate.candidateId,
            pageIndex: row.pageIndex,
            candidateSourceObjectIds: candidate.sourceObjectIds || [],
            resultSourceObjectIds: row.sourceObjectIds || []
        });
    }
    if (!_sourceSetsEqualForExtractionValidation(
            row.executionSourceObjectIds || [],
            candidate.sourceObjectIds || [])) {
        issues.push({
            code: "composite_source_set_result_execution_sources_not_closed",
            severity: "ERROR",
            exportId: row.exportId || null,
            candidateId: candidate.candidateId,
            pageIndex: row.pageIndex,
            candidateSourceObjectIds: candidate.sourceObjectIds || [],
            resultExecutionSourceObjectIds: row.executionSourceObjectIds || []
        });
    }
}

function _validateExtractionResultRows(rows, passById, candidateById, issues) {
    var resultByCandidateId = {};
    var resultCountByCandidateId = {};
    var resultExportsByCandidateId = {};
    var migratedPasses = _migratedExtractionPasses();
    var forbiddenMigratedStrategies = _forbiddenMigratedCandidateStrategies();
    for (var r = 0; r < rows.length; r++) {
        var row = rows[r];
        if (!row) continue;
        if (!row.planPassId) {
            issues.push({
                code: "result_without_plan_pass",
                severity: "ERROR",
                exportId: row.exportId || null,
                id: row.id,
                reason: row.reason
            });
            continue;
        }
        if (!passById[row.planPassId]) {
            issues.push({
                code: "result_from_unplanned_or_disabled_pass",
                severity: "ERROR",
                exportId: row.exportId || null,
                planPassId: row.planPassId,
                id: row.id,
                reason: row.reason
            });
        }
        if (!row.candidateId) {
            issues.push({
                code: "result_without_candidate",
                severity: "ERROR",
                exportId: row.exportId || null,
                planPassId: row.planPassId || null,
                id: row.id,
                reason: row.reason
            });
        } else if (!row.stampedCandidateId) {
            issues.push({
                code: "result_without_stamped_candidate",
                severity: "ERROR",
                exportId: row.exportId || null,
                planPassId: row.planPassId || null,
                id: row.id,
                reason: row.reason
            });
        } else if (!candidateById[row.candidateId]) {
            issues.push({
                code: "result_from_unknown_candidate",
                severity: "ERROR",
                exportId: row.exportId || null,
                candidateId: row.candidateId,
                id: row.id,
                reason: row.reason
            });
        } else {
            var candidate = candidateById[row.candidateId];
            resultByCandidateId[row.candidateId] = true;
            if (row.stampedCandidateId !== row.candidateId) {
                issues.push({
                    code: "result_stamped_candidate_mismatch",
                    severity: "ERROR",
                    exportId: row.exportId || null,
                    stampedCandidateId: row.stampedCandidateId,
                    candidateId: row.candidateId,
                    planPassId: row.planPassId,
                    id: row.id,
                    reason: row.reason
                });
            }
            if (!row.candidateMatchStrategy) {
                issues.push({
                    code: "result_without_candidate_match_strategy",
                    severity: "ERROR",
                    exportId: row.exportId || null,
                    candidateId: row.candidateId,
                    planPassId: row.planPassId,
                    id: row.id,
                    reason: row.reason
                });
            } else if (String(row.candidateMatchStrategy).indexOf("fallback") >= 0
                    || row.candidateMatchStrategy === "source_set_legacy_all_members") {
                issues.push({
                    code: "result_matched_by_forbidden_candidate_strategy",
                    severity: "ERROR",
                    exportId: row.exportId || null,
                    candidateId: row.candidateId,
                    candidateMatchStrategy: row.candidateMatchStrategy,
                    planPassId: row.planPassId,
                    id: row.id,
                    reason: row.reason
                });
            } else if (migratedPasses[row.planPassId] && forbiddenMigratedStrategies[row.candidateMatchStrategy]) {
                issues.push({
                    code: "migrated_pass_used_legacy_candidate_strategy",
                    severity: "ERROR",
                    exportId: row.exportId || null,
                    candidateId: row.candidateId,
                    candidateMatchStrategy: row.candidateMatchStrategy,
                    planPassId: row.planPassId,
                    id: row.id,
                    reason: row.reason
                });
            }
            if (!resultCountByCandidateId[row.candidateId]) {
                resultCountByCandidateId[row.candidateId] = 0;
                resultExportsByCandidateId[row.candidateId] = [];
            }
            resultCountByCandidateId[row.candidateId]++;
            resultExportsByCandidateId[row.candidateId].push(row.exportId || ("id:" + row.id));
            if (candidate.passId !== row.planPassId) {
                issues.push({
                    code: "result_candidate_pass_mismatch",
                    severity: "ERROR",
                    exportId: row.exportId || null,
                    candidateId: row.candidateId,
                    candidatePassId: candidate.passId,
                    resultPassId: row.planPassId,
                    id: row.id,
                    reason: row.reason
                });
            }
            if (candidate.pageIndex !== null && candidate.pageIndex !== undefined
                    && candidate.pageIndex >= 0
                    && row.pageIndex !== null && row.pageIndex !== undefined
                    && candidate.pageIndex !== row.pageIndex) {
                issues.push({
                    code: "result_candidate_page_mismatch",
                    severity: "ERROR",
                    exportId: row.exportId || null,
                    candidateId: row.candidateId,
                    candidatePageIndex: candidate.pageIndex,
                    resultPageIndex: row.pageIndex,
                    id: row.id,
                    reason: row.reason
                });
            }
            _validateCompositeSourceSetResultRow(candidate, row, issues);
            if (candidate.passId === "pass.inline_objects"
                    && candidate.requiresTextHidden === true) {
                if (row.textOwner !== "hwpx_tf"
                        || row.reason !== "inline_text_hidden"
                        || row.textHiddenBeforeExport !== true) {
                    issues.push({
                        code: "inline_text_hidden_candidate_rendered_with_text_pixels",
                        severity: "ERROR",
                        exportId: row.exportId || null,
                        candidateId: row.candidateId,
                        pageIndex: row.pageIndex,
                        reason: row.reason || null,
                        textOwner: row.textOwner || null,
                        textHiddenBeforeExport: row.textHiddenBeforeExport === true
                    });
                }
                if (!_sourceSetContainsAll(row.hiddenTextFrameIds || [], candidate.editableTextFrameIds || [])) {
                    issues.push({
                        code: "inline_text_hidden_result_missing_planned_text_frames",
                        severity: "ERROR",
                        exportId: row.exportId || null,
                        candidateId: row.candidateId,
                        pageIndex: row.pageIndex,
                        plannedEditableTextFrameIds: candidate.editableTextFrameIds || [],
                        hiddenTextFrameIds: row.hiddenTextFrameIds || []
                    });
                }
            }
            if (row.sourceObjectIds && row.sourceObjectIds.length > 0) {
                if (!candidate.sourceObjectIds || candidate.sourceObjectIds.length === 0) {
                    issues.push({
                        code: "result_has_sources_but_candidate_has_none",
                        severity: "ERROR",
                        exportId: row.exportId || null,
                        candidateId: row.candidateId,
                        sourceObjectIds: row.sourceObjectIds,
                        id: row.id,
                        reason: row.reason
                    });
                } else if (_isExtractionValidationDirectChildShellSlot(candidate)) {
                    if (!_sourceSetContainsAll(row.sourceObjectIds, candidate.exportSourceObjectIds || [])) {
                        issues.push({
                            code: "direct_child_shell_result_missing_export_sources",
                            severity: "ERROR",
                            exportId: row.exportId || null,
                            candidateId: row.candidateId,
                            exportSourceObjectIds: candidate.exportSourceObjectIds || [],
                            resultSourceObjectIds: row.sourceObjectIds,
                            id: row.id,
                            reason: row.reason
                        });
                    }
                } else if (!_sourceSetContainsAll(candidate.sourceObjectIds, row.sourceObjectIds)) {
                    issues.push({
                        code: "result_sources_not_subset_of_candidate",
                        severity: "ERROR",
                        exportId: row.exportId || null,
                        candidateId: row.candidateId,
                        candidateSourceObjectIds: candidate.sourceObjectIds,
                        resultSourceObjectIds: row.sourceObjectIds,
                        id: row.id,
                        reason: row.reason
                    });
                }
            }
            var rowPass = passById[row.planPassId];
            if (row.textHiddenBeforeExport === true) {
                if (rowPass && rowPass.mayHideText === false) {
                    issues.push({
                        code: "result_hid_text_in_non_textless_pass",
                        severity: "ERROR",
                        exportId: row.exportId || null,
                        candidateId: row.candidateId,
                        planPassId: row.planPassId,
                        id: row.id,
                        reason: row.reason
                    });
                }
                if (!row.hiddenTextFrameIds || row.hiddenTextFrameIds.length === 0) {
                    issues.push({
                        code: "textless_result_missing_hidden_text_frames",
                        severity: "ERROR",
                        exportId: row.exportId || null,
                        candidateId: row.candidateId,
                        planPassId: row.planPassId,
                        id: row.id,
                        reason: row.reason
                    });
                }
            }
        }
    }
    return {
        resultByCandidateId: resultByCandidateId,
        resultCountByCandidateId: resultCountByCandidateId,
        resultExportsByCandidateId: resultExportsByCandidateId
    };
}

function _migratedExtractionPasses() {
    return {
        "pass.vector_shape_frames": true,
        "pass.editable_textframe_visual_shells": true,
        "pass.complex_graphic_frames": true,
        "pass.image_textless_groups": true,
        "pass.page_textless_graphic_groups": true,
        "pass.image_placed_frames": true,
        "pass.decoration_groups": true,
        "pass.inline_objects": true,
        "pass.master_page_graphics": true
    };
}

function _forbiddenMigratedCandidateStrategies() {
    return {
        "primary_source_page": true,
        "primary_source": true,
        "primary_item_page": true,
        "primary_item": true,
        "single_source_page": true,
        "single_source": true,
        "exact_source_set_page": true,
        "exact_source_set": true
    };
}

function _validateExtractionCandidateResultCounts(candidateById, counters, issues) {
    var resultCountByCandidateId = counters.resultCountByCandidateId;
    var resultExportsByCandidateId = counters.resultExportsByCandidateId;
    for (var candidateId in resultCountByCandidateId) {
        if (!resultCountByCandidateId.hasOwnProperty(candidateId)) continue;
        var countedCandidate = candidateById[candidateId];
        if (!countedCandidate || countedCandidate.composite === true) continue;
        if (resultCountByCandidateId[candidateId] > 1) {
            issues.push({
                code: "non_composite_candidate_multiple_results",
                severity: "ERROR",
                candidateId: candidateId,
                passId: countedCandidate.passId || null,
                resultCount: resultCountByCandidateId[candidateId],
                exportIds: resultExportsByCandidateId[candidateId] || []
            });
        }
    }
}

function _validateRequiredExtractionCandidates(plan, resultByCandidateId, issues) {
    if (plan && plan.candidates) {
        for (var ri = 0; ri < plan.candidates.length; ri++) {
            var requiredCandidate = plan.candidates[ri];
            if (!requiredCandidate || requiredCandidate.required !== true) continue;
            if (!resultByCandidateId[requiredCandidate.candidateId]) {
                issues.push({
                    code: "required_candidate_without_result",
                    severity: "ERROR",
                    candidateId: requiredCandidate.candidateId,
                    passId: requiredCandidate.passId || null,
                    pageIndex: requiredCandidate.pageIndex
                });
            }
        }
    }
}

function _validatePlannedTextShellCandidateResults(plan, passById, resultByCandidateId, issues) {
    if (!plan || !plan.candidates) return;
    for (var si = 0; si < plan.candidates.length; si++) {
        var candidate = plan.candidates[si];
        if (!_isExtractionValidationPlannedTextShellCandidate(candidate)) continue;
        if (!passById[candidate.passId]) continue;
        if (resultByCandidateId[candidate.candidateId]) continue;
        issues.push({
            code: "planned_text_shell_candidate_without_result",
            severity: "ERROR",
            candidateId: candidate.candidateId,
            passId: candidate.passId || null,
            pageIndex: candidate.pageIndex,
            slotRole: candidate.slotRole || null,
            compositeRole: candidate.compositeRole || null,
            materialization: candidate.materialization || null,
            textOwner: candidate.textOwner || null,
            sourceObjectIds: candidate.sourceObjectIds || [],
            exportSourceObjectIds: candidate.exportSourceObjectIds || [],
            hiddenTextFrameIds: candidate.hiddenTextFrameIds || [],
            editableTextFrameIds: candidate.editableTextFrameIds || []
        });
    }
}
