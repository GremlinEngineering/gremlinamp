// GPL-2.0-or-later. One Atari music-ROM instance per worker; no game UI.
let ready, runtime, output=0;
const MAX_FRAMES=16384, MAX_BYTES=1024*1024;
function validate(message){
 if(!Number.isFinite(message.sampleRate)||message.sampleRate<8000||message.sampleRate>192000)throw Error('Недопустимая частота вывода Atari');
 if((message.subsong??0)!==0)throw Error('Музыкальный ROM Atari содержит одну программу; для другой темы нужен отдельный проверенный файл');
 if(message.dependencies?.length)throw Error('Музыкальный ROM Atari должен быть самостоятельным файлом');
 if(!['a26','a78'].includes(message.format))throw Error('Для Atari нужно явно указать формат a26 или a78');
 const bytes=new Uint8Array(message.bytes);
 if(bytes.length>MAX_BYTES||bytes.length<2048)throw Error('Некорректный размер музыкального ROM Atari');
 if(message.format==='a78'){
  if(bytes.length<=128||new TextDecoder().decode(bytes.slice(1,10))!=='ATARI7800')throw Error('Нет заголовка ATARI7800 в файле A78');
  const length=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength).getUint32(49,false);
  if(length!==bytes.length-128)throw Error('Длина ROM не соответствует заголовку A78');
  if(bytes[57]>1)throw Error('Неизвестный телевизионный стандарт A78');
 }else{
  if((bytes.length&(bytes.length-1))!==0)throw Error('A26 должен быть проверенным двоичным музыкальным ROM');
  if(new TextDecoder().decode(bytes.slice(1,10))==='ATARI7800')throw Error('Файл Atari 7800 нельзя воспроизводить ядром 2600');
 }
 const region=message.atari?.region??'auto';
 if(!['auto','ntsc','pal','pal60','secam'].includes(region))throw Error('Неизвестный телевизионный стандарт Atari');
 if(message.format==='a78'&&region!=='auto')throw Error('Телевизионный стандарт A78 берётся из исходного заголовка');
 return {bytes,region};
}
async function load(message){
 const {bytes,region}=validate(message),core=message.format==='a78'?'prosystem':'tia';
 const base='vendor/atari/',stem='gremlin-'+core;
 const response=await fetch(new URL(base+stem+'.wasm',self.location.href));
 if(!response.ok)throw Error('Декодер Atari недоступен ('+response.status+')');
 const module=await WebAssembly.compile(await response.arrayBuffer());
 importScripts(base+stem+'.js');
 runtime=await GremlinAtari({instantiateWasm(imports,receive){const instance=new WebAssembly.Instance(module,imports);receive(instance);return instance.exports;},print(){},printErr(){}});
 const input=runtime._malloc(bytes.byteLength);if(!input)throw Error('Недостаточно памяти для Atari ROM');
 let status;
 try{runtime.HEAPU8.set(bytes,input);status=runtime._gremlin_atari_load(input,bytes.byteLength,Math.round(message.sampleRate),['auto','ntsc','pal','pal60','secam'].indexOf(region));}
 finally{runtime._free(input);}
 if(status)throw Error('Atari: '+runtime.UTF8ToString(runtime._gremlin_atari_error()));
 output=runtime._malloc(MAX_FRAMES*2*4);if(!output)throw Error('Недостаточно памяти для звукового буфера Atari');
 postMessage({type:'ready',decoder:'atari',core,format:message.format,sampleRate:Math.round(message.sampleRate),nativeSampleRate:runtime._gremlin_atari_rate(),fps:runtime._gremlin_atari_fps()});return true;
}
function render(message){
 const frames=message.frames??4096;if(!Number.isInteger(frames)||frames<1||frames>MAX_FRAMES)throw Error('Некорректный размер блока Atari');
 if(runtime._gremlin_atari_render(output,frames))throw Error('Atari: '+runtime.UTF8ToString(runtime._gremlin_atari_error()));
 const pcm=new Float32Array(runtime.HEAPF32.buffer,output,frames*2).slice();
 for(const x of pcm)if(!Number.isFinite(x))throw Error('Atari вернул некорректный PCM');
 postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
function report(error){postMessage({type:'error',message:String(error?.message||error)});}
self.addEventListener('unhandledrejection',event=>{event.preventDefault();report(event.reason);});
onmessage=({data:m})=>{
 if(m.type==='load')ready=load(m).catch(e=>{report(e);return false;});
 else if(m.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(m);}).catch(report);
};
