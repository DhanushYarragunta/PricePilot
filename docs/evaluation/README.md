# Extraction evaluation

`products-50.csv` contains 50 candidate URLs, not verified reference prices. No live accuracy has been measured. `discovery-provenance.json` records candidate sources; `policy.json` fixes evaluation rules. Sangeetha still needs independently selected product URLs before claiming all-retailer coverage.

Manually verify each exact product/model/variant, displayed selling price, currency and stock on its retailer page. Save independent evidence and the verification timestamp in the CSV. Run small batches within the 600-second policy window. Never fill expected values from BrowserAct, PricePilot output, AI or search snippets, and never change references after an attempted extraction to match its output.

A URL-only workflow cannot reproduce a variant selected through clicks, login or delivery context absent from the URL. Leave such cases unresolved. Do not substitute MRP for an unavailable selling price. Incomplete reference rows keep the full benchmark incomplete.

The scripts document their options:

```bash
python3 scripts/run-extraction-evaluation.py --help
python3 scripts/evaluate-extraction.py --help
```

The runner's `--preflight` checks the local ledger without paid submissions. Live runs consume the shared BrowserAct budget. BrowserAct is the only extraction provider; alternate-parser fallback comparison is not applicable. Preserve original results and report price-only accuracy separately from exact product + price + currency accuracy using the full tested denominator.
