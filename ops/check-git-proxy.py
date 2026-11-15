"""Check the Caddy Git route without reloading the installed proxy.

Given Caddy, Git and Python 3 on this machine, start isolated loopback fixtures.
Verify read-only paths, headers, redirects, gzip, size limits and real GitHub reads.
Temporary processes and the PR fetch repository are removed when the check ends.
Run from the checkout with: python3 ops/check-git-proxy.py
"""

import concurrent.futures
import contextlib
import gzip
import http.client
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time

TEMPLATE = """{
    admin off
    auto_https off
    persist_config off
    servers {
        timeouts {
            read_body 30s
        }
    }
}
""" + Path(__file__).with_name('git-proxy.caddy').read_text().replace(
    'https://github.com', '__UPSTREAM__') + """
__LISTEN__ {
    import ignite_git_proxy
    respond 404
}
"""


def request(port, method, path, body=None, headers=None):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=20)
    try:
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()
    finally:
        connection.close()


@contextlib.contextmanager
def proxy(upstream):
    with socket.socket() as allocation:
        allocation.bind(('127.0.0.1', 0))
        port = allocation.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix='ignite-prc-caddy-readonly-review-') as directory:
        config = Path(directory, 'Caddyfile')
        config.write_text(TEMPLATE.replace('__LISTEN__', 'http://127.0.0.1:' + str(port))
            .replace('__UPSTREAM__', upstream))
        validation = subprocess.run(['caddy', 'validate', '--config', str(config), '--adapter', 'caddyfile'],
            text=True, capture_output=True)
        assert validation.returncode == 0, validation.stderr[-2500:]
        with Path(directory, 'caddy.log').open('w+') as log:
            process = subprocess.Popen(['caddy', 'run', '--config', str(config), '--adapter', 'caddyfile'],
                stdout=log, stderr=log)
            try:
                deadline = time.monotonic() + 10
                while True:
                    try:
                        assert request(port, 'GET', '/_readiness')[0] == 404
                        break
                    except (OSError, http.client.HTTPException):
                        if process.poll() is not None or time.monotonic() >= deadline:
                            log.seek(0)
                            raise RuntimeError(log.read()[-2500:])
                        time.sleep(0.1)
                yield port, Path(directory)
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


