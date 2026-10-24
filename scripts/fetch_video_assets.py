"""Download public, versioned offline models and the local math renderer.

No video or user content is sent anywhere. Run with Python 3.10+.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
MODEL = ASSETS / 'models'
MODEL.mkdir(parents=True, exist_ok=True)
BASE = 'https://github.com/RapidAI/RapidLaTeXOCR/releases/download/v0.0.0/'
FILES = {
    'encoder.onnx': BASE + 'encoder.onnx',
    'decoder.onnx': BASE + 'decoder.onnx',
    'image_resizer.onnx': BASE + 'image_resizer.onnx',
    'tokenizer.json': BASE + 'tokenizer.json',
    'mfd.onnx': 'https://huggingface.co/breezedeus/pix2text-mfd-1.5/resolve/f470a885e0fca1d3d2bfa2a54991db7ae01f1861/pix2text-mfd-1.5.onnx',
}
EXPECTED = {
    'encoder.onnx': '01bf5dc25539ca0cd5b1bd29296ea495977a6ba5f629dc4178277809d26e5e7d',
    'decoder.onnx': 'bd695497bf1b22279b7626f5916c79226e1e244c84355f8da7edfd2d921d0072',
    'image_resizer.onnx': 'e0b075c39700f64d50400f39c8fc186bbb3b5d84d31864008313f376603aca9d',
    'tokenizer.json': '1dc27b18d6a518d0d5ff3f4bb7bd98521fe80ad39e5b2a246d4109f1bb9d5019',
    'mfd.onnx': '40d4fc852d99bcbf25a9478897d2f49fbbb8f7fdd6569c088cd1c31386293bd7',
}

def download(item):
    name, url = item
    dest = MODEL / name
    if not dest.exists():
        print('Downloading', name, flush=True)
        temp = dest.with_suffix('.part')
        with urllib.request.urlopen(url, timeout=90) as src, temp.open('wb') as out:
            while chunk := src.read(1024 * 1024):
                out.write(chunk)
        temp.replace(dest)
    digest = hashlib.sha256(dest.read_bytes()).hexdigest()
    if digest != EXPECTED[name]:
        raise RuntimeError(f'{name}: checksum mismatch. Remove this file and rerun; do not package it.')
    print(name, dest.stat().st_size, digest, flush=True)
    return {'file': name, 'url': url, 'bytes': dest.stat().st_size, 'sha256': digest}

if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=3) as pool:
        manifest = list(pool.map(download, FILES.items()))
    (MODEL / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    katex = ASSETS / 'web/katex'
    if not (katex / 'katex.min.js').exists():
        archive = ROOT / '.tools/katex-0.16.22.tgz'
        archive.parent.mkdir(exist_ok=True)
        urllib.request.urlretrieve('https://registry.npmjs.org/katex/-/katex-0.16.22.tgz', archive)
        with tarfile.open(archive) as tar:
            for member in tar.getmembers():
                if member.name.startswith('package/dist/') and member.isfile():
                    path = katex / member.name.removeprefix('package/dist/')
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(tar.extractfile(member).read())
            (katex / 'LICENSE').write_bytes(tar.extractfile('package/LICENSE').read())
    print('Offline assets ready.', flush=True)
