#!/bin/sh
set -eu
# Single generator cannot establish million-RPS capacity. See capacity-planning.md.
for rate in 1000 10000 100000 500000 1000000; do
  RATE=$rate k6 run --summary-export="results-$rate.json" benchmarks/reservations.js
done
