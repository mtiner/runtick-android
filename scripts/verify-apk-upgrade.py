# Verify certificate continuity after apksigner verifies both APK signatures.
from pathlib import Path
from zipfile import ZipFile
import struct, hashlib

def cert(path):
 b=Path(path).read_bytes(); e=b.rfind(b'PK\x05\x06'); c=struct.unpack_from('<I',b,e+16)[0]; p=c-struct.unpack_from('<Q',b,c-24)[0]
 while p<c-24:
  n=struct.unpack_from('<Q',b,p)[0]; typ=struct.unpack_from('<I',b,p+8)[0]; v=b[p+12:p+8+n];p+=8+n
  if typ==0x7109871a:
   def field(d): n=struct.unpack_from('<I',d)[0];return d[4:4+n],d[4+n:]
   a,_=field(v);a,_=field(a);a,_=field(a);_,a=field(a);a,_=field(a);a,_=field(a)
   return hashlib.sha256(a).hexdigest()
 raise RuntimeError('APK v2 signing certificate missing')

if __name__ == '__main__':
 import sys
 if len(sys.argv) != 3: raise SystemExit('usage: verify-apk-upgrade.py previous.apk new.apk')
 old_hash, new_hash = cert(sys.argv[1]), cert(sys.argv[2])
 print('Previous certificate SHA-256:', old_hash)
 print('New certificate SHA-256:', new_hash)
 if old_hash != new_hash: raise SystemExit('Signing certificate mismatch: refusing to publish an incompatible upgrade')
 print('Official signing certificate matches; existing installs can upgrade.')
