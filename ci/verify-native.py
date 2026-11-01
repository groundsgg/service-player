#!/usr/bin/env python3
"""Exercise native Player joins and cross-proxy state using local PostgreSQL and NATS."""
import json
import base64
import http.server
import pathlib
import socket
import tempfile
import threading
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def docker(*args, check=True):
    return subprocess.run(['docker', *args], check=check, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout.strip()


TOKENS = {}

def nats_request(port, subject, body):
    with socket.create_connection(('127.0.0.1', int(port)), timeout=5) as connection:
        reader = connection.makefile('rb')
        assert reader.readline().startswith(b'INFO ')
        body = json.dumps(body).encode()
        connection.sendall(b'CONNECT {"verbose":false}\r\nSUB _INBOX.fixture 1\r\nPUB ' + subject.encode() + b' _INBOX.fixture ' + str(len(body)).encode() + b'\r\n' + body + b'\r\n')
        while True:
            line = reader.readline()
            if line == b'PING\r\n':
                connection.sendall(b'PONG\r\n')
            elif line.startswith(b'MSG '):
                response = json.loads(reader.read(int(line.split()[-1])))
                assert 'error' not in response, response
                return response

def create_player_stream(port):
    return nats_request(port, '$JS.API.STREAM.CREATE.PLAYER_EVENTS', {'name': 'PLAYER_EVENTS', 'subjects': ['player.name.seen.*', 'player.session.server-changed.*', 'player.session.ended.*']})

def await_writer_idle(port):
    for _ in range(60):
        state = nats_request(port, '$JS.API.CONSUMER.INFO.PLAYER_EVENTS.player-writer', {})
        if state['num_pending'] == 0 and state['num_ack_pending'] == 0:
            return
        time.sleep(0.1)
    raise AssertionError('Player event consumer did not finish pending writes')

def request(base, path, body=None, method=None, authenticated=True):
    data = json.dumps(body).encode() if body is not None else None
    headers = {'Content-Type': 'application/json'}
    if authenticated:
        headers['Authorization'] = 'Bearer ' + TOKENS[base]
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            payload = response.read()
            return response.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        payload = error.read()
        return error.code, json.loads(payload) if payload else None



class WorkloadIssuer:
    def __init__(self):
        self.directory = tempfile.TemporaryDirectory()
        self.key = pathlib.Path(self.directory.name) / 'fixture.pem'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:2048', '-out', str(self.key)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        modulus = subprocess.check_output(['openssl', 'rsa', '-in', str(self.key), '-noout', '-modulus'], text=True).strip().split('=', 1)[1]
        self.keys = {'keys': [{'kty': 'RSA', 'use': 'sig', 'alg': 'RS256', 'kid': 'native-player-fixture', 'e': 'AQAB', 'n': self.encode(bytes.fromhex(modulus))}]}
        keys = self.keys
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                body = json.dumps(keys).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        self.server = http.server.ThreadingHTTPServer(('0.0.0.0', 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @staticmethod
    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b'=').decode()

    def token(self, proxy, audience='grounds-services'):
        now = int(time.time())
        header = self.encode(json.dumps({'alg': 'RS256', 'kid': 'native-player-fixture'}).encode())
        claims = self.encode(json.dumps({'iss': 'https://native-player-fixture.invalid', 'sub': 'system:serviceaccount:test:' + proxy, 'aud': audience, 'iat': now, 'exp': now + 600}).encode())
        unsigned = (header + '.' + claims).encode()
        signature = subprocess.run(['openssl', 'dgst', '-sha256', '-sign', str(self.key)], input=unsigned, stdout=subprocess.PIPE, check=True).stdout
        return unsigned.decode() + '.' + self.encode(signature)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.directory.cleanup()


def main():
    image = os.environ.get('NATIVE_IMAGE_PREFIX', 'grounds/service-player') + ':native-micro-pilot'
    suffix = uuid.uuid4().hex[:12]
    network, database, nats, first, second = (f'player-native-{part}-{suffix}' for part in ('net', 'db', 'nats', 'a', 'b'))
    issuer = WorkloadIssuer()
    try:
        docker('network', 'create', network)
        docker('run', '-d', '--name', database, '--network', network, '-e', 'POSTGRES_USER=app', '-e', 'POSTGRES_PASSWORD=app', '-e', 'POSTGRES_DB=app', 'postgres:17-alpine')
        for _ in range(90):
            if subprocess.run(['docker', 'exec', database, 'pg_isready', '-U', 'app'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
                break
            time.sleep(1)
        else:
            raise AssertionError('PostgreSQL did not start')
        docker('run', '-d', '--name', nats, '--network', network, '-p', '127.0.0.1::4222', 'nats:2.12.1-alpine', '-js')
        nats_port = docker('port', nats, '4222/tcp').rsplit(':', 1)[1]
        for _ in range(30):
            try:
                create_player_stream(nats_port)
                break
            except (OSError, ConnectionError):
                time.sleep(1)
        else:
            raise AssertionError('JetStream did not start')
        url = f'jdbc:postgresql://{database}:5432/app?currentSchema=player'
        env = {'POSTGRES_URL': url, 'POSTGRES_DIRECT_URL': url, 'POSTGRES_USER': 'app', 'POSTGRES_PASSWORD': 'app', 'NATS_URL': f'nats://{nats}:4222', 'GROUNDS_AUTH_ENABLED': 'true', 'GROUNDS_AUTH_JWKS_URL': f'http://host.docker.internal:{issuer.server.server_port}/jwks', 'GROUNDS_AUTH_K8S_CA_FILE': '/fixture/no-cluster-ca', 'PLAYER_SETTINGS_CACHE_ENABLED': 'true', 'PLAYER_ROLE': 'all'}
        env.update({'PLAYER_ASYNC_WRITES': 'true', 'PLAYER_ASYNC_LOGOUT': 'true', 'PLAYER_EVENTS_CONSUMER_ENABLED': 'true'})
        bases = []
        for name in (first, second):
            args = ['run', '-d', '--name', name, '--network', network, '--add-host', 'host.docker.internal:host-gateway', '-p', '127.0.0.1::9000']
            for key, value in env.items():
                args.extend(['-e', f'{key}={value}'])
            docker(*args, image)
            port = docker('port', name, '9000/tcp').rsplit(':', 1)[1]
            base = f'http://127.0.0.1:{port}'
            TOKENS[base] = issuer.token('proxy-a' if name == first else 'proxy-b')
            for _ in range(90):
                try:
                    status, health = request(base, '/q/health/ready')
                    if status == 200 and health['status'] == 'UP':
                        break
                except OSError:
                    pass
                if docker('inspect', '-f', '{{.State.Running}}', name) != 'true':
                    raise AssertionError('native service exited before readiness')
                time.sleep(1)
            else:
                raise AssertionError('native service not ready')
            bases.append(base)
        a, b = bases
        status, _ = request(a, '/v1/players/counts/proxies', authenticated=False)
        assert status == 401, status
        valid = TOKENS[b]
        TOKENS[b] = issuer.token('proxy-b', audience='wrong-audience')
        status, _ = request(b, '/v1/players/counts/proxies')
        assert status == 401, status
        TOKENS[b] = valid
        player, source, destination = (str(uuid.uuid4()) for _ in range(3))
        claim = {'playerId': player, 'sessionId': source, 'playerName': 'NativeFixture', 'proxyId': 'proxy-a', 'region': 'eu'}
        status, result = request(a, '/v2/players/sessions', claim)
        assert status == 201 and result['playerId'] == player and result['sessionId'] == source and result.get('leaseExpiresAt'), (status, result)
        # The regional async name event must be consumed before directory lookup succeeds.
        for _ in range(30):
            status, result = request(b, '/v1/players/names/known?name=NativeFixture')
            assert status == 200, (status, result)
            if result['players'] == [{'playerId': player, 'playerName': 'NativeFixture'}]:
                break
            time.sleep(0.5)
        else:
            raise AssertionError(f'NameSeen event not applied: {status}, {result}')
        status, result = request(a, '/v1/players/names/known?prefix=Native')
        assert status == 200 and result['players'] == [{'playerId': player, 'playerName': 'NativeFixture'}], (status, result)
        status, result = request(a, '/v1/players/names/known?name=UnknownFixture')
        assert status == 200 and result['players'] == [], (status, result)
        status, _ = request(a, f'/v2/players/{player}/session/server', {'sessionId': source, 'serverName': 'lobby-fixture'}, 'PUT')
        assert status == 204, status
        for _ in range(30):
            status, result = request(b, f'/v1/players/{player}/session')
            if status == 200 and result['serverName'] == 'lobby-fixture':
                break
            time.sleep(0.5)
        else:
            raise AssertionError(f'ServerChanged event not applied: {status}, {result}')
        status, result = request(b, f'/v1/players/{player}/session')
        assert status == 200 and result['proxyId'] == 'proxy-a', (status, result)
        duplicate = dict(claim, sessionId=destination, proxyId='proxy-b')
        status, _ = request(b, '/v2/players/sessions', duplicate)
        assert status == 409, status
        status, result = request(b, f'/v1/players/{player}/locale')
        assert status == 200 and result['locale'] is None, (status, result)
        # The scheduled subscriber intentionally starts after a one-minute delay.
        for _ in range(90):
            if all('Subscribed to settings invalidations' in docker('logs', name) for name in (first, second)):
                break
            time.sleep(1)
        else:
            raise AssertionError('both native instances did not subscribe to settings invalidations')
        status, result = request(b, f'/v1/players/{player}/locale')
        assert status == 200 and result['locale'] is None, (status, result)
        status, _ = request(a, f'/v1/players/{player}/locale', {'locale': 'de-DE'}, 'PUT')
        assert status == 204, status
        for _ in range(20):
            status, result = request(b, f'/v1/players/{player}/locale')
            if status == 200 and result['locale'] == 'de-DE':
                break
            time.sleep(0.25)
        else:
            raise AssertionError(f'remote cached locale was not invalidated: {status}, {result}')
        status, handoff = request(a, f'/v2/players/{player}/session/transfer', {'sessionId': source})
        assert status == 201 and handoff.get('token'), (status, handoff)
        status, result = request(b, '/v2/players/sessions/transfer', {'playerId': player, 'sessionId': destination, 'handoffToken': handoff['token'], 'proxyId': 'proxy-b', 'region': 'eu'})
        assert status == 201 and result['sessionId'] == destination, (status, result)
        status, _ = request(a, '/v2/players/sessions/transfer', {'playerId': player, 'sessionId': str(uuid.uuid4()), 'handoffToken': handoff['token'], 'proxyId': 'proxy-a', 'region': 'eu'})
        assert status == 409, status
        status, result = request(a, f'/v1/players/{player}/session')
        assert status == 200 and result['proxyId'] == 'proxy-b', (status, result)
        status, result = request(b, '/v2/players/sessions/heartbeats', {'sessions': [{'playerId': player, 'sessionId': destination}]})
        assert status == 200 and result['updated'] == 1 and result['missing'] == 0, (status, result)
        status, _ = request(a, f'/v2/players/{player}/session?sessionId={source}', method='DELETE')
        # Async logout acknowledges queueing; the writer must fence the stale session.
        assert status == 204, status
        await_writer_idle(nats_port)
        status, result = request(a, f'/v1/players/{player}/session')
        assert status == 200 and result['proxyId'] == 'proxy-b', (status, result)
        status, _ = request(b, f'/v2/players/{player}/session?sessionId={destination}', method='DELETE')
        assert status == 204, status
        for _ in range(30):
            status, _ = request(a, f'/v1/players/{player}/session')
            if status == 404:
                break
            time.sleep(0.5)
        else:
            raise AssertionError('SessionEnded event did not remove the active session')
        # A real disconnect must permit a subsequent login on either proxy.
        reconnect = dict(claim, sessionId=str(uuid.uuid4()), proxyId='proxy-b')
        status, result = request(b, '/v2/players/sessions', reconnect)
        assert status == 201 and result['sessionId'] == reconnect['sessionId'], (status, result)
        await_writer_idle(nats_port)
        for name in (first, second):
            logs = docker('logs', name)
            assert 'ClassNotFoundException' not in logs and ' ERROR ' not in logs and 'KotlinReflectionInternalError' not in logs, 'native runtime failed'
        print('Native Player: JWT verification, authenticated cross-proxy claim, one-use handoff, global presence, NATS settings invalidation and stale logout passed')
    except BaseException:
        for name in (first, second):
            print(docker('logs', name, check=False))
        raise
    finally:
        for name in (first, second, nats, database):
            docker('rm', '-f', name, check=False)
        docker('network', 'rm', network, check=False)
        issuer.close()


if __name__ == '__main__':
    main()
