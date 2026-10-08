#!/usr/bin/env python3
"""Derive sanitized RX fixtures from owner-authorized M365 evidence, without editing it.

Expected physical values come from captured Parsed motor info records, not a new
implementation of the decoder. Phone CSVs are a separately recorded output of
the same fork app, so correspondence is regression evidence, not an independent
sensor/vehicle validation. All outputs use relative times and omit peer identity.
"""
import argparse
import csv
import hashlib
import io
import json
import re
from datetime import datetime
from pathlib import Path

CAPTURE_SHA256 = "e92cf09a2d3a6392367dc1dcbcf243a2174307b96e558bdd11b3f506a3b35b58"
PREFIX = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) D/ScooterRepo\(\s*\d+\): (.*)$")
RAW = re.compile(r"MotorInfo raw data \(32 bytes\): ([0-9a-fA-F]{64})$")
WORD = re.compile(r"\s*offset (\d+): 0x([0-9a-fA-F]+) = (\d+)$")
PARSED = re.compile(r"Parsed motor info: MotorInfo\(speed=([^,]+), battery=([^,]+), temp=([^,]+), mileage=([^,]+),")
RX_REGISTERS = {0xB0, 0x25, 0x3A}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def log_time(value):
    # Capture year is recorded in the evidence index; year is not emitted.
    return datetime.strptime("2026-" + value, "%Y-%m-%d %H:%M:%S.%f")


def parse_capture(data):
    motor, rx, counts = [], [], {}
    pending = None
    latest_rx = None
    for line_number, line in enumerate(data.decode("utf-8").splitlines(), 1):
        match = PREFIX.match(line)
        if not match:
            continue
        timestamp, body = match.groups()
        observed = log_time(timestamp)
        if body.startswith("Rx Decrypted: "):
            payload = bytes.fromhex(body.split(": ", 1)[1])
            if len(payload) < 8:
                raise ValueError(f"Short decrypted RX at line {line_number}")
            direction, reply_type, register = payload[:3]
            key = f"{register:02x}"
            counts[key] = counts.get(key, 0) + 1
            if direction == 0x23 and reply_type == 1 and register in RX_REGISTERS:
                latest_rx = {"line": line_number, "time": observed,
                             "register": key, "data_hex": payload[3:-4].hex()}
                rx.append(latest_rx)
        raw = RAW.match(body)
        if raw:
            if pending is not None:
                raise ValueError("MotorInfo record has no captured parsed diagnostics")
            if latest_rx is None or latest_rx["register"] != "b0" or latest_rx["data_hex"] != raw[1].lower():
                raise ValueError(f"Raw block lacks matching preceding RX at line {line_number}")
            if not 0 <= (observed - latest_rx["time"]).total_seconds() <= 0.1:
                raise ValueError("RX/raw association exceeds 100 ms")
            pending = {"sequence": len(motor), "time": observed,
                       "raw_line": line_number, "rx_line": latest_rx["line"],
                       "data_hex": raw[1].lower(), "words": {}}
        word = WORD.match(body)
        if word and pending is not None:
            offset, raw_hex, decimal = word.groups()
            if int(raw_hex, 16) != int(decimal):
                raise ValueError("Captured hex/decimal word diagnostics disagree")
            pending["words"][int(offset)] = int(decimal)
        parsed = PARSED.match(body)
        if parsed and pending is not None:
            if set(pending["words"]) != set(range(0, 24, 2)):
                raise ValueError("Missing captured word diagnostics")
            pending.update(zip(("speed_kmh", "soc_percent", "temp_frame_c", "mileage_km"), parsed.groups()))
            pending["parsed_line"] = line_number
            motor.append(pending)
            pending = None
    if pending is not None:
        raise ValueError("Unfinished MotorInfo record")
    if len(motor) != 152:
        raise ValueError(f"Expected 152 MotorInfo blocks; found {len(motor)}")
    return motor, rx, counts


def read_phone_logs(directory):
    records, sources = [], []
    for path in sorted(directory.glob("m365_telemetry_*.csv")):
        data = path.read_bytes()
        source_id = len(sources)
        sources.append({"id": source_id, "file": path.name, "sha256": sha256(data)})
        for row_number, row in enumerate(csv.DictReader(io.StringIO(data.decode("utf-8-sig"))), 2):
            records.append((datetime.strptime(row["Timestamp"], "%Y-%m-%d %H:%M:%S.%f"), source_id, row_number, row))
    return records, sources


def write_csv(path, columns, rows):
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(columns)
    writer.writerows(rows)
    path.write_bytes(buffer.getvalue().encode("utf-8"))


