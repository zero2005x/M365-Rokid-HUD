# Historical owner M365 RX replay

These sanitized derivatives come from the owner's 2026-09-20 wheel-off-ground
capture. Redistribution follows the existing owner-data authorization recorded
in `pev-protocol-core/SOURCE_PROVENANCE.md`; proprietary vendor code is absent.
Evidence is **wire-captured**, never a new vehicle-verified acceptance result.

Reproduce from the repo root (WSL):

```bash
python3 scripts/extract-m365-fixtures.py \
  --logcat /home/kali/PEVAppRE/scooter-apps/hardware-evidence/logcat-spin-raw.txt \
  --phone-logs /home/kali/PEVAppRE/scooter-apps/hardware-evidence/phone-logs \
  --out pev-protocol-core/src/test/resources/fixtures/xiaomi/m365
```

The extractor pins the original capture SHA-256, records all phone-source hashes,
its own hash, output hashes and transformations in `manifest.json`. It reads the
originals without changing them. Output uses LF/UTF-8 and relative times so the
same inputs and tool produce byte-identical outputs on Windows and Linux.

- `motor-info.csv`: all **152** 32-byte B0 payloads in source order, including
  duplicates. Expected physical values are copied from logged `Parsed motor info`
  values; the twelve unsigned word diagnostics are copied from the log's numeric
  offset records. Each row cites source raw/RX/parsed line numbers.
- `rx-registers.csv`: **156** RX-only data blocks, header and trailing padding
  removed: 152 B0, three 3A and one 25. TX, auth and device identity are omitted.
- `phone-correspondence.csv`: **92** unique matches by timestamp within 100 ms;
  observed offsets are 0–9 ms. These contain no telemetry disagreements. Rows
  92–151 have no corresponding phone CSV within this threshold. Matches are never
  chosen by telemetry values. Source-file IDs and row numbers are in the manifest.

Actual capture checks establish SOC {43,44,51}, nondecreasing odometer
400107–400871 m, frame temperature **31–45 C**, and signed decoded speed
**-5.514–0.150 km/h**. There is one negative transient (raw word 60022) at row 46
and one small positive value at row 47. The evidence README's 44 C maximum is
inaccurate for the full raw corpus. Odometer changes occur only across two large
gaps (151.195 s and 326.031 s), so this raw subset does **not** independently
establish the historical 43 moving-window speed-scale comparison.

Phone CSVs and parsed diagnostics were produced by the same historical fork app;
they are separate recordings suitable for regression correspondence but are not
independent physical or vendor-app oracles. Temperature and SOC ranges and
monotonic distance supplement those comparisons. Firmware/board are unknown;
these results cannot establish another model's layout or any command write.
Negative speed is preserved as a core regression observation, not a claim that
the scooter was physically moving backward.

Neutral additional register facts, without scale or semantic claims:

| Source line | Register | Exact RX data |
|---:|---|---|
| 22679 | 3A | b6019100 |
| 22701 | 25 | 0a05 |
| 26992 | 3A | f4019100 |
| 33911 | 3A | 2e029100 |

No keys, advertised device names, MAC addresses, ModelId/Confidence labels,
identity/serial replies or unrelated system logs are included. The source manifest
retains original filenames and hashes for audit, not original full log content.
