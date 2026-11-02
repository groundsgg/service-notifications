#!/usr/bin/env python3
"""Exercise the packaged production service against real Postgres and authenticated NATS.

The only fake is the external Forge HTTP boundary; no test profile or in-process service
is used. Runtime credentials live in private temporary files, never Docker arguments.
"""
import base64
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent.parent
USER = 'native-user-proxy-b'


def docker(*args, data=None, check=True):
    result = subprocess.run(['docker', *args], input=data, check=check,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return result.stdout.strip()


def wait_for(check, description, timeout=65):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            result = check()
            if result:
                return result
        except (OSError, AssertionError, ValueError) as error:
            last = error
        time.sleep(.5)
    raise AssertionError(f'Timed out: {description}; last error: {last}')


def http(base, path, token=None, body=None, status=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(base + path, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
    try:
        response = urllib.request.urlopen(request, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        content = response.read()
        assert response.status == status, f'{path}: expected {status}, got {response.status}'
        return json.loads(content) if content else None


def jwt(subject=USER, audience='notifications-native', issuer='https://native.example.invalid'):
    def encoded(value):
        return base64.urlsafe_b64encode(value).rstrip(b'=')
    payload = {'sub': subject, 'iss': issuer, 'aud': audience, 'iat': int(time.time()),
               'exp': int(time.time()) + 900, 'groups': ['NOTIFICATIONS_MANAGE']}
    signing = encoded(b'{"alg":"RS256","typ":"JWT"}') + b'.' + encoded(json.dumps(payload).encode())
    signature = subprocess.check_output(['openssl', 'dgst', '-sha256', '-sign',
                                         str(ROOT / 'src/test/resources/jwt/privateKey.pem')], input=signing)
    return (signing + b'.' + encoded(signature)).decode()


class Nats:
    def __init__(self, port, token):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=5)
        self.stream = self.socket.makefile('rb')
        assert self.stream.readline().startswith(b'INFO ')
        self.socket.sendall(b'CONNECT ' + json.dumps({'auth_token': token, 'verbose': False}).encode() + b'\r\n')

    def request(self, subject, payload):
        inbox = '_INBOX.' + uuid.uuid4().hex
        data = json.dumps(payload).encode()
        self.socket.sendall(f'SUB {inbox} 1\r\nUNSUB 1 1\r\nPUB {subject} {inbox} {len(data)}\r\n'.encode() + data + b'\r\n')
        while True:
            line = self.stream.readline()
            if line == b'PING\r\n':
                self.socket.sendall(b'PONG\r\n')
            elif line.startswith(b'MSG '):
                size = int(line.split()[-1])
                result = json.loads(self.stream.read(size))
                self.stream.read(2)
                assert 'error' not in result, result
                return result
            elif line.startswith(b'-ERR') or not line:
                raise AssertionError('NATS request rejected')

    def close(self):
        self.stream.close()
        self.socket.close()


def main():
    image = os.environ.get('NOTIFICATIONS_IMAGE', os.environ.get('NATIVE_IMAGE_PREFIX', 'grounds/service-notifications') + ':native-micro-pilot')
    network = 'notifications-contract-' + uuid.uuid4().hex[:10]
    containers = []
    nats = None
    with tempfile.TemporaryDirectory(prefix='notifications-native-contract-') as temporary:
        directory = Path(temporary)
        directory.chmod(0o700)
        def file(name, content, mode=0o600):
            path = directory / name
            path.write_text(content)
            path.chmod(mode)
            return str(path)
        def run(name, *args):
            containers.append(name)
            return docker('run', '-d', '--name', name, '--network', network, *args)
        def port(name, internal):
            return docker('port', name, str(internal) + '/tcp').rsplit(':', 1)[1]
        db_password, nats_token, forge_token = (secrets.token_urlsafe(24) for _ in range(3))
        try:
            docker('network', 'create', network)
            db, broker, forge = network + '-db', network + '-nats', network + '-forge'
            run(db, '--network-alias', 'db', '--env-file', file('postgres.env', f'POSTGRES_PASSWORD={db_password}\nPOSTGRES_DB=notifications\n'), 'postgres:17-alpine')
            wait_for(lambda: 'accepting connections' in docker('exec', db, 'pg_isready', '-U', 'postgres', check=False), 'Postgres')
            config = file('nats.conf', f'jetstream {{ store_dir: /data }}\nauthorization {{ token: "{nats_token}" }}\n')
            run(broker, '--network-alias', 'nats', '-p', '127.0.0.1::4222', '-v', config + ':/etc/nats/native.conf:ro', 'nats:2-alpine', '-c', '/etc/nats/native.conf')
            def connect_nats():
                return Nats(int(port(broker, 4222)), nats_token)
            nats = wait_for(connect_nats, 'NATS')
            bindings = [('MODERATION', 'moderation.report.ready-for-review'),
                        ('MODERATION_READY_STAGE_V2', 'moderation-v2.stage.report.ready-for-review'),
                        ('MODERATION_READY_PROD_V2', 'moderation-v2.prod.report.ready-for-review')]
            for stream, subject in bindings:
                nats.request('$JS.API.STREAM.CREATE.' + stream, {'name': stream, 'subjects': [subject], 'storage': 'memory'})
            run(forge, '--network-alias', 'forge', '--env-file', file('forge.env', f'FORGE_TOKEN={forge_token}\n'),
                '-v', str(ROOT / 'ci/forge-fixture.py') + ':/fixture.py:ro', 'python:3.13-alpine', 'python', '/fixture.py')
            public = file('publicKey.pem', (ROOT / 'src/test/resources/jwt/publicKey.pem').read_text(), 0o644)
            # NATS token-file is shared by both the moderation consumers and live event bus.
            token_file = file('nats.token', nats_token, 0o644)
            environment = file('application.env', '\n'.join([
                'QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://db:5432/notifications',
                'QUARKUS_DATASOURCE_USERNAME=postgres', f'QUARKUS_DATASOURCE_PASSWORD={db_password}',
                'NOTIFICATIONS_JWT_ISSUER=https://native.example.invalid',
                'NOTIFICATIONS_JWT_PUBLIC_KEY_LOCATION=/fixtures/publicKey.pem',
                'NOTIFICATIONS_JWT_AUDIENCES=notifications-native',
                'NOTIFICATIONS_FORGE_BASE_URL=http://forge:8080', f'NOTIFICATIONS_FORGE_INTERNAL_TOKEN={forge_token}',
                'NATS_URL=nats://nats:4222', 'NATS_TOKEN_FILE=/fixtures/nats.token',
                'NOTIFICATIONS_NATS_AUTHENTICATION_MODE=token-file',
                'NOTIFICATIONS_MODERATION_PROJECTOR_ENABLED=true',
                'NOTIFICATIONS_MODERATION_PROJECTOR_STAGE_ENABLED=true',
                'NOTIFICATIONS_MODERATION_PROJECTOR_PROD_ENABLED=true',
            ]) + '\n')
            bases = []
            for suffix in ('a', 'b'):
                name = network + '-' + suffix
                run(name, '--env-file', environment, '-p', '127.0.0.1::8080',
                    '-v', public + ':/fixtures/publicKey.pem:ro', '-v', token_file + ':/fixtures/nats.token:ro', image)
                base = 'http://127.0.0.1:' + port(name, 8080)
                wait_for(lambda: http(base, '/q/health/ready')['status'] == 'UP', 'ready instance ' + suffix)
                bases.append(base)
            a, b = bases
            token = jwt()
            for invalid in (None, jwt(audience='wrong'), jwt(issuer='https://wrong.invalid')):
                http(a, '/v1/notifications', invalid, status=401)
            http(a, '/v1/notifications?userId=someone-else', token, status=400)
            producer, minecraft = (secrets.token_urlsafe(24) for _ in range(2))
            def sql(statement):
                return docker('exec', '-i', db, 'psql', '-U', 'postgres', '-d', 'notifications', '-At', '-v', 'ON_ERROR_STOP=1', data=statement)
            for channel, secret, scopes, server in [('api', producer, ['notifications:write'], None),
                    ('minecraft', minecraft, ['minecraft.notifications.read', 'minecraft.notifications.action'], 'remote-proxy-b')]:
                server_sql = "'" + server + "'" if server else 'NULL'
                sql(f"INSERT INTO notification_channel_clients(id,channel,token_hash,scopes,server_id) VALUES ('{uuid.uuid4()}','{channel}','{hashlib.sha256(secret.encode()).hexdigest()}',ARRAY[{','.join(repr(s) for s in scopes)}],{server_sql});")
            request = {'idempotencyKey': 'native-contract', 'type': 'PROJECT_INVITE', 'category': 'PROJECT',
                       'priority': 'NORMAL', 'scope': {'type': 'server', 'id': 'remote-proxy-b'},
                       'actor': {'type': 'system'}, 'title': 'Native invitation', 'body': 'Populated contract',
                       'data': {'nested': {'proxy': 'b'}}, 'recipients': [{'userId': USER}],
                       'actions': [{'actionKey': 'accept', 'label': 'Accept', 'style': 'primary',
                                    'command': 'project_invite.accept', 'payload': {'inviteId': 'native-invite'}}]}
            http(a, '/v1/notification-events', body=request, status=401)
            created = http(a, '/v1/notification-events', producer, request, 201)
            notification = created['id']
            assert created['created'] is True
            duplicate = http(b, '/v1/notification-events', producer, request)
            assert duplicate == {'id': notification, 'created': False}
            item = http(b, '/v1/notifications', token)['items'][0]
            assert item['id'] == notification and item['recipientUserId'] == USER
            assert item['data']['nested']['proxy'] == 'b' and item['actions'][0]['actionKey'] == 'accept'
            assert http(b, '/v1/notifications', jwt('other-proxy-user'))['items'] == []
            player = str(uuid.uuid4())
            batch = http(b, '/v1/channel/minecraft/notifications/batch', minecraft,
                         {'serverId': 'remote-proxy-b', 'playerUuids': [player]})
            assert batch['players'][0]['playerUuid'] == player
            assert batch['players'][0]['notifications'][0]['id'] == notification
            http(b, '/v1/channel/minecraft/notifications/batch', minecraft,
                 {'serverId': 'wrong-proxy', 'playerUuids': [player]}, 403)
            http(a, f'/v1/notifications/{notification}/read', jwt('other-proxy-user'), {}, 404)
            # Connect to instance B while instance A publishes; real NATS performs the fanout.
            def live_read():
                req = urllib.request.Request(b + '/v1/notifications/live', headers={'Authorization': 'Bearer ' + token})
                with urllib.request.urlopen(req, timeout=35) as response:
                    for line in response:
                        if line.startswith(b'data:'):
                            event = json.loads(line[5:])
                            if event.get('reason') == 'read' and event.get('notificationId') == notification:
                                return event
                raise AssertionError('No cross-instance live event')
            with concurrent.futures.ThreadPoolExecutor() as executor:
                future = executor.submit(live_read)
                time.sleep(1)
                http(a, f'/v1/notifications/{notification}/read', token, {}, 204)
                event = future.result(timeout=40)
                assert event['userId'] == USER
            assert http(b, '/v1/notifications', token)['items'][0]['readAt'] is not None
            http(a, f'/v1/notifications/{notification}/unread', token, {}, 204)
            action = http(b, f'/v1/channel/minecraft/notifications/{notification}/actions/accept', minecraft,
                          {'serverId': 'remote-proxy-b', 'playerUuid': player})
            assert action['status'] == 'succeeded'
            assert http(a, f'/v1/notifications/{notification}/actions/accept', token, {})['status'] == 'succeeded'
            assert sql('SELECT count(*) FROM notification_action_results;') == '1'
            for index, (stream, subject) in enumerate(bindings):
                environment_name = (None, 'stage', 'prod')[index]
                case_id = str(uuid.uuid4())
                payload = {'eventId': str(uuid.uuid4()), 'eventType': 'moderation.report.ready-for-review',
                           'schemaVersion': 1 if index == 0 else 2, 'occurredAt': '2026-11-02T00:00:00Z',
                           'data': {'reportId': str(uuid.uuid4()), 'caseId': case_id,
                                    'evidenceCaptureId': str(uuid.uuid4()), 'captureStatus': 'COMPLETE', 'caseRevision': 1}}
                if environment_name:
                    payload['data']['environment'] = environment_name
                nats.request(subject, payload)
                def projected():
                    return next((i for i in http(a, '/v1/notifications', token)['items']
                                 if i['type'] == 'MODERATION_CASE_READY'
                                 and i['actions'][0]['entityId'] == case_id), None)
                projected_item = wait_for(projected, 'moderation ' + stream)
                assert projected_item['actions'][0]['kind'] == 'OPEN_PORTAL_CASE'
                assert projected_item['actions'][0].get('environment') == environment_name
                http(a, f"/v1/notifications/{projected_item['id']}/actions/open_case", token, {}, 409)
                nats.request(subject, payload)
            assert len(http(b, '/v1/notifications', token)['items']) == 4
            for path in ('channel-clients', 'recipients', 'action-results'):
                assert http(a, '/v1/admin/notifications/' + path, token)['items'], path
            wait_for(lambda: sql("SELECT count(*) FROM notification_outbox WHERE status != 'processed';") == '0', 'outbox delivery')
            print('Packaged Notifications contracts passed: JWT issuer/audience/identity, producer auth/idempotency, populated API, remote Minecraft identity/scope/action, persisted read/unread, cross-instance NATS live delivery, v1/Stage v2/Production v2 moderation projections, admin lists and outbox processing')
        except BaseException:
            # Logs never include the private environment files or generated credential values.
            for name in containers:
                if name.endswith(('-a', '-b', '-forge')):
                    logs = docker('logs', '--tail', '100', name, check=False)
                    for secret in (db_password, nats_token, forge_token):
                        logs = logs.replace(secret, '[redacted]')
                    print(name + '\n' + logs)
            raise
        finally:
            if nats:
                nats.close()
            for name in reversed(containers):
                docker('rm', '-f', name, check=False)
            docker('network', 'rm', network, check=False)


if __name__ == '__main__':
    main()
