"""WSGI adapter for one Waitress process behind an HTTPS reverse proxy."""
import json
import os
import threading
import time
from http import HTTPStatus
import server as s


def application(environ, start_response):
    try:
        method=environ.get('REQUEST_METHOD','GET')
        path=environ.get('PATH_INFO','/')
        if method not in ('GET','POST'):
            raise s.ApiError(405,'Método no permitido.')
        if path in ('/login','/activate/request','/activate/confirm','/password/request','/password/confirm'):
            ip=environ.get('REMOTE_ADDR','unknown')
            with s.Handler.attempts_lock:
                stamp=time.time()
                s.Handler.attempts={k:[t for t in ts if stamp-t<60] for k,ts in s.Handler.attempts.items() if ts and stamp-ts[-1]<60}
                hits=s.Handler.attempts.setdefault(ip,[])
                if len(hits)>=10: raise s.ApiError(429,'Demasiados intentos. Espera un minuto.')
                hits.append(stamp)
        data={}
        if method=='POST':
            length=int(environ.get('CONTENT_LENGTH') or '0')
            if not 0<length<=4000000: raise s.ApiError(413,'Solicitud vacía o demasiado grande.')
            data=json.loads(environ['wsgi.input'].read(length))
            if not isinstance(data,dict): raise s.ApiError(400,'Se esperaba un objeto JSON.')
        auth=environ.get('HTTP_AUTHORIZATION','')
        token=auth.removeprefix('Bearer ') if auth.startswith('Bearer ') else ''
        result=s.dispatch(method,path,data,token)
        status=200
    except s.ApiError as e:
        status=e.status; result={'error':e.message}
    except (ValueError,UnicodeError):
        status=400; result={'error':'JSON o datos inválidos.'}
    except Exception:
        status=500; result={'error':'No se pudo procesar la solicitud.'}
    payload=json.dumps(result,ensure_ascii=False,allow_nan=False).encode()
    start_response(f'{status} {HTTPStatus(status).phrase}',[
        ('Content-Type','application/json; charset=utf-8'),('Content-Length',str(len(payload))),
        ('Cache-Control','no-store'),('X-Content-Type-Options','nosniff')])
    return [payload]


if __name__=='__main__':
    from waitress import serve
    if not s.database.is_mysql() and not s.DB.is_file():
        raise SystemExit('Primero configura NEXOVISITA_DB y migra tu base o ejecuta server.py init.')
    if os.environ.get("RENDER") and not s.database.is_mysql():
        raise SystemExit("Render requiere MySQL: no se admite SQLite efímero.")
    if os.environ.get("RENDER"):
        import evidence_store
        if not evidence_store.enabled() or os.environ.get("NEXOVISITA_MAIL") != "gmail_api":
            raise SystemExit("Configura Cloudinary y Gmail API antes de desplegar.")
        evidence_store.configure()
        s.mailer.settings()
    s.initialize()
    threading.Thread(target=s.mail_worker,daemon=True).start()
    # Bind locally by default. Set a private interface only behind the platform's TLS proxy.
    options={}
    proxy=os.environ.get('NEXOVISITA_TRUSTED_PROXY')
    if proxy:
        options.update(trusted_proxy=proxy,trusted_proxy_headers={'x-forwarded-for','x-forwarded-proto'})
    serve(application,host=os.environ.get('HOST','127.0.0.1'),port=int(os.environ.get('PORT','8765')),
          threads=4,max_request_body_size=4000000,expose_tracebacks=False,**options)
