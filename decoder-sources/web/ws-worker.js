/* GPL-2.0-or-later. Original WonderSwan music capsule transport.
   Full pinned original machine executes original game/DSP; no rendered song. */
let state,ready,generation=0;
function fail(message){throw Error(message);}
function crc(bytes){let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^(0xedb88320&-(c&1));}return(c^0xffffffff)>>>0;}
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
async function unpack(input){
 if(!(input instanceof ArrayBuffer)||input.byteLength>40000000)fail('WonderSwan capsule input bounds');
 let bytes=new Uint8Array(input);
 if(bytes[0]===31&&bytes[1]===139){const reader=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')).getReader(),parts=[];let length=0;try{while(true){const{value,done}=await reader.read();if(done)break;length+=value.length;if(length>40000000)fail('WonderSwan capsule expansion bounds');parts.push(value);}}finally{reader.releaseLock();}bytes=new Uint8Array(length);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length;}}
 return bytes;
}
async function parse(input,subsong,rate){
 const b=await unpack(input);if(b.length<128||b[0]!==87||b[1]!==83||b[2]!==77||b[3]!==26)fail('Invalid WSM1 header');const v=new DataView(b.buffer,b.byteOffset,b.byteLength),u=o=>v.getUint32(o,true);
 const profile=u(8),romBytes=u(20),tracks=u(24),scriptsAt=u(36),scriptBytes=u(40),romAt=u(44);
 if(u(4)!==1||![1,2,3,4].includes(profile)||u(12)!==44100||u(16)!==50880||u(28)!==128||u(32)!==64||u(48)!==b.length)fail('Unsupported original WonderSwan profile');
 if(!Number.isInteger(rate)||rate<8000||rate>192000||!Number.isInteger(subsong)||subsong<0||subsong>=tracks||!tracks||tracks>64)fail('WonderSwan rate or subsong bounds');
 if(romBytes!==({"1": 1048576, "2": 524288, "3": 1048576, "4": 1048576})[profile]||scriptsAt!==128+tracks*64||scriptBytes>262144||(scriptBytes%8)||romAt!==scriptsAt+scriptBytes||romAt+romBytes!==b.length)fail('WonderSwan capsule region bounds');
 if(hex(b.subarray(96,116))!=='4b01295838ea89e3f1355bbe4cb5cf98aa6108cd'||b.subarray(116,128).some(x=>x))fail('WonderSwan source revision differs');
 const table=b.subarray(128,scriptsAt),scripts=b.subarray(scriptsAt,romAt),rom=b.subarray(romAt);
 if(crc(table)!==u(52)||crc(scripts)!==u(56)||crc(rom)!==u(60))fail('WonderSwan original data checksum differs');
 const sha=hex(new Uint8Array(await crypto.subtle.digest('SHA-256',rom))),expected={"1": "097db90ac6d714144c4763a87cba0f0679a248260f3dce9a2e9908dd3169ea30", "2": "b291bc44f9bfc787c03b43cd72d07534816052f0d83a1e8c8ed5feb71dd8835c", "3": "f3a2abc993324172a2c345fbb6ad576da977789af747148c84490b2b81f56164", "4": "f3a2abc993324172a2c345fbb6ad576da977789af747148c84490b2b81f56164"}[profile];
 if(sha!==expected||hex(b.subarray(64,96))!==sha)fail('Original WonderSwan cartridge identity differs');
 const rows=[];
 function script(at,count,total,limit){if(!count||count>1024||at%8||at+count*8>scriptBytes||!total||total>limit)fail('WonderSwan original input script bounds');let sum=0,result=[];for(let i=0;i<count;i++){const pos=scriptsAt+at+i*8,frames=u(pos),mask=u(pos+4);if(!frames||frames>limit||mask>0xffff)fail('Invalid WonderSwan original input event');sum+=frames;result.push({frames,mask});}if(sum!==total)fail('WonderSwan input timeline differs');return result;}
 for(let i=0;i<tracks;i++){const at=128+i*64;if(u(at)!==i||b.subarray(at+32,at+64).some(x=>x))fail('Invalid WonderSwan selection descriptor');const prefixFrames=u(at+12),playFrames=u(at+24),audioFrames=u(at+28);if(!audioFrames||audioFrames<playFrames*500||audioFrames>playFrames*1000)fail('WonderSwan audio window differs');rows.push({prefix:script(u(at+4),u(at+8),prefixFrames,30000),play:script(u(at+16),u(at+20),playFrames,36000),prefixFrames,playFrames,audioFrames});}
 return{rom,sha,profile,rate,...rows[subsong]};
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);
 if(p.profile===4){if(typeof GremlinWSHeadroomMusic==='undefined')importScripts('vendor/ws/gremlin-ws-headroom.js')}else if(typeof GremlinWSMusic==='undefined')importScripts('vendor/ws/gremlin-ws.js');
 const factory=p.profile===4?GremlinWSHeadroomMusic:GremlinWSMusic;
 const m=await factory({locateFile:n=>new URL('vendor/ws/'+n,self.location.href).href,print(){},printErr(){}});const ptr=m._malloc(p.rom.length);if(!ptr)fail('WonderSwan memory allocation failed');m.HEAPU8.set(p.rom,ptr);const failed=m._gremlin_ws_load(ptr,p.rom.length);m._free(ptr);if(!failed&&(m._gremlin_ws_source_rate()!==44100||Math.abs(m._gremlin_ws_source_fps()-60.37735849056604)>1e-10))fail('Original WS clock profile differs');if(failed)fail(m.UTF8ToString(m._gremlin_ws_error())||'Original WonderSwan load failed');m._gremlin_ws_capture_video(0);
 for(const event of p.prefix)for(let frame=0;frame<event.frames;frame++){if(m._gremlin_ws_step(event.mask))fail('Original WonderSwan prefix failed');if(m._gremlin_ws_audio_frames()>4096)fail('Original WonderSwan frame profile differs');}
 if(token!==generation){m._gremlin_ws_close();return false;}
 if(state)state.m._gremlin_ws_close();state={m,...p,position:0,sourceFrames:0,generated:0,queue:new Float32Array(0),queueBase:0,playEvent:0,eventFrames:0};
 postMessage({type:'ready',decoder:'ws',devices:[{type:'wonderswan_v30_cpu_audio',core:'original BeetleWonderSwan',source_revision:'4b01295838ea89e3f1355bbe4cb5cf98aa6108cd',source_rom_sha256:p.sha,original_CPU_APU_and_cartridge:true,original_WonderSwan_wave_and_noise:true,bios_profile:'original core initialization, no external BIOS',source_master_clock_hz:3072000,source_frame_cycles:50880,frontend_reported_fps:60.37735849056604,source_audio_rate:44100,original_input_scripts:true,pre_s16_mixer_gain:p.profile===4?.5:1,source_mixer_headroom_corrected:p.profile===4,rendered_track:false,physical_board_measured:false}]});return true;
}
function generate(s){
 const event=s.play[s.playEvent];if(!event)fail('WonderSwan selected source window exhausted');if(s.m._gremlin_ws_step(event.mask))fail('Original WonderSwan music frame failed');const n=s.m._gremlin_ws_audio_frames();if(n>4096)fail('Original WonderSwan frame profile differs');const next=new Float32Array(s.queue.length+n*2);next.set(s.queue);const p=s.m._gremlin_ws_audio()>>1;for(let i=0;i<n*2;i++)next[s.queue.length+i]=s.m.HEAP16[p+i]/32768;s.queue=next;s.generated+=n;s.sourceFrames++;if(++s.eventFrames===event.frames){s.eventFrames=0;s.playEvent++;}
}
function sample(s,index,ch){if(index>=s.audioFrames)return 0;while(index>=s.queueBase+s.queue.length/2)generate(s);return s.queue[(index-s.queueBase)*2+ch];}
function render(message){
 const frames=message.frames;if(!Number.isInteger(frames)||frames<1||frames>16384)fail('WonderSwan output frame bounds');if(!state)fail('WonderSwan music not loaded');const s=state,pcm=new Float32Array(frames*2);
 for(let i=0;i<frames;i++,s.position++){const numerator=s.position*44100,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);pcm[i*2+ch]=a+(b-a)*rem/s.rate;}}
 const nextAt=Math.floor(s.position*44100/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,nextAt-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard;}
 postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
onmessage=({data:message})=>{
 if(message.type==='load'){const token=++generation;ready=load(message,token).catch(e=>{if(token===generation)postMessage({type:'error',message:String(e.message||e)});return false;});}
 else if(message.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(message);}).catch(e=>postMessage({type:'error',message:String(e.message||e)}));
};
