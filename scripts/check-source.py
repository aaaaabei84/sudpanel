#!/usr/bin/env python3
"""Check the source tree for accidental runtime artifacts and personal paths.
This is a packaging guard, not proof that the source contains no secrets.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ALLOWED_ROOTS = {'src', 'frontend', 'tools', 'config', 'scripts', '.github', 'licenses'}
ALLOWED_FILES = {'README.md', 'LICENSE', 'SECURITY.md', 'THIRD_PARTY_NOTICES.md',
                 '.gitignore', '.gitattributes', '.gitleaks.toml', '.env.example', 'pom.xml'}
SKIP_DIRS = {'.git', 'node_modules', 'target', 'build', 'dist', '__pycache__',
             'data', 'logs', 'reports', 'artifacts', 'exports', 'profile', '.local-data'}
BAD_SUFFIXES = {'.db', '.sqlite', '.sqlite3', '.log', '.pem', '.key', '.p12', '.pfx',
                '.dmg', '.jar', '.zip', '.har', '.pcap', '.pcapng', '.hprof'}
PRIVATE_HOME = re.compile(r'(?:/Users/|/home/)[A-Za-z0-9_.-]+/')
PRIVATE_KEY = re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')

def source_files():
    for path in sorted(ROOT.rglob('*')):
        relative = path.relative_to(ROOT)
        if any(part in SKIP_DIRS or part.startswith('profile-') for part in relative.parts):
            continue
        if relative.parts[:4] == ('src','main','resources','static'):
            continue
        if path.is_symlink():
            raise ValueError(f'symlink is not allowed: {relative}')
        if path.is_file():
            yield path, relative

def main():
    failures=[]
    count=0
    for path, relative in source_files():
        count+=1
        if relative.parts[0] not in ALLOWED_ROOTS and str(relative) not in ALLOWED_FILES:
            failures.append(f'{relative}: unexpected top-level file')
        if path.suffix.lower() in BAD_SUFFIXES or '.db-' in path.name:
            failures.append(f'{relative}: runtime or sensitive file type')
        if path.name.startswith('.env') and path.name != '.env.example':
            failures.append(f'{relative}: environment file')
        if relative.parts[0]=='config' and path.name.endswith('.properties'):
            failures.append(f'{relative}: live configuration')
        try: text=path.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            failures.append(f'{relative}: unexpected binary; review before publication')
            continue
        for number,line in enumerate(text.splitlines(),1):
            if PRIVATE_HOME.search(line):
                failures.append(f'{relative}:{number}: personal absolute path')
            if PRIVATE_KEY.search(line):
                failures.append(f'{relative}:{number}: private-key block; review fixture provenance')
    print(f'Checked {count} source files; {len(failures)} findings.')
    for failure in failures: print(failure)
    return bool(failures)

if __name__=='__main__':
    try: sys.exit(main())
    except ValueError as error:
        print(error);sys.exit(1)
