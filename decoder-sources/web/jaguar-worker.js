/* GPL-3.0-or-later. Original Jaguar music capsule transport.
   Full pinned original machine executes original game/DSP; no rendered song. */
let state,ready,generation=0;
function fail(message){throw Error(message);}
function crc(bytes){let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^(0xedb88320&-(c&1));}return(c^0xffffffff)>>>0;}
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
async function unpack(input){
 if(!(input instanceof ArrayBuffer)||input.byteLength>8000000)fail('Jaguar capsule input bounds');
 let bytes=new Uint8Array(input);
 if(bytes[0]===31&&bytes[1]===139){const reader=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')).getReader(),parts=[];let length=0;try{while(true){const{value,done}=await reader.read();if(done)break;length+=value.length;if(length>8000000)fail('Jaguar capsule expansion bounds');parts.push(value);}}finally{reader.releaseLock();}bytes=new Uint8Array(length);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length;}}
 return bytes;
}
async function parse(input,subsong,rate){
 const b=await unpack(input);if(b.length<128||b[0]!==74||b[1]!==71||b[2]!==77||b[3]!==26)fail('Invalid JGM1 header');const v=new DataView(b.buffer,b.byteOffset,b.byteLength),u=o=>v.getUint32(o,true);
 const profile=u(8),romBytes=u(20),tracks=u(24),scriptsAt=u(36),scriptBytes=u(40),romAt=u(44);
 if(u(4)!==1||![1,2,3].includes(profile)||u(12)!==48000||u(16)!==800||u(28)!==128||u(32)!==64||u(48)!==b.length)fail('Unsupported original Jaguar profile');
 if(!Number.isInteger(rate)||rate<8000||rate>192000||!Number.isInteger(subsong)||subsong<0||subsong>=tracks||!tracks||tracks>16)fail('Jaguar rate or subsong bounds');
 if(romBytes!==(profile===2?4194304:2097152)||scriptsAt!==128+tracks*64||scriptBytes>65536||(scriptBytes%8)||romAt!==scriptsAt+scriptBytes||romAt+romBytes!==b.length)fail('Jaguar capsule region bounds');
 if(hex(b.subarray(96,116))!=='34696082dbc0448caf23aa4ecb5ed40817cb8da0'||b.subarray(116,128).some(x=>x))fail('Jaguar source revision differs');
 const table=b.subarray(128,scriptsAt),scripts=b.subarray(scriptsAt,romAt),rom=b.subarray(romAt);
 if(crc(table)!==u(52)||crc(scripts)!==u(56)||crc(rom)!==u(60))fail('Jaguar original data checksum differs');
 const sha=hex(new Uint8Array(await crypto.subtle.digest('SHA-256',rom))),expected=({1:'bfeda829b9b4dfc0b4013eb1e319fbf17d7af900f0b39db31332f0cd818e791e',2:'09805f11017dfa587863a4e63aa01d63f3535ba8f56355ea0a6c0efb6bc8a8f0',3:'27fec93e3171a3c6d91c420992d74395a8dfc6c3f26d85fbcd8243c176d545bb'})[profile];
 if(sha!==expected||hex(b.subarray(64,96))!==sha)fail('Original Jaguar cartridge identity differs');
 const rows=[];
 function script(at,count,total,limit){if(!count||count>256||at%8||at+count*8>scriptBytes||!total||total>limit)fail('Jaguar original input script bounds');let sum=0,result=[];for(let i=0;i<count;i++){const pos=scriptsAt+at+i*8,frames=u(pos),mask=u(pos+4);if(!frames||frames>limit||mask>0x3ffffff)fail('Invalid Jaguar original input event');sum+=frames;result.push({frames,mask});}if(sum!==total)fail('Jaguar input timeline differs');return result;}
 for(let i=0;i<tracks;i++){const at=128+i*64;if(u(at)!==i||b.subarray(at+32,at+64).some(x=>x))fail('Invalid Jaguar selection descriptor');const prefixFrames=u(at+12),playFrames=u(at+24),audioFrames=u(at+28);if(audioFrames!==playFrames*800)fail('Jaguar audio window differs');rows.push({prefix:script(u(at+4),u(at+8),prefixFrames,12000),play:script(u(at+16),u(at+20),playFrames,36000),prefixFrames,playFrames,audioFrames});}
 return{rom,sha,profile,rate,...rows[subsong]};
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);
 if(typeof GremlinJaguarMusic==='undefined')importScripts('vendor/jaguar/gremlin-jaguar.js');
 const m=await GremlinJaguarMusic({locateFile:n=>new URL('vendor/jaguar/'+n,self.location.href).href,print(){},printErr(){}});const ptr=m._malloc(p.rom.length);if(!ptr)fail('Jaguar memory allocation failed');m.HEAPU8.set(p.rom,ptr);const failed=m._gremlin_jag_load(ptr,p.rom.length);m._free(ptr);if(failed)fail(m.UTF8ToString(m._gremlin_jag_error())||'Original Jaguar load failed');m._gremlin_jag_capture_video(0);
 for(const event of p.prefix)for(let frame=0;frame<event.frames;frame++){if(m._gremlin_jag_step(event.mask))fail('Original Jaguar prefix failed');if(m._gremlin_jag_audio_frames()!==800)fail('Original Jaguar frame profile differs');}
 if(token!==generation){m._gremlin_jag_close();return false;}
 if(state)state.m._gremlin_jag_close();state={m,...p,position:0,sourceFrames:0,generated:0,queue:new Float32Array(0),queueBase:0,playEvent:0,eventFrames:0};
 postMessage({type:'ready',decoder:'jaguar',devices:[{type:'jaguar_jerry_dsp',core:'original VirtualJaguar3.11.0',source_revision:'34696082dbc0448caf23aa4ecb5ed40817cb8da0',source_rom_sha256:p.sha,original_machine_and_DSP:true,source_audio_rate:48000,original_input_scripts:true,rendered_track:false,physical_board_measured:false}]});return true;
}
function generate(s){
 const event=s.play[s.playEvent];if(!event)fail('Jaguar selected source window exhausted');if(s.m._gremlin_jag_step(event.mask))fail('Original Jaguar music frame failed');const n=s.m._gremlin_jag_audio_frames();if(n!==800)fail('Original Jaguar frame profile differs');const next=new Float32Array(s.queue.length+n*2);next.set(s.queue);const p=s.m._gremlin_jag_audio()>>1;for(let i=0;i<n*2;i++)next[s.queue.length+i]=s.m.HEAP16[p+i]/32768;s.queue=next;s.generated+=n;s.sourceFrames++;if(++s.eventFrames===event.frames){s.eventFrames=0;s.playEvent++;}
}
function sample(s,index,ch){if(index>=s.audioFrames)return 0;while(index>=s.queueBase+s.queue.length/2)generate(s);return s.queue[(index-s.queueBase)*2+ch];}
function render(message){
 const frames=message.frames;if(!Number.isInteger(frames)||frames<1||frames>16384)fail('Jaguar output frame bounds');if(!state)fail('Jaguar music not loaded');const s=state,pcm=new Float32Array(frames*2);
 for(let i=0;i<frames;i++,s.position++){const numerator=s.position*48000,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);pcm[i*2+ch]=a+(b-a)*rem/s.rate;}}
 const nextAt=Math.floor(s.position*48000/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,nextAt-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard;}
 postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
onmessage=({data:message})=>{
 if(message.type==='load'){const token=++generation;ready=load(message,token).catch(e=>{if(token===generation)postMessage({type:'error',message:String(e.message||e)});return false;});}
 else if(message.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(message);}).catch(e=>postMessage({type:'error',message:String(e.message||e)}));
};
