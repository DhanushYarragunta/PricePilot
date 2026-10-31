#!/usr/bin/env python3
"""Collect public aggregate scrape counters without loading credentials or starting scrapes."""

import argparse
from datetime import datetime, timezone
import http.client
import json
import math
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


DEFAULT_URL = 'http://localhost:8080/api/metrics'
MAX_BODY_BYTES = 1_048_576
HTTP_TIMEOUT_SECONDS = 10
REQUEST_UNIT = 'BrowserAct workflow submission attempts'
COUNTER_FIELDS = (
    'retailerRequests', 'browserActApiCalls', 'successfulRequests', 'failedRequests',
    'cacheHits', 'duplicateRequestsSkipped', 'scheduledChecksDue',
    'scheduledChecksCompleted', 'scheduledChecksCompletedOnTime',
    'scheduledChecksFailed', 'scheduledChecksRejected',
)
GAUGE_FIELDS = ('dueBacklog', 'overdueBacklog')
MAX_COUNTER = (1 << 63) - 1
TIMESTAMP = re.compile(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}'
                       r'(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})\Z')


class CollectionError(Exception):
    """Only fixed local categories, never upstream text or input values."""


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally echoes invalid argument values, which may contain URL credentials.
        raise CollectionError('invalid_arguments')


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def build_opener():
    # Do not inherit proxy credentials; the optional URL is requested directly.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())


def validate_url(value):
    try:
        url = urllib.parse.urlsplit(value)
        url.port
        if (url.scheme not in ('http', 'https') or not url.hostname
                or url.username is not None or url.password is not None
                or url.path.rstrip('/') != '/api/metrics'
                or '?' in value or '#' in value
                or any(character.isspace() or ord(character) < 32 for character in value)):
            raise ValueError()
    except (TypeError, ValueError):
        raise CollectionError('invalid_metrics_url') from None
    return value


def timestamp(value):
    if not isinstance(value, str) or not TIMESTAMP.fullmatch(value):
        raise CollectionError('invalid_snapshot')
    try:
        datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        raise CollectionError('invalid_snapshot') from None
    # Preserve nanosecond precision from Java Instant rather than silently rounding it.
    return value


def safe_snapshot(payload, captured_at):
    if not isinstance(payload, dict) or not isinstance(payload.get('optimization'), dict):
        raise CollectionError('invalid_snapshot')
    values = payload['optimization']
    if values.get('requestUnit') != REQUEST_UNIT:
        raise CollectionError('invalid_snapshot')
    safe = {'since': timestamp(values.get('since')), 'requestUnit': REQUEST_UNIT}
    for name in COUNTER_FIELDS + GAUGE_FIELDS:
        value = values.get(name)
        if type(value) is not int or not 0 <= value <= MAX_COUNTER:
            raise CollectionError('invalid_snapshot')
        safe[name] = value
    if not isinstance(captured_at, datetime) or captured_at.tzinfo is None:
        raise CollectionError('invalid_collection_clock')
    captured = captured_at.astimezone(timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z')
    return {'generatedAt': timestamp(payload.get('generatedAt')),
            'capturedAt': timestamp(captured), 'optimization': safe}


def unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise CollectionError('invalid_json')
        result[name] = value
    return result


def fetch_snapshot(opener, url, now):
    request = urllib.request.Request(url, method='GET', headers={'Accept': 'application/json'})
    try:
        with opener.open(request, timeout=HTTP_TIMEOUT_SECONDS) as response:
            if response.status != 200:
                raise CollectionError('redirect_refused' if 300 <= response.status < 400 else 'http_error')
            raw = response.read(MAX_BODY_BYTES + 1)
    except urllib.error.HTTPError as error:
        try:
            raise CollectionError('redirect_refused' if 300 <= error.code < 400 else 'http_error') from None
        finally:
            error.close()
    except (OSError, ValueError, http.client.HTTPException):
        raise CollectionError('network_or_response_read_error') from None
    if not isinstance(raw, bytes) or len(raw) > MAX_BODY_BYTES:
        raise CollectionError('response_too_large')
    try:
        payload = json.loads(raw, object_pairs_hook=unique_object)
    except (ValueError, UnicodeError, RecursionError):
        raise CollectionError('invalid_json') from None
    return safe_snapshot(payload, now())


def validate_progress(previous, current):
    if previous is None:
        return
    old = previous['optimization']
    new = current['optimization']
    if new['since'] != old['since']:
        raise CollectionError('backend_restarted_start_a_new_collection')
    if any(new[name] < old[name] for name in COUNTER_FIELDS):
        raise CollectionError('counter_regression_start_a_new_collection')
    # Gauges may decrease as the backlog drains. Concurrent snapshot reads may also observe
    # related counters at slightly different instants, so no cross-counter equality is assumed.


def collect(url, output, samples, interval_seconds, opener, now, sleep):
    previous = None
    # Exclusive creation also refuses an existing symlink; never overwrite earlier measurements.
    with output.open('x', encoding='utf-8') as destination:
        for index in range(samples):
            if index:
                sleep(interval_seconds)
            current = fetch_snapshot(opener, url, now)
            validate_progress(previous, current)
            destination.write(json.dumps(current, sort_keys=True, separators=(',', ':')) + '\n')
            destination.flush()
            previous = current
            print(f'Saved sample {index + 1} of {samples}.')
    return samples


def main(argv=None, opener=None, now=None, sleep=None):
    parser = SafeArgumentParser(description=__doc__)
    parser.add_argument('--url', default=DEFAULT_URL, help='Public /api/metrics endpoint; no URL credentials or redirects.')
    parser.add_argument('--output', required=True, type=Path, help='New JSONL file; existing paths are never overwritten.')
    parser.add_argument('--samples', default=2, type=int, help='Positive number of samples (default: 2).')
    parser.add_argument('--interval-seconds', default=60.0, type=float,
                        help='Positive delay between completed samples (default: 60).')
    try:
        arguments = parser.parse_args(argv)
        url = validate_url(arguments.url)
        if arguments.samples < 1 or not math.isfinite(arguments.interval_seconds) or arguments.interval_seconds <= 0:
            raise CollectionError('invalid_arguments')
        saved = collect(url, arguments.output, arguments.samples, arguments.interval_seconds,
                        opener if opener is not None else build_opener(),
                        now if now is not None else lambda: datetime.now(timezone.utc),
                        sleep if sleep is not None else time.sleep)
        print(f'Collection complete: {saved} samples. No request-reduction percentage was inferred.')
        return 0
    except CollectionError as error:
        print(f'Collection failed: {error}. Any previously saved samples are retained.')
        return 1
    except FileExistsError:
        print('Collection failed: output_already_exists; choose a new output file.')
        return 1
    except OSError:
        print('Collection failed: output_unavailable. Any previously saved samples are retained.')
        return 1
    except KeyboardInterrupt:
        print('Collection interrupted. Any previously saved samples are retained.')
        return 130


if __name__ == '__main__':
    sys.exit(main())
