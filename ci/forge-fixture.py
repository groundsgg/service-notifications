#!/usr/bin/env python3
"""Bounded Forge HTTP contracts used by the packaged Notifications test."""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        status, response = 200, {}
        if self.headers.get('Authorization') != 'Bearer ' + os.environ['FORGE_TOKEN']:
            status = 401
        elif self.path == '/v1/internal/minecraft-identities/resolve':
            response = {'mappings': [{'playerUuid': p, 'userId': 'native-user-proxy-b'}
                                     for p in sorted(body['playerUuids'])]}
        elif self.path == '/v1/internal/notification-audiences/resolve':
            assert body['match'] == 'ALL'
            assert {'MODERATION_NOTIFICATIONS_VIEW', 'MODERATION_REPORTS_VIEW'} <= set(body['permissionKeys'])
            response = {'recipients': [{'userId': 'native-user-proxy-b'}],
                        'resolvedAt': '2026-11-02T00:00:00Z', 'resolutionFingerprint': 'a' * 64}
        elif self.path == '/v1/internal/notification-actions/project-invites/native-invite/accept':
            if body['userId'] != 'native-user-proxy-b' or not body['notificationId'] or not body['requestId']:
                status = 403
        else:
            status = 404
        data = json.dumps(response).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


ThreadingHTTPServer(('0.0.0.0', 8080), Handler).serve_forever()
