-- wrk report script: one machine-readable line with the percentiles the
-- benchmark harness collects (microseconds).
done = function(summary, latency, requests)
  io.write(string.format("PCTL,%.0f,%.0f,%.0f,%.0f,%.0f,%.0f\n",
    latency.mean,
    latency:percentile(50), latency:percentile(90),
    latency:percentile(99), latency:percentile(99.9),
    latency.max))
  io.write(string.format("ERRS,%d,%d,%d,%d,%d\n",
    summary.errors.connect, summary.errors.read,
    summary.errors.write, summary.errors.status, summary.errors.timeout))
end
