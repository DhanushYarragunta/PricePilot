#!/usr/bin/env python3
"""Check SMTP STARTTLS and login; optionally send exactly one labeled test email.

The local development scheduler is normally disabled. SMTP success alone does not
enable automatic price checks or alerts; a verified price event must trigger them.
"""

import argparse
from email.message import EmailMessage
from email.utils import getaddresses
import importlib.util
import os
from pathlib import Path
import smtplib
import socket
import ssl
import sys


_spec = importlib.util.spec_from_file_location('pricepilot_mail_dotenv', Path(__file__).with_name('with-env.py'))
_dotenv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_dotenv)
FIELDS = ('MAIL_ENABLED', 'MAIL_HOST', 'MAIL_PORT', 'MAIL_USERNAME', 'MAIL_PASSWORD', 'MAIL_FROM', 'SCHEDULER_ENABLED')
TIMEOUT_SECONDS = 10


def flag(value):
    return 'true' if value else 'false'


def single_address(value):
    if not isinstance(value, str) or '\r' in value or '\n' in value:
        raise ValueError('invalid_email_address')
    addresses = getaddresses([value])
    if len(addresses) != 1:
        raise ValueError('invalid_email_address')
    address = addresses[0][1]
    if address.count('@') != 1 or any(character.isspace() for character in address):
        raise ValueError('invalid_email_address')
    local, domain = address.split('@')
    if not local or not domain or '.' not in domain:
        raise ValueError('invalid_email_address')
    return address


def report_configuration(local, inherited, config):
    for key in FIELDS:
        print(f'{key}: file_present={flag(bool(local.get(key)))} '
              f'exported={flag(key in inherited)} '
              f'export_differs_from_file={flag(key in inherited and inherited[key] != local.get(key))} '
              f'effective_present={flag(bool(config.get(key)))}')
    print('Email alerts enabled: ' + flag(config.get('MAIL_ENABLED', 'false').lower() == 'true'))
    print('SMTP password contains whitespace: ' + flag(any(character.isspace() for character in config.get('MAIL_PASSWORD', ''))))
    print('Automatic alerts require the scheduler or a manual refresh and an eligible verified price event.')


def settings(config):
    host = config.get('MAIL_HOST', 'smtp.gmail.com')
    if not host or any(character.isspace() for character in host) or any(character in host for character in '/@?#'):
        raise ValueError('invalid_smtp_host')
    try:
        port = int(config.get('MAIL_PORT', '587'))
    except (ValueError, TypeError):
        raise ValueError('invalid_smtp_port') from None
    if not 1 <= port <= 65535:
        raise ValueError('invalid_smtp_port')
    username = config.get('MAIL_USERNAME', '')
    password = config.get('MAIL_PASSWORD', '')
    if not username.strip() or not password.strip():
        raise ValueError('smtp_credentials_missing')
    sender = config.get('MAIL_FROM', 'PricePilot <noreply@example.com>')
    return host, port, username, password, sender, single_address(sender)


def category(error):
    if isinstance(error, smtplib.SMTPAuthenticationError):
        return 'smtp_authentication_failed'
    if isinstance(error, smtplib.SMTPNotSupportedError):
        return 'smtp_required_feature_unavailable'
    if isinstance(error, ssl.SSLError):
        return 'smtp_tls_failed'
    if isinstance(error, (TimeoutError, socket.timeout)):
        return 'smtp_timeout'
    if isinstance(error, smtplib.SMTPRecipientsRefused):
        return 'smtp_recipient_rejected'
    if isinstance(error, smtplib.SMTPSenderRefused):
        return 'smtp_sender_rejected'
    if isinstance(error, smtplib.SMTPDataError):
        return 'smtp_message_rejected'
    if isinstance(error, (OSError, smtplib.SMTPConnectError, smtplib.SMTPServerDisconnected)):
        return 'smtp_connection_failed'
    return 'smtp_failed'


