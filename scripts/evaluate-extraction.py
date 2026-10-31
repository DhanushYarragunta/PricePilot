#!/usr/bin/env python3
"""Prepare and score an offline, independently verified price-extraction benchmark.

This tool never loads .env, calls a provider, supplies expected observations, or
decides product identity. Those inputs must be recorded independently by a human.
"""

import argparse
import csv
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
from pathlib import Path
import re
import sys
from urllib.parse import parse_qsl, urlencode, urlsplit


RETAILERS = {
    'Amazon.in': 'amazon.in', 'Flipkart': 'flipkart.com', 'Croma': 'croma.com',
    'Reliance Digital': 'reliancedigital.in', 'Vijay Sales': 'vijaysales.com',
    'Tata CLiQ': 'tatacliq.com', 'JioMart': 'jiomart.com', 'Snapdeal': 'snapdeal.com',
    'Myntra': 'myntra.com', 'AJIO': 'ajio.com', 'Nykaa': 'nykaa.com',
    'Sangeetha Mobiles': 'sangeethamobiles.com', 'Poorvika': 'poorvika.com',
}
EXPECTED_FIELDS = (
    'case_id', 'retailer', 'product_url', 'expected_title', 'expected_model',
    'expected_variant', 'expected_price', 'expected_currency', 'expected_stock',
    'verified_at_utc', 'evidence_path', 'evidence_sha256', 'verifier', 'verifier_type',
    'delivery_region', 'offer_conditions', 'verification_status', 'verification_notes',
)
REVIEW_FIELDS = (
    'case_id', 'result_record_sha256', 'identity_correct', 'identity_reason',
    'reviewer', 'reviewer_type', 'reviewed_at_utc',
)
RESULT_FIELDS = {
    'schema_version', 'run_id', 'case_id', 'submitted_url', 'final_url',
    'started_at', 'completed_at', 'observed_at', 'outcome', 'returned_title',
    'returned_price', 'returned_currency', 'returned_stock', 'strategy',
    'source', 'reuse', 'safe_failure_category', 'fallback_used',
    'expected_record_sha256', 'policy_sha256',
}
OUTCOMES = {'SUCCESS', 'NO_PRICE', 'BLOCKED', 'HTTP_ERROR', 'TIMEOUT', 'ERROR', 'SKIPPED_FRESH'}
SCORED_FIELDS = (
    'case_id', 'retailer', 'product_url', 'verification_status', 'attempted',
    'expected_title', 'expected_model', 'expected_variant', 'expected_price',
    'expected_currency', 'expected_stock', 'verified_at_utc', 'outcome',
    'returned_title', 'returned_price', 'returned_currency', 'returned_stock',
    'observed_at', 'source', 'reuse', 'identity_review', 'result_record_sha256',
    'price_correct', 'identity_correct', 'currency_correct', 'stock_correct',
    'end_to_end_correct', 'failure_reasons', 'fallback_used', 'fallback_comparison',
)
CASE_ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9_-]{0,79}\Z')
HASH = re.compile(r'[0-9a-f]{64}\Z')
MONEY = re.compile(r'[0-9]+(?:\.[0-9]+)?\Z')
TIMESTAMP = re.compile(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})\Z')
MAX_FILE_BYTES = 25_000_000


class EvaluationError(Exception):
    """Fixed diagnostic categories: never echo arbitrary input or exception text."""


def utc_now():
    return datetime.now(timezone.utc)