def extract(logcat, phone_directory, output):
    for source_directory in (logcat.resolve().parent, phone_directory.resolve()):
        if output.resolve().is_relative_to(source_directory):
            raise ValueError("Fixture output must be outside the read-only evidence directories")
    data = logcat.read_bytes()
    if sha256(data) != CAPTURE_SHA256:
        raise ValueError("Capture hash changed; review provenance before accepting a new capture")
    motor, rx, rx_counts = parse_capture(data)
    phones, phone_sources = read_phone_logs(phone_directory)
    if not phone_sources:
        raise ValueError("No telemetry CSV source files found")
    output.mkdir(parents=True, exist_ok=True)
    start = motor[0]["time"]
    elapsed = lambda timestamp: round((timestamp - start).total_seconds() * 1000)
    columns = ["sequence", "elapsed_ms", "raw_line", "rx_line", "parsed_line", "data_hex",
               "speed_kmh", "soc_percent", "temp_frame_c", "mileage_km"]
    columns += [f"word_{offset}" for offset in range(0, 24, 2)]
    write_csv(output / "motor-info.csv", columns, [
        [m["sequence"], elapsed(m["time"]), m["raw_line"], m["rx_line"], m["parsed_line"], m["data_hex"],
         m["speed_kmh"], m["soc_percent"], m["temp_frame_c"], m["mileage_km"]] +
        [m["words"][offset] for offset in range(0, 24, 2)] for m in motor])
    write_csv(output / "rx-registers.csv", ["elapsed_ms", "source_line", "register", "data_hex"],
              [[elapsed(r["time"]), r["line"], r["register"], r["data_hex"]] for r in rx])
    matches, unmatched, ambiguous = [], [], []
    for m in motor:
        candidates = [p for p in phones if abs((p[0] - m["time"]).total_seconds()) <= 0.1]
        if not candidates:
            unmatched.append(m["sequence"])
        elif len(candidates) != 1:
            ambiguous.append(m["sequence"])
        else:
            time, source_id, row_number, row = candidates[0]
            matches.append([m["sequence"], source_id, row_number, elapsed(time) - elapsed(m["time"]),
                            row["Speed"], row["Battery"], row["Temperature"], row["Mileage"]])
    write_csv(output / "phone-correspondence.csv",
              ["sequence", "phone_source_id", "phone_row", "delta_ms", "speed_kmh", "soc_percent", "temp_frame_c", "mileage_km"], matches)
    files = {p.name: sha256(p.read_bytes()) for p in sorted(output.glob("*.csv"))}
    manifest = {
        "schema": 1, "evidence": "wire-captured", "model_scope": "owner Xiaomi M365 only",
        "redistribution": "Owner-authorized captured telemetry, derived into owner's MIT implementation repo; no vendor code.",
        "source": {"file": "logcat-spin-raw.txt", "sha256": sha256(data)},
        "phone_sources": phone_sources, "tool": {"file": "scripts/extract-m365-fixtures.py", "sha256": sha256(Path(__file__).read_bytes())},
        "outputs_sha256": files, "counts": {"motor_info": len(motor), "selected_rx": len(rx), "all_rx_by_register": rx_counts,
                                                "phone_matches": len(matches), "phone_unmatched": unmatched, "phone_ambiguous": ambiguous},
        "transform": [
            "Select only ScooterRepo Rx Decrypted direction 0x23, type 0x01, registers B0/25/3A; exclude TX, identity and all unrelated records.",
            "Strip 3-byte decrypted header and 4-byte trailing padding; retain exact data bytes, relative millisecond times and 1-based source line numbers.",
            "Associate each MotorInfo raw block with preceding matching B0 RX within 100ms; retain all 152 in capture order including duplicates.",
            "Copy logged offset hex/decimal word diagnostics and Parsed motor info physical values verbatim; do not generate expected values by decoding payload.",
            "Read telemetry CSVs only; copy columns Speed/Battery/Temperature/Mileage for a unique timestamp match within 100ms; never match by telemetry value.",
            "Omit device names, MAC addresses, Model/ModelId/Confidence, keys, identity/serial replies and unrelated logcat; emit no absolute timestamps."
        ],
        "limits": [
            "Parsed diagnostics and phone CSVs come from the historical fork app, not an independent vendor app or sensor; they establish regression consistency only.",
            "Captured odometer increments provide an additional speed-scale cross-check; stationary wheel-off-ground capture is not a new hardware acceptance test.",
            "Firmware and board identity are not inferred. Only selected decrypted RX is replayed; BLE notifications, auth and encryption are not covered.",
            "Logged negative speed remains signed for core regression; historical notes describe it as sentinel/transient, not proven reverse motion.",
            "0x25/0x3A data is preserved as neutral register evidence; shared 0x3A scale/semantics require source resolution."
        ]
    }
    (output / "manifest.json").write_bytes((json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8"))
    print(json.dumps(manifest["counts"], sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--logcat", type=Path, required=True)
    parser.add_argument("--phone-logs", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    arguments = parser.parse_args()
    extract(arguments.logcat, arguments.phone_logs, arguments.out)


if __name__ == "__main__":
    main()
