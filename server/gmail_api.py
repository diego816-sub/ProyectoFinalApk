"""Gmail HTTPS transport. Secrets stay on the server; only gmail.send is needed."""
import base64
import json
import os
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def settings():
    keys = ('GMAIL_CLIENT_ID', 'GMAIL_CLIENT_SECRET', 'GMAIL_REFRESH_TOKEN', 'GMAIL_FROM')
    if any(not os.environ.get(k) for k in keys):
        raise RuntimeError('Configura la autorización Gmail API en las variables del servidor.')
    return {'from': os.environ['GMAIL_FROM']}, ''


def post(url, body, headers):
    with urlopen(Request(url, data=body, headers=headers, method='POST'), timeout=15) as response:
        return json.load(response)


def send(message):
    settings()
    token = post('https://oauth2.googleapis.com/token', urlencode({
        'client_id': os.environ['GMAIL_CLIENT_ID'], 'client_secret': os.environ['GMAIL_CLIENT_SECRET'],
        'refresh_token': os.environ['GMAIL_REFRESH_TOKEN'], 'grant_type': 'refresh_token'
    }).encode(), {'Content-Type': 'application/x-www-form-urlencoded'})
    result = post('https://gmail.googleapis.com/gmail/v1/users/me/messages/send',
        json.dumps({'raw': base64.urlsafe_b64encode(message.as_bytes()).decode()}).encode(),
        {'Content-Type': 'application/json', 'Authorization': 'Bearer '+token['access_token']})
    if not result.get('id'): raise RuntimeError('Gmail no confirmó el envío.')
