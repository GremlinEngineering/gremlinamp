/* Isolated NSF/SGC extension. Keep the input alive for the entire emulator lifetime. */
let emu=0,source=0,output=0,outputFrames=0;
let resolveReady,rejectReady;
const ready=new Promise((resolve,reject)=>{resolveReady=resolve;rejectReady=reject;});
var Module={noInitialRun:true,print(){},printErr(){},
 locateFile:name=>new URL('./vendor/gme-plus/'+name,self.location.href).href,
 onRuntimeInitialized:()=>resolveReady(),onAbort:reason=>rejectReady(Error(String(reason)))};
importScripts('./vendor/gme-plus/gme-plus.js');
function checked(code){if(code)throw Error(Module.UTF8ToString(code)||'Ошибка музыкального декодера');}
function dispose(){
 if(emu)Module._gme_delete(emu);
 if(output)Module._free(output);
 if(source)Module._free(source);
 emu=source=output=outputFrames=0;
}
onmessage=async({data:m})=>{
 try{
  await ready;
  if(m.type==='load'){
   dispose();
   if(!Number.isInteger(m.sampleRate)||m.sampleRate<8000||m.sampleRate>192000)throw Error('Недопустимая частота вывода');
   const input=new Uint8Array(m.bytes);
   if(input.length<4||input.length>64*1024*1024)throw Error('Недопустимый музыкальный файл');
   source=Module._malloc(input.length);const ref=Module._malloc(4);
   if(!source||!ref)throw Error('Недостаточно памяти для музыки');
   try{
    Module.HEAPU8.set(input,source);
    checked(Module._gremlin_gme_open_data(source,input.length,ref,m.sampleRate));
    emu=Module.HEAP32[ref>>2];
   }finally{Module._free(ref);}
   const tracks=Module._gme_track_count(emu),subsong=m.subsong||0;
   if(!Number.isInteger(subsong)||subsong<0||subsong>=tracks)throw Error('Недопустимый номер дорожки');
   Module._gme_ignore_silence(emu,1);
   checked(Module._gme_start_track(emu,subsong));
   postMessage({type:'ready',tracks,decoder:'gme-plus'});
  }else if(m.type==='render'){
   if(!emu)throw Error('Музыка ещё не загружена');
   const requested=m.frames??4096;
   if(!Number.isInteger(requested)||requested<1||requested>16384)throw Error('Недопустимый размер аудиоблока');
   if(outputFrames<requested){if(output)Module._free(output);output=Module._malloc(requested*4);outputFrames=requested;}
   if(!output)throw Error('Недостаточно памяти для звука');
   checked(Module._gme_play(emu,requested*2,output));
   const pcm=new Float32Array(requested*2);
   for(let i=0;i<pcm.length;i++)pcm[i]=Module.HEAP16[(output>>1)+i]/32768;
   postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
  }else if(m.type==='dispose')dispose();
 }catch(e){dispose();postMessage({type:'error',message:String(e.message||e)});}
};
