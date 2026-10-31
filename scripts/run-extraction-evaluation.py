#!/usr/bin/env python3
"""Explicit local live benchmark launcher. Preflight is read-only; a run consumes BrowserAct credits.

The Java utility is test-only and wires the existing extraction pipeline to the existing
PostgreSQL api_usage ledger. It cannot write trackers, history, alerts, or schema migrations.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import parse_qsl, urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAVA_INJECTION_ENV = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"}


class EvaluationInputError(ValueError):
    """Fixed public categories only, without rejected values or provider text."""
    def __init__(self, category):
        self.category = category if category in {"no_human_verified_cases", "invalid_reference_evidence"} \
            else "invalid_reference_evidence"
        super().__init__(self.category)


def case_manifest(expected, policy_path, selected_ids=None):
    """Read reference evidence without ever passing expected answers to the extractor."""
    spec = importlib.util.spec_from_file_location("pricepilot_evaluation_scoring", ROOT / "scripts" / "evaluate-extraction.py")
    scoring = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(scoring)
    try:
        now = datetime.now(timezone.utc)
        policy = scoring.load_policy(policy_path, now)
        expected_rows = scoring.load_expected(expected, policy, now)
    except scoring.EvaluationError:
        raise ValueError("Reference dataset or predeclared evaluation policy failed validation.") from None
    if not 1 <= len(expected_rows) <= 100:
        raise ValueError("Expected CSV must contain 1..100 products.")
    policy_hash = hashlib.sha256(policy_path.read_bytes()).hexdigest()
    manifest = []
    selected = set(selected_ids or [])
    for case_id, row in expected_rows.items():
        verified = row.get("verification_status") == "verified" and row.get("verifier_type") == "human"
        if not verified:
            if case_id in selected:
                raise ValueError("An explicitly selected case is not human-verified.")
            continue
        if any(re.search(r"(?:token|secret|password|api.?key|authorization)", name, re.I)
               for name, _ in parse_qsl(urlsplit(row["product_url"]).query)):
            raise ValueError("Reference contains a credential-like product URL parameter.")
        baseline_hash = hashlib.sha256(json.dumps(row, sort_keys=True, separators=(",", ":"),
                                                   ensure_ascii=False).encode("utf-8")).hexdigest()
        manifest.append({"case_id": case_id, "product_url": row["product_url"],
                         "selected": not selected or case_id in selected,
                         "verified_at_utc": row["verified_at_utc"],
                         "max_observation_gap_seconds": policy["max_observation_gap_seconds"],
                         "expected_record_sha256": baseline_hash, "policy_sha256": policy_hash})
    if selected - expected_rows.keys():
        raise ValueError("A selected case identifier is absent from the expected CSV.")
    if not manifest:
        raise EvaluationInputError("no_human_verified_cases")
    return manifest, len(expected_rows) - sum(item["selected"] for item in manifest)


def safe_environment(inherited):
    # Use the project's established dotenv parser; inherited exports retain precedence.
    spec = importlib.util.spec_from_file_location("pricepilot_with_env", ROOT / "scripts" / "with-env.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    environment = module.child_environment(ROOT / ".env", inherited)
    # JVM diagnostic/injection switches may dump headers or launch arbitrary agents.
    return {name: value for name, value in environment.items() if name not in JAVA_INJECTION_ENV}


def runtime_classpath(jar, destination):
    """Reuse already resolved runtime dependencies from the local executable jar, offline."""
    libraries = []
    with zipfile.ZipFile(jar) as archive:
        for entry in archive.infolist():
            if re.fullmatch(r"BOOT-INF/lib/[A-Za-z0-9_.+\-]+\.jar", entry.filename):
                target = destination / Path(entry.filename).name
                target.write_bytes(archive.read(entry))
                libraries.append(str(target))
    if not libraries:
        raise ValueError("Backend executable jar has no runtime dependencies.")
    return os.pathsep.join([str(ROOT / "backend" / "target" / "test-classes"),
                            str(ROOT / "backend" / "target" / "classes"), *libraries])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preflight", action="store_true", help="Read configuration and current budget; no paid request or ledger write")
    parser.add_argument("--expected", type=Path, help="Human-verified expected CSV")
    parser.add_argument("--policy", type=Path, help="Predeclared evaluation policy used by the scorer")
    parser.add_argument("--output", type=Path, help="Append-only result JSONL")
    parser.add_argument("--journal", type=Path, help="Durable attempts JSONL; defaults beside results")
    parser.add_argument("--limit", type=int, default=3, help="Maximum new attempts in this invocation (1..50; default 3)")
    parser.add_argument("--case-id", action="append", default=[], help="Select a verified case; repeat for multiple IDs")
    args = parser.parse_args(argv)
    if not 1 <= args.limit <= 50:
        parser.error("--limit must be between 1 and 50")
    if not args.preflight and (not args.expected or not args.policy or not args.output):
        parser.error("a live run requires --expected, --policy and --output")
    try:
        try:
            manifest, pending = ([], 0) if args.preflight else case_manifest(args.expected.resolve(), args.policy.resolve(), args.case_id)
        except EvaluationInputError:
            raise
        except (ValueError, OSError):
            raise EvaluationInputError("invalid_reference_evidence") from None
        if not args.preflight:
            args.output = args.output.resolve()
            args.journal = (args.journal or args.output.with_suffix(".journal.jsonl")).resolve()
            if args.output == args.journal or args.output == args.expected.resolve() or args.journal == args.expected.resolve():
                raise ValueError("Reference, result and journal files must have different paths.")
            if not args.output.parent.is_dir() or not args.journal.parent.is_dir():
                raise ValueError("Create the local evidence directory before running.")
            print(json.dumps({"human_verified_selected": sum(item["selected"] for item in manifest), "other_rows_not_selected": pending,
                              "max_new_attempts": args.limit, "fallback": "not_applicable"}), flush=True)
        # Compile without loading .env, and without Maven network downloads.
        clean_build_env = {key: value for key, value in os.environ.items() if key not in JAVA_INJECTION_ENV}
        build = subprocess.run([str(ROOT / "backend" / "mvnw"), "-o", "-B", "-ntp", "-DskipTests", "package"],
                               cwd=ROOT / "backend", env=clean_build_env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if build.returncode:
            print("Offline backend build failed. Run the backend build separately for compiler diagnostics.", file=sys.stderr)
            return 2
        with tempfile.TemporaryDirectory(prefix="pricepilot-evaluation-") as directory:
            temporary = Path(directory)
            classpath = runtime_classpath(ROOT / "backend" / "target" / "pricepilot-backend-1.0.0.jar", temporary)
            command = ["java", "-cp", classpath, "com.pricepilot.evaluation.LiveExtractionEvaluation"]
            if args.preflight:
                command.append("--preflight")
            else:
                cases = temporary / "cases.json"
                cases.write_text(json.dumps(manifest), encoding="utf-8")
                command.extend(["--manifest", str(cases), "--output", str(args.output), "--journal", str(args.journal),
                                "--limit", str(args.limit)])
            return subprocess.run(command, cwd=ROOT / "backend", env=safe_environment(os.environ)).returncode
    except EvaluationInputError as failure:
        print(json.dumps({"status": "evaluation_stopped", "safe_failure_category": failure.category}), file=sys.stderr)
        return 2
    except (OSError, ValueError, zipfile.BadZipFile):
        # Paths and parser exceptions can contain unexpected sensitive values. Keep stdout safe.
        print("Evaluation stopped: invalid configuration, reference evidence, or local build artifact. No secrets printed.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
