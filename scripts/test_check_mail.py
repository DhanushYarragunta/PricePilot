from contextlib import redirect_stdout
import importlib.util
import io
from pathlib import Path
import smtplib
import ssl
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('pricepilot_check_mail', Path(__file__).with_name('check-mail.py'))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class Smtp:
    def __init__(self, fail_at=None, error=None):
        self.events = []
        self.messages = []
        self.fail_at = fail_at
        self.error = error

    def event(self, name):
        self.events.append(name)
        if name == self.fail_at:
            raise self.error

    def ehlo(self):
        self.event('ehlo')
        return 250, b'PRIVATE_SERVER_RESPONSE'

    def starttls(self, context):
        self.event('starttls')
        assert context.check_hostname
        assert context.verify_mode == ssl.CERT_REQUIRED

    def login(self, username, password):
        self.event('login')
        self.credentials = (username, password)

    def send_message(self, message, from_addr, to_addrs):
        self.event('send')
        self.messages.append((message, from_addr, to_addrs))
        return {}

    def quit(self):
        self.event('quit')

    def close(self):
        self.event('close')


class CheckMailTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.env = Path(self.directory.name) / '.env'
        self.env.write_text('MAIL_ENABLED=true\nMAIL_HOST=smtp.gmail.com\nMAIL_PORT=587\n'
                            'MAIL_USERNAME=sender@example.com\nMAIL_PASSWORD=LOCAL_SECRET\n'
                            'MAIL_FROM="PricePilot <sender@example.com>"\n')

    def tearDown(self):
        self.directory.cleanup()

    def run_check(self, arguments=(), inherited=None, connection=None):
        connection = connection or Smtp()
        self.connections = []
        def factory(host, port, timeout):
            self.connections.append((host, port, timeout))
            return connection
        output = io.StringIO()
        with redirect_stdout(output):
            status = checker.main(['--env-file', str(self.env), *arguments],
                                  inherited={} if inherited is None else inherited, smtp_factory=factory)
        return status, output.getvalue(), connection

    def test_offline_reports_only_flags_and_makes_no_connection(self):
        status, output, smtp = self.run_check(['--offline'], {'MAIL_PASSWORD': 'EXPORTED_SECRET'})
        self.assertEqual(status, 0)
        self.assertIn('export_differs_from_file=true', output)
        self.assertNotIn('EXPORTED_SECRET', output)
        self.assertNotIn('LOCAL_SECRET', output)
        self.assertEqual(self.connections, [])
        self.assertEqual(smtp.events, [])

    def test_default_verifies_tls_and_login_without_sending(self):
        status, output, smtp = self.run_check()
        self.assertEqual(status, 0)
        self.assertEqual(smtp.events, ['ehlo', 'starttls', 'ehlo', 'login', 'quit'])
        self.assertEqual(smtp.messages, [])
        self.assertIn('SMTP authentication: passed', output)
        self.assertNotIn('LOCAL_SECRET', output)
        self.assertNotIn('PRIVATE_SERVER_RESPONSE', output)

    def test_explicit_test_sends_exactly_one_message_after_tls_and_login(self):
        status, output, smtp = self.run_check(['--send-test-to', 'signed-in@example.com'])
        self.assertEqual(status, 0)
        self.assertEqual(smtp.events, ['ehlo', 'starttls', 'ehlo', 'login', 'send', 'quit'])
        self.assertEqual(len(smtp.messages), 1)
        message, sender, recipients = smtp.messages[0]
        self.assertEqual(sender, 'sender@example.com')
        self.assertEqual(recipients, ['signed-in@example.com'])
        self.assertEqual(message['Subject'], '[PricePilot test] Email delivery check')
        self.assertIn('No price alert or price-history entry', message.get_content())
        self.assertIn('SMTP accepted one', output)
        self.assertNotIn('signed-in@example.com', output)

    def test_auth_failure_is_sanitized_and_never_sends_or_retries(self):
        smtp = Smtp('login', smtplib.SMTPAuthenticationError(535, b'LOCAL_SECRET PRIVATE_REASON'))
        status, output, smtp = self.run_check(['--send-test-to', 'signed-in@example.com'], connection=smtp)
        self.assertEqual(status, 1)
        self.assertIn('smtp_authentication_failed', output)
        self.assertIn('App Password', output)
        self.assertNotIn('LOCAL_SECRET', output)
        self.assertNotIn('PRIVATE_REASON', output)
        self.assertEqual(smtp.events.count('login'), 1)
        self.assertEqual(smtp.messages, [])

    def test_tls_failure_never_exposes_password_to_smtp_login(self):
        smtp = Smtp('starttls', ssl.SSLError('PRIVATE_TLS_DETAILS'))
        status, output, smtp = self.run_check(connection=smtp)
        self.assertEqual(status, 1)
        self.assertIn('smtp_tls_failed', output)
        self.assertNotIn('login', smtp.events)
        self.assertNotIn('PRIVATE_TLS_DETAILS', output)

    def test_send_timeout_is_unconfirmed_and_not_retried(self):
        smtp = Smtp('send', TimeoutError('PRIVATE_DETAILS'))
        status, output, smtp = self.run_check(['--send-test-to', 'signed-in@example.com'], connection=smtp)
        self.assertEqual(status, 1)
        self.assertIn('smtp_timeout', output)
        self.assertIn('unconfirmed', output)
        self.assertEqual(smtp.events.count('send'), 1)
        self.assertNotIn('PRIVATE_DETAILS', output)

    def test_quit_failure_after_acceptance_does_not_report_send_failure(self):
        smtp = Smtp('quit', smtplib.SMTPServerDisconnected('PRIVATE_DETAILS'))
        status, output, smtp = self.run_check(['--send-test-to', 'signed-in@example.com'], connection=smtp)
        self.assertEqual(status, 0)
        self.assertEqual(smtp.events.count('send'), 1)
        self.assertIn('close', smtp.events)
        self.assertNotIn('PRIVATE_DETAILS', output)

    def test_missing_config_or_multiple_recipients_never_connect(self):
        for args, overrides in (([], {'MAIL_PASSWORD': ''}),
                                (['--send-test-to', 'first@example.com, second@example.com'], {}),
                                (['--send-test-to', 'first@example.com\r\nBcc: second@example.com'], {})):
            with self.subTest(args=args):
                status, output, _ = self.run_check(args, overrides)
                self.assertEqual(status, 2)
                self.assertEqual(self.connections, [])
                self.assertNotIn('first@example.com', output)

    def test_disabled_mail_prevents_explicit_send(self):
        status, output, _ = self.run_check(['--send-test-to', 'signed-in@example.com'], {'MAIL_ENABLED': 'false'})
        self.assertEqual(status, 1)
        self.assertEqual(self.connections, [])
        self.assertIn('MAIL_ENABLED is false', output)

    def test_password_is_used_exactly_as_configured(self):
        status, output, smtp = self.run_check(inherited={'MAIL_PASSWORD': ' GROUPED SECRET '})
        self.assertEqual(status, 0)
        self.assertEqual(smtp.credentials[1], ' GROUPED SECRET ')
        self.assertIn('SMTP password contains whitespace: true', output)
        self.assertNotIn('GROUPED SECRET', output)


if __name__ == '__main__':
    unittest.main()
