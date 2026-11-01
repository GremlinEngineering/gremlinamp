#!/usr/bin/env python3
"""Render a self-generated, YM2413-only VGM through a built GME library."""
import ctypes as C
import gzip
import math
from pathlib import Path
import struct
import sys
import tempfile

lib=C.CDLL(str(Path(sys.argv[1]).resolve()))
lib.gme_open_file.argtypes=[C.c_char_p,C.POINTER(C.c_void_p),C.c_int];lib.gme_open_file.restype=C.c_char_p
lib.gme_start_track.argtypes=[C.c_void_p,C.c_int];lib.gme_start_track.restype=C.c_char_p
lib.gme_play.argtypes=[C.c_void_p,C.c_int,C.POINTER(C.c_short)];lib.gme_play.restype=C.c_char_p
lib.gme_delete.argtypes=[C.c_void_p]
lib.gme_mute_voices.argtypes=[C.c_void_p,C.c_int]
header=bytearray(64);header[:4]=b'Vgm ';struct.pack_into('<I',header,8,0x150);struct.pack_into('<I',header,0x10,3579545);struct.pack_into('<I',header,0x18,735*180);struct.pack_into('<I',header,0x34,12)
# No PSG clock or PSG commands; all audio must come from OPLL/YM2413.
commands=bytes([0x51,0x30,0x10,0x51,0x10,0xaa,0x51,0x20,0x19])+b'\x62'*120+bytes([0x51,0x20,0x09])+b'\x62'*60+b'\x66'
raw=header+commands;struct.pack_into('<I',raw,4,len(raw)-4)
def render(path,muted=False):
 emu=C.c_void_p()
 def check(error):
  if error:raise AssertionError(error.decode())
 check(lib.gme_open_file(str(path).encode(),C.byref(emu),44100))
 try:
  check(lib.gme_start_track(emu,0))
  if muted:lib.gme_mute_voices(emu,0x7fffffff)
  pcm=(C.c_short*(44100*2*3))();check(lib.gme_play(emu,len(pcm),pcm));return list(pcm)
 finally:lib.gme_delete(emu)
with tempfile.TemporaryDirectory() as folder:
 p=Path(folder)/'ym2413-only.vgm';p.write_bytes(raw);q=p.with_suffix('.vgz');q.write_bytes(gzip.compress(raw))
 a=render(p);b=render(q)
 peak=max(map(abs,a));rms=math.sqrt(sum(x*x for x in a)/len(a))
 assert peak>100 and rms>20,(peak,rms)
 assert a==b,'VGM and VGZ decoding differ'
 assert render(p)==a,'Restart is not deterministic'
 muted=render(p,True);assert max(map(abs,muted[44100:]))==0,'Mute failed'
 assert any(a[:44100*2]),'First second is silent'
 print(f'5 YM2413 regression checks passed: sound, VGM/VGZ parity, restart, mute, early audio; peak={peak}, RMS={rms:.2f}')
