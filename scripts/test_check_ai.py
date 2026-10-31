from contextlib import redirect_stdout
import http.client
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request


SOURCE = Path(__file__).with_name('check-ai.py')
spec = importlib.util.spec_from_file_location('pricepilot_check_ai', SOURCE)
checker = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = checker
spec.loader.exec_module(checker)


class Response:
    def __init__(self, body, status=200, content_type='application/json'):
        self.status = status
        self.headers = {'Content-Type': content_type}
        self.body = body if isinstance(body, bytes) else json.dumps(body).encode()

    def __enter__(self):
        return self

    def __exit__(self, *arguments):
        return False

    def read(self, size):
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
            raise AssertionError('Unexpected HTTP request')
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


class AuthErrorBody(io.BytesIO):
    """Observe bounded error reads without retaining provider data in test output."""

    def __init__(self, body, fail_read=False):
        super().__init__(body)
        self.read_sizes = []
        self.fail_read = fail_read

    def read(self, size=-1):
        self.read_sizes.append(size)
        if self.fail_read:
            if isinstance(self.fail_read, BaseException):
                raise self.fail_read
            raise OSError('PRIVATE_READ_ERROR LOCAL_SECRET')
        return super().read(size)


class CheckAiTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.env = Path(self.directory.name) / '.env'
        self.env.write_text('AI_PROVIDER=openai-compatible\nAI_BASE_URL=https://gateway.example/v1\n'
                            'AI_MODEL=gpt-6.1-sol\nAI_API_KEY=LOCAL_SECRET\n')

    def tearDown(self):
        self.directory.cleanup()

    def run_check(self, arguments=(), inherited=None, opener=None):
        output = io.StringIO()
        with redirect_stdout(output):
            status = checker.main(['--env-file', str(self.env), *arguments],
                                  inherited={} if inherited is None else inherited, opener=opener)
        return status, output.getvalue()

    def test_offline_reports_export_mismatch_only_as_flags(self):
        opener = Opener()
        status, output = self.run_check(['--offline'], {'AI_API_KEY': 'EXPORTED_SECRET'}, opener)
        self.assertEqual(status, 0)
        self.assertIn('AI_API_KEY: file_present=true exported=true export_differs_from_file=true effective_present=true', output)
        self.assertNotIn('EXPORTED_SECRET', output)
        self.assertNotIn('LOCAL_SECRET', output)
        self.assertEqual(opener.requests, [])

    def test_default_only_lists_models_with_effective_bearer_key(self):
        opener = Opener(Response({'data': [{'id': 'gpt-6.1-sol'}]}))
        status, output = self.run_check(inherited={'AI_API_KEY': 'EXPORTED_SECRET', 'AGENTROUTER_API_KEY': 'UNRELATED_SECRET'}, opener=opener)
        self.assertEqual(status, 0)
        self.assertEqual(len(opener.requests), 1)
        self.assertEqual(opener.requests[0].get_method(), 'GET')
        self.assertEqual(opener.requests[0].full_url, 'https://gateway.example/v1/models')
        self.assertEqual(opener.requests[0].get_header('Authorization'), 'Bearer EXPORTED_SECRET')
        self.assertIn('Selected model listed: yes', output)
        for secret in ('LOCAL_SECRET', 'EXPORTED_SECRET', 'UNRELATED_SECRET'):
            self.assertNotIn(secret, output)

    def test_completion_is_opt_in_and_bounded_without_printing_reply(self):
        opener = Opener(Response({'data': []}), Response({'choices': [{'message': {'content': 'PRIVATE_PROVIDER_REPLY'}, 'finish_reason': 'stop'}]}))
        status, output = self.run_check(['--completion'], opener=opener)
        self.assertEqual(status, 0)
        self.assertEqual(len(opener.requests), 2)
        request = opener.requests[1]
        self.assertEqual(request.full_url, 'https://gateway.example/v1/chat/completions')
        body = json.loads(request.data)
        self.assertEqual(body['model'], 'gpt-6.1-sol')
        self.assertEqual(body['max_completion_tokens'], 4096)
        self.assertEqual(body['reasoning_effort'], 'low')
        self.assertEqual([message['role'] for message in body['messages']], ['system', 'user'])
        self.assertNotIn('temperature', body)
        self.assertIn('gateway may charge', output)
        self.assertIn('Completion completed: true', output)
        self.assertIn('Completion result: complete', output)
        self.assertNotIn('PRIVATE_PROVIDER_REPLY', output)

    def test_completion_only_checks_backend_endpoint_without_model_listing(self):
        opener = Opener(Response({'choices': [{'message': {'content': 'PRIVATE_REPLY'}, 'finish_reason': 'stop'}]}))
        status, output = self.run_check(['--completion-only'], opener=opener)
        self.assertEqual(status, 0)
        self.assertEqual(len(opener.requests), 1)
        request = opener.requests[0]
        self.assertEqual(request.get_method(), 'POST')
        self.assertEqual(request.full_url, 'https://gateway.example/v1/chat/completions')
        self.assertEqual(request.get_header('Authorization'), 'Bearer LOCAL_SECRET')
        self.assertEqual(json.loads(request.data)['model'], 'gpt-6.1-sol')
        self.assertEqual(json.loads(request.data)['max_completion_tokens'], 4096)
        self.assertEqual(opener.timeouts, [45])
        self.assertIn('Model list: not requested', output)
        self.assertIn('Completion result: complete', output)
        self.assertNotIn('PRIVATE_REPLY', output)
        self.assertNotIn('LOCAL_SECRET', output)

    def test_completion_only_auth_failure_never_retries_or_changes_credentials(self):
        error = urllib.error.HTTPError('https://gateway.example/v1/chat/completions', 401,
                                       'PRIVATE_REASON', {}, io.BytesIO(b'LOCAL_SECRET PRIVATE_BODY'))
        opener = Opener(error)
        status, output = self.run_check(['--completion-only'], opener=opener)
        self.assertEqual(status, 1)
        self.assertEqual(len(opener.requests), 1)
        self.assertIn('Completion: HTTP 401; category=authentication_rejected', output)
        self.assertIn('Completion result: request_failed', output)
        self.assertNotIn('PRIVATE_', output)
        self.assertNotIn('LOCAL_SECRET', output)

    def test_completion_only_still_respects_offline_mode(self):
        opener = Opener()
        status, output = self.run_check(['--offline', '--completion-only'], opener=opener)
        self.assertEqual(status, 0)
        self.assertEqual(opener.requests, [])
        self.assertIn('Offline check complete', output)

    def test_completion_respects_configured_token_limit_and_reasoning_effort(self):
        for maximum, expected in (('512', 512), ('1024', 1024), ('0', 4096), ('8192', 4096)):
            with self.subTest(maximum=maximum):
                opener = Opener(Response({'data': []}), Response({'choices': [{'message': {'content': 'OK'}, 'finish_reason': 'stop'}]}))
                status, _ = self.run_check(['--completion'], inherited={'AI_MAX_TOKENS': maximum,
                                          'AI_REASONING_EFFORT': ' high '}, opener=opener)
                self.assertEqual(status, 0)
                body = json.loads(opener.requests[1].data)
                self.assertEqual(body['max_completion_tokens'], expected)
                self.assertEqual(body['reasoning_effort'], 'high')
                self.assertEqual(body['model'], 'gpt-6.1-sol')

    def test_completion_uses_adapter_timeout_and_can_omit_reasoning_effort(self):
        opener = Opener(Response({'data': []}), Response({'choices': [{'message': {'content': 'OK'}, 'finish_reason': 'stop'}]}))
        status, _ = self.run_check(['--completion'], inherited={'AI_TIMEOUT_MS': '90000',
                                  'AI_REASONING_EFFORT': ' '}, opener=opener)
        self.assertEqual(status, 0)
        self.assertEqual(opener.timeouts, [15, 90])
        self.assertNotIn('reasoning_effort', json.loads(opener.requests[1].data))

    def test_java_trim_does_not_silently_remove_nonbreaking_spaces_from_key(self):
        self.assertEqual(checker.adapter_value(' \tKEY\r\n'), 'KEY')
        self.assertEqual(checker.adapter_value('\u2003'), '')
        self.assertEqual(checker.adapter_value('\u00a0KEY\u00a0'), '\u00a0KEY\u00a0')
        self.assertTrue(checker.java_whitespace('\u2003'))
        self.assertFalse(checker.java_whitespace('\u00a0'))

    def test_truncated_refused_or_empty_completion_does_not_pass(self):
        cases = (
            ({'message': {'content': 'PRIVATE_PARTIAL'}, 'finish_reason': 'length'}, 'output_limit_reached'),
            ({'message': {'content': ''}, 'finish_reason': 'length'}, 'output_limit_reached'),
            ({'message': {'content': ''}, 'finish_reason': 'stop'}, 'no_answer'),
            ({'message': {'content': 'PRIVATE_REPLY', 'refusal': 'PRIVATE_REFUSAL'}, 'finish_reason': 'stop'}, 'provider_refusal'),
            ({'message': {'content': 'PRIVATE_REPLY'}}, 'finish_not_confirmed'),
        )
        for choice, category in cases:
            with self.subTest(category=category):
                opener = Opener(Response({'data': [{'id': 'gpt-6.1-sol'}]}), Response({'choices': [choice]}))
                status, output = self.run_check(['--completion'], opener=opener)
                self.assertEqual(status, 1)
                self.assertIn('Selected model listed: yes', output)
                self.assertIn('Completion completed: false', output)
                self.assertIn('Completion result: ' + category, output)
                self.assertNotIn('PRIVATE_', output)
                self.assertEqual(len(opener.requests), 2)

    def test_completion_auth_failure_after_models_success_is_distinct_and_never_retried(self):
        for code in (401, 403):
            with self.subTest(code=code):
                error = urllib.error.HTTPError('https://gateway.example/v1/chat/completions', code,
                                               'PRIVATE_REASON', {}, io.BytesIO(b'LOCAL_SECRET PRIVATE_BODY'))
                opener = Opener(Response({'data': [{'id': 'gpt-6.1-sol'}]}), error)
                status, output = self.run_check(['--completion'], opener=opener)
                self.assertEqual(status, 1)
                self.assertIn('Model list: HTTP 200; category=ok', output)
                self.assertIn('Completion: HTTP ' + str(code), output)
                self.assertIn('Completion result: request_failed', output)
                self.assertEqual(len(opener.requests), 2)
                self.assertNotIn('PRIVATE_', output)
                self.assertNotIn('LOCAL_SECRET', output)

    def test_surrounding_configuration_whitespace_matches_adapter_trimming(self):
        opener = Opener(Response({'data': [{'id': 'gpt-6.1-sol'}]}))
        status, output = self.run_check(inherited={'AI_API_KEY': ' \tEXPORTED_SECRET\r\n',
                                      'AI_MODEL': ' gpt-6.1-sol ',
                                      'AI_BASE_URL': ' https://gateway.example/ '}, opener=opener)
        self.assertEqual(status, 0)
        self.assertEqual(opener.requests[0].get_header('Authorization'), 'Bearer EXPORTED_SECRET')
        self.assertEqual(opener.requests[0].full_url, 'https://gateway.example/v1/models')
        self.assertIn('Selected model listed: yes', output)
        self.assertNotIn('EXPORTED_SECRET', output)

    def test_configuration_validation_matches_adapter_limits_and_provider(self):
        for overrides in ({'AI_MAX_TOKENS': '-1'}, {'AI_MAX_TOKENS': 'not-a-number'},
                          {'AI_TIMEOUT_MS': '0'}, {'AI_TIMEOUT_MS': '-1'},
                          {'AI_PROVIDER': ' openai-compatible '}, {'AI_API_KEY': 'KEY WITH SPACE'}):
            with self.subTest(overrides=overrides):
                opener = Opener()
                status, output = self.run_check(inherited=overrides, opener=opener)
                self.assertEqual(status, 2)
                self.assertEqual(opener.requests, [])
                self.assertNotIn('KEY WITH SPACE', output)

    def test_auth_failure_never_prints_error_body_or_attempts_completion(self):
        error = urllib.error.HTTPError('https://gateway.example/v1/models', 401, 'PRIVATE_REASON', {},
                                       io.BytesIO(b'{"error":{"message":"LOCAL_SECRET PRIVATE_BODY"}}'))
        opener = Opener(error)
        status, output = self.run_check(['--completion'], opener=opener)
        self.assertEqual(status, 1)
        self.assertEqual(len(opener.requests), 1)
        self.assertIn('HTTP 401; category=authentication_rejected', output)
        for text in ('PRIVATE_REASON', 'PRIVATE_BODY', 'LOCAL_SECRET'):
            self.assertNotIn(text, output)

    def test_redirect_handler_blocks_redirects_and_probe_does_not_follow_them(self):
        opener = checker.build_opener()
        redirects = [handler for handler in opener.handlers if isinstance(handler, checker.NoRedirects)]
        self.assertEqual(len(redirects), 1)
        request = urllib.request.Request('https://gateway.example/v1/models', headers={'Authorization': 'Bearer LOCAL_SECRET'})
        self.assertIsNone(redirects[0].redirect_request(request, None, 302, 'Found', {}, 'https://other.example'))
        fake = Opener(urllib.error.HTTPError(request.full_url, 302, 'Found', {'Location': 'https://other.example'}, io.BytesIO(b'PRIVATE_HTML')))
        status, output = self.run_check(['--completion'], opener=fake)
        self.assertEqual(status, 1)
        self.assertEqual(len(fake.requests), 1)
        self.assertIn('redirect_blocked', output)
        self.assertNotIn('other.example', output)
        self.assertNotIn('PRIVATE_HTML', output)

    def test_invalid_gateway_urls_make_no_http_request(self):
        for url in ('http://gateway.example', 'https://user:secret@gateway.example',
                    'https://gateway.example?key=SECRET', 'https://gateway.example/#SECRET',
                    'https://gateway.example:bad', 'https://gateway.example?', 'https://gateway.example#'):
            with self.subTest(url=url):
                opener = Opener()
                status, output = self.run_check(inherited={'AI_BASE_URL': url}, opener=opener)
                self.assertEqual(status, 2)
                self.assertIn('invalid_gateway_url', output)
                self.assertNotIn(url, output)
                self.assertEqual(opener.requests, [])

    def test_html_or_invalid_models_response_never_reaches_completion(self):
        for response, category in ((Response(b'<html>LOCAL_SECRET</html>', content_type='text/html'), 'html_gateway_response'),
                                   (Response({'unexpected': 'LOCAL_SECRET'}), 'invalid_models_response')):
            opener = Opener(response)
            status, output = self.run_check(['--completion'], opener=opener)
            self.assertEqual(status, 1)
            self.assertIn(category, output)
            self.assertNotIn('LOCAL_SECRET', output)
            self.assertEqual(len(opener.requests), 1)

    def test_unrelated_gateway_key_is_not_used_as_fallback(self):
        opener = Opener()
        status, output = self.run_check(inherited={'AI_API_KEY': '', 'AGENTROUTER_API_KEY': 'UNRELATED_SECRET'}, opener=opener)
        self.assertEqual(status, 2)
        self.assertIn('api_key_missing_or_invalid_whitespace', output)
        self.assertEqual(opener.requests, [])
        self.assertNotIn('UNRELATED_SECRET', output)

    def test_permission_and_quota_errors_have_fixed_hints(self):
        for code, category in ((403, 'access_denied'), (429, 'rate_or_quota_limited')):
            opener = Opener(urllib.error.HTTPError('https://gateway.example/v1/models', code, 'SECRET_REASON', {}, io.BytesIO(b'SECRET_BODY')))
            status, output = self.run_check(opener=opener)
            self.assertEqual(status, 1)
            self.assertIn(category, output)
            self.assertNotIn('SECRET', output)

    def auth_error(self, payload, status=401, fail_read=False):
        raw = payload if isinstance(payload, bytes) else json.dumps(payload).encode('utf-8')
        body = AuthErrorBody(raw, fail_read=fail_read)
        error = urllib.error.HTTPError(
            'https://gateway.example/v1/chat/completions', status, 'PRIVATE_HTTP_REASON',
            {'Content-Type': 'application/json', 'X-Request-Id': 'PRIVATE_REQUEST_ID',
             'WWW-Authenticate': 'Bearer realm="LOCAL_SECRET"',
             'Location': 'https://PRIVATE_REDIRECT.example/LOCAL_SECRET'}, body)
        return error, body

    def assert_safe_auth_output(self, output):
        for value in ('LOCAL_SECRET', 'PRIVATE_', 'EXPORTED_SECRET', 'UNRELATED_SECRET'):
            self.assertNotIn(value, output)

    def test_auth_details_maps_only_known_codes_to_fixed_labels(self):
        reasons = {
            'unauthorized_client_error': 'client_not_authorized',
            'invalid_api_key': 'token_rejected',
            'invalid_token': 'token_rejected',
            'token_expired': 'token_expired',
            'token_revoked': 'token_revoked',
            'token_disabled': 'token_disabled',
            'permission_denied': 'permission_denied',
            'insufficient_permissions': 'permission_denied',
            'model_not_found': 'model_unavailable_or_not_permitted',
            'model_access_denied': 'model_access_denied',
            'unsupported_endpoint': 'endpoint_not_supported',
            'ip_not_allowed': 'ip_restricted',
        }
        for code, reason in reasons.items():
            for nested in (False, True):
                for field in ('code', 'type'):
                    with self.subTest(code=code, nested=nested, field=field):
                        detail = {field: code, 'message': 'PRIVATE_MESSAGE LOCAL_SECRET',
                                  'request_id': 'PRIVATE_JSON_REQUEST_ID'}
                        payload = {'error': detail} if nested else detail
                        error, body = self.auth_error(payload)
                        opener = Opener(error)
                        with patch.object(checker, 'report_probe', wraps=checker.report_probe) as report:
                            status, output = self.run_check(['--completion-only', '--auth-details'], opener=opener)
                        self.assertEqual(status, 1)
                        self.assertIn('Provider-reported auth reason: ' + reason, output)
                        self.assertEqual(len(opener.requests), 1)
                        self.assertEqual(body.read_sizes, [checker.MAX_AUTH_ERROR_BYTES + 1])
                        self.assertTrue(body.closed)
                        probe = report.call_args.args[1]
                        self.assertEqual(probe.auth_reason, reason)
                        self.assertIsNone(probe.payload)
                        self.assert_safe_auth_output(output)

    def test_auth_details_recognizes_only_exact_documented_message(self):
        cases = (
            ({'error': {'message': '无效的令牌', 'type': 'new_api_error'}}, 'token_rejected'),
            ({'error': {'message': '无效的令牌 LOCAL_SECRET'}}, 'unclassified'),
            ({'error': {'message': 'invalid API key LOCAL_SECRET'}}, 'unclassified'),
            ({'error': {'type': 'new_api_error'}}, 'unclassified'),
            ({'error': {'code': 'PRIVATE_CODE', 'type': 'PRIVATE_TYPE', 'message': 'LOCAL_SECRET'}}, 'unclassified'),
        )
        for payload, reason in cases:
            with self.subTest(reason=reason):
                error, body = self.auth_error(payload)
                status, output = self.run_check(['--completion-only', '--auth-details'], opener=Opener(error))
                self.assertEqual(status, 1)
                self.assertIn('Provider-reported auth reason: ' + reason, output)
                self.assertTrue(body.closed)
                self.assert_safe_auth_output(output)

    def test_auth_details_rejects_conflicting_reasons(self):
        cases = (
            {'error': {'code': 'invalid_token', 'type': 'token_expired'}},
            {'code': 'invalid_api_key', 'error': {'code': 'ip_not_allowed'}},
            {'error': {'code': 'model_access_denied', 'message': '无效的令牌'}},
        )
        for payload in cases:
            with self.subTest(payload=payload):
                error, body = self.auth_error(payload)
                status, output = self.run_check(['--completion-only', '--auth-details'], opener=Opener(error))
                self.assertEqual(status, 1)
                self.assertIn('Provider-reported auth reason: unclassified', output)
                self.assertTrue(body.closed)

    def test_auth_details_malformed_oversized_deep_or_nonstring_errors_stay_private(self):
        cases = (
            b'<html>PRIVATE_HTML LOCAL_SECRET</html>',
            b'{"error": "LOCAL_SECRET", broken',
            b'\xffLOCAL_SECRET',
            b'x' * (checker.MAX_AUTH_ERROR_BYTES + 1),
            b'{"error":' + b'[' * 1500 + b'"LOCAL_SECRET"' + b']' * 1500 + b'}',
            [],
            {'error': ['invalid_api_key', 'LOCAL_SECRET']},
            {'error': {'code': ['invalid_api_key'], 'type': {'LOCAL_SECRET': True}}},
            {'error': {'code': 401, 'type': False, 'message': {'text': '无效的令牌'}}},
            {'error': {'inner': {'code': 'invalid_api_key'}}},
        )
        for index, payload in enumerate(cases):
            with self.subTest(case=index):
                error, body = self.auth_error(payload)
                status, output = self.run_check(['--completion-only', '--auth-details'], opener=Opener(error))
                self.assertEqual(status, 1)
                self.assertIn('Provider-reported auth reason: unclassified', output)
                self.assertEqual(body.read_sizes, [checker.MAX_AUTH_ERROR_BYTES + 1])
                self.assertTrue(body.closed)
                self.assert_safe_auth_output(output)

    def test_auth_details_read_failure_is_safe_and_closes_response(self):
        for failure in (OSError('PRIVATE_READ_ERROR LOCAL_SECRET'),
                        http.client.IncompleteRead(b'LOCAL_SECRET', 10),
                        http.client.HTTPException('PRIVATE_HTTP_ERROR LOCAL_SECRET')):
            with self.subTest(failure_type=type(failure).__name__):
                error, body = self.auth_error(b'LOCAL_SECRET', fail_read=failure)
                status, output = self.run_check(['--completion-only', '--auth-details'], opener=Opener(error))
                self.assertEqual(status, 1)
                self.assertIn('Provider-reported auth reason: unclassified', output)
                self.assertEqual(body.read_sizes, [checker.MAX_AUTH_ERROR_BYTES + 1])
                self.assertTrue(body.closed)
                self.assert_safe_auth_output(output)

    def test_error_bodies_are_not_read_by_default(self):
        error, body = self.auth_error({'error': {'code': 'invalid_api_key'}}, fail_read=True)
        status, output = self.run_check(['--completion-only'], opener=Opener(error))
        self.assertEqual(status, 1)
        self.assertEqual(body.read_sizes, [])
        self.assertTrue(body.closed)
        self.assertNotIn('Provider-reported auth reason:', output)

    def test_auth_details_never_reads_non_auth_errors_or_follows_redirects(self):
        for code in (302, 400, 404, 429, 500):
            with self.subTest(code=code):
                error, body = self.auth_error({'error': {'code': 'invalid_api_key'}}, status=code, fail_read=True)
                opener = Opener(error)
                status, output = self.run_check(['--completion-only', '--auth-details'], opener=opener)
                self.assertEqual(status, 1)
                self.assertEqual(body.read_sizes, [])
                self.assertTrue(body.closed)
                self.assertEqual(len(opener.requests), 1)
                self.assertNotIn('Provider-reported auth reason:', output)
                self.assert_safe_auth_output(output)

    def test_auth_details_preserves_completion_request_and_offline_behavior(self):
        error, body = self.auth_error({'error': {'code': 'model_access_denied'}}, status=403)
        opener = Opener(error)
        status, output = self.run_check(['--completion-only', '--auth-details'], opener=opener)
        self.assertEqual(status, 1)
        self.assertEqual(len(opener.requests), 1)
        request = opener.requests[0]
        self.assertEqual(request.full_url, 'https://gateway.example/v1/chat/completions')
        self.assertEqual(request.get_method(), 'POST')
        self.assertEqual(request.get_header('Authorization'), 'Bearer LOCAL_SECRET')
        self.assertEqual(request.get_header('Content-type'), 'application/json')
        self.assertEqual(json.loads(request.data)['model'], 'gpt-6.1-sol')
        self.assertEqual(json.loads(request.data)['max_completion_tokens'], 4096)
        self.assertEqual(opener.timeouts, [45])
        self.assertIn('Provider-reported auth reason: model_access_denied', output)
        self.assertTrue(body.closed)
        offline = Opener()
        status, output = self.run_check(['--offline', '--completion-only', '--auth-details'], opener=offline)
        self.assertEqual(status, 0)
        self.assertEqual(offline.requests, [])
        self.assertNotIn('Provider-reported auth reason:', output)

    def test_auth_details_supports_model_listing_without_extra_calls(self):
        for arguments, responses, expected_calls in (
                (['--auth-details'], [], 1),
                (['--completion', '--auth-details'], [Response({'data': [{'id': 'gpt-6.1-sol'}]})], 2)):
            with self.subTest(arguments=arguments):
                error, body = self.auth_error({'error': {'code': 'permission_denied'}}, status=403)
                opener = Opener(*responses, error)
                status, output = self.run_check(arguments, opener=opener)
                self.assertEqual(status, 1)
                self.assertEqual(len(opener.requests), expected_calls)
                self.assertEqual(opener.requests[0].full_url, 'https://gateway.example/v1/models')
                self.assertIn('Provider-reported auth reason: permission_denied', output)
                self.assertTrue(body.closed)
                self.assert_safe_auth_output(output)


if __name__ == '__main__':
    unittest.main()
