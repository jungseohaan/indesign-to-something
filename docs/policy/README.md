# Source Ownership Policy Modules

This directory contains the canonical source ownership policy modules.

The canonical entry point remains
[`docs/specs/POLICY-source-ownership.md`](../specs/POLICY-source-ownership.md).
That file links to these modules and makes them canonical by reference.

## Modules

- [00-overview.md](00-overview.md): core principles, refactoring direction, performance policy
- [10-source-bundle.md](10-source-bundle.md): source bundles, slots, materialization, text-shell roles
- [20-object-plan.md](20-object-plan.md): ObjectPlan contract and Stage 1 decision order
- [30-placement-inline-policy.md](30-placement-inline-policy.md): page/story placement and inline ownership
- [40-text-policy.md](40-text-policy.md): HWPX text ownership and TextFrame layout constraints
- [50-textless-shell-policy.md](50-textless-shell-policy.md): native/extracted textless shell rules
- [60-table-policy.md](60-table-policy.md): table/cell style and table-like carrier rules
- [70-layer-zdepth.md](70-layer-zdepth.md): four policy layers and source z-depth
- [80-executor-rules.md](80-executor-rules.md): executor behavior and forbidden fallback paths
- [90-validation-invariants.md](90-validation-invariants.md): invariants, forbidden patterns, cleanup direction