class Fixture(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    calls = []
    mode = 'echo'

    def log_message(self, *_):
        pass

    def do_GET(self):
        self.respond()

    def do_POST(self):
        self.respond()

    def respond(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        type(self).calls.append({'path': self.path, 'headers': dict(self.headers), 'body': body})
        self.send_response(302 if self.mode == 'redirect' else 200)
        self.send_header('Content-Type', 'application/x-git-upload-pack-result')
        self.send_header('Set-Cookie', 'fixture-cookie=not-secret')
        self.send_header('WWW-Authenticate', 'Basic realm=fixture')
        if self.mode == 'redirect':
            self.send_header('Location', '/redirect-target')
        self.send_header('Content-Length', '2')
        self.end_headers()
        self.wfile.write(b'OK')


report = {'productionChanged': False, 'newPublicListener': False}
fixture = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
thread = threading.Thread(target=fixture.serve_forever, daemon=True)
thread.start()
try:
    with proxy('http://127.0.0.1:' + str(fixture.server_port)) as (port, _):
        status_response = request(port, 'GET', '/api/git-proxy/status')
        status = json.loads(status_response[2])
        assert status_response[0] == 200 and status['enabled'] is True and status['engine'] == 'caddy'
        assert len(status['repositories']) == 6
        report['configuredRouteStatus'] = True
        refs = '/git/ignite.git/info/refs?service=git-upload-pack'
        response = request(port, 'GET', refs, headers={'Git-Protocol': 'version=2',
            'Authorization': 'Bearer fixture-not-secret', 'Cookie': 'fixture=not-secret',
            'Proxy-Authorization': 'Basic fixture-not-secret', 'X-Github-Token': 'fixture-not-secret'})
        assert response[0] == 200
        received = {k.lower(): v for k, v in Fixture.calls[-1]['headers'].items()}
        assert not set(received).intersection({'authorization', 'cookie', 'proxy-authorization', 'x-github-token'})
        assert received.get('git-protocol') == 'version=2'
        assert received.get('host') == 'github.com'
        assert Fixture.calls[-1]['path'] == '/apache/ignite.git/info/refs?service=git-upload-pack'
        assert not {k.lower() for k in response[1]}.intersection({'set-cookie', 'www-authenticate'})
        report['credentialAndPathGuards'] = True
        count = len(Fixture.calls)
        blocked = [('GET', '/git/ignite.git/info/refs?service=git-receive-pack'),
            ('GET', refs + '&extra=1'), ('GET', refs + '&service=git-upload-pack'),
            ('POST', '/git/ignite.git/git-receive-pack'), ('PUT', '/git/ignite.git/git-upload-pack'),
            ('GET', '/git/unknown.git/info/refs?service=git-upload-pack'),
            ('GET', '/git/ignite.git/config')]
        for method, path in blocked:
            assert 400 <= request(port, method, path)[0] < 500, (method, path)
        assert len(Fixture.calls) == count
        report['writeAndUnknownPathGuards'] = len(blocked)
        encoded = gzip.compress(b'fixture compressed negotiation')
        response = request(port, 'POST', '/git/ignite.git/git-upload-pack', body=encoded,
            headers={'Content-Type': 'application/x-git-upload-pack-request', 'Content-Encoding': 'gzip'})
        assert response[0] == 200
        assert Fixture.calls[-1]['body'] == encoded
        assert Fixture.calls[-1]['headers'].get('Content-Encoding') == 'gzip'
        report['gzipBytesPreserved'] = True
        Fixture.mode = 'redirect'
        response = request(port, 'GET', refs)
        assert response[0] == 502 and 'Location' not in response[1]
        assert not any(x['path'] == '/redirect-target' for x in Fixture.calls)
        report['redirectRejected'] = True
        count = len(Fixture.calls)
        connection = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
        connection.putrequest('POST', '/git/ignite.git/git-upload-pack')
        connection.putheader('Content-Type', 'application/x-git-upload-pack-request')
        connection.putheader('Content-Length', str(8 * 1024 * 1024 + 1))
        connection.endheaders()
        assert connection.getresponse().status == 413
        connection.close()
        assert len(Fixture.calls) == count
        report['declaredBodyLimit'] = True
finally:
    fixture.shutdown()
    fixture.server_close()
    thread.join(timeout=5)

environment = dict(os.environ, GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL='/dev/null',
    GIT_TERMINAL_PROMPT='0', GIT_ASKPASS='/bin/false')
with proxy('https://github.com') as (port, directory):
    def git(*args):
        return subprocess.run(['git', '-c', 'credential.helper=', *args], cwd=directory,
            env=environment, text=True, capture_output=True, timeout=120)

    def read(slug):
        result = git('-c', 'protocol.version=2', 'ls-remote',
            'http://127.0.0.1:' + str(port) + '/git/' + slug + '.git', 'refs/heads/master')
        assert result.returncode == 0, (slug, result.stderr)
        return {'slug': slug, 'sha': result.stdout.split()[0]}

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        report['parallelNativeReads'] = list(pool.map(read, ['ignite', 'ignite-python-thin-client',
            'ignite-php-thin-client', 'ignite-nodejs-thin-client', 'ignite-release', 'ignite-extensions']))
    url = 'http://127.0.0.1:' + str(port) + '/git/ignite.git'
    report['otherGitProtocols'] = []
    for protocol in (0, 1):
        result = git('-c', 'protocol.version=' + str(protocol), 'ls-remote', url, 'refs/pull/13834/head')
        assert result.returncode == 0, result.stderr
        report['otherGitProtocols'].append({'protocol': protocol, 'prSha': result.stdout.split()[0]})
    assert git('init', '--bare', 'fetch-probe.git').returncode == 0
    result = git('-C', 'fetch-probe.git', 'fetch', '--depth=1', '--quiet', url, 'refs/pull/13834/head')
    assert result.returncode == 0, result.stderr
    result = git('-C', 'fetch-probe.git', 'rev-parse', 'FETCH_HEAD')
    assert result.returncode == 0
    report['nativePrFetchSha'] = result.stdout.strip()
    result = git('-C', 'fetch-probe.git', 'push', '--dry-run', url, 'FETCH_HEAD:refs/heads/proxy-no-write-probe')
    assert result.returncode != 0 and ('403' in result.stderr or '404' in result.stderr), result.stderr
    report['nativePushDryRunRejected'] = True
report['isolatedProcessesAndRepositoriesRemoved'] = True
print(json.dumps(report, indent=2), flush=True)
