#!/usr/bin/env python3
"""Check the configured AI gateway without displaying credentials or provider responses."""

import argparse
from dataclasses import dataclass
import http.client
import importlib.util
import json
import os
from pathlib import Path
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request


_spec = importlib.util.spec_from_file_location('pricepilot_dotenv', Path(__file__).with_name('with-env.py'))
_dotenv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_dotenv)
FIELDS = ('AI_PROVIDER', 'AI_BASE_URL', 'AI_MODEL', 'AI_API_KEY',
          'AI_REASONING_EFFORT', 'AI_MAX_TOKENS', 'AI_TIMEOUT_MS')
MAX_BODY_BYTES = 1_048_576
MAX_AUTH_ERROR_BYTES = 16_384
TIMEOUT_SECONDS = 15
MAX_PROBE_COMPLETION_TOKENS = 4096
JAVA_TRIM_CHARACTERS = ''.join(chr(value) for value in range(33))
AUTH_REASON_CODES = {
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


class NoRedirects(urllib.request.HTTPRedirectHandler):
    """Never forward the configured bearer credential to a redirected request."""

    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def build_opener():
    return urllib.request.build_opener(NoRedirects())


def java_whitespace(character):
    """Match Character.isWhitespace, used by AppProperties.Ai validation."""
    return (character in '\t\n\v\f\r\x1c\x1d\x1e\x1f'
            or unicodedata.category(character) in ('Zs', 'Zl', 'Zp')
            and character not in '\u00a0\u2007\u202f')


def adapter_value(value):
    """Match AppProperties.Ai valueOr(value, "") rather than Python's broader strip."""
    if not value or all(java_whitespace(character) for character in value):
        return ''
    return value.strip(JAVA_TRIM_CHARACTERS)


def runtime_limits(config):
    maximum = int(config.get('AI_MAX_TOKENS', '0').strip())
    timeout_ms = int(config.get('AI_TIMEOUT_MS', '45000').strip())
    maximum = 4096 if maximum == 0 else maximum
    if maximum <= 0 or timeout_ms <= 0:
        raise ValueError('invalid_runtime_limits')
    return maximum, timeout_ms


def endpoint(base, resource):
    base = adapter_value(base).rstrip('/')
    try:
        url = urllib.parse.urlsplit(base)
        # Evaluating port also rejects malformed ports without printing the URL.
        url.port
        if (url.scheme != 'https' or not url.hostname or url.username is not None
                or url.password is not None or '?' in base or '#' in base
                or any(character.isspace() for character in url.netloc)):
            raise ValueError('invalid_gateway_url')
    except (ValueError, TypeError):
        raise ValueError('invalid_gateway_url') from None
    return base + ('/' if base.endswith('/v1') else '/v1/') + resource


def flag(value):
    return 'true' if value else 'false'


def report_configuration(local, inherited, effective):
    for key in FIELDS:
        print(f'{key}: file_present={flag(bool(local.get(key)))} '
              f'exported={flag(key in inherited)} '
              f'export_differs_from_file={flag(key in inherited and inherited[key] != local.get(key))} '
              f'effective_present={flag(bool(effective.get(key)))}')


def configuration_valid(config):
    if config.get('AI_PROVIDER', '').lower() != 'openai-compatible':
        print('Configuration: compatible_provider_required')
        return False
    key = adapter_value(config.get('AI_API_KEY', ''))
    if not key or any(java_whitespace(character) for character in key):
        print('Configuration: api_key_missing_or_invalid_whitespace')
        return False
    if not adapter_value(config.get('AI_MODEL', '')):
        print('Configuration: model_missing')
        return False
    try:
        endpoint(config.get('AI_BASE_URL', ''), 'models')
    except ValueError:
        print('Configuration: invalid_gateway_url; use the configured HTTPS base without URL credentials, query or fragment')
        return False
    try:
        runtime_limits(config)
    except (ValueError, TypeError):
        print('Configuration: invalid_runtime_limits; AI_MAX_TOKENS must be zero or positive and AI_TIMEOUT_MS must be positive')
        return False
    print('Configuration: valid_local_shape')
    return True


def status_category(status):
    if 300 <= status < 400:
        return 'redirect_blocked'
    return {401: 'authentication_rejected', 403: 'access_denied', 404: 'endpoint_not_found',
            429: 'rate_or_quota_limited'}.get(status, 'provider_error' if status >= 500 else 'http_error')


@dataclass
class Probe:
    status: int | None
    category: str
    payload: dict | None = None
    auth_reason: str | None = None


def auth_failure_reason(raw):
    """Map explicit provider error identifiers to local labels; never return raw text."""
    if not isinstance(raw, bytes) or len(raw) > MAX_AUTH_ERROR_BYTES:
        return 'unclassified'
    try:
        body = json.loads(raw)
    except (ValueError, UnicodeError, RecursionError):
        return 'unclassified'
    if not isinstance(body, dict):
        return 'unclassified'
    error = body.get('error')
    containers = [body, error] if isinstance(error, dict) else [body]
    reasons = set()
    for container in containers:
        for field in ('code', 'type'):
            value = container.get(field)
            if isinstance(value, str) and value in AUTH_REASON_CODES:
                reasons.add(AUTH_REASON_CODES[value])
    # This exact New API error is documented in AgentRouter integration reports.
    # Generic new_api_error and arbitrary message fragments establish no cause.
    if isinstance(error, dict) and error.get('message') == '\u65e0\u6548\u7684\u4ee4\u724c':
        reasons.add('token_rejected')
    return next(iter(reasons)) if len(reasons) == 1 else 'unclassified'


def decode_response(status, headers, raw, auth_details=False):
    if status != 200:
        reason = auth_failure_reason(raw) if auth_details and status in (401, 403) else None
        return Probe(status, status_category(status), auth_reason=reason)
    if len(raw) > MAX_BODY_BYTES:
        return Probe(status, 'response_too_large')
    content_type = headers.get('Content-Type', '').lower()
    if 'text/html' in content_type or raw.lstrip().startswith(b'<'):
        return Probe(status, 'html_gateway_response')
    try:
        payload = json.loads(raw)
    except (ValueError, UnicodeError):
        return Probe(status, 'invalid_json_response')
    if not isinstance(payload, dict):
        return Probe(status, 'invalid_json_response')
    return Probe(status, 'ok', payload)


def fetch(opener, request, timeout=TIMEOUT_SECONDS, auth_details=False):
    try:
        with opener.open(request, timeout=timeout) as response:
            maximum = MAX_AUTH_ERROR_BYTES if response.status in (401, 403) else MAX_BODY_BYTES
            return decode_response(response.status, response.headers, response.read(maximum + 1), auth_details)
    except urllib.error.HTTPError as error:
        # Opt-in classification retains no raw error payload and follows no Location.
        try:
            reason = None
            if auth_details and error.code in (401, 403):
                try:
                    reason = auth_failure_reason(error.read(MAX_AUTH_ERROR_BYTES + 1))
                except (OSError, ValueError, UnicodeError, http.client.HTTPException):
                    reason = 'unclassified'
            return Probe(error.code, status_category(error.code), auth_reason=reason)
        finally:
            error.close()
    except (urllib.error.URLError, OSError):
        return Probe(None, 'network_or_tls_error')
    except (ValueError, UnicodeError):
        return Probe(None, 'request_format_error')


def report_probe(label, probe):
    print(f'{label}: HTTP {probe.status if probe.status is not None else "unavailable"}; category={probe.category}')
    if probe.auth_reason is not None:
        print('Provider-reported auth reason: ' + probe.auth_reason)
    if probe.auth_reason == 'client_not_authorized':
        print('Action: ask the provider whether this API token permits a custom server application '
              'on the documented endpoint. Active token status alone does not establish client access.')
    elif probe.auth_reason == 'token_rejected':
        print('Action: confirm AI_API_KEY is a model API token from the issuing account\'s API Tokens page, '
              'and matches its API host. A personal or system management token may be a different credential type.')
    elif probe.auth_reason == 'permission_denied':
        print('Action: check this API token\'s permissions for the requested endpoint and model in the provider dashboard.')
    elif probe.auth_reason in ('model_access_denied', 'model_unavailable_or_not_permitted'):
        print('Action: verify the configured model is available to this token on the documented endpoint.')
    elif probe.auth_reason == 'endpoint_not_supported':
        print('Action: compare the endpoint and protocol with the API example from the account that issued this token.')
    elif probe.auth_reason == 'ip_restricted':
        print('Action: check the API token\'s IP restrictions in the provider dashboard.')
    elif probe.status == 401:
        print('Action: verify that the effective AI_API_KEY is active for the configured AI_BASE_URL. '
              'Check exported overrides, update the server environment if needed, then restart the backend.')
    elif probe.status == 403:
        print('Action: check the configured gateway account and token permissions, including access to AI_MODEL.')
    elif probe.status == 429:
        print('Action: check the gateway quota and rate limits before retrying.')
    elif probe.category == 'redirect_blocked':
        print('Action: verify the endpoint in your gateway account. No redirect or alternate host was used.')
    elif probe.category == 'html_gateway_response':
        print('Action: the gateway returned HTML rather than API JSON; verify its API endpoint and access requirements.')


def check(config, opener, completion=False, completion_only=False, auth_details=False):
    headers = {'Authorization': 'Bearer ' + adapter_value(config['AI_API_KEY']), 'Accept': 'application/json'}
    if completion_only:
        print('Model list: not requested; checking the same completion endpoint used by the backend.')
        return check_completion(config, opener, headers, auth_details)
    models_request = urllib.request.Request(endpoint(config['AI_BASE_URL'], 'models'), headers=headers)
    models = fetch(opener, models_request, auth_details=auth_details)
    if models.category == 'ok' and not isinstance(models.payload.get('data'), list):
        models = Probe(models.status, 'invalid_models_response')
    report_probe('Model list', models)
    if models.category != 'ok':
        if completion:
            print('Completion: skipped because the model-list check did not succeed.')
        return 1
    model = adapter_value(config['AI_MODEL'])
    listed = any(isinstance(item, dict) and item.get('id') == model for item in models.payload['data'])
    print('Selected model listed: ' + ('yes' if listed else 'no'))
    if not completion:
        print('Completion: not requested; no inference call was made.')
        return 0

    return check_completion(config, opener, headers, auth_details)


def check_completion(config, opener, headers, auth_details=False):
    """One request to the configured inference endpoint; no model or credential fallback."""
    model = adapter_value(config['AI_MODEL'])

    maximum, timeout_ms = runtime_limits(config)
    probe_maximum = min(maximum, MAX_PROBE_COMPLETION_TOKENS)
    print(f'Completion: one optional inference call; the gateway may charge for it '
          f'(maximum {probe_maximum} output/reasoning tokens, never above AI_MAX_TOKENS).')
    body = {'model': model, 'max_completion_tokens': probe_maximum,
            'messages': [{'role': 'system', 'content': 'Reply briefly to the user.'},
                         {'role': 'user', 'content': 'Reply with OK.'}]}
    effort = adapter_value(config.get('AI_REASONING_EFFORT', 'low'))
    if effort:
        body['reasoning_effort'] = effort
    request = urllib.request.Request(endpoint(config['AI_BASE_URL'], 'chat/completions'),
                                     data=json.dumps(body).encode('utf-8'),
                                     headers={**headers, 'Content-Type': 'application/json'}, method='POST')
    result = fetch(opener, request, timeout=timeout_ms / 1000, auth_details=auth_details)
    report_probe('Completion', result)
    if result.category != 'ok':
        print('Completion result: request_failed')
        return 1
    choices = result.payload.get('choices')
    choice = choices[0] if isinstance(choices, list) and choices and isinstance(choices[0], dict) else {}
    message = choice.get('message')
    content = message.get('content') if isinstance(message, dict) else None
    answer_received = isinstance(content, str) and bool(content.strip())
    refusal = message.get('refusal') if isinstance(message, dict) else None
    refused = isinstance(refusal, str) and bool(refusal.strip())
    finished = choice.get('finish_reason') == 'stop'
    complete = answer_received and finished and not refused
    print('Completion answer received: ' + flag(answer_received))
    print('Completion completed: ' + flag(complete))
    if refused:
        print('Completion result: provider_refusal')
    elif choice.get('finish_reason') == 'length':
        print('Completion result: output_limit_reached')
    elif not answer_received:
        print('Completion result: no_answer')
    elif not finished:
        print('Completion result: finish_not_confirmed')
    else:
        print('Completion result: complete')
    # Provider text and usage metadata are deliberately never printed.
    return 0 if complete else 1


def main(argv=None, inherited=None, opener=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=Path(__file__).resolve().parents[1] / '.env')
    parser.add_argument('--offline', action='store_true', help='Check configuration only; make no network requests.')
    parser.add_argument('--auth-details', action='store_true',
                        help='Classify an authentication error into a fixed local label; never display raw provider errors.')
    inference = parser.add_mutually_exclusive_group()
    inference.add_argument('--completion', action='store_true', help='After a successful model-list check, make one small billable inference request.')
    inference.add_argument('--completion-only', action='store_true',
                           help='Check the backend completion endpoint directly with one bounded billable request; do not list models.')
    arguments = parser.parse_args(argv)
    inherited = dict(os.environ if inherited is None else inherited)
    try:
        local = _dotenv.load_defaults(arguments.env_file)
        config = _dotenv.child_environment(arguments.env_file, inherited)
    except (OSError, ValueError, UnicodeError):
        print('Configuration: dotenv_file_unreadable_or_invalid; no credential values were displayed.')
        return 2
    report_configuration(local, inherited, config)
    if not configuration_valid(config):
        return 2
    if arguments.offline:
        print('Offline check complete; no network request was made.')
        return 0
    return check(config, opener if opener is not None else build_opener(), arguments.completion,
                 arguments.completion_only, arguments.auth_details)


if __name__ == '__main__':
    sys.exit(main())
