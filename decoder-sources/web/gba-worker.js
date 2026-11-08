/* GPL-2.0-or-later. Original Game Boy Advance music capsule transport.
   Full pinned original machine executes original game/DSP; no rendered song. */
let state,ready,generation=0;
function fail(message){throw Error(message);}
function crc(bytes){let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^(0xedb88320&-(c&1));}return(c^0xffffffff)>>>0;}
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
async function unpack(input){
 if(!(input instanceof ArrayBuffer)||input.byteLength>40000000)fail('Game Boy Advance capsule input bounds');
 let bytes=new Uint8Array(input);
 if(bytes[0]===31&&bytes[1]===139){const reader=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')).getReader(),parts=[];let length=0;try{while(true){const{value,done}=await reader.read();if(done)break;length+=value.length;if(length>40000000)fail('Game Boy Advance capsule expansion bounds');parts.push(value);}}finally{reader.releaseLock();}bytes=new Uint8Array(length);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length;}}
 return bytes;
}
async function parse(input,subsong,rate){
 const b=await unpack(input);if(b.length<128||b[0]!==65||b[1]!==71||b[2]!==77||b[3]!==26)fail('Invalid AGM1 header');const v=new DataView(b.buffer,b.byteOffset,b.byteLength),u=o=>v.getUint32(o,true);
 const profile=u(8),romBytes=u(20),tracks=u(24),scriptsAt=u(36),scriptBytes=u(40),romAt=u(44);
 if(u(4)!==1||![1,2,3,4,5,6,7,8,9,10,11].includes(profile)||u(12)!==32768||u(16)!==280896||u(28)!==128||u(32)!==64||u(48)!==b.length)fail('Unsupported original Game Boy Advance profile');
 if(!Number.isInteger(rate)||rate<8000||rate>192000||!Number.isInteger(subsong)||subsong<0||subsong>=tracks||!tracks||tracks>64)fail('Game Boy Advance rate or subsong bounds');
 if(romBytes!==({"1": 4194304, "2": 4194304, "3": 4194304, "4": 4194304, "5": 4194304, "6": 4194304, "7": 4194304, "8": 8388608, "9": 4194304, "10": 4194304, "11": 4194304})[profile]||scriptsAt!==128+tracks*64||scriptBytes>262144||(scriptBytes%8)||romAt!==scriptsAt+scriptBytes||romAt+romBytes!==b.length)fail('Game Boy Advance capsule region bounds');
 if(hex(b.subarray(96,116))!=='1c61b54208ca6266129d0f2394c04bd8c44f98c5'||b.subarray(116,128).some(x=>x))fail('Game Boy Advance source revision differs');
 const table=b.subarray(128,scriptsAt),scripts=b.subarray(scriptsAt,romAt),rom=b.subarray(romAt);
 if(crc(table)!==u(52)||crc(scripts)!==u(56)||crc(rom)!==u(60))fail('Game Boy Advance original data checksum differs');
 const sha=hex(new Uint8Array(await crypto.subtle.digest('SHA-256',rom))),expected={"1": "c9f0a8cba357c954c84e8633e6f884cba8bf3f65828216d8d9441f12248b9d9b", "2": "644aefca56a7061aa620d6b0f7ecaa923618530721c683b32e3d61138ac05bbb", "3": "3d5c2f242710da7d34345bf7cd05fa7ae753b3ff1eabebfde7ba87a78737f6d4", "4": "a634d6e5f491fcca472692307eaabadac3cf4d4e9d0dc9af3cd13ee0f8526a70", "5": "d3c2c0d3add8979e4101e8ead73e6d6df06f530beb688c10be0a999232398c22", "6": "87eb602a50fc80f205cab2a22f1455b0d49c7cb777b568e15227aabc0a9b1d71", "7": "02a951f2918e13052f4b28844106093a28a3f9c78739434b1e49a2839babc333", "8": "0e047503cb6cbf837372a4cdf318b9eb6a7ceac2c8bf99837c9d8dd0b35b877f", "9": "1151b7b986881300b9c7db484a295833f7c12a6561b50f79fa1bce988bad9335", "10": "c7c40bc964e62fdda41f005dad3a8acfe7c80a0698fffa3c809df0ffa3e0f6af", "11": "23e247be81422f29dfc0e48c152d670292c304a8f9f35fe05e4dcb1b02a1a6c3"}[profile];
 if(sha!==expected||hex(b.subarray(64,96))!==sha)fail('Original Game Boy Advance cartridge identity differs');
 const rows=[];
 function script(at,count,total,limit){if(!count||count>1024||at%8||at+count*8>scriptBytes||!total||total>limit)fail('Game Boy Advance original input script bounds');let sum=0,result=[];for(let i=0;i<count;i++){const pos=scriptsAt+at+i*8,frames=u(pos),mask=u(pos+4);if(!frames||frames>limit||mask>0xffff)fail('Invalid Game Boy Advance original input event');sum+=frames;result.push({frames,mask});}if(sum!==total)fail('Game Boy Advance input timeline differs');return result;}
 for(let i=0;i<tracks;i++){const at=128+i*64;if(u(at)!==i||b.subarray(at+32,at+64).some(x=>x))fail('Invalid Game Boy Advance selection descriptor');const prefixFrames=u(at+12),playFrames=u(at+24),audioFrames=u(at+28);if(!audioFrames||audioFrames<playFrames*500||audioFrames>playFrames*600)fail('Game Boy Advance audio window differs');rows.push({prefix:script(u(at+4),u(at+8),prefixFrames,30000),play:script(u(at+16),u(at+20),playFrames,36000),prefixFrames,playFrames,audioFrames});}
 return{rom,sha,profile,rate,...rows[subsong]};
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);
 if(typeof GremlinGBAMusic==='undefined')importScripts('vendor/gba/gremlin-gba.js');
 const m=await GremlinGBAMusic({locateFile:n=>new URL('vendor/gba/'+n,self.location.href).href,print(){},printErr(){}});const ptr=m._malloc(p.rom.length);if(!ptr)fail('Game Boy Advance memory allocation failed');m.HEAPU8.set(p.rom,ptr);const failed=m._gremlin_gba_load(ptr,p.rom.length);m._free(ptr);if(!failed&&(m._gremlin_gba_source_rate()!==32768||Math.abs(m._gremlin_gba_source_fps()-59.727500915527344)>1e-10))fail('Original GBA clock profile differs');if(failed)fail(m.UTF8ToString(m._gremlin_gba_error())||'Original Game Boy Advance load failed');m._gremlin_gba_capture_video(0);
 for(const event of p.prefix)for(let frame=0;frame<event.frames;frame++){if(m._gremlin_gba_step(event.mask))fail('Original Game Boy Advance prefix failed');if(m._gremlin_gba_audio_frames()>4096)fail('Original Game Boy Advance frame profile differs');}
 if(token!==generation){m._gremlin_gba_close();return false;}
 if(state)state.m._gremlin_gba_close();state={m,...p,position:0,sourceFrames:0,generated:0,queue:new Float32Array(0),queueBase:0,playEvent:0,eventFrames:0};
 postMessage({type:'ready',decoder:'gba',devices:[{type:'gameboy_advance_arm_cpu_audio',core:'original mGBA0.10.3',source_revision:'1c61b54208ca6266129d0f2394c04bd8c44f98c5',source_rom_sha256:p.sha,original_CPU_APU_and_cartridge:true,original_GBA_PSG_and_DirectSound_FIFO:true,bios_profile:'original mGBA HLE, no external BIOS',source_master_clock_hz:16777216,source_frame_cycles:280896,frontend_reported_fps:59.727500915527344,source_audio_rate:32768,original_input_scripts:true,rendered_track:false,physical_board_measured:false}]});return true;
}
function generate(s){
 const event=s.play[s.playEvent];if(!event)fail('Game Boy Advance selected source window exhausted');if(s.m._gremlin_gba_step(event.mask))fail('Original Game Boy Advance music frame failed');const n=s.m._gremlin_gba_audio_frames();if(n>4096)fail('Original Game Boy Advance frame profile differs');const next=new Float32Array(s.queue.length+n*2);next.set(s.queue);const p=s.m._gremlin_gba_audio()>>1;for(let i=0;i<n*2;i++)next[s.queue.length+i]=s.m.HEAP16[p+i]/32768;s.queue=next;s.generated+=n;s.sourceFrames++;if(++s.eventFrames===event.frames){s.eventFrames=0;s.playEvent++;}
}
function sample(s,index,ch){if(index>=s.audioFrames)return 0;while(index>=s.queueBase+s.queue.length/2)generate(s);return s.queue[(index-s.queueBase)*2+ch];}
function render(message){
 const frames=message.frames;if(!Number.isInteger(frames)||frames<1||frames>16384)fail('Game Boy Advance output frame bounds');if(!state)fail('Game Boy Advance music not loaded');const s=state,pcm=new Float32Array(frames*2);
 for(let i=0;i<frames;i++,s.position++){const numerator=s.position*32768,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);pcm[i*2+ch]=a+(b-a)*rem/s.rate;}}
 const nextAt=Math.floor(s.position*32768/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,nextAt-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard;}
 postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
onmessage=({data:message})=>{
 if(message.type==='load'){const token=++generation;ready=load(message,token).catch(e=>{if(token===generation)postMessage({type:'error',message:String(e.message||e)});return false;});}
 else if(message.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(message);}).catch(e=>postMessage({type:'error',message:String(e.message||e)}));
};
