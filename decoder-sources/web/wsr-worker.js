/* WSR is an original executable music rip. The main playlist supplies validated selectors. */
let loaded=false,output=0,pending=640;
let resolveReady,rejectReady;
const ready=new Promise((resolve,reject)=>{resolveReady=resolve;rejectReady=reject;});
var Module={noInitialRun:true,print(){},printErr(){},
 locateFile:name=>new URL('./vendor/wsr/'+name,self.location.href).href,
 onRuntimeInitialized:()=>resolveReady(),onAbort:reason=>rejectReady(Error(String(reason)))};
importScripts('./vendor/wsr/gremlin-wsr.js');
function dispose(){if(loaded)Module._Close_WSR();if(output)Module._free(output);loaded=false;output=0;pending=640;}
onmessage=async({data:m})=>{
 try{
  await ready;
  if(m.type==='load'){
   dispose();
   if(!Number.isInteger(m.sampleRate)||m.sampleRate<11025||m.sampleRate>192000)throw Error('Недопустимая частота вывода WSR');
   const bytes=new Uint8Array(m.bytes),len=bytes.length,subsong=m.subsong??0;
   if(len<65536||len>16*1024*1024||len%65536!==0||bytes[len-32]!==87||bytes[len-31]!==83||bytes[len-30]!==82||bytes[len-29]!==70)throw Error('Недопустимый заголовок WSR');
   if(!Number.isInteger(subsong)||subsong<0||subsong>255)throw Error('Недопустимый селектор WSR');
   const source=Module._malloc(len);if(!source)throw Error('Недостаточно памяти для WSR');
   try{Module.HEAPU8.set(bytes,source);Module._Set_Frequency(m.sampleRate);if(!Module._Load_WSR(source,len))throw Error('Декодер отверг WSR');loaded=true;}finally{Module._free(source);}
   Module._Reset_WSR(subsong);if(Module._Set_Frequency(m.sampleRate)!==m.sampleRate)throw Error('Недопустимая частота WSR');Module._Set_ChannelMuting(0);
   output=Module._malloc(640*4);if(!output)throw Error('Недостаточно памяти для звука');pending=640;
   postMessage({type:'ready',decoder:'wsr',tracks:256,firstSong:Module._Get_FirstSong()});
  }else if(m.type==='render'){
   if(!loaded)throw Error('WSR ещё не загружен');
   const frames=m.frames??4096;if(!Number.isInteger(frames)||frames<1||frames>16384)throw Error('Недопустимый размер аудиоблока');
   const pcm=new Float32Array(frames*2);let done=0;
   while(done<frames){
    if(pending===640){if(!Module._Update_WSR(output,640*4,640))throw Error('Ошибка исполнения WSR');pending=0;}
    const n=Math.min(frames-done,640-pending);
    for(let i=0;i<n*2;i++)pcm[done*2+i]=Module.HEAP16[(output>>1)+pending*2+i]/32768;
    done+=n;pending+=n;
   }
   postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
  }else if(m.type==='dispose')dispose();
 }catch(error){dispose();postMessage({type:'error',message:String(error.message||error)});}
};
