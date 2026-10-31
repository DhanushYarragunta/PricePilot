#!/usr/bin/env python3
"""Run a command with dotenv defaults, without evaluating the file as shell code."""

import os
from pathlib import Path
import re
import sys


def parse_value(raw, number):
    value = raw.strip()
    if not value or value[0] not in ("'", '"'):
        return re.split(r"\s+#", value, maxsplit=1)[0].rstrip()
    quote = value[0]
    result = []
    index = 1
    while index < len(value):
        char = value[index]
        if char == quote:
            remainder = value[index + 1:].strip()
            if remainder and not remainder.startswith('#'):
                raise ValueError(f"Invalid dotenv quoting on line {number}")
            return ''.join(result)
        if quote == '"' and char == '\\' and index + 1 < len(value):
            escaped = value[index + 1]
            replacements = {'n': '\n', 'r': '\r', 't': '\t', '"': '"', '\\': '\\'}
            if escaped in replacements:
                result.append(replacements[escaped])
                index += 2
                continue
        result.append(char)
        index += 1
    raise ValueError(f"Unclosed dotenv quote on line {number}")


def load_defaults(file):
    defaults = {}
    if not file.exists():
        return defaults
    for number, raw in enumerate(file.read_text(encoding='utf-8-sig').splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:].lstrip()
        key, separator, value = line.partition('=')
        key = key.strip()
        if not separator or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key):
            raise ValueError(f"Invalid dotenv assignment on line {number}")
        defaults[key] = parse_value(value, number)
    return defaults


def child_environment(file, inherited):
    defaults = load_defaults(file)
    defaults.update(inherited)  # Explicit exports take precedence over file defaults.
    return defaults


def main():
    if len(sys.argv) < 3:
        print('Usage: python3 scripts/with-env.py PATH_TO_ENV COMMAND [ARGS...]', file=sys.stderr)
        return 2
    try:
        environment = child_environment(Path(sys.argv[1]), os.environ)
        command = sys.argv[2:]
        os.execvpe(command[0], command, environment)
    except ValueError as error:
        print(str(error), file=sys.stderr)  # Line numbers only, never credential values.
    except OSError as error:
        print(f'Could not start the local command ({type(error).__name__})', file=sys.stderr)
    return 1


if __name__ == '__main__':
    sys.exit(main())
