"""Synthetic launcher tests: no .env reads, network calls, or external submissions."""

import csv
from contextlib import redirect_stderr
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import zipfile


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


launcher = load('live_evaluation_launcher_test', 'run-extraction-evaluation.py')
scoring = load('live_evaluation_scoring_test', 'evaluate-extraction.py')


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.expected = self.root / 'expected.csv'
        self.policy = self.root / 'policy.json'
        (self.root / 'evidence').mkdir()
        evidence = self.root / 'evidence' / 'case_001.txt'
        evidence.write_text('Synthetic unit-test evidence only. Not a real observed price.')
        now = datetime.now(timezone.utc)
        self.row = {field: '' for field in scoring.EXPECTED_FIELDS}
        self.row.update(case_id='case_001', retailer='Amazon.in', product_url='https://www.amazon.in/dp/B012345678',
                        expected_title='Synthetic exact model black 256 GB', expected_model='Synthetic model',
                        expected_variant='Black 256 GB', expected_price='1299.00', expected_currency='INR',
                        expected_stock='in_stock', verified_at_utc=(now - timedelta(seconds=60)).isoformat(),
                        evidence_path='evidence/case_001.txt', evidence_sha256=hashlib.sha256(evidence.read_bytes()).hexdigest(),
                        verifier='Synthetic fixture human marker', verifier_type='human', delivery_region='Test region',
                        offer_conditions='Synthetic unconditional selling price', verification_status='verified')
        self.policy.write_text(json.dumps({'schema_version': 1, 'planned_products': 1, 'price_tolerance': '0.00',
                                           'currency': 'INR', 'require_human_verifier': True, 'fallback_supported': False,
                                           'max_observation_gap_seconds': 600,
                                           'created_at': (now - timedelta(hours=1)).isoformat()}))
        self.write_rows([self.row])

    def write_rows(self, rows):
        with self.expected.open('w', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=scoring.EXPECTED_FIELDS)
            writer.writeheader()
            writer.writerows(rows)

    def test_manifest_contains_url_and_hashes_without_expected_answers(self):
        manifest, pending = launcher.case_manifest(self.expected, self.policy)
        self.assertEqual(pending, 0)
        self.assertEqual(manifest[0]['product_url'], self.row['product_url'])
        self.assertNotIn('expected_price', manifest[0])
        self.assertNotIn('expected_title', manifest[0])
        expected_hash = hashlib.sha256(json.dumps(self.row, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()).hexdigest()
        self.assertEqual(manifest[0]['expected_record_sha256'], expected_hash)
        self.assertEqual(manifest[0]['policy_sha256'], hashlib.sha256(self.policy.read_bytes()).hexdigest())
        self.assertEqual(manifest[0]['max_observation_gap_seconds'], 600)

    def test_pending_dataset_cannot_start_any_process(self):
        self.row['verification_status'] = 'pending'
        self.write_rows([self.row])
        diagnostic = io.StringIO()
        with patch.object(launcher.subprocess, 'run') as process, \
                patch.object(launcher, 'safe_environment') as environment, redirect_stderr(diagnostic):
            code = launcher.main(['--expected', str(self.expected), '--policy', str(self.policy),
                                  '--output', str(self.root / 'results.jsonl')])
        self.assertEqual(code, 2)
        process.assert_not_called()
        environment.assert_not_called()
        self.assertEqual(json.loads(diagnostic.getvalue())['safe_failure_category'], 'no_human_verified_cases')

    def test_invalid_reference_has_clear_safe_category_before_loading_environment(self):
        self.row['expected_price'] = 'sensitive-untrusted-input'
        self.write_rows([self.row])
        diagnostic = io.StringIO()
        with patch.object(launcher.subprocess, 'run') as process, \
                patch.object(launcher, 'safe_environment') as environment, redirect_stderr(diagnostic):
            code = launcher.main(['--expected', str(self.expected), '--policy', str(self.policy),
                                  '--output', str(self.root / 'results.jsonl')])
        self.assertEqual(code, 2)
        process.assert_not_called()
        environment.assert_not_called()
        self.assertEqual(json.loads(diagnostic.getvalue())['safe_failure_category'], 'invalid_reference_evidence')
        self.assertNotIn('sensitive-untrusted-input', diagnostic.getvalue())

    def test_agent_verification_is_not_treated_as_human_ground_truth(self):
        self.row['verifier_type'] = 'agent'
        self.write_rows([self.row])
        with self.assertRaises(ValueError):
            launcher.case_manifest(self.expected, self.policy)

    def test_modified_evidence_rejected(self):
        (self.root / 'evidence' / 'case_001.txt').write_text('Modified synthetic evidence')
        with self.assertRaises(ValueError):
            launcher.case_manifest(self.expected, self.policy)

    def test_unknown_explicit_case_is_rejected(self):
        with self.assertRaises(ValueError):
            launcher.case_manifest(self.expected, self.policy, ['absent'])

    def test_changed_expected_price_changes_binding(self):
        old, _ = launcher.case_manifest(self.expected, self.policy)
        self.row['expected_price'] = '1300.00'
        self.write_rows([self.row])
        new, _ = launcher.case_manifest(self.expected, self.policy)
        self.assertNotEqual(old[0]['expected_record_sha256'], new[0]['expected_record_sha256'])

    def test_selection_keeps_historical_baselines_in_manifest_for_locked_validation(self):
        second = dict(self.row, case_id='case_002', product_url='https://www.amazon.in/dp/B012345679')
        self.write_rows([self.row, second])
        policy = json.loads(self.policy.read_text())
        policy['planned_products'] = 2
        self.policy.write_text(json.dumps(policy))
        manifest, other = launcher.case_manifest(self.expected, self.policy, ['case_002'])
        self.assertEqual(len(manifest), 2)
        self.assertFalse(manifest[0]['selected'])
        self.assertTrue(manifest[1]['selected'])
        self.assertEqual(other, 1)

    def test_credential_like_url_query_is_rejected(self):
        self.row['product_url'] += '?api_key=fake-test-value'
        self.write_rows([self.row])
        with self.assertRaises(ValueError):
            launcher.case_manifest(self.expected, self.policy)

    def test_with_env_loader_is_reused_and_jvm_injection_removed(self):
        defaults = {'BROWSERACT_API_KEY': 'fake-test-only', 'JAVA_TOOL_OPTIONS': '-verbose',
                    'JDK_JAVA_OPTIONS': '-verbose', '_JAVA_OPTIONS': '-verbose', 'CLASSPATH': 'unsafe',
                    'PATH': '/safe/test/path'}
        module = types.SimpleNamespace(child_environment=Mock(return_value=defaults))
        spec = types.SimpleNamespace(loader=types.SimpleNamespace(exec_module=Mock()))
        with patch.object(launcher.importlib.util, 'spec_from_file_location', return_value=spec), \
                patch.object(launcher.importlib.util, 'module_from_spec', return_value=module):
            environment = launcher.safe_environment({'PATH': '/safe/test/path'})
        self.assertEqual(environment, {'BROWSERACT_API_KEY': 'fake-test-only', 'PATH': '/safe/test/path'})
        module.child_environment.assert_called_once_with(launcher.ROOT / '.env', {'PATH': '/safe/test/path'})

    def test_runtime_jar_extraction_only_allows_library_basenames(self):
        jar = self.root / 'fake.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('BOOT-INF/lib/example-1.0.jar', b'synthetic jar bytes')
            archive.writestr('BOOT-INF/lib/../../outside.jar', b'must not extract')
        path = launcher.runtime_classpath(jar, self.root)
        self.assertIn(str(self.root / 'example-1.0.jar'), path)
        self.assertNotIn('outside.jar', path)
        self.assertFalse((self.root.parent / 'outside.jar').exists())


if __name__ == '__main__':
    unittest.main()
