from pathlib import Path
import argparse,zipfile,hashlib,subprocess,os,shlex,json,shutil,sys
p=argparse.ArgumentParser();p.add_argument('--emsdk',required=True);p.add_argument('--output',required=True);a=p.parse_args();R=Path(__file__).resolve().parent;SDK=Path(a.emsdk).resolve();S=SDK/'install/emscripten';B=Path(a.output).resolve();E=B/'core';B.mkdir(parents=True,exist_ok=True)
archive=R/'original-sources.zip';assert hashlib.sha256(archive.read_bytes()).hexdigest()=='0b202f18f3890683c1686577f9bc2e02529a70eba7d3e437a3de899ef0b3ef21'
with zipfile.ZipFile(archive)as z:
 for x in z.infolist():
  if x.is_dir():continue
  rel=Path(*Path(x.filename).parts[1:]);assert not rel.is_absolute()and'..'not in rel.parts;dest=E/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(z.read(x))
p=E/'mednafen/wswan/sound.c';text=p.read_text();assert text.count('Blip_Synth_set_volume_n(&WaveSynth, 1, 4, 256);')==1;p.write_text(text.replace('Blip_Synth_set_volume_n(&WaveSynth, 1, 4, 256);','Blip_Synth_set_volume_n(&WaveSynth, 1, 8, 256);'))
env={**os.environ,'EM_CONFIG':str(SDK/'.emscripten'),'SOURCE_DATE_EPOCH':'0','GIT_CEILING_DIRECTORIES':str(B)}
cmd=['make','-j4','platform=emscripten','GIT_VERSION=4b01295','CC=python3 '+shlex.quote(str(S/'emcc.py')),'CXX=python3 '+shlex.quote(str(S/'em++.py')),'AR=python3 '+shlex.quote(str(S/'emar.py'))]
with(B/'make.log').open('w')as f:subprocess.run(cmd,cwd=E,env=env,stdout=f,stderr=subprocess.STDOUT,check=True)
cc=[sys.executable,str(S/'emcc.py')];host=B/'ws_host.o';subprocess.run([*cc,'-O2','-DNDEBUG','-I'+str(E/'libretro-common/include'),'-c',str(R/'ws_host.c'),'-o',str(host)],env=env,check=True);shutil.copyfile(E/'mednafen_wswan_libretro_emscripten.bc',B/'libwswan.a')
exports=['_malloc','_free']+['_gremlin_ws_'+x for x in ['load','close','step','audio','audio_frames','video','video_width','video_height','capture_video','error','source_rate','source_fps']]
cmd=[*cc,'-O2','--no-entry',str(host),str(B/'libwswan.a'),'-sMODULARIZE=1','-sEXPORT_NAME=GremlinWSHeadroomMusic','-sENVIRONMENT=web,worker,node','-sALLOW_MEMORY_GROWTH=1','-sINITIAL_MEMORY=33554432','-sSTACK_SIZE=1048576','-sFILESYSTEM=1','-sEXPORTED_FUNCTIONS='+json.dumps(exports),'-sEXPORTED_RUNTIME_METHODS=["UTF8ToString","HEAPU8","HEAP16","HEAPU32"]','-o',str(B/'gremlin-ws-headroom.js')]
with(B/'link.log').open('w')as f:subprocess.run(cmd,env=env,stdout=f,stderr=subprocess.STDOUT,check=True)
meta={'core':'Original Beetle WonderSwan / Mednafen Cygne','commit':'4b01295838ea89e3f1355bbe4cb5cf98aa6108cd','core_changes':'pre-S16 mixer headroom denominator4->8 only','default_options':True,'outputs':{n:{'bytes':(B/n).stat().st_size,'sha256':hashlib.sha256((B/n).read_bytes()).hexdigest()}for n in ['gremlin-ws-headroom.js','gremlin-ws-headroom.wasm']}};(B/'BUILD.json').write_text(json.dumps(meta,indent=2)+'\n');print(json.dumps(meta))
