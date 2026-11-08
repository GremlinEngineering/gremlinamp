/* Separate lifetime per song: switching/stopping terminates decoding immediately. */
let ready,decoder,frame=0,sourceRate=44100,targetRate=44100,pcm16=null;
let left=0,right=0,allocated=0;
let pending=new Float32Array(0),position=0;
var Module;
self.addEventListener('error',event=>{
  event.preventDefault();postMessage({type:'error',message:'Ошибка музыкального декодера. Проверьте файл и общие библиотеки набора.'});
});
self.addEventListener('unhandledrejection',event=>{
  event.preventDefault();postMessage({type:'error',message:'Ошибка музыкального декодера. Проверьте файл и общие библиотеки набора.'});
});
function safePath(value){
  if(typeof value!=='string'||!value||value.startsWith('/')||/[\\\u0000-\u001f|]/.test(value)||value.split('/').some(p=>!p||p==='.'||p==='..'))throw Error('Недопустимый путь в музыкальном наборе');
  return '/music/'+value;
}
async function asset(path){
  const r=await fetch(new URL(path,self.location.href));
  if(!r.ok)throw Error('Декодер недоступен ('+r.status+')');
  return r.arrayBuffer();
}
function writeFiles(fs,m){
  fs.mkdirTree('/music');
  const files=[{path:m.filename,bytes:m.bytes},...(m.dependencies||[])],seen=new Set();
  for(const file of files){
    const path=safePath(file.path);if(seen.has(path))throw Error('Повтор имени в музыкальном наборе');seen.add(path);
    fs.mkdirTree(path.slice(0,path.lastIndexOf('/')));fs.writeFile(path,new Uint8Array(file.bytes));
  }
  return safePath(m.filename);
}
async function loadBroad(m){
  const [wasm,data]=await Promise.all([asset('vendor/broad/vgmplay-js.wasm'),asset('vendor/broad/vgmplay-js.data')]);
  const runtime=new Promise((resolve,reject)=>{
    Module={noInitialRun:true,wasmBinary:wasm,getPreloadedPackage:()=>data,
      locateFile:n=>new URL('vendor/broad/'+n,self.location.href).href,
      print(){},printErr(){},onRuntimeInitialized:resolve,onAbort:reason=>reject(Error(String(reason)))};
    importScripts('vendor/broad/vgmplay-js.js');
  });
  await runtime;
  const path=writeFiles(Module.FS,m);
  // PSF in this upstream build emits 44100 Hz regardless of SetSampleRate.
  // Keep all its cores at that rate, then convert for the actual AudioContext.
  Module._SetSampleRate(sourceRate);
  const suffix=m.subsong?'|track='+m.subsong:'';
  if(!Module.ccall('OpenVGMFile','number',['string'],[path+suffix]))throw Error('Файл не принят декодером. Проверьте формат и общие библиотеки набора.');
  Module._PlayVGM();decoder='broad';
}
async function loadGSF(m){
  const wasm=await asset('vendor/gsf/gsf2wav.wasm');
  importScripts('vendor/gsf/gsf2wav.js');
  // This upstream GSF interface renders into its private worker filesystem.
  // Decode the requested playback length; resample only at PCM delivery.
  const length=Math.ceil(m.renderMs||120000);
  try { Module=await WebGSFplayer({wasmBinary:wasm,arguments:[safePath(m.filename),String(length)],
    preRun:[mod=>writeFiles(mod.FS,m)],
    locateFile:n=>new URL('vendor/gsf/'+n,self.location.href).href,print(){},printErr(){}}); } catch {throw Error('GSF не прочитан: проверьте файл и все GSFLIB');}
  const raw=Module.FS.readFile('gsfPcmOut.raw');
  if(raw.byteLength<4)throw Error('GSF не содержит звука');
  pcm16=new Int16Array(raw.buffer.slice(raw.byteOffset,raw.byteOffset+raw.byteLength));
  Module.FS.unlink('gsfPcmOut.raw');decoder='gsf';
}
async function load(m){
  if(!Number.isInteger(m.subsong||0)||(m.subsong||0)<0)throw Error('Недопустимый subsong');
  targetRate=m.sampleRate;
  if(!Number.isFinite(targetRate)||targetRate<8000||targetRate>192000)throw Error('Недопустимая частота вывода');
  if(m.decoder==='gsf')await loadGSF(m);else if(m.decoder==='broad')await loadBroad(m);else throw Error('Неизвестный декодер');
  postMessage({type:'ready',decoder});
  return true;
}
function pullBroad(frames){
  const output=new Float32Array(frames*2);
  for(let offset=0;offset<frames;){
    const count=Math.min(16384,frames-offset);
    if(allocated<count){if(left)Module._free(left);if(right)Module._free(right);left=Module._malloc(count*4);right=Module._malloc(count*4);allocated=count;}
    if(Module._PrefillPSF)Module._PrefillPSF(count,4);
    Module._FillBuffer2(left,right,count);
    // This upstream build keeps heap views as globals, refreshed after growth.
    for(let i=0;i<count;i++){output[2*(offset+i)]=HEAPF32[(left>>2)+i];output[2*(offset+i)+1]=HEAPF32[(right>>2)+i];}
    offset+=count;
  }
  return output;
}
function render(m){
  const frames=Math.min(Math.max(1,m.frames||4096),16384),pcm=new Float32Array(frames*2);
  if(decoder==='broad'){
    const ratio=sourceRate/targetRate,required=Math.floor(position+(frames-1)*ratio)+2;
    if(pending.length/2<required){
      const next=new Float32Array(required*2);next.set(pending);next.set(pullBroad(required-pending.length/2),pending.length);pending=next;
    }
    for(let i=0;i<frames;i++){
      const p=position+i*ratio,a=Math.floor(p),mix=p-a;
      for(let ch=0;ch<2;ch++)pcm[2*i+ch]=pending[2*a+ch]*(1-mix)+pending[2*(a+1)+ch]*mix;
    }
    position+=frames*ratio;const consumed=Math.floor(position);pending=pending.slice(consumed*2);position-=consumed;
  }else if(decoder==='gsf'){
    const count=pcm16.length/2,ratio=sourceRate/targetRate;
    for(let i=0;i<frames;i++,frame++){
      const position=frame*ratio,a=Math.floor(position),b=Math.min(a+1,count-1),mix=position-a;
      if(a>=count)break;
      for(let ch=0;ch<2;ch++)pcm[2*i+ch]=(pcm16[2*a+ch]*(1-mix)+pcm16[2*b+ch]*mix)/32768;
    }
  }else throw Error('Музыка ещё не загружена');
  for(const value of pcm)if(!Number.isFinite(value))throw Error('Декодер вернул некорректный PCM');
  postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
onmessage=({data:m})=>{
  if(m.type==='load')ready=load(m).catch(e=>{postMessage({type:'error',message:String(e.message||e)});return false;});
  else if(m.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(m)}).catch(e=>postMessage({type:'error',message:String(e.message||e)}));
};
