#!/usr/bin/env python3
"""Conservative public-source guard. Reports locations, never matched secrets."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PRIVATE_SUFFIXES = {'.jks', '.keystore', '.pem', '.key', '.p12', '.pfx', '.crt',
                    '.cer', '.db', '.sqlite', '.sqlite3', '.apk', '.aab', '.log',
                    '.zip', '.dmg', '.hprof'}
PRIVATE_PARTS = {'data', 'backups', 'uploads', 'logs', 'verification', '.ssh'}
PATTERNS = {
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----'),
    'model API key': re.compile(r'\bsk-[A-Za-z0-9_-]{20,}'),
    'GitHub token': re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})'),
    'cloud access key': re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b|\bLTAI[A-Za-z0-9]{16,}\b'),
    'Slack token': re.compile(r'\bxox[baprs]-[A-Za-z0-9-]{20,}'),
}
SECRET_ENV = re.compile(r'(?i)(?:API_KEY|ACCESS_KEY_ID|ACCESS_KEY_SECRET|AUTH_SECRET|TOKEN|TOKENS|PASSWORD|PASSWORD_HASH)$')

def main():
    result = subprocess.run(['git', 'ls-files', '-z'], cwd=ROOT, capture_output=True, check=True)
    names = [x.decode('utf-8') for x in result.stdout.split(b'\0') if x]
    errors = []
    for name in names:
        p = ROOT / name
        if (p.suffix.lower() in PRIVATE_SUFFIXES or set(Path(name).parts) & PRIVATE_PARTS
                or p.name in {'local.properties', 'release-identity.json', '.credentials'}
                or (p.name.startswith('.env') and p.name != '.env.example')):
            errors.append((name, 0, 'private file type or location'))
        if p.is_symlink():
            errors.append((name, 0, 'symlink requires explicit review'))
            continue
        try:
            content = p.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            # Only reviewed artwork and the Gradle wrapper are expected binaries.
            if name not in {'app/src/main/res/drawable-nodpi/motuan.png',
                            'macos/Web/motuan.png', 'gradle/wrapper/gradle-wrapper.jar'}:
                errors.append((name, 0, 'unreviewed binary file'))
            continue
        for line_no, line in enumerate(content.splitlines(), 1):
            for label, pattern in PATTERNS.items():
                if pattern.search(line):
                    errors.append((name, line_no, label))
            if p.name == '.env.example' and not line.lstrip().startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                if SECRET_ENV.search(key.strip()) and value.strip():
                    errors.append((name, line_no, 'secret example must be empty'))
    for name, line, reason in errors:
        print(f'{name}:{line}: {reason}')
    if errors:
        print(f'Public-source check failed: {len(errors)} finding(s).')
        return 1
    print(f'Public-source check passed: {len(names)} tracked files.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
