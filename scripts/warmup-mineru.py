"""Warm up the real CPU parser without creating application database records."""
from pathlib import Path
import time
import requests

root = Path(__file__).resolve().parent.parent
source = next((root / 'src/main/resources/testFile').glob('04*'))
start = time.time()
print('Warm up MinerU pipeline on the first PDF page, including real layout/OCR/formula/table models.', flush=True)
with source.open('rb') as file:
    response = requests.post('http://127.0.0.1:8000/file_parse',
                             files={'files': ('warmup.pdf', file, 'application/pdf')},
                             data={'backend': 'pipeline', 'response_format_zip': 'true', 'return_images': 'true',
                                   'formula_enable': 'true', 'table_enable': 'true', 'end_page_id': '0'}, timeout=2400)
response.raise_for_status()
if not response.content.startswith(b'PK'):
    raise RuntimeError(response.text[:1000])
(root / 'target/functional-audit/mineru-warmup.zip').write_bytes(response.content)
print('MINERU_WARMUP_OK seconds=' + str(round(time.time() - start, 1)), flush=True)