def iso_time(value):
    return value.astimezone(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')


def timestamp(value):
    if not isinstance(value, str) or not TIMESTAMP.fullmatch(value):
        raise EvaluationError('invalid_timestamp')
    try:
        return datetime.fromisoformat(value.replace('Z', '+00:00')).astimezone(timezone.utc)
    except ValueError:
        raise EvaluationError('invalid_timestamp') from None


def money(value):
    """Formatting equivalence only: nonzero fractions of a paisa are not rounded."""
    if not isinstance(value, str) or len(value) > 40 or not MONEY.fullmatch(value):
        return None
    try:
        number = Decimal(value)
        normalized = number.quantize(Decimal('0.01'))
        if number <= 0 or number > Decimal('1000000000000') or number != normalized:
            return None
        return normalized
    except InvalidOperation:
        return None


def unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise EvaluationError('duplicate_json_field')
        result[name] = value
    return result


def decode_json(raw):
    try:
        return json.loads(raw, object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(EvaluationError('nonfinite_json')))
    except (ValueError, UnicodeError, RecursionError):
        raise EvaluationError('invalid_json') from None


def read_bytes(path):
    # An evidence or policy path must never turn this offline tool into an env reader.
    if any(candidate.name == '.env' or candidate.name.startswith('.env.')
           for candidate in (Path(path), Path(path).resolve())):
        raise EvaluationError('environment_file_refused')
    with Path(path).open('rb') as source:
        raw = source.read(MAX_FILE_BYTES + 1)
    if len(raw) > MAX_FILE_BYTES:
        raise EvaluationError('input_too_large')
    return raw


def file_hash(path):
    return hashlib.sha256(read_bytes(path)).hexdigest()


def record_hash(record):
    raw = json.dumps(record, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False)
    return hashlib.sha256(raw.encode('utf-8')).hexdigest()


def read_csv(path, fields):
    try:
        reader = csv.DictReader(io.StringIO(read_bytes(path).decode('utf-8-sig'), newline=''))
        if reader.fieldnames is None or len(reader.fieldnames) != len(set(reader.fieldnames)) \
                or set(reader.fieldnames) != set(fields):
            raise EvaluationError('invalid_csv_headers')
        rows = list(reader)
        if len(rows) > 10000:
            raise EvaluationError('too_many_cases')
        if any(None in row or any(value is None for value in row.values()) for row in rows):
            raise EvaluationError('invalid_csv_row')
        return [{name: value.strip() for name, value in row.items()} for row in rows]
    except (csv.Error, UnicodeError):
        raise EvaluationError('invalid_csv') from None


def load_policy(path, now):
    policy = decode_json(read_bytes(path))
    if not isinstance(policy, dict) or policy.get('schema_version') != 1 \
            or type(policy.get('planned_products')) is not int \
            or not 1 <= policy['planned_products'] <= 10000 \
            or policy.get('price_tolerance') != '0.00' \
            or policy.get('currency') != 'INR' \
            or policy.get('require_human_verifier') is not True \
            or policy.get('fallback_supported') is not False \
            or type(policy.get('max_observation_gap_seconds')) is not int \
            or not 1 <= policy['max_observation_gap_seconds'] <= 86400:
        raise EvaluationError('invalid_policy')
    if timestamp(policy.get('created_at')) > now:
        raise EvaluationError('future_policy')
    return policy


def product_key(value, retailer):
    if not isinstance(value, str) or not value or len(value) > 4096 \
            or any(c.isspace() or ord(c) < 32 for c in value):
        raise EvaluationError('invalid_product_url')
    try:
        url = urlsplit(value)
        host = (url.hostname or '').lower().removeprefix('www.')
        if retailer == 'Snapdeal' and host == 'm.snapdeal.com':
            host = 'snapdeal.com'
        expected_host = RETAILERS[retailer]
        if url.scheme != 'https' or url.username is not None or url.password is not None \
                or url.port not in (None, 443) or host != expected_host:
            raise ValueError()
        if retailer == 'Amazon.in':
            match = re.search(r'/(?:dp|gp/product)/([A-Za-z0-9]{10})(?:/|$)', url.path)
            if not match:
                raise ValueError()
            return host + '/dp/' + match.group(1).upper()
        if not url.path or url.path == '/':
            raise ValueError()
        query = [(key, val) for key, val in parse_qsl(url.query, keep_blank_values=True)
                 if not key.lower().startswith('utm_') and key not in {'ref', 'ref_', 'tag', 'gclid', 'fbclid'}]
        if retailer == 'Flipkart':
            pid = [val for key, val in query if key == 'pid']
            if len(pid) == 1 and pid[0]:
                return host + '/pid/' + pid[0]
        return host + re.sub('/+', '/', url.path).rstrip('/') + '?' + urlencode(sorted(query))
    except (KeyError, ValueError):
        raise EvaluationError('invalid_product_url') from None


def evidence_path(expected_path, value):
    relative = Path(value)
    root = Path(expected_path).resolve().parent / 'evidence'
    candidate = Path(expected_path).resolve().parent / relative
    if relative.is_absolute() or '..' in relative.parts or not relative.parts \
            or relative.parts[0] != 'evidence' \
            or candidate.suffix.lower() not in {'.png', '.jpg', '.jpeg', '.webp', '.pdf', '.txt', '.html'}:
        raise EvaluationError('invalid_evidence_path')
    if not candidate.resolve().is_relative_to(root.resolve()) or root.is_symlink():
        raise EvaluationError('evidence_outside_dataset')
    return candidate


def load_expected(path, policy, now):
    rows = read_csv(path, EXPECTED_FIELDS)
    if len(rows) != policy['planned_products']:
        raise EvaluationError('dataset_count_differs_from_policy')
    seen_ids, seen_products = set(), set()
    required = ('product_url', 'expected_title', 'expected_model', 'expected_variant',
                'expected_price', 'expected_currency', 'expected_stock', 'verified_at_utc',
                'evidence_path', 'evidence_sha256', 'verifier', 'verifier_type',
                'delivery_region', 'offer_conditions')
    for row in rows:
        if not CASE_ID.fullmatch(row['case_id']) or row['case_id'] in seen_ids:
            raise EvaluationError('invalid_or_duplicate_case_id')
        seen_ids.add(row['case_id'])
        if row['retailer'] not in RETAILERS:
            raise EvaluationError('unsupported_retailer')
        if row['verification_status'] not in {'pending', 'blocked', 'verified'}:
            raise EvaluationError('invalid_verification_status')
        if row['product_url']:
            key = product_key(row['product_url'], row['retailer'])
            if key in seen_products:
                raise EvaluationError('duplicate_product_url')
            seen_products.add(key)
        if row['verification_status'] != 'verified':
            continue
        if any(not row[name] for name in required):
            raise EvaluationError('verified_case_missing_expected_value')
        if row['verifier_type'] != 'human':
            raise EvaluationError('independent_human_verification_required')
        if money(row['expected_price']) is None or row['expected_currency'] != 'INR' \
                or row['expected_stock'] not in {'in_stock', 'out_of_stock'} \
                or not 3 <= len(row['expected_title']) <= 500:
            raise EvaluationError('invalid_expected_observation')
        if timestamp(row['verified_at_utc']) > now:
            raise EvaluationError('future_expected_observation')
        if not HASH.fullmatch(row['evidence_sha256']) \
                or file_hash(evidence_path(path, row['evidence_path'])) != row['evidence_sha256']:
            raise EvaluationError('evidence_hash_mismatch')
    return {row['case_id']: row for row in rows}


def load_jsonl(path):
    try:
        lines = read_bytes(path).decode('utf-8').splitlines()
    except UnicodeError:
        raise EvaluationError('invalid_jsonl') from None
    records = [decode_json(line) for line in lines if line.strip()]
    if any(not isinstance(record, dict) for record in records) or len(records) > 10000:
        raise EvaluationError('invalid_jsonl')
    return records


def load_results(path, expected, now, policy_sha256):
    results = {}
    for record in load_jsonl(path):
        if not RESULT_FIELDS.issubset(record) or set(record) - RESULT_FIELDS - {'workflow_submissions'}:
            raise EvaluationError('invalid_result_fields')
        case_id = record.get('case_id')
        if not isinstance(case_id, str) or case_id not in expected or case_id in results:
            raise EvaluationError('unknown_or_duplicate_result_case')
        if record['expected_record_sha256'] != record_hash(expected[case_id]):
            raise EvaluationError('result_expected_record_hash_mismatch')
        if record['policy_sha256'] != policy_sha256:
            raise EvaluationError('result_policy_hash_mismatch')
        if record.get('schema_version') != 1 or not isinstance(record.get('run_id'), str) \
                or not CASE_ID.fullmatch(record['run_id']) or record.get('outcome') not in OUTCOMES \
                or record.get('reuse') not in {'NONE', 'CACHE', 'SHARED'} \
                or type(record.get('fallback_used')) is not bool:
            raise EvaluationError('invalid_result_metadata')
        for name in ('submitted_url', 'final_url', 'returned_title', 'returned_price',
                     'returned_currency', 'strategy', 'source', 'safe_failure_category'):
            if record[name] is not None and (not isinstance(record[name], str) or len(record[name]) > 10000):
                raise EvaluationError('invalid_result_value_type')
        if record['returned_stock'] is not None and type(record['returned_stock']) is not bool:
            raise EvaluationError('invalid_result_value_type')
        if 'workflow_submissions' in record and (type(record['workflow_submissions']) is not int
                                                or record['workflow_submissions'] not in (0, 1)):
            raise EvaluationError('invalid_submission_count')
        started, completed = timestamp(record['started_at']), timestamp(record['completed_at'])
        if completed < started or completed > now:
            raise EvaluationError('invalid_result_time_order')
        if record['observed_at'] is not None:
            timestamp(record['observed_at'])
        results[case_id] = record
    if len({record['run_id'] for record in results.values()}) > 1:
        raise EvaluationError('mixed_run_ids')
    return results


def load_journal(path, expected, results, now, policy_sha256=None):
    entries = {}
    if path is None:
        return entries
    fields = {'schema_version', 'event', 'run_id', 'case_id', 'submitted_url', 'canonical_url', 'started_at',
              'expected_record_sha256', 'policy_sha256'}
    for entry in load_jsonl(path):
        case_id = entry.get('case_id')
        if set(entry) != fields or entry.get('schema_version') != 1 or entry.get('event') != 'started' \
                or not isinstance(case_id, str) or case_id not in expected or case_id in entries \
                or not isinstance(entry.get('run_id'), str) or not CASE_ID.fullmatch(entry['run_id']):
            raise EvaluationError('invalid_attempt_journal')
        if timestamp(entry['started_at']) > now:
            raise EvaluationError('future_attempt')
        if not isinstance(entry['submitted_url'], str) or not isinstance(entry['canonical_url'], str):
            raise EvaluationError('invalid_attempt_journal')
        if entry['expected_record_sha256'] != record_hash(expected[case_id]):
            raise EvaluationError('attempt_expected_record_hash_mismatch')
        if policy_sha256 is None or entry['policy_sha256'] != policy_sha256:
            raise EvaluationError('attempt_policy_hash_mismatch')
        entries[case_id] = entry
    if len({r['run_id'] for r in list(entries.values()) + list(results.values())}) > 1:
        raise EvaluationError('mixed_run_ids')
    for case_id, result in results.items():
        entry = entries.get(case_id)
        if entry is None or any(entry[name] != result[name]
                                for name in ('run_id', 'submitted_url', 'started_at',
                                             'expected_record_sha256', 'policy_sha256')):
            raise EvaluationError('result_journal_mismatch')
    return entries


def load_reviews(path, results, now):
    reviews = {}
    if path is None:
        return reviews
    for row in read_csv(path, REVIEW_FIELDS):
        case_id = row['case_id']
        if case_id not in results or case_id in reviews:
            raise EvaluationError('unknown_or_duplicate_review_case')
        if row['result_record_sha256'] != record_hash(results[case_id]):
            raise EvaluationError('review_result_hash_mismatch')
        if not row['identity_correct']:
            if any(row[name] for name in ('identity_reason', 'reviewer', 'reviewer_type', 'reviewed_at_utc')):
                raise EvaluationError('incomplete_identity_review')
        elif row['identity_correct'] not in {'true', 'false', 'unknown'} \
                or row['reviewer_type'] != 'human' or not row['reviewer'] or not row['identity_reason']:
            raise EvaluationError('invalid_identity_review')
        else:
            reviewed = timestamp(row['reviewed_at_utc'])
            if reviewed < timestamp(results[case_id]['completed_at']) or reviewed > now:
                raise EvaluationError('invalid_review_time')
        reviews[case_id] = row
    return reviews


def percentage(correct, attempted):
    return None if not attempted else float((Decimal(correct) * 100 / attempted).quantize(Decimal('0.01')))


def score(expected, results, reviews, journal, policy):
    scored = []
    for case_id, baseline in expected.items():
        result, attempt = results.get(case_id), journal.get(case_id)
        attempted = result is not None or attempt is not None
        row = {name: baseline.get(name, '') for name in SCORED_FIELDS}
        row.update(attempted=attempted, price_correct=False, identity_correct=False,
                   currency_correct=False, stock_correct=False, end_to_end_correct=False,
                   fallback_used=False, fallback_comparison='not_applicable', identity_review='not_reviewed')
        reasons = []
        if not attempted:
            row['failure_reasons'] = 'not_attempted'
            scored.append(row)
            continue
        if baseline['verification_status'] != 'verified':
            raise EvaluationError('attempted_case_not_independently_verified')
        call = result if result is not None else attempt
        started = timestamp(call['started_at'])
        verified = timestamp(baseline['verified_at_utc'])
        if timestamp(policy['created_at']) > started:
            raise EvaluationError('policy_created_after_attempt')
        if verified > started or (started - verified).total_seconds() > policy['max_observation_gap_seconds']:
            reasons.append('evidence_outside_predeclared_window')
        if call['submitted_url'] != baseline['product_url']:
            reasons.append('submitted_url_differs_from_expected')
        if result is None:
            row.update(outcome='INTERRUPTED', failure_reasons=';'.join(reasons + ['interrupted_attempt']))
            scored.append(row)
            continue
        if result['outcome'] == 'SUCCESS' and result['observed_at'] is not None \
                and (timestamp(result['observed_at']) - verified).total_seconds() > policy['max_observation_gap_seconds']:
            reasons.append('evidence_outside_predeclared_window')
        for name in ('outcome', 'returned_title', 'returned_price', 'returned_currency',
                     'returned_stock', 'observed_at', 'source', 'reuse', 'fallback_used'):
            row[name] = result[name]
        row['result_record_sha256'] = record_hash(result)
        if result['fallback_used']:
            reasons.append('fallback_not_allowed')
        if result['reuse'] != 'NONE':
            reasons.append('reused_observation_not_fresh')
        if result['outcome'] != 'SUCCESS':
            reasons.append({'NO_PRICE': 'invalid_output_or_identity', 'TIMEOUT': 'timeout',
                            'ERROR': 'provider_or_pipeline_error', 'BLOCKED': 'blocked',
                            'HTTP_ERROR': 'provider_http_error', 'SKIPPED_FRESH': 'skipped_fresh'}[result['outcome']])
        else:
            if result['source'] != 'BROWSERACT':
                reasons.append('source_not_browseract')
            if result['observed_at'] is None or not started <= timestamp(result['observed_at']) <= timestamp(result['completed_at']):
                reasons.append('missing_or_stale_observation_time')
        eligible = not reasons
        review = reviews.get(case_id)
        verdict = review['identity_correct'] if review and review['identity_correct'] else 'not_reviewed'
        row['identity_review'] = verdict
        if result['outcome'] == 'SUCCESS':
            actual_price = money(result['returned_price'])
            row['price_correct'] = eligible and actual_price is not None and actual_price == money(baseline['expected_price'])
            row['currency_correct'] = eligible and result['returned_currency'] == baseline['expected_currency']
            row['stock_correct'] = eligible and result['returned_stock'] is not None \
                and result['returned_stock'] == (baseline['expected_stock'] == 'in_stock')
            try:
                url_matches = product_key(result['final_url'], baseline['retailer']) == product_key(baseline['product_url'], baseline['retailer'])
            except EvaluationError:
                url_matches = False
            title_present = isinstance(result['returned_title'], str) and 3 <= len(result['returned_title'].strip()) <= 500
            row['identity_correct'] = eligible and verdict == 'true' and title_present and url_matches
            if not row['price_correct']:
                reasons.append('invalid_price' if actual_price is None else 'price_not_confirmed')
            if not row['currency_correct']:
                reasons.append('currency_not_confirmed')
            if not row['stock_correct']:
                reasons.append('stock_not_confirmed')
            if not row['identity_correct']:
                reasons.append('wrong_product' if verdict == 'false' or not url_matches else
                               'identity_unknown' if verdict == 'unknown' else 'identity_not_confirmed')
        row['end_to_end_correct'] = row['price_correct'] and row['identity_correct'] and row['currency_correct']
        row['failure_reasons'] = ';'.join(dict.fromkeys(reasons))
        scored.append(row)
    attempted = sum(row['attempted'] for row in scored)
    verified_count = sum(row['verification_status'] == 'verified' for row in scored)
    completed = len(results)
    unresolved_reasons = {'evidence_outside_predeclared_window', 'reused_observation_not_fresh',
                          'fallback_not_allowed', 'source_not_browseract',
                          'missing_or_stale_observation_time', 'submitted_url_differs_from_expected'}
    unresolved = sum(row['attempted'] and (row['outcome'] == 'INTERRUPTED'
                     or bool(unresolved_reasons.intersection(row['failure_reasons'].split(';')))
                     or (row['outcome'] == 'SUCCESS' and row['identity_review'] in ('not_reviewed', 'unknown')))
                     for row in scored)
    counts = {name: sum(row[name] for row in scored) for name in
              ('price_correct', 'identity_correct', 'currency_correct', 'stock_correct', 'end_to_end_correct')}
    complete = verified_count == policy['planned_products'] == attempted == completed and unresolved == 0
    summary = {
        'schema_version': 1, 'planned_products': policy['planned_products'],
        'independently_verified_products': verified_count, 'attempted_products': attempted,
        'completed_results': completed, 'interrupted_attempts': attempted - completed,
        'not_attempted_products': policy['planned_products'] - attempted,
        'unresolved_attempts': unresolved, 'benchmark_complete': complete,
        'status': 'complete' if complete else 'not_started' if not attempted else 'partial',
        'accuracy_denominator': attempted,
        'accuracy_scope': 'attempted_cases_only_failures_and_unresolved_count_as_incorrect',
        'correct_counts': counts,
        'price_accuracy_percent': percentage(counts['price_correct'], attempted),
        'end_to_end_accuracy_percent': percentage(counts['end_to_end_correct'], attempted),
        'stock_accuracy_percent': percentage(counts['stock_correct'], attempted),
        'fallback_comparison': 'not_applicable_browseract_only',
        'publishable_full_dataset_claim': complete,
        'limitations': ['Human declarations and evidence hashes are auditable inputs, not proof that a human actually verified them.',
                        'Final URL equality is supplementary; exact model and variant require independent human adjudication.',
                        'No fallback-enabled comparison exists in the BrowserAct-only pipeline.'],
    }
    summary['retailers'] = {retailer: {
        'planned': sum(row['retailer'] == retailer for row in scored),
        'attempted': sum(row['retailer'] == retailer and row['attempted'] for row in scored),
        'end_to_end_correct': sum(row['retailer'] == retailer and row['end_to_end_correct'] for row in scored),
    } for retailer in RETAILERS}
    return scored, summary


def spreadsheet_safe(value):
    if value is None:
        return ''
    text = str(value).lower() if isinstance(value, bool) else str(value)
    if text.lstrip().startswith(('=', '+', '-', '@')) or text.startswith(('\t', '\r', '\n')):
        return "'" + text
    return text


def write_csv(path, fields, rows):
    with Path(path).open('x', encoding='utf-8', newline='') as destination:
        writer = csv.DictWriter(destination, fieldnames=fields, lineterminator='\n')
        writer.writeheader()
        for row in rows:
            writer.writerow({name: spreadsheet_safe(row.get(name, '')) for name in fields})


def write_json(path, value):
    with Path(path).open('x', encoding='utf-8') as destination:
        json.dump(value, destination, indent=2, sort_keys=True, ensure_ascii=False, allow_nan=False)
        destination.write('\n')


def template(output, policy_output, count, max_gap, now):
    if not 1 <= count <= 10000 or not 1 <= max_gap <= 86400:
        raise EvaluationError('invalid_template_options')
    retailers = list(RETAILERS)
    rows = [dict(case_id=f'PP-{index + 1:03d}', retailer=retailers[index % len(retailers)],
                 verification_status='pending') for index in range(count)]
    policy = {'schema_version': 1, 'planned_products': count, 'created_at': iso_time(now),
              'price_tolerance': '0.00', 'currency': 'INR', 'max_observation_gap_seconds': max_gap,
              'require_human_verifier': True, 'fallback_supported': False}
    if Path(output).exists() or Path(policy_output).exists() or Path(output).resolve() == Path(policy_output).resolve():
        raise EvaluationError('output_already_exists_or_overlaps')
    write_csv(output, EXPECTED_FIELDS, rows)
    write_json(policy_output, policy)


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise EvaluationError('invalid_arguments')


def main(argv=None, now=None):
    parser = SafeArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prepare = commands.add_parser('template', help='Create blank cases and a predeclared scoring policy.')
    prepare.add_argument('--output', required=True, type=Path)
    prepare.add_argument('--policy-output', required=True, type=Path)
    prepare.add_argument('--count', type=int, default=50)
    prepare.add_argument('--max-observation-gap-seconds', type=int, default=600)
    for name in ('validate', 'reviews', 'score'):
        command = commands.add_parser(name)
        command.add_argument('--expected', required=True, type=Path)
        command.add_argument('--policy', required=True, type=Path)
        if name != 'validate':
            command.add_argument('--results', required=True, type=Path)
        if name == 'reviews':
            command.add_argument('--output', required=True, type=Path)
        if name == 'score':
            command.add_argument('--reviews', type=Path)
            command.add_argument('--journal', type=Path)
            command.add_argument('--output', required=True, type=Path)
            command.add_argument('--summary', required=True, type=Path)
    try:
        args = parser.parse_args(argv)
        clock = now if now is not None else utc_now()
        if args.command == 'template':
            template(args.output, args.policy_output, args.count, args.max_observation_gap_seconds, clock)
            print(f'Prepared {args.count} unverified cases. No product observations or identity verdicts were generated.')
            return 0
        input_names = [name for name in ('expected', 'policy', 'results', 'reviews', 'journal')
                       if getattr(args, name, None) is not None]
        input_hashes = {name: file_hash(getattr(args, name)) for name in input_names}
        policy = load_policy(args.policy, clock)
        expected = load_expected(args.expected, policy, clock)
        verified = sum(row['verification_status'] == 'verified' for row in expected.values())
        if args.command == 'validate':
            print(f'Dataset: {verified}/{len(expected)} independently verified; {len(expected) - verified} pending or blocked.')
            print('Dataset complete.' if verified == len(expected) else 'Dataset incomplete. No extraction accuracy has been established.')
            return 0
        results = load_results(args.results, expected, clock, input_hashes['policy'])
        if args.command == 'reviews':
            rows = [dict(case_id=case_id, result_record_sha256=record_hash(result)) for case_id, result in results.items()]
            write_csv(args.output, REVIEW_FIELDS, rows)
            print(f'Prepared {len(rows)} blank identity reviews. A human must supply every verdict.')
            return 0
        reviews = load_reviews(args.reviews, results, clock)
        journal = load_journal(args.journal, expected, results, clock, input_hashes['policy'])
        rows, summary = score(expected, results, reviews, journal, policy)
        if any(file_hash(getattr(args, name)) != input_hashes[name] for name in input_names):
            raise EvaluationError('input_changed_during_scoring')
        summary['input_sha256'] = input_hashes
        summary['journal_provided'] = args.journal is not None
        if args.journal is None:
            summary['limitations'].append('No attempt journal supplied; interruptions absent from JSONL cannot be detected.')
            summary['publishable_full_dataset_claim'] = False
            summary['benchmark_complete'] = False
            if summary['attempted_products']:
                summary['status'] = 'partial'
        output_paths = [args.output.resolve(), args.summary.resolve()]
        input_paths = [getattr(args, name).resolve() for name in ('expected', 'policy', 'results', 'reviews', 'journal')
                       if getattr(args, name) is not None]
        if len(set(output_paths)) != 2 or any(path in input_paths or path.exists() for path in output_paths):
            raise EvaluationError('output_already_exists_or_overlaps')
        write_csv(args.output, SCORED_FIELDS, rows)
        write_json(args.summary, summary)
        print(f"Benchmark {summary['status']}: {summary['attempted_products']}/{summary['planned_products']} attempted; "
              f"{summary['unresolved_attempts']} unresolved.")
        if summary['attempted_products']:
            print(f"Price accuracy: {summary['price_accuracy_percent']:.2f}%; end-to-end: {summary['end_to_end_accuracy_percent']:.2f}% "
                  f"over {summary['accuracy_denominator']} attempted cases only.")
            if not summary['publishable_full_dataset_claim']:
                print('Do not claim full-dataset accuracy: completion, evidence, reviews or attempt-journal requirements remain unmet.')
        else:
            print('Accuracy: not measured. No live attempt is recorded.')
        print('Fallback comparison: not applicable (BrowserAct only).')
        return 0
    except EvaluationError as error:
        print(f'Evaluation failed: {error}. No accuracy claim should be made from this command.', file=sys.stderr)
        return 1
    except (OSError, UnicodeError, ValueError, TypeError):
        print('Evaluation failed: file_or_input_error. No accuracy claim should be made from this command.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
