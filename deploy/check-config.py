#!/usr/bin/env python3
"""Validate the literal environment used by deploy/compose.sh.

Read-only: no network, Docker, DB or provider calls. Both entrypoints reuse the
same dotenv parser and exported-variable precedence; compose.sh disables a second
Compose dotenv parse so literal dollar characters retain their value.
"""

import argparse
from decimal import Decimal, InvalidOperation
from email.utils import parseaddr
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
HOSTNAME = re.compile(r'(?=.{1,253}\Z)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}\Z')
IDENTIFIER = re.compile(r'[A-Za-z_][A-Za-z0-9_-]{0,62}\Z')
EMAIL = re.compile(r'[^\s@<>;,]+@[^\s@<>;,]+\.[^\s@<>;,]+\Z')
PLACEHOLDER = re.compile(r'^(?:change[_ -]?me|replace[_ -]?me|your[_ -]|dev-only-secret|example[_ -])', re.I)


class ConfigurationError(Exception):
    pass


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise ConfigurationError('invalid_arguments')


def loaded_environment(path, inherited):
    if not path.is_file():
        raise ConfigurationError('environment_file_missing')
    spec = importlib.util.spec_from_file_location('pricepilot_deploy_env', ROOT / 'scripts' / 'with-env.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    try:
        return module.child_environment(path, inherited)
    except (OSError, ValueError, UnicodeError):
        raise ConfigurationError('environment_file_invalid') from None


def plain(value):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= 4096 \
        and not any(ord(character) < 32 or ord(character) == 127 for character in value)


def secret(value, minimum=1, whitespace=False):
    return plain(value) and len(value) >= minimum and not PLACEHOLDER.match(value) \
        and value.lower() not in {'pricepilot', 'postgres', 'password', 'secret'} \
        and (whitespace or not any(character.isspace() for character in value))


def hostname(value):
    return isinstance(value, str) and HOSTNAME.fullmatch(value) is not None \
        and not value.lower().endswith(('.localhost', '.local'))


def email(value):
    return plain(value) and EMAIL.fullmatch(value) is not None


def integer(value, minimum, maximum):
    return isinstance(value, str) and re.fullmatch(r'-?\d+', value) is not None \
        and minimum <= int(value) <= maximum


def duration_seconds(value):
    """Accept explicit units shared by the Spring/Compose deployment examples."""
    if not isinstance(value, str) or len(value) > 80:
        return None
    tokens = re.findall(r'(\d+(?:\.\d+)?)(ms|s|m|h)', value)
    if not tokens or ''.join(amount + unit for amount, unit in tokens) != value:
        return None
    factors = {'ms': Decimal('0.001'), 's': Decimal(1), 'm': Decimal(60), 'h': Decimal(3600)}
    try:
        total = sum((Decimal(amount) * factors[unit] for amount, unit in tokens), Decimal(0))
        return total if total.is_finite() and total > 0 else None
    except InvalidOperation:
        return None


def url(value, origin_only=False):
    if not plain(value) or any(character.isspace() for character in value):
        return None
    try:
        parsed = urlsplit(value)
        if parsed.scheme != 'https' or not hostname(parsed.hostname) \
                or parsed.username is not None or parsed.password is not None \
                or parsed.query or parsed.fragment or parsed.port not in (None, 443) \
                or (origin_only and parsed.path not in ('', '/')):
            return None
        return parsed
    except (ValueError, TypeError):
        return None


def validate(environment, tls_bootstrap=False, allow_unlimited_browseract=False):
    issues, warnings, checks = [], [], {}

    def check(name, valid, code):
        checks[name] = bool(valid)
        if not valid:
            issues.append(code)

    def get(name, default=''):
        return environment.get(name, default)

    domain = get('DOMAIN')
    check('domain', hostname(domain), 'DOMAIN_requires_a_valid_public_hostname')
    if tls_bootstrap:
        check('certificate_email', email(get('CERTBOT_EMAIL')), 'CERTBOT_EMAIL_requires_one_email_address')
        check('certificate_mode', get('STAGING', '0') in ('0', '1'), 'STAGING_must_be_0_or_1')
        check('certificate_domain', not domain.lower().endswith('.amazonaws.com'),
              'certificate_requires_a_domain_you_control')
    else:
        profiles = {name for name in re.split(r'[,\s]+', get('SPRING_PROFILES_ACTIVE').strip()) if name}
        check('production_profile', not any(name.lower() == 'dev' for name in profiles),
              'dev_profile_is_not_allowed_for_production')
        if 'cloudwatch' in profiles:
            namespace = get('CLOUDWATCH_NAMESPACE')
            namespace_valid = bool(namespace.strip()) and len(namespace) <= 255 \
                and namespace == namespace.strip() and not namespace.startswith('AWS/') \
                and not namespace.startswith(':') and all(32 <= ord(c) <= 126 for c in namespace)
            check('cloudwatch_namespace', namespace_valid, 'CLOUDWATCH_NAMESPACE_requires_a_valid_custom_namespace')
            check('cloudwatch_region', bool(re.fullmatch(r'[a-z]{2}(?:-[a-z0-9]+)+-[0-9]+', get('AWS_REGION'))),
                  'AWS_REGION_required_for_cloudwatch_profile')
            check('cloudwatch_step', duration_seconds(get('CLOUDWATCH_STEP', '60s')) is not None,
                  'CLOUDWATCH_STEP_requires_a_positive_explicit_duration')
        check('tls_enabled', get('TLS_ENABLED') == 'true', 'TLS_ENABLED_must_be_true_for_production')
        public = url(get('APP_PUBLIC_URL'), origin_only=True)
        check('public_origin', public is not None and public.hostname.lower() == domain.lower(),
              'APP_PUBLIC_URL_must_be_the_HTTPS_DOMAIN_origin')
        origins = [part.strip() for part in get('CORS_ALLOWED_ORIGINS').split(',') if part.strip()]
        public_origin = get('APP_PUBLIC_URL').rstrip('/')
        check('cors_origins', bool(origins) and all(url(origin, origin_only=True) for origin in origins)
              and public_origin in [origin.rstrip('/') for origin in origins],
              'CORS_ALLOWED_ORIGINS_requires_matching_HTTPS_origins')
        check('database_name', bool(IDENTIFIER.fullmatch(get('POSTGRES_DB', 'pricepilot'))), 'POSTGRES_DB_invalid_identifier')
        check('database_user', bool(IDENTIFIER.fullmatch(get('POSTGRES_USER', 'pricepilot'))), 'POSTGRES_USER_invalid_identifier')
        check('database_password', secret(get('POSTGRES_PASSWORD'), minimum=16, whitespace=True),
              'POSTGRES_PASSWORD_missing_weak_or_placeholder')
        check('jwt_secret', secret(get('JWT_SECRET'), minimum=32), 'JWT_SECRET_missing_weak_or_placeholder')
        check('browseract_key', secret(get('BROWSERACT_API_KEY')), 'BROWSERACT_API_KEY_missing_or_invalid')
        check('browseract_workflow', secret(get('BROWSERACT_WORKFLOW_ID')), 'BROWSERACT_WORKFLOW_ID_missing_or_invalid')
        budget = get('BROWSERACT_MONTHLY_CALL_BUDGET', '0')
        valid_budget = integer(budget, 1, 2147483647) or (budget == '-1' and allow_unlimited_browseract)
        check('browseract_budget', valid_budget, 'BROWSERACT_requires_positive_budget_or_explicit_unlimited_opt_in')
        if budget == '-1' and allow_unlimited_browseract:
            warnings.append('BrowserAct_submissions_are_unlimited_and_metered')
        check('browseract_timeout', integer(get('BROWSERACT_TIMEOUT_MS', '90000'), 1, 2147483647),
              'BROWSERACT_TIMEOUT_MS_invalid')
        limits = (
            ('MANUAL_REFRESH_WORKERS', '1', 1, 4),
            ('MANUAL_REFRESH_QUEUE_CAPACITY', '8', 1, 100),
            ('MANUAL_REFRESH_MAX_JOBS', '200', 1, 10000),
            ('MANUAL_REFRESH_RETENTION_SECONDS', '3600', 1, 86400),
            ('MANUAL_REFRESH_SHUTDOWN_GRACE_SECONDS', '400', 0, 600),
            ('SCHEDULER_SHUTDOWN_GRACE_SECONDS', '400', 0, 600),
        )
        for name, default, minimum, maximum in limits:
            check(name.lower(), integer(get(name, default), minimum, maximum), name + '_invalid')
        phase = duration_seconds(get('SHUTDOWN_TIMEOUT', '420s'))
        container = duration_seconds(get('STOP_GRACE_PERIOD', '900s'))
        manual = get('MANUAL_REFRESH_SHUTDOWN_GRACE_SECONDS', '400')
        scheduler = get('SCHEDULER_SHUTDOWN_GRACE_SECONDS', '400')
        browser_timeout = get('BROWSERACT_TIMEOUT_MS', '90000')
        valid_graces = integer(manual, 0, 600) and integer(scheduler, 0, 600)
        check('shutdown_phase_duration', phase is not None, 'SHUTDOWN_TIMEOUT_requires_a_positive_explicit_duration')
        check('container_stop_duration', container is not None, 'STOP_GRACE_PERIOD_requires_a_positive_explicit_duration')
        if valid_graces and phase is not None:
            check('shutdown_phase_covers_workers', phase >= max(int(manual), int(scheduler)) + 10,
                  'SHUTDOWN_TIMEOUT_must_cover_worker_grace_plus_10_seconds')
        if phase is not None and container is not None:
            # The web and background lifecycle phases may drain separately.
            check('container_stop_covers_phases', container >= phase * 2 + 10,
                  'STOP_GRACE_PERIOD_must_cover_two_shutdown_phases_plus_10_seconds')
        if valid_graces and integer(browser_timeout, 1, 2147483647):
            needed = Decimal(browser_timeout) / 1000
            check('worker_grace_covers_browseract', int(manual) >= needed
                  and (get('SCHEDULER_ENABLED', 'true') == 'false' or int(scheduler) >= needed),
                  'worker_shutdown_grace_must_cover_BrowserAct_timeout')
        check('scheduler_mode', get('SCHEDULER_ENABLED', 'true') in ('true', 'false'), 'SCHEDULER_ENABLED_invalid')
        if get('SCHEDULER_ENABLED', 'true') == 'false':
            warnings.append('scheduled_refreshes_and_plan_reassessment_are_disabled')
        if not get('SIGNUP_INVITE_CODE'):
            warnings.append('registration_is_public_and_registered_users_can_consume_provider_budgets')

        provider = get('AI_PROVIDER', 'anthropic').lower()
        ai_budget = get('AI_MONTHLY_CALL_BUDGET', '100')
        check('ai_provider', provider in ('anthropic', 'openai-compatible'), 'AI_PROVIDER_invalid')
        check('ai_budget', integer(ai_budget, 0, 2147483647), 'AI_MONTHLY_CALL_BUDGET_invalid')
        if ai_budget == '0' and provider == 'openai-compatible':
            warnings.append('AI_is_explicitly_disabled')
        else:
            if ai_budget == '0' and provider == 'anthropic':
                warnings.append('Anthropic_uses_its_USD_budget_AI_MONTHLY_CALL_BUDGET_does_not_disable_it')
            key = get('AI_API_KEY') or (get('ANTHROPIC_API_KEY') if provider == 'anthropic' else '')
            base = get('AI_BASE_URL') or (get('ANTHROPIC_BASE_URL', 'https://api.anthropic.com') if provider == 'anthropic' else '')
            model = get('AI_MODEL') or (get('ANTHROPIC_MODEL', 'claude-haiku-4-5') if provider == 'anthropic' else '')
            check('ai_key', secret(key), 'AI_API_KEY_missing_or_invalid_for_enabled_provider')
            parsed_base = url(base)
            check('ai_base', parsed_base is not None and not parsed_base.path.rstrip('/').endswith(('/chat/completions', '/messages')),
                  'AI_BASE_URL_requires_a_base_URL_not_a_completion_endpoint')
            check('ai_model', plain(model) and not any(c.isspace() for c in model), 'AI_MODEL_missing_or_invalid')
            check('ai_timeout', integer(get('AI_TIMEOUT_MS', '45000'), 1, 2147483647), 'AI_TIMEOUT_MS_invalid')
            check('ai_max_tokens', integer(get('AI_MAX_TOKENS', '0'), 0, 2147483647), 'AI_MAX_TOKENS_invalid')
            try:
                usd_budget = Decimal(get('AI_MONTHLY_BUDGET_USD', '5.00'))
                usd_valid = usd_budget.is_finite() and usd_budget > 0
            except InvalidOperation:
                usd_valid = False
            check('ai_spend_budget', usd_valid, 'AI_MONTHLY_BUDGET_USD_must_be_positive_when_AI_is_enabled')

        tavily_key = get('TAVILY_API_KEY')
        if tavily_key:
            check('discovery_key', secret(tavily_key), 'TAVILY_API_KEY_invalid')
            check('discovery_budget', integer(get('TAVILY_MONTHLY_CALL_BUDGET', '500'), 1, 2147483647),
                  'TAVILY_MONTHLY_CALL_BUDGET_invalid')
        else:
            warnings.append('live_product_discovery_is_not_configured')

        check('mail_mode', get('MAIL_ENABLED', 'false') in ('true', 'false'), 'MAIL_ENABLED_invalid')
        if get('MAIL_ENABLED', 'false') == 'true':
            check('mail_host', hostname(get('MAIL_HOST', 'smtp.gmail.com')), 'MAIL_HOST_invalid')
            check('mail_port', integer(get('MAIL_PORT', '587'), 1, 65535), 'MAIL_PORT_invalid')
            check('mail_username', plain(get('MAIL_USERNAME')), 'MAIL_USERNAME_missing_or_invalid')
            check('mail_password', secret(get('MAIL_PASSWORD'), whitespace=True), 'MAIL_PASSWORD_missing_or_invalid')
            sender = get('MAIL_FROM')
            address = parseaddr(sender)[1] if plain(sender) else ''
            check('mail_sender', email(address) and ',' not in sender and ';' not in sender,
                  'MAIL_FROM_requires_one_valid_sender')
            if get('MAIL_HOST', 'smtp.gmail.com').lower() == 'smtp.gmail.com':
                check('gmail_sender', address.lower() == get('MAIL_USERNAME').lower(),
                      'Gmail_sender_must_match_MAIL_USERNAME')
        else:
            warnings.append('email_delivery_is_disabled')
    return {'valid': not issues, 'mode': 'tls_bootstrap' if tls_bootstrap else 'production',
            'checks': checks, 'issues': issues, 'warnings': warnings,
            'live_checks_performed': False,
            'scope': 'configuration_shape_only_no_provider_authentication_DNS_TLS_certificate_or_database_verification'}


def main(argv=None, inherited=None):
    parser = SafeArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / '.env')
    parser.add_argument('--tls-bootstrap', action='store_true')
    parser.add_argument('--allow-unlimited-browseract', action='store_true',
                        help='Explicitly accept BROWSERACT_MONTHLY_CALL_BUDGET=-1; provider charges still apply.')
    try:
        args = parser.parse_args(argv)
        environment = loaded_environment(args.env_file, os.environ if inherited is None else inherited)
        report = validate(environment, args.tls_bootstrap, args.allow_unlimited_browseract)
        print(json.dumps(report, sort_keys=True))
        return 0 if report['valid'] else 1
    except ConfigurationError as error:
        print(json.dumps({'valid': False, 'error': str(error), 'live_checks_performed': False}))
        return 1
    except (OSError, ValueError, TypeError, UnicodeError):
        print(json.dumps({'valid': False, 'error': 'invalid_configuration_input', 'live_checks_performed': False}))
        return 1


if __name__ == '__main__':
    sys.exit(main())
