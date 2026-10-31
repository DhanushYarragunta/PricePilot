"""Synthetic offline fixtures test scoring rules, never real retailer accuracy."""

import contextlib
import csv
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name('evaluate-extraction.py')
SPEC = importlib.util.spec_from_file_location('evaluate_extraction', MODULE_PATH)
evaluation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluation)


class EvaluationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='pricepilot-synthetic-scoring-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.now = datetime(2026, 10, 31, 14, 0, tzinfo=timezone.utc)
        self.start = self.now - timedelta(minutes=2)
        self.expected_path = self.root / 'expected.csv'
        self.policy_path = self.root / 'policy.json'
        self.results_path = self.root / 'results.jsonl'
        self.reviews_path = self.root / 'reviews.csv'
        self.journal_path = self.root / 'attempts.jsonl'
        (self.root / 'evidence').mkdir()
        self.evidence = self.root / 'evidence' / 'synthetic-page.txt'
        self.evidence.write_text('SYNTHETIC UNIT-TEST EVIDENCE. Not a real retailer page.', encoding='utf-8')
        self.policy = dict(schema_version=1, planned_products=1,
                           created_at=evaluation.iso_time(self.start - timedelta(hours=1)),
                           price_tolerance='0.00', currency='INR',
                           max_observation_gap_seconds=600, require_human_verifier=True,
                           fallback_supported=False)

    def baseline(self, number=1, **changes):
        value = dict.fromkeys(evaluation.EXPECTED_FIELDS, '')
        value.update(case_id=f'PP-{number:03d}', retailer='Amazon.in',
                     product_url=f'https://www.amazon.in/dp/B{number:09d}',
                     expected_title=f'SYNTHETIC device {number} 256GB blue',
                     expected_model=f'SYNTHETIC-model-{number}', expected_variant='256GB / blue',
                     expected_price='24990.00', expected_currency='INR', expected_stock='in_stock',
                     verified_at_utc=evaluation.iso_time(self.start - timedelta(seconds=30)),
                     evidence_path='evidence/synthetic-page.txt',
                     evidence_sha256=hashlib.sha256(self.evidence.read_bytes()).hexdigest(),
                     verifier='synthetic-test-person', verifier_type='human', delivery_region='synthetic-region',
                     offer_conditions='SYNTHETIC unconditional selling price; shipping excluded',
                     verification_status='verified')
        value.update(changes)
        return value

    def result(self, number=1, **changes):
        base = self.baseline(number)
        value = dict(schema_version=1, run_id='synthetic-run-1', case_id=base['case_id'],
                     submitted_url=base['product_url'], final_url=base['product_url'],
                     started_at=evaluation.iso_time(self.start),
                     completed_at=evaluation.iso_time(self.start + timedelta(seconds=20)),
                     observed_at=evaluation.iso_time(self.start + timedelta(seconds=15)),
                     outcome='SUCCESS', returned_title=base['expected_title'], returned_price='24990',
                     returned_currency='INR', returned_stock=True, strategy='browseract',
                     source='BROWSERACT', reuse='NONE', safe_failure_category=None,
                     fallback_used=False, workflow_submissions=1,
                     expected_record_sha256='', policy_sha256='')
        value.update(changes)
        return value

    def review(self, result, verdict='true', **changes):
        value = dict(case_id=result['case_id'], result_record_sha256=evaluation.record_hash(result),
                     identity_correct=verdict, identity_reason='SYNTHETIC explicit model and variant comparison',
                     reviewer='synthetic-reviewer', reviewer_type='human',
                     reviewed_at_utc=evaluation.iso_time(self.now - timedelta(seconds=10)))
        value.update(changes)
        return value

    def journal(self, result, **changes):
        value = {name: result[name] for name in ('schema_version', 'run_id', 'case_id', 'submitted_url', 'started_at')}
        value.update(event='started', canonical_url=result['submitted_url'])
        value.update(expected_record_sha256='', policy_sha256='')
        value.update(changes)
        return value

    def csv(self, path, fields, rows):
        with path.open('w', newline='', encoding='utf-8') as destination:
            writer = csv.DictWriter(destination, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    def jsonl(self, path, records):
        path.write_text(''.join(json.dumps(record) + '\n' for record in records), encoding='utf-8')

    def inputs(self, baselines=None, results=None, reviews=None, journal=None):
        baselines = [self.baseline()] if baselines is None else baselines
        results = [self.result()] if results is None else results
        reviews = [self.review(r) for r in results] if reviews is None else reviews
        journal = [self.journal(r) for r in results] if journal is None else journal
        self.policy['planned_products'] = len(baselines)
        self.csv(self.expected_path, evaluation.EXPECTED_FIELDS, baselines)
        self.policy_path.write_text(json.dumps(self.policy), encoding='utf-8')
        for result in results:
            expected = next((row for row in baselines if row['case_id'] == result['case_id']), None)
            result['expected_record_sha256'] = evaluation.record_hash(expected)
            result['policy_sha256'] = evaluation.file_hash(self.policy_path)
        for review in reviews:
            result = next((row for row in results if row['case_id'] == review['case_id']), None)
            if result is not None:
                review['result_record_sha256'] = evaluation.record_hash(result)
        for entry in journal:
            expected = next((row for row in baselines if row['case_id'] == entry['case_id']), None)
            if not entry['expected_record_sha256']:
                entry['expected_record_sha256'] = evaluation.record_hash(expected)
            if not entry['policy_sha256']:
                entry['policy_sha256'] = evaluation.file_hash(self.policy_path)
        self.jsonl(self.results_path, results)
        self.csv(self.reviews_path, evaluation.REVIEW_FIELDS, reviews)
        self.jsonl(self.journal_path, journal)

    def scored(self):
        policy = evaluation.load_policy(self.policy_path, self.now)
        expected = evaluation.load_expected(self.expected_path, policy, self.now)
        results = evaluation.load_results(self.results_path, expected, self.now,
                                           evaluation.file_hash(self.policy_path))
        reviews = evaluation.load_reviews(self.reviews_path, results, self.now)
        journal = evaluation.load_journal(self.journal_path, expected, results, self.now,
                                           evaluation.file_hash(self.policy_path))
        return evaluation.score(expected, results, reviews, journal, policy)

    def cli(self, *arguments):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = evaluation.main(list(arguments), now=self.now)
        return code, output.getvalue()

    def score_cli(self, suffix=''):
        return self.cli('score', '--expected', str(self.expected_path), '--policy', str(self.policy_path),
                        '--results', str(self.results_path), '--reviews', str(self.reviews_path),
                        '--journal', str(self.journal_path), '--output', str(self.root / f'scored{suffix}.csv'),
                        '--summary', str(self.root / f'summary{suffix}.json'))

    def test_template_prepares_50_cases_across_13_retailers_without_observations(self):
        evaluation.template(self.expected_path, self.policy_path, 50, 600, self.now)
        expected = evaluation.load_expected(self.expected_path,
                    evaluation.load_policy(self.policy_path, self.now), self.now)
        self.assertEqual(50, len(expected))
        self.assertEqual(set(evaluation.RETAILERS), {r['retailer'] for r in expected.values()})
        for row in expected.values():
            self.assertEqual('pending', row['verification_status'])
            self.assertFalse(row['expected_price'])
            self.assertFalse(row['product_url'])
            self.assertFalse(row['verifier_type'])

    def test_pending_dataset_validation_and_empty_results_make_no_accuracy_claim(self):
        evaluation.template(self.expected_path, self.policy_path, 50, 600, self.now)
        self.results_path.write_text('', encoding='utf-8')
        self.csv(self.reviews_path, evaluation.REVIEW_FIELDS, [])
        self.journal_path.write_text('', encoding='utf-8')
        code, text = self.cli('validate', '--expected', str(self.expected_path), '--policy', str(self.policy_path))
        self.assertEqual(0, code)
        self.assertIn('0/50', text)
        self.assertIn('incomplete', text)
        code, text = self.score_cli()
        self.assertEqual(0, code)
        summary = json.loads((self.root / 'summary.json').read_text())
        self.assertIsNone(summary['price_accuracy_percent'])
        self.assertIsNone(summary['end_to_end_accuracy_percent'])
        self.assertEqual(0, summary['accuracy_denominator'])
        self.assertEqual('not_started', summary['status'])
        self.assertNotIn('0.00%', text)

    def test_pending_candidate_urls_need_no_fabricated_expected_values(self):
        value = dict.fromkeys(evaluation.EXPECTED_FIELDS, '')
        value.update(case_id='PP-001', retailer='Croma', product_url='https://www.croma.com/synthetic/p/123',
                     verification_status='pending')
        self.inputs([value], [], [])
        rows, summary = self.scored()
        self.assertEqual(0, summary['independently_verified_products'])
        self.assertEqual('not_attempted', rows[0]['failure_reasons'])

    def test_decimal_formatting_only(self):
        for amount in ('24990', '24990.0', '24990.00', '24990.000', '024990.00'):
            with self.subTest(amount=amount):
                self.inputs(results=[self.result(returned_price=amount)])
                rows, summary = self.scored()
                self.assertTrue(rows[0]['price_correct'])
                self.assertEqual(100.0, summary['end_to_end_accuracy_percent'])

    def test_price_difference_is_not_rounded_or_tolerated(self):
        for amount in ('24989.99', '24990.001', '24990.009', '24990.01', '₹24,990', '-1', 'NaN', 'Infinity', '0', None):
            with self.subTest(amount=amount):
                self.inputs(results=[self.result(returned_price=amount)])
                rows, summary = self.scored()
                self.assertFalse(rows[0]['price_correct'])
                self.assertEqual(0.0, summary['end_to_end_accuracy_percent'])

    def test_same_price_wrong_variant_fails_end_to_end_but_not_price_only(self):
        result = self.result(returned_title='SYNTHETIC device 128GB red')
        self.inputs(results=[result], reviews=[self.review(result, 'false')])
        rows, summary = self.scored()
        self.assertTrue(rows[0]['price_correct'])
        self.assertFalse(rows[0]['identity_correct'])
        self.assertEqual(100.0, summary['price_accuracy_percent'])
        self.assertEqual(0.0, summary['end_to_end_accuracy_percent'])
        self.assertIn('wrong_product', rows[0]['failure_reasons'])

    def test_currency_accuracy_is_separate(self):
        self.inputs(results=[self.result(returned_currency='USD')])
        rows, summary = self.scored()
        self.assertTrue(rows[0]['price_correct'])
        self.assertTrue(rows[0]['identity_correct'])
        self.assertFalse(rows[0]['currency_correct'])
        self.assertEqual(0.0, summary['end_to_end_accuracy_percent'])

    def test_stock_accuracy_is_separate(self):
        self.inputs(results=[self.result(returned_stock=False)])
        rows, summary = self.scored()
        self.assertFalse(rows[0]['stock_correct'])
        self.assertTrue(rows[0]['end_to_end_correct'])
        self.assertEqual(0.0, summary['stock_accuracy_percent'])

    def test_unknown_or_missing_identity_cannot_pass(self):
        for verdict in ('unknown', None):
            with self.subTest(verdict=verdict):
                result = self.result()
                self.inputs(results=[result], reviews=[] if verdict is None else [self.review(result, verdict)])
                rows, summary = self.scored()
                self.assertFalse(rows[0]['identity_correct'])
                self.assertTrue(rows[0]['price_correct'])
                self.assertFalse(summary['benchmark_complete'])
                self.assertEqual(1, summary['unresolved_attempts'])

    def test_wrong_asin_fails_even_if_review_says_true(self):
        self.inputs(results=[self.result(final_url='https://www.amazon.in/dp/B999999999')])
        rows, summary = self.scored()
        self.assertTrue(rows[0]['price_correct'])
        self.assertFalse(rows[0]['identity_correct'])

    def test_title_missing_cannot_pass_identity_even_if_review_says_true(self):
        self.inputs(results=[self.result(returned_title=None)])
        rows, summary = self.scored()
        self.assertFalse(rows[0]['identity_correct'])

    def test_failures_stay_in_denominator(self):
        results = [self.result(), self.result(2, outcome='TIMEOUT', returned_price=None, observed_at=None),
                   self.result(3, outcome='NO_PRICE', returned_price=None, observed_at=None),
                   self.result(4, outcome='HTTP_ERROR', returned_price=None, observed_at=None)]
        self.inputs([self.baseline(i) for i in range(1, 5)], results, [self.review(results[0])])
        rows, summary = self.scored()
        self.assertEqual(4, summary['accuracy_denominator'])
        self.assertEqual(25.0, summary['price_accuracy_percent'])
        self.assertEqual(25.0, summary['end_to_end_accuracy_percent'])
        self.assertTrue(summary['benchmark_complete'])
        self.assertIn('invalid_output_or_identity', rows[2]['failure_reasons'])
        self.assertNotIn('wrong_product', rows[2]['failure_reasons'])

    def test_interrupted_attempt_stays_in_denominator_without_fabricated_result(self):
        result = self.result()
        interrupted = self.result(2)
        self.inputs([self.baseline(), self.baseline(2)], [result], journal=[self.journal(result), self.journal(interrupted)])
        rows, summary = self.scored()
        self.assertEqual(2, summary['accuracy_denominator'])
        self.assertEqual(1, summary['completed_results'])
        self.assertEqual(1, summary['interrupted_attempts'])
        self.assertEqual(50.0, summary['end_to_end_accuracy_percent'])
        self.assertEqual('INTERRUPTED', rows[1]['outcome'])
        self.assertFalse(summary['publishable_full_dataset_claim'])

    def test_partial_run_reports_actual_denominator_not_planned_50(self):
        baselines = [self.baseline(i) for i in range(1, 51)]
        self.inputs(baselines, [self.result()])
        rows, summary = self.scored()
        self.assertEqual(50, summary['planned_products'])
        self.assertEqual(1, summary['accuracy_denominator'])
        self.assertEqual(49, summary['not_attempted_products'])
        self.assertEqual('partial', summary['status'])
        self.assertFalse(summary['publishable_full_dataset_claim'])

    def test_stale_and_after_extraction_evidence_cannot_pass(self):
        for verified in (self.start - timedelta(seconds=601), self.start + timedelta(seconds=1)):
            self.inputs([self.baseline(verified_at_utc=evaluation.iso_time(verified))])
            rows, summary = self.scored()
            self.assertFalse(rows[0]['price_correct'])
            self.assertEqual(1, summary['accuracy_denominator'])
            self.assertIn('evidence_outside_predeclared_window', rows[0]['failure_reasons'])
            self.assertFalse(summary['benchmark_complete'])

    def test_future_evidence_rejected(self):
        self.inputs([self.baseline(verified_at_utc=evaluation.iso_time(self.now + timedelta(seconds=1)))])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'future_expected_observation'):
            self.scored()

    def test_nonhuman_or_missing_expected_observations_rejected(self):
        for changes in ({'verifier_type': 'agent'}, {'verifier_type': 'search'}, {'expected_price': ''},
                        {'expected_variant': ''}, {'expected_currency': 'USD'}, {'expected_stock': 'unknown'}):
            with self.subTest(changes=changes):
                self.inputs([self.baseline(**changes)])
                with self.assertRaises(evaluation.EvaluationError):
                    self.scored()

    def test_unverified_case_may_not_be_scored_as_live_attempt(self):
        self.inputs([self.baseline(verification_status='pending')])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'attempted_case_not_independently_verified'):
            self.scored()

    def test_evidence_hash_mismatch_rejected(self):
        self.inputs()
        self.evidence.write_text('Changed synthetic evidence after verification')
        with self.assertRaisesRegex(evaluation.EvaluationError, 'evidence_hash_mismatch'):
            self.scored()

    def test_evidence_outside_dataset_or_symlink_escape_rejected(self):
        for value in ('../evidence.txt', '/private/tmp/evidence.txt', '.env', 'evidence/../.env'):
            self.inputs([self.baseline(evidence_path=value)])
            with self.assertRaises(evaluation.EvaluationError):
                self.scored()
        outside = self.root / 'outside.txt'
        outside.write_text('not evidence')
        (self.root / 'evidence' / 'link.txt').symlink_to(outside)
        self.inputs([self.baseline(evidence_path='evidence/link.txt')])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'evidence_outside_dataset'):
            self.scored()

    def test_env_file_read_refused_without_opening_it(self):
        for name in ('.env', '.env.local'):
            with self.assertRaisesRegex(evaluation.EvaluationError, 'environment_file_refused'):
                evaluation.read_bytes(self.root / name)
        (self.root / 'disguised.txt').symlink_to(self.root / '.env')
        with self.assertRaisesRegex(evaluation.EvaluationError, 'environment_file_refused'):
            evaluation.read_bytes(self.root / 'disguised.txt')

    def test_snapdeal_mobile_alias_matches_only_known_retailer_host(self):
        self.assertEqual(evaluation.product_key('https://m.snapdeal.com/product/synthetic/123', 'Snapdeal'),
                         evaluation.product_key('https://www.snapdeal.com/product/synthetic/123', 'Snapdeal'))
        with self.assertRaises(evaluation.EvaluationError):
            evaluation.product_key('https://unknown.snapdeal.com/product/synthetic/123', 'Snapdeal')

    def test_long_workflow_may_age_expected_evidence_out_of_window(self):
        self.start = self.now - timedelta(minutes=11)
        result = self.result(completed_at=evaluation.iso_time(self.now - timedelta(seconds=5)),
                             observed_at=evaluation.iso_time(self.now - timedelta(seconds=10)))
        self.policy['created_at'] = evaluation.iso_time(self.start - timedelta(hours=1))
        self.inputs(results=[result], reviews=[self.review(result, reviewed_at_utc=evaluation.iso_time(self.now))])
        rows, summary = self.scored()
        self.assertFalse(rows[0]['price_correct'])
        self.assertIn('evidence_outside_predeclared_window', rows[0]['failure_reasons'])

    def test_missing_journal_cannot_publish_full_dataset_claim(self):
        self.inputs()
        code, _ = self.cli('score', '--expected', str(self.expected_path), '--policy', str(self.policy_path),
                            '--results', str(self.results_path), '--reviews', str(self.reviews_path),
                            '--output', str(self.root / 'scored.csv'), '--summary', str(self.root / 'summary.json'))
        self.assertEqual(0, code)
        summary = json.loads((self.root / 'summary.json').read_text())
        self.assertFalse(summary['benchmark_complete'])
        self.assertFalse(summary['publishable_full_dataset_claim'])
        self.assertEqual('partial', summary['status'])

    def test_duplicate_ids_urls_results_and_unknown_result_rejected(self):
        self.inputs([self.baseline(), self.baseline()], [])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'duplicate_case_id'):
            self.scored()
        self.inputs([self.baseline(), self.baseline(2, product_url=self.baseline()['product_url'] + '?tag=synthetic')], [])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'duplicate_product_url'):
            self.scored()
        self.inputs(results=[self.result(), self.result()])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'duplicate_result_case'):
            self.scored()
        self.inputs(results=[self.result(2)])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'unknown_or_duplicate_result_case'):
            self.scored()

    def test_missing_csv_columns_and_policy_count_rejected(self):
        self.inputs()
        fields = tuple(field for field in evaluation.EXPECTED_FIELDS if field != 'expected_price')
        self.csv(self.expected_path, fields, [{name: self.baseline()[name] for name in fields}])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'invalid_csv_headers'):
            self.scored()
        self.inputs()
        self.policy['planned_products'] = 50
        self.policy_path.write_text(json.dumps(self.policy))
        with self.assertRaisesRegex(evaluation.EvaluationError, 'dataset_count_differs_from_policy'):
            self.scored()

    def test_changed_result_invalidates_identity_review(self):
        self.inputs()
        result = evaluation.load_jsonl(self.results_path)[0]
        result['returned_title'] = 'SYNTHETIC changed variant'
        self.jsonl(self.results_path, [result])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'review_result_hash_mismatch'):
            self.scored()

    def test_changed_baseline_or_policy_after_submission_is_rejected(self):
        self.inputs()
        self.csv(self.expected_path, evaluation.EXPECTED_FIELDS, [self.baseline(expected_price='1.00')])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'expected_record_hash_mismatch'):
            self.scored()
        self.inputs()
        self.policy['max_observation_gap_seconds'] = 900
        self.policy_path.write_text(json.dumps(self.policy))
        with self.assertRaisesRegex(evaluation.EvaluationError, 'policy_hash_mismatch'):
            self.scored()

    def test_nonhuman_review_and_future_review_rejected(self):
        for changes in ({'reviewer_type': 'ai'}, {'reviewed_at_utc': evaluation.iso_time(self.now + timedelta(seconds=1))},
                        {'reviewed_at_utc': evaluation.iso_time(self.start)}, {'identity_reason': ''}):
            self.inputs(reviews=[self.review(self.result(), **changes)])
            with self.assertRaises(evaluation.EvaluationError):
                self.scored()

    def test_cache_sharing_fallback_and_other_provider_never_pass_fresh_benchmark(self):
        for changes in ({'reuse': 'CACHE'}, {'reuse': 'SHARED'}, {'fallback_used': True},
                        {'source': 'DIRECT'}, {'source': 'UNBLOCKER'}, {'observed_at': None},
                        {'observed_at': evaluation.iso_time(self.start - timedelta(seconds=1))}):
            with self.subTest(changes=changes):
                self.inputs(results=[self.result(**changes)])
                rows, summary = self.scored()
                self.assertFalse(rows[0]['end_to_end_correct'])
                self.assertFalse(rows[0]['price_correct'])
                self.assertEqual('not_applicable_browseract_only', summary['fallback_comparison'])

    def test_result_journal_mismatch_and_mixed_run_ids_rejected(self):
        self.inputs(journal=[self.journal(self.result(), submitted_url='https://www.amazon.in/dp/B999999999')])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'result_journal_mismatch'):
            self.scored()
        self.inputs([self.baseline(), self.baseline(2)], [self.result(), self.result(2, run_id='another-synthetic-run')])
        with self.assertRaisesRegex(evaluation.EvaluationError, 'mixed_run_ids'):
            self.scored()

    def test_record_hash_is_canonical_and_unicode_is_not_escaped(self):
        self.assertEqual(evaluation.record_hash({'b': 1, 'a': '₹'}),
                         hashlib.sha256('{"a":"₹","b":1}'.encode('utf-8')).hexdigest())
        self.assertEqual(evaluation.record_hash({'a': 2, 'b': 1}), evaluation.record_hash({'b': 1, 'a': 2}))

    def test_json_duplicate_keys_and_nonfinite_values_rejected(self):
        for raw in ('{"a":1,"a":2}', '{"a": NaN}', '{"a":Infinity}'):
            with self.assertRaises(evaluation.EvaluationError):
                evaluation.decode_json(raw)

    def test_reviews_command_never_generates_identity_verdicts(self):
        self.inputs()
        target = self.root / 'blank-reviews.csv'
        code, text = self.cli('reviews', '--expected', str(self.expected_path), '--policy', str(self.policy_path),
                             '--results', str(self.results_path), '--output', str(target))
        self.assertEqual(0, code)
        rows = evaluation.read_csv(target, evaluation.REVIEW_FIELDS)
        self.assertEqual(evaluation.record_hash(evaluation.load_jsonl(self.results_path)[0]), rows[0]['result_record_sha256'])
        self.assertFalse(rows[0]['identity_correct'])
        self.assertFalse(rows[0]['reviewer_type'])

    def test_export_escapes_spreadsheet_formulas_in_any_string_field(self):
        result = self.result(returned_title='=HYPERLINK("https://invalid.example", "synthetic")')
        self.inputs(results=[result])
        code, _ = self.score_cli()
        self.assertEqual(0, code)
        with (self.root / 'scored.csv').open(newline='') as source:
            row = next(csv.DictReader(source))
        self.assertTrue(row['returned_title'].startswith("'="))
        for value in ('=1+1', ' +1', '-10', '@SUM(1)', '\tplain', '\rplain', '\nplain'):
            self.assertTrue(evaluation.spreadsheet_safe(value).startswith("'"))
        self.assertEqual('24990.00', evaluation.spreadsheet_safe('24990.00'))

    def test_scoring_preserves_inputs_and_produces_repeatable_scores(self):
        self.inputs()
        paths = [self.expected_path, self.policy_path, self.results_path, self.reviews_path, self.journal_path, self.evidence]
        before = {path: path.read_bytes() for path in paths}
        self.assertEqual(0, self.score_cli('a')[0])
        self.assertEqual(0, self.score_cli('b')[0])
        self.assertEqual((self.root / 'scoreda.csv').read_bytes(), (self.root / 'scoredb.csv').read_bytes())
        self.assertEqual((self.root / 'summarya.json').read_bytes(), (self.root / 'summaryb.json').read_bytes())
        self.assertEqual(before, {path: path.read_bytes() for path in paths})
        summary = json.loads((self.root / 'summarya.json').read_text())
        self.assertEqual(hashlib.sha256(before[self.results_path]).hexdigest(), summary['input_sha256']['results'])

    def test_existing_output_never_overwritten(self):
        self.inputs()
        output = self.root / 'scored.csv'
        output.write_text('preserve this existing artifact')
        code, text = self.score_cli()
        self.assertEqual(1, code)
        self.assertEqual('preserve this existing artifact', output.read_text())

    def test_policy_cannot_allow_fallback_or_be_created_after_test(self):
        for changes in ({'fallback_supported': True}, {'price_tolerance': '1.00'},
                        {'created_at': evaluation.iso_time(self.start + timedelta(seconds=1))}):
            self.inputs()
            policy = dict(self.policy, **changes)
            self.policy_path.write_text(json.dumps(policy))
            with self.assertRaises(evaluation.EvaluationError):
                self.scored()

    def test_malformed_arguments_are_not_echoed(self):
        code, text = self.cli('template', '--output', 'not-printed', '--policy-output', 'not-printed',
                              '--count', 'potential-secret-value')
        self.assertEqual(1, code)
        self.assertNotIn('potential-secret-value', text)


if __name__ == '__main__':
    unittest.main()
