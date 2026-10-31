import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


LAUNCHER = Path(__file__).with_name('with-env.py')
spec = importlib.util.spec_from_file_location('with_env', LAUNCHER)
loader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(loader)


class WithEnvTest(unittest.TestCase):
    def test_dotenv_values_are_preserved_and_never_executed(self):
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / '.env'
            sentinel = Path(directory) / 'must-not-exist'
            literal = '$(touch ' + str(sentinel) + ') `echo test` a=b#c'
            env_file.write_text(
                'AI_PROVIDER=openai-compatible\n'
                'export AI_MODEL="gpt-6.1-sol" # comment\n'
                'MAIL_FROM=PricePilot <sender@example.com>\n'
                "TOKEN='literal $value = # content'\n"
                'HASH_TOKEN=abc#123 # explanation\n'
                'LITERAL=' + literal + '\n', encoding='utf-8')
            child = subprocess.run([
                sys.executable, str(LAUNCHER), str(env_file), sys.executable, '-c',
                'import json,os; print(json.dumps({k:os.environ[k] for k in '
                '["AI_PROVIDER","AI_MODEL","MAIL_FROM","TOKEN","HASH_TOKEN","LITERAL"]}))'
            ], env={k: v for k, v in os.environ.items() if k not in
                    {'AI_PROVIDER', 'AI_MODEL', 'MAIL_FROM', 'TOKEN', 'HASH_TOKEN', 'LITERAL'}},
                capture_output=True, text=True, check=True)
            values = json.loads(child.stdout)
            self.assertEqual(values['AI_PROVIDER'], 'openai-compatible')
            self.assertEqual(values['AI_MODEL'], 'gpt-6.1-sol')
            self.assertEqual(values['MAIL_FROM'], 'PricePilot <sender@example.com>')
            self.assertEqual(values['TOKEN'], 'literal $value = # content')
            self.assertEqual(values['HASH_TOKEN'], 'abc#123')
            self.assertEqual(values['LITERAL'], literal)
            self.assertFalse(sentinel.exists())

    def test_exports_override_file_values_without_mutating_parent(self):
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / '.env'
            env_file.write_text('AI_MODEL=gpt-6.1-sol\nAI_API_KEY=fixture-key\n')
            inherited = {'AI_MODEL': 'gpt-6-astra', 'PATH': '/bin'}
            child = loader.child_environment(env_file, inherited)
            self.assertEqual(child['AI_MODEL'], 'gpt-6-astra')
            self.assertEqual(child['AI_API_KEY'], 'fixture-key')
            self.assertNotIn('AI_API_KEY', inherited)

    def test_invalid_line_reports_location_without_exposing_value(self):
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / '.env'
            env_file.write_text('AI_API_KEY="secret-fixture\n')
            with self.assertRaisesRegex(ValueError, 'line 1') as error:
                loader.load_defaults(env_file)
            self.assertNotIn('secret-fixture', str(error.exception))


if __name__ == '__main__':
    unittest.main()