def report_failure(result):
    print('SMTP result: ' + result)
    if result == 'smtp_authentication_failed':
        print('Action: verify MAIL_USERNAME and MAIL_PASSWORD. Gmail requires an App Password; check exported overrides and restart the backend after changes.')
    elif result in ('smtp_tls_failed', 'smtp_required_feature_unavailable'):
        print('Action: check the SMTP host, STARTTLS support and port. TLS verification was not disabled.')
    elif result == 'smtp_timeout':
        print('Action: delivery is unconfirmed. No automatic resend was attempted.')
    else:
        print('Action: check SMTP configuration, provider status and sender/recipient settings.')


def check(config, smtp_factory=smtplib.SMTP, recipient=None):
    host, port, username, password, sender, sender_address = settings(config)
    if recipient is not None:
        recipient = single_address(recipient)
        if config.get('MAIL_ENABLED', 'false').lower() != 'true':
            print('Test email: not sent; MAIL_ENABLED is false.')
            return 1
    connection = None
    stage = 'connect'
    try:
        connection = smtp_factory(host, port, timeout=TIMEOUT_SECONDS)
        code, _ = connection.ehlo()
        if code != 250:
            raise smtplib.SMTPHeloError(code, b'')
        stage = 'starttls'
        connection.starttls(context=ssl.create_default_context())
        code, _ = connection.ehlo()
        if code != 250:
            raise smtplib.SMTPHeloError(code, b'')
        print('SMTP STARTTLS: passed')
        stage = 'login'
        # Match JavaMail's configured credentials exactly; do not silently edit passwords.
        connection.login(username, password)
        print('SMTP authentication: passed')
        if recipient is None:
            print('Test email: not requested; no email was sent.')
            return 0
        message = EmailMessage()
        message['From'] = sender
        message['To'] = recipient
        message['Subject'] = '[PricePilot test] Email delivery check'
        message.set_content('This is the email delivery test you requested from PricePilot.\n\n'
                            'No price alert or price-history entry was created by this test.\n')
        stage = 'send'
        refused = connection.send_message(message, from_addr=sender_address, to_addrs=[recipient])
        if refused:
            print('Test email: SMTP did not accept the recipient; no resend was attempted.')
            return 1
        print('Test email: SMTP accepted one labeled test message. Check the inbox and spam folder.')
        return 0
    except (smtplib.SMTPException, OSError, ValueError, UnicodeError) as error:
        print('SMTP failed stage: ' + stage)
        report_failure(category(error))
        return 1
    finally:
        if connection is not None:
            # A QUIT failure after SMTP acceptance must not turn success into a retry signal.
            try:
                connection.quit()
            except (smtplib.SMTPException, OSError):
                try:
                    connection.close()
                except OSError:
                    pass


def main(argv=None, inherited=None, smtp_factory=smtplib.SMTP):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=Path(__file__).resolve().parents[1] / '.env')
    parser.add_argument('--offline', action='store_true', help='Check local settings only; make no network requests.')
    parser.add_argument('--send-test-to', help='Explicitly send one labeled test email to this authorized address after TLS and login succeed.')
    args = parser.parse_args(argv)
    inherited = dict(os.environ if inherited is None else inherited)
    try:
        local = _dotenv.load_defaults(args.env_file)
        config = _dotenv.child_environment(args.env_file, inherited)
    except (OSError, ValueError, UnicodeError):
        print('Configuration: dotenv_file_unreadable_or_invalid')
        return 2
    report_configuration(local, inherited, config)
    try:
        settings(config)
        if args.send_test_to is not None:
            single_address(args.send_test_to)
    except ValueError as error:
        # Only locally generated category identifiers are printed, never input values.
        print('Configuration: ' + str(error))
        return 2
    if args.offline:
        print('Offline check complete; no network request or email was sent.')
        return 0
    return check(config, smtp_factory, args.send_test_to)


if __name__ == '__main__':
    sys.exit(main())
