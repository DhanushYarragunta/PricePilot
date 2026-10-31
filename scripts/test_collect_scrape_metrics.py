from contextlib import redirect_stdout
from datetime import datetime, timezone
import http.client
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
import urllib.request


spec = importlib.util.spec_from_file_location('collector', Path(__file__).with_name('collect-scrape-metrics.py'))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class Response:
    def __init__(self, payload, status=200, read_error=None):
        self.body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.status = status
        self.read_error = read_error
        self.read_sizes = []
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *arguments):
        self.closed = True

    def read(self, size):
        self.read_sizes.append(size)
        if self.read_error:
            raise self.read_error
        return self.body[:size]


class Opener:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []
        self.timeouts = []

    def open(self, request, timeout):
        self.requests.append(request)
        self.timeouts.append(timeout)
        if not self.responses:
            raise AssertionError('Unexpected extra request')
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


def payload(value=0, since='2026-10-31T00:00:00.123456789Z'):
    return {
        'generatedAt': '2026-10-31T01:00:00Z',
        'private': 'PRIVATE_ROOT',
        'optimization': {
            **{field: value for field in collector.COUNTER_FIELDS + collector.GAUGE_FIELDS},
            'since': since, 'requestUnit': collector.REQUEST_UNIT,
            'apiKey': 'PRIVATE_KEY', 'url': 'https://private.example', 'price': 1234,
        },
    }


class CollectScrapeMetricsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.output = Path(self.directory.name) / 'metrics.jsonl'
        self.sleeps = []

    def tearDown(self):
        self.directory.cleanup()

    def run_collect(self, responses=(), arguments=()):
        opener = Opener(*responses)
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            result = collector.main(['--output', str(self.output), *arguments], opener=opener,
                                    now=lambda: datetime(2026, 10, 31, 1, 2, 3, tzinfo=timezone.utc),
                                    sleep=self.sleeps.append)
        return result, stdout.getvalue(), opener

    def saved(self):
        return [json.loads(line) for line in self.output.read_text().splitlines()]

    def test_default_two_samples_only_get_public_metrics_and_store_allowlist(self):
        first, second = Response(payload()), Response(payload(10))
        status, output, opener = self.run_collect([first, second])
        self.assertEqual(status, 0)
        self.assertEqual(self.sleeps, [60])
        self.assertEqual(opener.timeouts, [collector.HTTP_TIMEOUT_SECONDS] * 2)
        self.assertTrue(all(r.get_method() == 'GET' and r.data is None for r in opener.requests))
        self.assertTrue(all(r.full_url == collector.DEFAULT_URL for r in opener.requests))
        self.assertTrue(all(r.get_header('Authorization') is None for r in opener.requests))
        self.assertTrue(all(r.get_header('Cookie') is None for r in opener.requests))
        self.assertEqual(first.read_sizes, [collector.MAX_BODY_BYTES + 1])
        self.assertTrue(first.closed and second.closed)
        rows = self.saved()
        self.assertEqual(len(rows), 2)
        self.assertEqual(set(rows[0]), {'generatedAt', 'capturedAt', 'optimization'})
        self.assertEqual(set(rows[0]['optimization']), set(collector.COUNTER_FIELDS + collector.GAUGE_FIELDS)
                         | {'since', 'requestUnit'})
        self.assertEqual(rows[0]['optimization']['since'], '2026-10-31T00:00:00.123456789Z')
        self.assertEqual(rows[1]['optimization']['retailerRequests'], 10)
        self.assertEqual(rows[0]['capturedAt'], '2026-10-31T01:02:03.000000Z')
        for private in ('PRIVATE_ROOT', 'PRIVATE_KEY', 'private.example', '1234'):
            self.assertNotIn(private, output)
            # Timestamp precision intentionally contains 1234, so only check named private fields.
            if private != '1234': self.assertNotIn(private, self.output.read_text())

    def test_existing_file_is_preserved_without_any_request(self):
        self.output.write_text('prior measurement')
        status, output, opener = self.run_collect()
        self.assertEqual(status, 1)
        self.assertIn('output_already_exists', output)
        self.assertEqual(self.output.read_text(), 'prior measurement')
        self.assertEqual(opener.requests, [])

    def test_existing_symlink_is_not_followed_or_overwritten(self):
        target = Path(self.directory.name) / 'original'
        target.write_text('keep')
        self.output.symlink_to(target)
        status, output, opener = self.run_collect()
        self.assertEqual(status, 1)
        self.assertEqual(target.read_text(), 'keep')
        self.assertEqual(opener.requests, [])
        self.assertIn('output_already_exists', output)

    def test_backend_restart_retains_only_previous_valid_sample(self):
        status, output, opener = self.run_collect([
            Response(payload(10)), Response(payload(0, '2026-10-31T00:30:00Z'))])
        self.assertEqual(status, 1)
        self.assertIn('backend_restarted_start_a_new_collection', output)
        self.assertEqual(len(self.saved()), 1)
        self.assertEqual(len(opener.requests), 2)

    def test_any_cumulative_counter_regression_fails_but_gauges_may_decrease(self):
        for name in collector.COUNTER_FIELDS:
            with self.subTest(counter=name):
                self.output.unlink(missing_ok=True)
                lower = payload(10)
                lower['optimization'][name] = 9
                status, output, _ = self.run_collect([Response(payload(10)), Response(lower)])
                self.assertEqual(status, 1)
                self.assertIn('counter_regression', output)
                self.assertEqual(len(self.saved()), 1)
        self.output.unlink()
        lower = payload(10)
        for name in collector.GAUGE_FIELDS: lower['optimization'][name] = 0
        status, _, _ = self.run_collect([Response(payload(10)), Response(lower)])
        self.assertEqual(status, 0)
        self.assertEqual(len(self.saved()), 2)

    def test_no_rigid_cross_counter_equality_is_assumed_during_concurrent_updates(self):
        current = payload()
        current['optimization']['scheduledChecksCompleted'] = 5
        current['optimization']['scheduledChecksCompletedOnTime'] = 6
        current['optimization']['successfulRequests'] = 3
        status, _, _ = self.run_collect([Response(current)], ['--samples', '1'])
        self.assertEqual(status, 0)
        self.assertEqual(len(self.saved()), 1)

    def test_missing_negative_boolean_fractional_and_excessive_counters_are_rejected(self):
        for name in collector.COUNTER_FIELDS + collector.GAUGE_FIELDS:
            for value in (None, -1, True, False, 1.5, '1', [], collector.MAX_COUNTER + 1):
                with self.subTest(field=name, invalid_type=type(value).__name__):
                    self.output.unlink(missing_ok=True)
                    invalid = payload()
                    if value is None: invalid['optimization'].pop(name)
                    else: invalid['optimization'][name] = value
                    status, output, opener = self.run_collect([Response(invalid)], ['--samples', '1'])
                    self.assertEqual(status, 1)
                    self.assertIn('invalid_snapshot', output)
                    self.assertEqual(self.saved(), [])
                    self.assertEqual(len(opener.requests), 1)

    def test_invalid_timestamps_units_and_shapes_never_copy_provider_strings(self):
        cases = [None, [], {}, {'optimization': []}]
        for field in ('since', 'requestUnit'):
            invalid = payload(); invalid['optimization'][field] = 'PRIVATE_FIELD'
            cases.append(invalid)
        for value in ('2026-10-31', '2026-99-31T00:00:00Z', '2026-10-31T00:00:00', 'PRIVATE_TIME', None):
            invalid = payload(); invalid['generatedAt'] = value
            cases.append(invalid)
        for invalid in cases:
            self.output.unlink(missing_ok=True)
            status, output, _ = self.run_collect([Response(invalid)], ['--samples', '1'])
            self.assertEqual(status, 1)
            self.assertIn('invalid_snapshot', output)
            self.assertNotIn('PRIVATE_', output)
            self.assertEqual(self.saved(), [])

    def test_malformed_deep_duplicate_and_oversized_json_is_safe(self):
        bodies = (b'<html>PRIVATE_BODY</html>', b'{broken PRIVATE_BODY',
                  b'{"optimization":{},"optimization":{}}', b'[' * 2000 + b']' * 2000,
                  b'x' * (collector.MAX_BODY_BYTES + 1))
        for body in bodies:
            self.output.unlink(missing_ok=True)
            response = Response(body)
            status, output, _ = self.run_collect([response], ['--samples', '1'])
            self.assertEqual(status, 1)
            self.assertNotIn('PRIVATE_', output)
            self.assertEqual(self.saved(), [])
            self.assertTrue(response.closed)
            self.assertEqual(response.read_sizes, [collector.MAX_BODY_BYTES + 1])

    def test_redirects_errors_and_read_failures_are_never_retried_or_exposed(self):
        exceptions = (
            urllib.error.HTTPError('http://PRIVATE_HOST', 302, 'PRIVATE_REASON',
                                   {'Location': 'http://PRIVATE_DESTINATION'}, io.BytesIO(b'PRIVATE_BODY')),
            urllib.error.HTTPError('http://PRIVATE_HOST', 500, 'PRIVATE_REASON', {}, io.BytesIO(b'PRIVATE_BODY')),
            urllib.error.URLError('PRIVATE_NETWORK_ERROR'),
        )
        for error in exceptions:
            self.output.unlink(missing_ok=True)
            status, output, opener = self.run_collect([error], ['--samples', '1'])
            self.assertEqual(status, 1)
            self.assertEqual(len(opener.requests), 1)
            self.assertNotIn('PRIVATE_', output)
            self.assertEqual(self.saved(), [])
        for error in (OSError('PRIVATE_IO'), http.client.IncompleteRead(b'PRIVATE_PARTIAL')):
            self.output.unlink(missing_ok=True)
            response = Response(b'', read_error=error)
            status, output, _ = self.run_collect([response], ['--samples', '1'])
            self.assertEqual(status, 1)
            self.assertTrue(response.closed)
            self.assertNotIn('PRIVATE_', output)

    def test_redirect_handler_and_direct_opener_do_not_follow_other_endpoints(self):
        opener = collector.build_opener()
        redirects = [h for h in opener.handlers if isinstance(h, collector.NoRedirects)]
        self.assertEqual(len(redirects), 1)
        self.assertIsNone(redirects[0].redirect_request(None, None, 302, 'Found', {}, 'http://elsewhere.example'))
        proxies = [h for h in opener.handlers if isinstance(h, urllib.request.ProxyHandler)]
        self.assertTrue(all(not h.proxies for h in proxies))

    def test_arguments_and_endpoint_validation_fail_before_network_without_echoing_input(self):
        cases = (
            ['--samples', '0'], ['--samples', '-1'], ['--samples', 'PRIVATE_NOT_INT'],
            ['--interval-seconds', '0'], ['--interval-seconds', '-1'], ['--interval-seconds', 'nan'],
            ['--interval-seconds', 'inf'], ['--unknown', 'PRIVATE_OPTION'],
            ['--url', 'http://user:PRIVATE_KEY@localhost:8080/api/metrics'],
            ['--url', 'file:///PRIVATE_FILE'], ['--url', 'http://localhost:8080/api/products/1/refresh'],
            ['--url', 'https://example.org/api/metrics?key=PRIVATE_KEY'],
            ['--url', 'https://example.org/api/metrics#PRIVATE_FRAGMENT'],
            ['--url', 'http://localhost:bad/api/metrics'],
        )
        for arguments in cases:
            with self.subTest(kind=arguments[0]):
                status, output, opener = self.run_collect(arguments=arguments)
                self.assertEqual(status, 1)
                self.assertEqual(opener.requests, [])
                self.assertFalse(self.output.exists())
                self.assertNotIn('PRIVATE_', output)

    def test_custom_metrics_endpoint_and_positive_fractional_interval_are_supported(self):
        status, _, opener = self.run_collect([Response(payload()), Response(payload(1))],
                                             ['--url', 'https://example.org/api/metrics',
                                              '--interval-seconds', '0.25'])
        self.assertEqual(status, 0)
        self.assertEqual(self.sleeps, [0.25])
        self.assertEqual(opener.requests[0].full_url, 'https://example.org/api/metrics')

    def test_interruption_retains_written_sample_and_does_not_start_another_request(self):
        opener = Opener(Response(payload()))
        output = io.StringIO()

        def interrupted_sleep(seconds):
            raise KeyboardInterrupt()

        with redirect_stdout(output):
            status = collector.main(['--output', str(self.output)], opener=opener,
                                    now=lambda: datetime.now(timezone.utc), sleep=interrupted_sleep)
        self.assertEqual(status, 130)
        self.assertEqual(len(self.saved()), 1)
        self.assertEqual(len(opener.requests), 1)
        self.assertIn('Collection interrupted', output.getvalue())
        self.assertNotIn('Collection complete', output.getvalue())

    def test_unwritable_output_parent_is_reported_without_a_request_or_path(self):
        self.output = Path(self.directory.name) / 'PRIVATE_PARENT' / 'samples.jsonl'
        status, output, opener = self.run_collect()
        self.assertEqual(status, 1)
        self.assertEqual(opener.requests, [])
        self.assertIn('output_unavailable', output)
        self.assertNotIn('PRIVATE_PARENT', output)

    def test_capture_timestamp_requires_a_timezone(self):
        with self.assertRaisesRegex(collector.CollectionError, '^invalid_collection_clock$'):
            collector.safe_snapshot(payload(), datetime(2026, 10, 31, 0, 0, 0))


if __name__ == '__main__':
    unittest.main()
