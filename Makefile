.PHONY: issue issue-dry-run inventory trace regression regression-suite reconvert

issue:
	@test -n "$(CASE)" || (echo "CASE is required, e.g. make issue CASE=park31-u1 PAGE=8" && exit 2)
	@test -n "$(PAGE)" || (echo "PAGE is required, e.g. make issue CASE=park31-u1 PAGE=8" && exit 2)
	python3 scripts/dev/issue.py --case "$(CASE)" --page "$(PAGE)" $(if $(END_PAGE),--end-page "$(END_PAGE)",) $(if $(SOURCE),--source "$(SOURCE)",) $(if $(SNIPPET),--snippet "$(SNIPPET)",) $(if $(filter 0 false no,$(OPEN)),--no-open,)

issue-dry-run:
	@test -n "$(CASE)" || (echo "CASE is required, e.g. make issue-dry-run CASE=park31-u1 PAGE=8" && exit 2)
	@test -n "$(PAGE)" || (echo "PAGE is required, e.g. make issue-dry-run CASE=park31-u1 PAGE=8" && exit 2)
	python3 scripts/dev/issue.py --case "$(CASE)" --page "$(PAGE)" $(if $(END_PAGE),--end-page "$(END_PAGE)",) $(if $(SOURCE),--source "$(SOURCE)",) $(if $(SNIPPET),--snippet "$(SNIPPET)",) --dry-run --no-open

inventory:
	@test -n "$(EXTRACT)" || (echo "EXTRACT is required, e.g. make inventory EXTRACT=output/issues/.../extract PAGE=8" && exit 2)
	python3 scripts/dev/page_inventory.py "$(EXTRACT)" $(if $(PAGE),--page "$(PAGE)",) $(if $(PAGE_INDEX),--page-index "$(PAGE_INDEX)",) $(if $(OUT),--out "$(OUT)",)

trace:
	@test -n "$(EXTRACT)" || (echo "EXTRACT is required, e.g. make trace EXTRACT=output/issues/.../extract SOURCE=123 PAGE=8" && exit 2)
	python3 scripts/dev/trace_source.py "$(EXTRACT)" $(if $(SOURCE),--source "$(SOURCE)",) $(if $(PAGE),--page "$(PAGE)",) $(if $(PAGE_INDEX),--page-index "$(PAGE_INDEX)",) $(if $(SNIPPET),--snippet "$(SNIPPET)",) $(if $(OUT),--markdown "$(OUT)",)

reconvert:
	@test -n "$(EXTRACT)" || (echo "EXTRACT is required, e.g. make reconvert EXTRACT=output/issues/.../extract" && exit 2)
	python3 scripts/dev/reconvert.py --extract "$(EXTRACT)" $(if $(OUT),--out "$(OUT)",) $(if $(filter 0 false no,$(OPEN)),--no-open,)

regression:
	@if [ -z "$(EXTRACT)" ] && { [ -z "$(CASE)" ] || [ -z "$(PAGE)" ]; }; then \
		echo "Use EXTRACT=... or CASE=... PAGE=..., e.g. make regression EXTRACT=output/issues/.../extract"; \
		exit 2; \
	fi
	python3 scripts/dev/regression.py $(if $(EXTRACT),--extract "$(EXTRACT)",--case "$(CASE)" --page "$(PAGE)") $(if $(END_PAGE),--end-page "$(END_PAGE)",) $(if $(filter 1 true yes,$(STRICT_WARNINGS)),--strict-warnings,) $(if $(OUT),--out "$(OUT)",)

regression-suite:
	python3 scripts/dev/regression_suite.py $(if $(REGISTRY),--registry "$(REGISTRY)",) $(if $(OUTPUT_ROOT),--output-root "$(OUTPUT_ROOT)",) $(if $(OUT),--out "$(OUT)",) $(if $(ID),--id "$(ID)",) $(if $(TAG),--tag "$(TAG)",) $(if $(filter 1 true yes,$(RUN_MISSING)),--run-missing,) $(if $(filter 1 true yes,$(FORCE)),--force-run,) $(if $(filter 1 true yes,$(STRICT_WARNINGS)),--strict-warnings,)
