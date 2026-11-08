/* GPL-2.0-or-later. Original Game Boy Color music capsule transport.
   Full pinned original machine executes original game/DSP; no rendered song. */
let state,ready,generation=0;
function fail(message){throw Error(message);}
function crc(bytes){let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^(0xedb88320&-(c&1));}return(c^0xffffffff)>>>0;}
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
async function unpack(input){
 if(!(input instanceof ArrayBuffer)||input.byteLength>8000000)fail('Game Boy Color capsule input bounds');
 let bytes=new Uint8Array(input);
 if(bytes[0]===31&&bytes[1]===139){const reader=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')).getReader(),parts=[];let length=0;try{while(true){const{value,done}=await reader.read();if(done)break;length+=value.length;if(length>8000000)fail('Game Boy Color capsule expansion bounds');parts.push(value);}}finally{reader.releaseLock();}bytes=new Uint8Array(length);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length;}}
 return bytes;
}
async function parse(input,subsong,rate){
 const b=await unpack(input);if(b.length<128||b[0]!==71||b[1]!==66||b[2]!==77||b[3]!==26)fail('Invalid GBM1 header');const v=new DataView(b.buffer,b.byteOffset,b.byteLength),u=o=>v.getUint32(o,true);
 const profile=u(8),romBytes=u(20),tracks=u(24),scriptsAt=u(36),scriptBytes=u(40),romAt=u(44);
 if(u(4)!==1||![1,2,3,4,5].includes(profile)||u(12)!==32768||u(16)!==70224||u(28)!==128||u(32)!==64||u(48)!==b.length)fail('Unsupported original Game Boy Color profile');
 if(!Number.isInteger(rate)||rate<8000||rate>192000||!Number.isInteger(subsong)||subsong<0||subsong>=tracks||!tracks||tracks>16)fail('Game Boy Color rate or subsong bounds');
 if(romBytes!==({1:1048576,2:1048576,3:1048576,4:1048576,5:4194304})[profile]||scriptsAt!==128+tracks*64||scriptBytes>65536||(scriptBytes%8)||romAt!==scriptsAt+scriptBytes||romAt+romBytes!==b.length)fail('Game Boy Color capsule region bounds');
 if(hex(b.subarray(96,116))!=='77f2567d718b8973d02caa2daa8dce4953faf811'||b.subarray(116,128).some(x=>x))fail('Game Boy Color source revision differs');
 const table=b.subarray(128,scriptsAt),scripts=b.subarray(scriptsAt,romAt),rom=b.subarray(romAt);
 if(crc(table)!==u(52)||crc(scripts)!==u(56)||crc(rom)!==u(60))fail('Game Boy Color original data checksum differs');
 const sha=hex(new Uint8Array(await crypto.subtle.digest('SHA-256',rom))),expected={"1": "c8497ee7a25d1eb7cf4ef1604b2fd7836a64c5c30e8334f7a48f1ed2a27b5cc1", "2": "3f0688ed9219c44ce133647f0668cde50fdcba7ed8f04c34c3a0fc5ce596bfe5", "3": "d96da0c0c17a164c398b2d5b02cbe92e7eafbfa755fee649d159b46e61814dc6", "4": "0b3e5a9611551cc135cebfa67fe3f9aa1a54ef9855c0888b715351035908e126", "5": "bf88d77267fbe3cca6590c45bd74f779b6b458b92ff9d5016cd15ee2b0f6e774"}[profile];
 if(sha!==expected||hex(b.subarray(64,96))!==sha)fail('Original Game Boy Color cartridge identity differs');
 const rows=[];
 function script(at,count,total,limit){if(!count||count>256||at%8||at+count*8>scriptBytes||!total||total>limit)fail('Game Boy Color original input script bounds');let sum=0,result=[];for(let i=0;i<count;i++){const pos=scriptsAt+at+i*8,frames=u(pos),mask=u(pos+4);if(!frames||frames>limit||mask>0xffff)fail('Invalid Game Boy Color original input event');sum+=frames;result.push({frames,mask});}if(sum!==total)fail('Game Boy Color input timeline differs');return result;}
 for(let i=0;i<tracks;i++){const at=128+i*64;if(u(at)!==i||b.subarray(at+32,at+64).some(x=>x))fail('Invalid Game Boy Color selection descriptor');const prefixFrames=u(at+12),playFrames=u(at+24),audioFrames=u(at+28);if(!audioFrames||audioFrames<playFrames*500||audioFrames>playFrames*600)fail('Game Boy Color audio window differs');rows.push({prefix:script(u(at+4),u(at+8),prefixFrames,12000),play:script(u(at+16),u(at+20),playFrames,36000),prefixFrames,playFrames,audioFrames});}
 return{rom,sha,profile,rate,...rows[subsong]};
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);
 if(typeof GremlinGBCMusic==='undefined')importScripts('vendor/gbc/gremlin-gbc.js');
 const m=await GremlinGBCMusic({locateFile:n=>new URL('vendor/gbc/'+n,self.location.href).href,print(){},printErr(){}});const ptr=m._malloc(p.rom.length);if(!ptr)fail('Game Boy Color memory allocation failed');m.HEAPU8.set(p.rom,ptr);const failed=m._gremlin_gb_load(ptr,p.rom.length);m._free(ptr);if(!failed&&(m._gremlin_gb_source_rate()!==32768||Math.abs(m._gremlin_gb_source_fps()-4194304/70224)>1e-10))fail('Original GBC clock profile differs');if(failed)fail(m.UTF8ToString(m._gremlin_gb_error())||'Original Game Boy Color load failed');m._gremlin_gb_capture_video(0);
 for(const event of p.prefix)for(let frame=0;frame<event.frames;frame++){if(m._gremlin_gb_step(event.mask))fail('Original Game Boy Color prefix failed');if(m._gremlin_gb_audio_frames()>4096)fail('Original Game Boy Color frame profile differs');}
 if(token!==generation){m._gremlin_gb_close();return false;}
 if(state)state.m._gremlin_gb_close();state={m,...p,position:0,sourceFrames:0,generated:0,queue:new Float32Array(0),queueBase:0,playEvent:0,eventFrames:0};
 postMessage({type:'ready',decoder:'gbc',devices:[{type:'gameboy_color_cpu_apu',core:'original Gambatte',source_revision:'77f2567d718b8973d02caa2daa8dce4953faf811',source_rom_sha256:p.sha,original_CPU_APU_and_cartridge:true,source_audio_rate:32768,original_input_scripts:true,rendered_track:false,physical_board_measured:false}]});return true;
}
function generate(s){
 const event=s.play[s.playEvent];if(!event)fail('Game Boy Color selected source window exhausted');if(s.m._gremlin_gb_step(event.mask))fail('Original Game Boy Color music frame failed');const n=s.m._gremlin_gb_audio_frames();if(n>4096)fail('Original Game Boy Color frame profile differs');const next=new Float32Array(s.queue.length+n*2);next.set(s.queue);const p=s.m._gremlin_gb_audio()>>1;for(let i=0;i<n*2;i++)next[s.queue.length+i]=s.m.HEAP16[p+i]/32768;s.queue=next;s.generated+=n;s.sourceFrames++;if(++s.eventFrames===event.frames){s.eventFrames=0;s.playEvent++;}
}
function sample(s,index,ch){if(index>=s.audioFrames)return 0;while(index>=s.queueBase+s.queue.length/2)generate(s);return s.queue[(index-s.queueBase)*2+ch];}
function render(message){
 const frames=message.frames;if(!Number.isInteger(frames)||frames<1||frames>16384)fail('Game Boy Color output frame bounds');if(!state)fail('Game Boy Color music not loaded');const s=state,pcm=new Float32Array(frames*2);
 for(let i=0;i<frames;i++,s.position++){const numerator=s.position*32768,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);pcm[i*2+ch]=a+(b-a)*rem/s.rate;}}
 const nextAt=Math.floor(s.position*32768/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,nextAt-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard;}
 postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
onmessage=({data:message})=>{
 if(message.type==='load'){const token=++generation;ready=load(message,token).catch(e=>{if(token===generation)postMessage({type:'error',message:String(e.message||e)});return false;});}
 else if(message.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(message);}).catch(e=>postMessage({type:'error',message:String(e.message||e)}));
};
