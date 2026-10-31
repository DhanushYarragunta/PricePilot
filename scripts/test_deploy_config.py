"""Offline deployment guards; every credential and hostname fixture is synthetic."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('pricepilot_deploy_config', ROOT / 'deploy' / 'check-config.py')
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)


def production():
    return {
        'DOMAIN': 'shop.pricepilot-test.in', 'TLS_ENABLED': 'true',
        'APP_PUBLIC_URL': 'https://shop.pricepilot-test.in',
        'CORS_ALLOWED_ORIGINS': 'https://shop.pricepilot-test.in',
        'POSTGRES_PASSWORD': 'synthetic-db-password-123456',
        'JWT_SECRET': 'synthetic-signing-secret-12345678901234567890',
        'BROWSERACT_API_KEY': 'synthetic-browseract-key', 'BROWSERACT_WORKFLOW_ID': 'synthetic-workflow-id',
        'BROWSERACT_MONTHLY_CALL_BUDGET': '100',
        'AI_PROVIDER': 'openai-compatible', 'AI_MONTHLY_CALL_BUDGET': '100',
        'AI_API_KEY': 'synthetic-ai-key', 'AI_MODEL': 'gpt-6.1-sol', 'AI_BASE_URL': 'https://agentrouter.org/v1',
        'MAIL_ENABLED': 'false',
    }


class ProductionConfigurationTest(unittest.TestCase):
    def test_valid_shape_does_not_claim_provider_or_database_works(self):
        report = checker.validate(production())
        self.assertTrue(report['valid'])
        self.assertFalse(report['live_checks_performed'])
        self.assertIn('configuration_shape_only', report['scope'])

    def test_disabled_and_unlimited_browseract_require_deliberate_settings(self):
        for value in ('0', '-1', '-2', 'abc', '1.5'):
            self.assertFalse(checker.validate(dict(production(), BROWSERACT_MONTHLY_CALL_BUDGET=value))['valid'])
        report = checker.validate(dict(production(), BROWSERACT_MONTHLY_CALL_BUDGET='-1'),
                                  allow_unlimited_browseract=True)
        self.assertTrue(report['valid'])
        self.assertIn('BrowserAct_submissions_are_unlimited_and_metered', report['warnings'])

    def test_known_development_and_example_secrets_fail(self):
        for field, value in (('POSTGRES_PASSWORD', 'pricepilot'),
                             ('POSTGRES_PASSWORD', 'change_me_before_deploying'),
                             ('JWT_SECRET', 'dev-only-secret-change-me-this-must-be-32-bytes-or-more'),
                             ('JWT_SECRET', 'change_me_to_a_long_random_string_at_least_32_bytes'),
                             ('JWT_SECRET', 'too-short'), ('BROWSERACT_API_KEY', 'your_api_key')):
            with self.subTest(field=field):
                self.assertFalse(checker.validate(dict(production(), **{field: value}))['valid'])

    def test_spaces_and_metacharacters_in_database_password_are_data(self):
        value = 'synthetic long password $(do-not-run); `literal` & <literal>'
        self.assertTrue(checker.validate(dict(production(), POSTGRES_PASSWORD=value))['valid'])

    def test_public_tls_and_cors_must_agree(self):
        for changes in ({'TLS_ENABLED': 'false'}, {'DOMAIN': 'localhost'},
                        {'DOMAIN': "shop.test.in'; touch anything"},
                        {'APP_PUBLIC_URL': 'http://shop.pricepilot-test.in'},
                        {'APP_PUBLIC_URL': 'https://another.pricepilot-test.in'},
                        {'APP_PUBLIC_URL': 'https://user:password@shop.pricepilot-test.in'},
                        {'APP_PUBLIC_URL': 'https://shop.pricepilot-test.in/path'},
                        {'CORS_ALLOWED_ORIGINS': '*'},
                        {'CORS_ALLOWED_ORIGINS': 'https://shop.pricepilot-test.in,http://localhost:5173'}):
            with self.subTest(changes=changes):
                self.assertFalse(checker.validate(dict(production(), **changes))['valid'])

    def test_enabled_ai_rejects_wrong_protocol_missing_model_whitespace_and_endpoint_path(self):
        for changes in ({'AI_MODEL': ''}, {'AI_API_KEY': 'synthetic key with spaces'},
                        {'AI_BASE_URL': 'https://agentrouter.org/v1/chat/completions'},
                        {'AI_BASE_URL': 'http://agentrouter.org/v1'},
                        {'AI_BASE_URL': 'https://user:password@agentrouter.org/v1'},
                        {'AI_MONTHLY_BUDGET_USD': 'NaN'}, {'AI_MONTHLY_BUDGET_USD': '0'},
                        {'AI_PROVIDER': 'unknown'}):
            with self.subTest(changes=changes):
                self.assertFalse(checker.validate(dict(production(), **changes))['valid'])

    def test_compatible_ai_can_be_disabled_explicitly_without_credentials(self):
        report = checker.validate(dict(production(), AI_MONTHLY_CALL_BUDGET='0', AI_API_KEY='', AI_MODEL='', AI_BASE_URL=''))
        self.assertTrue(report['valid'])
        self.assertIn('AI_is_explicitly_disabled', report['warnings'])

    def test_anthropic_is_not_misreported_disabled_by_compatible_call_limit(self):
        report = checker.validate(dict(production(), AI_PROVIDER='anthropic', AI_MONTHLY_CALL_BUDGET='0'))
        self.assertTrue(report['valid'])
        self.assertNotIn('AI_is_explicitly_disabled', report['warnings'])

    def test_enabled_gmail_sender_and_password_are_checked(self):
        configuration = dict(production(), MAIL_ENABLED='true', MAIL_USERNAME='sender@pricepilot-test.in',
                             MAIL_FROM='PricePilot <sender@pricepilot-test.in>', MAIL_PASSWORD='synthetic-app-password')
        self.assertTrue(checker.validate(configuration)['valid'])
        for changes in ({'MAIL_FROM': 'Other <other@pricepilot-test.in>'},
                        {'MAIL_FROM': 'Sender <sender@pricepilot-test.in>\nBcc: attacker@pricepilot-test.in'},
                        {'MAIL_PASSWORD': ''}, {'MAIL_PORT': '0'}):
            self.assertFalse(checker.validate(dict(configuration, **changes))['valid'])

    def test_tls_bootstrap_checks_only_hostname_email_and_mode(self):
        configuration = {'DOMAIN': 'shop.pricepilot-test.in', 'CERTBOT_EMAIL': 'owner@pricepilot-test.in'}
        self.assertTrue(checker.validate(configuration, tls_bootstrap=True)['valid'])
        for changes in ({'DOMAIN': 'localhost'}, {'DOMAIN': 'bad;command'},
                        {'DOMAIN': 'ec2-1-2-3.compute.amazonaws.com'}, {'CERTBOT_EMAIL': 'two@example.in;evil'},
                        {'STAGING': '$(command)'}):
            self.assertFalse(checker.validate(dict(configuration, **changes), tls_bootstrap=True)['valid'])

    def test_dev_profile_is_rejected_even_when_scheduler_flag_is_true(self):
        for profile in ('dev', 'dev,cloudwatch', 'production, dev'):
            report = checker.validate(dict(production(), SPRING_PROFILES_ACTIVE=profile, SCHEDULER_ENABLED='true'))
            self.assertFalse(report['valid'])
            self.assertIn('dev_profile_is_not_allowed_for_production', report['issues'])
        self.assertTrue(checker.validate(dict(production(), SPRING_PROFILES_ACTIVE=''))['valid'])

    def test_cloudwatch_requires_explicit_valid_namespace_and_region_when_selected(self):
        base = dict(production(), SPRING_PROFILES_ACTIVE='cloudwatch')
        self.assertFalse(checker.validate(base)['valid'])
        valid = dict(base, CLOUDWATCH_NAMESPACE='PricePilot/Synthetic-Test', AWS_REGION='ap-south-1')
        self.assertTrue(checker.validate(valid)['valid'])
        for changes in ({'CLOUDWATCH_NAMESPACE': 'AWS/PricePilot'}, {'CLOUDWATCH_NAMESPACE': ' trailing '},
                        {'CLOUDWATCH_NAMESPACE': ':invalid'}, {'CLOUDWATCH_NAMESPACE': 'a' * 256},
                        {'CLOUDWATCH_NAMESPACE': 'not\nprintable'}, {'CLOUDWATCH_NAMESPACE': 'unicode-₹'},
                        {'AWS_REGION': ''}, {'AWS_REGION': 'India'}, {'CLOUDWATCH_STEP': '0s'}):
            with self.subTest(changes=changes):
                self.assertFalse(checker.validate(dict(valid, **changes))['valid'])
        # Unselected optional profile does not require or activate AWS settings.
        self.assertTrue(checker.validate(dict(production(), CLOUDWATCH_NAMESPACE='', AWS_REGION=''))['valid'])

    def test_manual_job_limits_match_backend_bounds(self):
        for field, values in (
            ('MANUAL_REFRESH_WORKERS', ('0', '5')),
            ('MANUAL_REFRESH_QUEUE_CAPACITY', ('0', '101')),
            ('MANUAL_REFRESH_MAX_JOBS', ('0', '10001')),
            ('MANUAL_REFRESH_RETENTION_SECONDS', ('0', '86401')),
            ('MANUAL_REFRESH_SHUTDOWN_GRACE_SECONDS', ('-1', '601')),
            ('SCHEDULER_SHUTDOWN_GRACE_SECONDS', ('-1', '601')),
        ):
            for value in values:
                self.assertFalse(checker.validate(dict(production(), **{field: value}))['valid'])

    def test_shutdown_envelopes_cover_workers_and_separate_lifecycle_phases(self):
        self.assertTrue(checker.validate(dict(production(), SHUTDOWN_TIMEOUT='7m', STOP_GRACE_PERIOD='15m'))['valid'])
        for changes in ({'SHUTDOWN_TIMEOUT': '20s'}, {'STOP_GRACE_PERIOD': '40s'},
                        {'SHUTDOWN_TIMEOUT': '420'}, {'STOP_GRACE_PERIOD': 'unknown'},
                        {'MANUAL_REFRESH_SHUTDOWN_GRACE_SECONDS': '20'},
                        {'BROWSERACT_TIMEOUT_MS': '500000'}):
            self.assertFalse(checker.validate(dict(production(), **changes))['valid'])

    def test_dotenv_loading_preserves_exports_and_never_echoes_values(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'synthetic.env'
            configuration = production()
            file.write_text(''.join(f'{name}="{value}"\n' for name, value in configuration.items()))
            loaded = checker.loaded_environment(file, {'AI_MODEL': 'gpt-6-astra'})
            self.assertEqual('gpt-6-astra', loaded['AI_MODEL'])
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = checker.main(['--env-file', str(file)], inherited={})
            self.assertEqual(0, code)
            for field in ('POSTGRES_PASSWORD', 'JWT_SECRET', 'BROWSERACT_API_KEY', 'BROWSERACT_WORKFLOW_ID', 'AI_API_KEY'):
                self.assertNotIn(configuration[field], output.getvalue())

    def test_bad_dotenv_and_arguments_do_not_echo_secret_text(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / 'synthetic.env'
            file.write_text('AI_API_KEY="synthetic-unclosed-value\n')
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                code = checker.main(['--env-file', str(file)], inherited={})
            self.assertEqual(1, code)
            self.assertNotIn('synthetic-unclosed-value', output.getvalue())
            with contextlib.redirect_stdout(output):
                code = checker.main(['--unknown', 'synthetic-secret-argument'], inherited={})
            self.assertEqual(1, code)
            self.assertNotIn('synthetic-secret-argument', output.getvalue())


class CertificateLauncherTest(unittest.TestCase):
    def test_real_dotenv_loader_preserves_arguments_and_does_not_evaluate_values(self):
        # Execute only the outer wrapper in a copied temporary project. A fake
        # child bash stops before ALL certificate, network and Docker operations.
        with tempfile.TemporaryDirectory(prefix='pricepilot-synthetic-tls-') as directory:
            root = Path(directory)
            (root / 'deploy').mkdir()
            (root / 'scripts').mkdir()
            (root / 'bin').mkdir()
            shutil.copy(ROOT / 'deploy' / 'init-letsencrypt.sh', root / 'deploy')
            shutil.copy(ROOT / 'scripts' / 'with-env.py', root / 'scripts')
            marker = root / 'must-not-exist'
            literal = f'$(touch {marker}); `touch {marker}` & <literal>'
            (root / '.env').write_text('MAIL_FROM="PricePilot Test <sender@pricepilot-test.in>"\n'
                                       f'TEST_LITERAL=\'{literal}\'\nDOMAIN=dotenv.pricepilot-test.in\n')
            capture = root / 'captured.json'
            (root / 'bin' / 'python3').symlink_to(sys.executable)
            child = root / 'bin' / 'bash'
            child.write_text('#!/usr/bin/env python3\nimport json,os,sys\n'
                             'from pathlib import Path\n'
                             'Path(os.environ["TEST_CAPTURE"]).write_text(json.dumps({"argv":sys.argv[1:],'
                             '"sender":os.environ["MAIL_FROM"],"literal":os.environ["TEST_LITERAL"],'
                             '"domain":os.environ["DOMAIN"]}))\n')
            child.chmod(0o755)
            environment = {'PATH': str(root / 'bin') + os.pathsep + os.defpath,
                           'DOMAIN': 'exported.pricepilot-test.in', 'TEST_CAPTURE': str(capture)}
            arguments = ['one space', '$(not-executed)', '--literal=value;two']
            process = subprocess.run(['/bin/bash', str(root / 'deploy' / 'init-letsencrypt.sh'), *arguments],
                                     env=environment, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, process.returncode, process.stderr)
            result = json.loads(capture.read_text())
            self.assertEqual(['deploy/init-letsencrypt.sh', '--pricepilot-dotenv-loaded', *arguments], result['argv'])
            self.assertEqual('PricePilot Test <sender@pricepilot-test.in>', result['sender'])
            self.assertEqual(literal, result['literal'])
            self.assertEqual('exported.pricepilot-test.in', result['domain'])
            self.assertFalse(marker.exists())

    def test_compose_wrapper_uses_literal_environment_and_preserves_arguments(self):
        # The only docker executable available is a local capture stub. No daemon
        # is contacted, and no real deployment configuration is read.
        with tempfile.TemporaryDirectory(prefix='pricepilot-synthetic-compose-') as directory:
            root = Path(directory)
            for name in ('deploy', 'scripts', 'bin'):
                (root / name).mkdir()
            shutil.copy(ROOT / 'deploy' / 'compose.sh', root / 'deploy')
            shutil.copy(ROOT / 'scripts' / 'with-env.py', root / 'scripts')
            literal = 'synthetic-$UNSET_VALUE-${ALSO_UNSET}-$(not-a-command)-`literal`; &'
            (root / '.env').write_text(f'JWT_SECRET=\'{literal}\'\n'
                                       'MAIL_FROM="PricePilot Test <sender@pricepilot-test.in>"\n'
                                       'DOMAIN=dotenv.pricepilot-test.in\n')
            capture = root / 'captured.json'
            (root / 'bin' / 'python3').symlink_to(sys.executable)
            docker = root / 'bin' / 'docker'
            docker.write_text('#!/usr/bin/env python3\nimport json,os,sys\nfrom pathlib import Path\n'
                              'Path(os.environ["TEST_CAPTURE"]).write_text(json.dumps({'
                              '"argv":sys.argv[1:],"secret":os.environ["JWT_SECRET"],'
                              '"sender":os.environ["MAIL_FROM"],"domain":os.environ["DOMAIN"],'
                              '"cwd":os.getcwd()}))\n')
            docker.chmod(0o755)
            arguments = ['config', '--format', 'json', 'space argument', '$literal;not-executed']
            environment = {'PATH': str(root / 'bin') + os.pathsep + os.defpath,
                           'TEST_CAPTURE': str(capture), 'DOMAIN': 'exported.pricepilot-test.in'}
            process = subprocess.run(['/bin/bash', str(root / 'deploy/compose.sh'), *arguments],
                                     env=environment, text=True, capture_output=True, timeout=10)
            self.assertEqual(0, process.returncode, process.stderr)
            result = json.loads(capture.read_text())
            self.assertEqual(['compose', '--env-file', '/dev/null', *arguments], result['argv'])
            self.assertEqual(literal, result['secret'])
            self.assertEqual('PricePilot Test <sender@pricepilot-test.in>', result['sender'])
            self.assertEqual('exported.pricepilot-test.in', result['domain'])
            self.assertEqual(root.resolve(), Path(result['cwd']).resolve())
            self.assertEqual('', process.stdout)
            self.assertNotIn(literal, process.stderr)

    def test_bootstrap_uses_the_same_compose_environment_wrapper(self):
        script = (ROOT / 'deploy/init-letsencrypt.sh').read_text()
        self.assertNotRegex(script, r'(?m)^\s*(?:if\s+|TLS_ENABLED=true\s+)?docker\s+compose\s')
        self.assertIn('TLS_ENABLED=true ./deploy/compose.sh up -d --build web', script)

    def test_certificate_probe_preserves_unmarked_partial_material_and_allows_owned_placeholders(self):
        script = (ROOT / 'deploy/init-letsencrypt.sh').read_text()
        probe = script.split("CERT_PROBE=$(cat <<'CERTIFICATE_PROBE'\n", 1)[1].split('\nCERTIFICATE_PROBE\n', 1)[0]
        for state in ('missing', 'complete', 'certificate_only', 'key_only', 'broken_symlink',
                      'archive_only', 'renewal_only', 'placeholder', 'placeholder_with_archive'):
            with self.subTest(state=state), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                live, archive, renewal = root / 'live', root / 'archive', root / 'renewal.conf'
                if state not in ('missing', 'archive_only', 'renewal_only'):
                    live.mkdir()
                if state in ('complete', 'certificate_only', 'placeholder', 'placeholder_with_archive'):
                    (live / 'fullchain.pem').write_text('synthetic certificate')
                if state in ('complete', 'key_only', 'placeholder', 'placeholder_with_archive'):
                    (live / 'privkey.pem').write_text('synthetic key')
                if state == 'broken_symlink':
                    (live / 'fullchain.pem').symlink_to(root / 'missing.pem')
                if state in ('placeholder', 'placeholder_with_archive'):
                    (live / '.self-signed').touch()
                if state in ('archive_only', 'placeholder_with_archive'):
                    archive.mkdir()
                if state == 'renewal_only':
                    renewal.write_text('synthetic renewal data')
                before = sorted(str(path.relative_to(root)) for path in root.rglob('*'))
                result = subprocess.run(['/bin/sh', '-c', probe, 'probe', str(live), str(archive), str(renewal)],
                                        text=True, capture_output=True, check=True, timeout=5)
                expected = state if state in ('missing', 'complete', 'placeholder') else 'incomplete'
                self.assertEqual(expected, result.stdout)
                self.assertEqual(before, sorted(str(path.relative_to(root)) for path in root.rglob('*')))


def blocks(text, header):
    """Inspect balanced nginx blocks without treating comment braces as syntax."""
    text = re.sub(r'(?m)^\s*#.*$', '', text)
    result = []
    for match in re.finditer(re.escape(header) + r'\s*\{', text):
        start, depth, position = match.end(), 1, match.end()
        while depth and position < len(text):
            if text[position] == '{':
                depth += 1
            elif text[position] == '}':
                depth -= 1
            position += 1
        if depth:
            raise AssertionError('Unclosed nginx block')
        result.append(text[start:position - 1])
    return result


class NginxConfigurationContractTest(unittest.TestCase):
    def setUp(self):
        self.http = (ROOT / 'frontend/deploy/nginx/site-http.conf.template').read_text()
        self.tls = (ROOT / 'frontend/deploy/nginx/site-tls.conf.template').read_text()
        self.dockerfile = (ROOT / 'frontend/Dockerfile').read_text()

    def pick_site(self, tls_enabled, certificate=False, private_key=False):
        # Exercise a copied startup script with all fixed container paths
        # redirected into this synthetic fixture; no Docker service is started.
        with tempfile.TemporaryDirectory(prefix='pricepilot-synthetic-site-') as directory:
            root = Path(directory)
            available, templates = root / 'available', root / 'templates'
            available.mkdir()
            templates.mkdir()
            (available / 'site-http.conf.template').write_text('synthetic HTTP template')
            (available / 'site-tls.conf.template').write_text('synthetic TLS template')
            live = root / 'certificates' / 'shop.pricepilot-test.in'
            live.mkdir(parents=True)
            if certificate:
                (live / 'fullchain.pem').write_text('synthetic placeholder certificate')
            if private_key:
                (live / 'privkey.pem').write_text('synthetic placeholder private key')
            source = (ROOT / 'frontend/deploy/nginx/10-pick-site.sh').read_text()
            source = source.replace('AVAILABLE=/etc/nginx/available', f'AVAILABLE="{available}"')
            source = source.replace('TEMPLATES=/etc/nginx/templates', f'TEMPLATES="{templates}"')
            source = source.replace('/etc/letsencrypt/live/', str(root / 'certificates') + '/')
            script = root / 'pick-site.sh'
            script.write_text(source)
            process = subprocess.run(['/bin/sh', str(script)], text=True, capture_output=True, timeout=5,
                                     env={'PATH': os.defpath, 'TLS_ENABLED': tls_enabled,
                                          'DOMAIN': 'shop.pricepilot-test.in'})
            selected = templates / 'default.conf.template'
            return process, selected.read_text() if selected.is_file() else None

    def test_tls_requested_with_either_file_missing_fails_without_http_downgrade(self):
        for certificate, private_key in ((False, False), (True, False), (False, True)):
            with self.subTest(certificate=certificate, private_key=private_key):
                process, selected = self.pick_site('true', certificate, private_key)
                self.assertNotEqual(0, process.returncode)
                self.assertIn('requires both fullchain.pem and privkey.pem', process.stderr)
                self.assertIn('refusing to start', process.stderr)
                self.assertIsNone(selected)

    def test_explicit_http_bootstrap_remains_available_without_certificate(self):
        process, selected = self.pick_site('false')
        self.assertEqual(0, process.returncode)
        self.assertEqual('synthetic HTTP template', selected)

    def test_tls_selects_https_when_both_placeholder_files_exist(self):
        process, selected = self.pick_site('true', certificate=True, private_key=True)
        self.assertEqual(0, process.returncode)
        self.assertEqual('synthetic TLS template', selected)

    def test_certificate_bootstrap_creates_both_files_before_enabling_tls(self):
        script = (ROOT / 'deploy/init-letsencrypt.sh').read_text()
        start = script.index('TLS_ENABLED=true ./deploy/compose.sh up')
        self.assertLess(script.index("-keyout '${LIVE_PATH}/privkey.pem'"), start)
        self.assertLess(script.index("-out '${LIVE_PATH}/fullchain.pem'"), start)
        self.assertIn('set -euo pipefail', script)
        self.assertIn('A simple restart does not reload changed environment', script)

    def test_local_health_never_redirects_or_depends_on_backend_and_checks_index(self):
        for template in (self.http, self.tls):
            server = blocks(template.replace('${DOMAIN}', 'shop.pricepilot-test.in'), 'server')[0]
            self.assertRegex(server, r'listen\s+80(?:\s|;)')
            health = blocks(server, 'location = /healthz')
            self.assertEqual(1, len(health))
            self.assertIn('root /usr/share/nginx/html;', health[0])
            self.assertIn('try_files /index.html =503;', health[0])
            self.assertIn('allow 127.0.0.1;', health[0])
            self.assertIn('deny all;', health[0])
            self.assertNotIn('proxy_pass', health[0])
            self.assertNotRegex(health[0], r'return\s+30[1278]')
        self.assertIn('http://127.0.0.1/healthz', self.dockerfile)
        self.assertNotIn('http://127.0.0.1/index.html', self.dockerfile)
        self.assertLess(self.dockerfile.index('RUN rm -rf /usr/share/nginx/html/*'),
                        self.dockerfile.index('COPY --from=build /build/dist /usr/share/nginx/html'))

    def test_both_proxy_routes_use_docker_dns_with_original_uri(self):
        for template in (self.http, self.tls):
            self.assertIn('resolver 127.0.0.11 valid=10s ipv6=off;', template)
            self.assertIn('set $app_upstream http://app:8080;', template)
            for location in ('location /api/', 'location /actuator/health'):
                body = blocks(template, location)[0]
                self.assertIn('proxy_pass $app_upstream$request_uri;', body)
                self.assertNotIn('rewrite ', body)
                for header in ('Host', 'X-Real-IP', 'X-Forwarded-For', 'X-Forwarded-Proto', 'X-Forwarded-Host', 'X-Forwarded-Port'):
                    self.assertRegex(body, r'proxy_set_header\s+' + header + r'\s+\$')
            self.assertNotRegex(template, r'proxy_pass\s+http://app:8080')

    def test_tls_redirect_and_acme_are_separate_from_health(self):
        server = blocks(self.tls.replace('${DOMAIN}', 'shop.pricepilot-test.in'), 'server')[0]
        acme = blocks(server, 'location /.well-known/acme-challenge/')[0]
        self.assertIn('root /var/www/certbot;', acme)
        self.assertNotIn('return 301', acme)
        self.assertIn('return 301 https://$host$request_uri;', blocks(server, 'location /')[0])

    @unittest.skipUnless(shutil.which('envsubst'), 'envsubst is not installed; nginx runtime validation still required')
    def test_restricted_envsubst_preserves_nginx_variables_even_if_environment_names_collide(self):
        self.assertIn('NGINX_ENVSUBST_FILTER="^DOMAIN$"', self.dockerfile)
        result = subprocess.run([shutil.which('envsubst'), '${DOMAIN}'], input=self.tls,
                                env={'DOMAIN': 'shop.pricepilot-test.in', 'request_uri': 'do-not-substitute',
                                     'app_upstream': 'do-not-substitute'}, text=True, capture_output=True, check=True)
        self.assertNotIn('${DOMAIN}', result.stdout)
        self.assertIn('$request_uri', result.stdout)
        self.assertIn('$app_upstream', result.stdout)
        self.assertNotIn('do-not-substitute', result.stdout)


if __name__ == '__main__':
    unittest.main()
