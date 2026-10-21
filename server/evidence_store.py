"""Cloudinary originals stay authenticated; only authorized API calls fetch them.
The mobile JSON format is unchanged, so offline drafts still work.
"""
import base64
import copy
import hashlib
import os
import re
import time
from urllib.request import urlopen


def enabled():
    mode = os.environ.get('NEXOVISITA_MEDIA', 'local')
    if mode not in ('local', 'cloudinary'): raise RuntimeError('Proveedor de imágenes inválido.')
    return mode == 'cloudinary'


def configure():
    import cloudinary
    required = ('CLOUDINARY_CLOUD_NAME', 'CLOUDINARY_API_KEY', 'CLOUDINARY_API_SECRET', 'CLOUDINARY_PREFIX')
    if any(not os.environ.get(k) for k in required): raise RuntimeError('Falta configurar Cloudinary.')
    if not re.fullmatch(r'[A-Za-z0-9_-]{3,80}', os.environ['CLOUDINARY_PREFIX']):
        raise RuntimeError('CLOUDINARY_PREFIX debe ser un nombre exclusivo del proyecto, sin espacios.')
    cloudinary.config(cloud_name=os.environ['CLOUDINARY_CLOUD_NAME'], api_key=os.environ['CLOUDINARY_API_KEY'],
                      api_secret=os.environ['CLOUDINARY_API_SECRET'], secure=True)


def externalize(report, visit_id):
    result = copy.deepcopy(report)
    if not enabled(): return result
    configure()
    import cloudinary.uploader
    for photo in result.get('photos', []):
        if 'data' not in photo: continue
        raw = base64.b64decode(photo['data'], validate=True)
        digest = hashlib.sha256(raw).hexdigest()
        # Same visit+bytes always address the same original, including retries.
        public_id = f"{os.environ['CLOUDINARY_PREFIX']}/{visit_id}/{digest}"
        uploaded = cloudinary.uploader.upload('data:image/jpeg;base64,'+photo['data'],
            public_id=public_id, resource_type='image', type='authenticated',
            overwrite=True, invalidate=True, timeout=25)
        if uploaded.get('public_id') != public_id or uploaded.get('type') != 'authenticated':
            raise RuntimeError('Cloudinary no confirmó una evidencia privada.')
        photo.pop('data')
        photo['cloudinary_id'] = public_id
        photo['sha256'] = digest
    return result


def materialize(report):
    result = copy.deepcopy(report)
    for photo in result.get('photos', []):
        if 'cloudinary_id' not in photo: continue
        configure()
        import cloudinary.utils
        url = cloudinary.utils.private_download_url(photo['cloudinary_id'], 'jpg',
             resource_type='image', type='authenticated', expires_at=int(time.time())+60)
        with urlopen(url, timeout=25) as response:
            raw = response.read(400001)
        if len(raw)>400000 or hashlib.sha256(raw).hexdigest()!=photo['sha256']:
            raise RuntimeError('La evidencia recibida no coincide con la guardada.')
        photo['data'] = base64.b64encode(raw).decode('ascii')
        photo.pop('cloudinary_id'); photo.pop('sha256')
    return result


def remove_photos(report):
    """Explicit retention action; idempotent, retry after partial provider failure."""
    import cloudinary.uploader
    for p in report.get('photos', []):
        if 'cloudinary_id' in p:
            configure()
            result = cloudinary.uploader.destroy(p['cloudinary_id'], resource_type='image',
                          type='authenticated', invalidate=True, timeout=25)
            if result.get('result') not in ('ok', 'not found'):
                raise RuntimeError('No se confirmó el borrado en Cloudinary.')
