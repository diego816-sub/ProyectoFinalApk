"""Run locally, once: authorize only sending mail and save the refresh token privately."""
import argparse
import base64
import hashlib
import json
import secrets
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import Request, urlopen


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--client',required=True,help='JSON del cliente OAuth de tipo aplicación de escritorio')
    p.add_argument('--config',default='cloud_config.json')
    args=p.parse_args()
    client=json.loads(Path(args.client).read_text(encoding='utf-8'))['installed']
    path=Path(args.config)
    config=json.loads(path.read_text(encoding='utf-8-sig'))
    state=secrets.token_urlsafe(32);verifier=secrets.token_urlsafe(64)
    challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
    outcome={}
    class Callback(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_GET(self):
            query=parse_qs(urlsplit(self.path).query)
            valid=secrets.compare_digest(query.get('state',[''])[0],state)
            if valid:
                outcome.update(code=query.get('code',[''])[0],error=query.get('error',[''])[0])
            self.send_response(200 if valid else 400)
            self.send_header('Content-Type','text/plain; charset=utf-8');self.end_headers()
            self.wfile.write('Regresa a la consola de NexoVisita. Puedes cerrar esta ventana.'.encode())
    with HTTPServer(('127.0.0.1',0),Callback) as http:
        http.timeout=1
        redirect=f'http://127.0.0.1:{http.server_port}/'
        url='https://accounts.google.com/o/oauth2/v2/auth?'+urlencode(dict(
            client_id=client['client_id'],redirect_uri=redirect,response_type='code',
            scope='https://www.googleapis.com/auth/gmail.send',access_type='offline',
            prompt='consent',state=state,code_challenge=challenge,code_challenge_method='S256',
            login_hint=config.get('GMAIL_FROM','')))
        print('Se abrirá Google. Autoriza la cuenta dedicada al proyecto; no se solicita acceso a leer mensajes.')
        if not webbrowser.open(url):
            raise RuntimeError('No se pudo abrir el navegador predeterminado.')
        until=time.monotonic()+300
        while not outcome and time.monotonic()<until:http.handle_request()
    if not outcome.get('code'):raise RuntimeError('Autorización cancelada o vencida.')
    body=urlencode(dict(client_id=client['client_id'],client_secret=client['client_secret'],
        code=outcome['code'],code_verifier=verifier,redirect_uri=redirect,grant_type='authorization_code')).encode()
    with urlopen(Request('https://oauth2.googleapis.com/token',data=body,
                        headers={'Content-Type':'application/x-www-form-urlencoded'}),timeout=20) as response:
        token=json.load(response)
    if not token.get('refresh_token'):raise RuntimeError('Google no entregó autorización renovable.')
    config.update(GMAIL_CLIENT_ID=client['client_id'],GMAIL_CLIENT_SECRET=client['client_secret'],
                  GMAIL_REFRESH_TOKEN=token['refresh_token'],NEXOVISITA_MAIL='gmail_api')
    path.write_text(json.dumps(config,indent=2,ensure_ascii=False),encoding='utf-8')
    print('Autorización guardada en tu archivo privado. No lo subas a GitHub ni lo envíes por chat.')


if __name__=='__main__':
    try:main()
    except Exception as e:
        raise SystemExit('No se completó la autorización ('+type(e).__name__+'). Revisa el tipo de cliente, usuario de prueba y consentimiento.') from None
