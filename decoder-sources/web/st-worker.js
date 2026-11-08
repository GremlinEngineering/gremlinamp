/* GPL-2.0-or-later. Original Atari2600 music capsule transport.
   Full pinned original machine executes original game/DSP; no rendered song. */
let state,ready,generation=0;
function fail(message){throw Error(message);}
function crc(bytes){let c=0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^(0xedb88320&-(c&1));}return(c^0xffffffff)>>>0;}
const hex=b=>Array.from(b,x=>x.toString(16).padStart(2,'0')).join('');
async function unpack(input){
 if(!(input instanceof ArrayBuffer)||input.byteLength>40000000)fail('Atari2600 capsule input bounds');
 let bytes=new Uint8Array(input);
 if(bytes[0]===31&&bytes[1]===139){const reader=new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')).getReader(),parts=[];let length=0;try{while(true){const{value,done}=await reader.read();if(done)break;length+=value.length;if(length>40000000)fail('Atari2600 capsule expansion bounds');parts.push(value);}}finally{reader.releaseLock();}bytes=new Uint8Array(length);let at=0;for(const p of parts){bytes.set(p,at);at+=p.length;}}
 return bytes;
}
async function parse(input,subsong,rate){
 const b=await unpack(input);if(b.length<128||b[0]!==83||b[1]!==84||b[2]!==77||b[3]!==26)fail('Invalid STM1 header');const v=new DataView(b.buffer,b.byteOffset,b.byteLength),u=o=>v.getUint32(o,true);
 const profile=u(8),romBytes=u(20),tracks=u(24),scriptsAt=u(36),scriptBytes=u(40),romAt=u(44);
 if(u(4)!==1||![1,2,3,4,5,6,7,8,9].includes(profile)||u(12)!==31400||u(16)!==1498||u(28)!==128||u(32)!==64||u(48)!==b.length)fail('Unsupported original Atari2600 profile');
 if(!Number.isInteger(rate)||rate<8000||rate>192000||!Number.isInteger(subsong)||subsong<0||subsong>=tracks||!tracks||tracks>64)fail('Atari2600 rate or subsong bounds');
 if(romBytes!==({"1": 8192, "2": 8192, "3": 4096, "4": 8192, "5": 8192, "6": 4096, "7": 4096, "8": 4096, "9": 4096})[profile]||scriptsAt!==128+tracks*64||scriptBytes>262144||(scriptBytes%8)||romAt!==scriptsAt+scriptBytes||romAt+romBytes!==b.length)fail('Atari2600 capsule region bounds');
 if(hex(b.subarray(96,116))!=='7d1361e407e63f29e52892655069e5fb4096e691'||b.subarray(116,128).some(x=>x))fail('Atari2600 source revision differs');
 const table=b.subarray(128,scriptsAt),scripts=b.subarray(scriptsAt,romAt),rom=b.subarray(romAt);
 if(crc(table)!==u(52)||crc(scripts)!==u(56)||crc(rom)!==u(60))fail('Atari2600 original data checksum differs');
 const sha=hex(new Uint8Array(await crypto.subtle.digest('SHA-256',rom))),expected={"1": "c66996f41f8f8d8a276db6c52dee161f6787b4a99d1cf125f2260869a60b1aab", "2": "3c6f3b5f0d0a5707de1a263a44d6edb49d91fef352fd863e2a9818f427d129bd", "3": "c1bd22c6b84c59a00af425a35f43a65f5e64f0fa401bdf277db4106d35170763", "4": "157835de83ddfb0008d96ea50ed2d143cbece4ab1b10cfd5050ab91adf34be47", "5": "d1a6e808412b4535c7e54fc25414943a9418b1095b4d4969dc33290f7eaa72a4", "6": "7224b17462b992d67f4e06a3c85f269c9822b06df6015bf038b55f384ced0301", "7": "e3c7ed7a073fe8f4b2711bebd624d0b249957daa0ebb93d78823fe36b235ba8c", "8": "46c444b91854e3652cc00facf52101d3c9fa3528eaba7674cc790acc3ad59086", "9": "58e781b472e0583b851b7e56cbfe87bd8294a291e6d39d49d7c999107ed5b918"}[profile];
 if(sha!==expected||hex(b.subarray(64,96))!==sha)fail('Original Atari2600 cartridge identity differs');
 const rows=[];
 function script(at,count,total,limit){if(!count||count>1024||at%8||at+count*8>scriptBytes||!total||total>limit)fail('Atari2600 original input script bounds');let sum=0,result=[];for(let i=0;i<count;i++){const pos=scriptsAt+at+i*8,frames=u(pos),mask=u(pos+4);if(!frames||frames>limit||mask>0xffff)fail('Invalid Atari2600 original input event');sum+=frames;result.push({frames,mask});}if(sum!==total)fail('Atari2600 input timeline differs');return result;}
 for(let i=0;i<tracks;i++){const at=128+i*64;if(u(at)!==i||b.subarray(at+32,at+64).some(x=>x))fail('Invalid Atari2600 selection descriptor');const prefixFrames=u(at+12),playFrames=u(at+24),audioFrames=u(at+28);if(!audioFrames||audioFrames<playFrames*500||audioFrames>playFrames*600)fail('Atari2600 audio window differs');rows.push({prefix:script(u(at+4),u(at+8),prefixFrames,30000),play:script(u(at+16),u(at+20),playFrames,36000),prefixFrames,playFrames,audioFrames});}
 return{rom,sha,profile,rate,...rows[subsong]};
}
async function load(message,token){
 const p=await parse(message.bytes,message.subsong??0,message.sampleRate);
 if(typeof GremlinSTMusic==='undefined')importScripts('vendor/st/gremlin-st.js');
 const m=await GremlinSTMusic({locateFile:n=>new URL('vendor/st/'+n,self.location.href).href,print(){},printErr(){}});const ptr=m._malloc(p.rom.length);if(!ptr)fail('Atari2600 memory allocation failed');m.HEAPU8.set(p.rom,ptr);const failed=m._gremlin_st_load(ptr,p.rom.length);m._free(ptr);if(!failed&&(m._gremlin_st_source_rate()!==31400||Math.abs(m._gremlin_st_source_fps()-59.92)>1e-10))fail('Original WS clock profile differs');if(failed)fail(m.UTF8ToString(m._gremlin_st_error())||'Original Atari2600 load failed');m._gremlin_st_capture_video(0);
 for(const event of p.prefix)for(let frame=0;frame<event.frames;frame++){if(m._gremlin_st_step(event.mask))fail('Original Atari2600 prefix failed');if(m._gremlin_st_audio_frames()>4096)fail('Original Atari2600 frame profile differs');}
 if(token!==generation){m._gremlin_st_close();return false;}
 if(state)state.m._gremlin_st_close();state={m,...p,position:0,sourceFrames:0,generated:0,queue:new Float32Array(0),queueBase:0,playEvent:0,eventFrames:0};
 postMessage({type:'ready',decoder:'st',devices:[{type:'atari2600_6507_tia',core:'original Stella2014',source_revision:'7d1361e407e63f29e52892655069e5fb4096e691',source_rom_sha256:p.sha,original_CPU_APU_and_cartridge:true,original_TIA_synth_and_6507_CPU:true,bios_profile:'original core initialization, no external BIOS',frontend_fps_num:1498,frontend_fps_den:25,frontend_reported_fps:59.92,source_audio_rate:31400,original_input_scripts:true,rendered_track:false,physical_board_measured:false}]});return true;
}
function generate(s){
 const event=s.play[s.playEvent];if(!event)fail('Atari2600 selected source window exhausted');if(s.m._gremlin_st_step(event.mask))fail('Original Atari2600 music frame failed');const n=s.m._gremlin_st_audio_frames();if(n>4096)fail('Original Atari2600 frame profile differs');const next=new Float32Array(s.queue.length+n*2);next.set(s.queue);const p=s.m._gremlin_st_audio()>>1;for(let i=0;i<n*2;i++)next[s.queue.length+i]=s.m.HEAP16[p+i]/32768;s.queue=next;s.generated+=n;s.sourceFrames++;if(++s.eventFrames===event.frames){s.eventFrames=0;s.playEvent++;}
}
function sample(s,index,ch){if(index>=s.audioFrames)return 0;while(index>=s.queueBase+s.queue.length/2)generate(s);return s.queue[(index-s.queueBase)*2+ch];}
function render(message){
 const frames=message.frames;if(!Number.isInteger(frames)||frames<1||frames>16384)fail('Atari2600 output frame bounds');if(!state)fail('Atari2600 music not loaded');const s=state,pcm=new Float32Array(frames*2);
 for(let i=0;i<frames;i++,s.position++){const numerator=s.position*31400,at=Math.floor(numerator/s.rate),rem=numerator%s.rate;for(let ch=0;ch<2;ch++){const a=sample(s,at,ch),b=sample(s,at+1,ch);pcm[i*2+ch]=a+(b-a)*rem/s.rate;}}
 const nextAt=Math.floor(s.position*31400/s.rate),discard=Math.max(0,Math.min(s.queue.length/2,nextAt-s.queueBase));if(discard){s.queue=s.queue.slice(discard*2);s.queueBase+=discard;}
 postMessage({type:'pcm',pcm:pcm.buffer},[pcm.buffer]);
}
onmessage=({data:message})=>{
 if(message.type==='load'){const token=++generation;ready=load(message,token).catch(e=>{if(token===generation)postMessage({type:'error',message:String(e.message||e)});return false;});}
 else if(message.type==='render')Promise.resolve(ready).then(ok=>{if(ok)render(message);}).catch(e=>postMessage({type:'error',message:String(e.message||e)}));
};
