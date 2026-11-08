/* Routing is by the published asset format, never by a game's display label. */
export const DECODERS=Object.freeze({
  k0:Object.freeze(['k0m','k0z']),
  g0:Object.freeze(['g0m','g0z']),
  dcs:Object.freeze(['dcm','dcz']),
  a5:Object.freeze(['a5m','a5z']),
  nf:Object.freeze(['nfm','nfz']),
  vx:Object.freeze(['vxm','vxz']),
  sv:Object.freeze(['svm','svz']),
  st:Object.freeze(['stm','stz']),
  iv:Object.freeze(['ivm','ivz']),
  ws:Object.freeze(['wsm','wsz']),
  gba:Object.freeze(['agm','agz']),
  md:Object.freeze(['mdm','mdz']),
  gbc:Object.freeze(['gbm','gbz']),
  jaguar:Object.freeze(['jgm','jgz']),
  cps3:Object.freeze(['c3m','c3z']),
  mario:Object.freeze(['mrm']),
  dkong:Object.freeze(['dkm']),
  ki:Object.freeze(['kim','kiz']),
  f3:Object.freeze(['f3m','f3z']),
  astro:Object.freeze(['acm','acz']),
  enson:Object.freeze(['e6m','e6z']),
  bsmt:Object.freeze(['bms','bmz']),
  model2:Object.freeze(['m2s','m2z']),
  ngp:Object.freeze(['ngl','ngz']),
  lynx:Object.freeze(['lsm','lsz']),
  spt:Object.freeze(['spt','spz']),
  'theme-snes':Object.freeze(['snes-registers']),
  gme:Object.freeze(['ay','gym','hes','nsf','nsfe','sap','spc']),
  'gme-plus':Object.freeze(['sgc','gbs']),
  wsr:Object.freeze(['wsr']),
  namco:Object.freeze(['nws','nwz']),
  libvgm:Object.freeze(['vgm','vgz','s98','tmc']),
  atari:Object.freeze(['a26','a78']),
  broad:Object.freeze(['kss','kssx','kscc','psf','minipsf','xa',
    'usf','miniusf','sid','psid','rsid','ssf','minissf','dsf','minidsf',
    'mod','xm','it','s3m','mtm','stm']),
  gsf:Object.freeze(['gsf','minigsf'])
});
export function decoderFor(track,bytes){
  const format=String(track.asset?.format||track.format||'').toLowerCase().replace(/^\./,'');
  if(format==='nsf'&&bytes){
    const b=bytes instanceof Uint8Array?bytes:new Uint8Array(bytes);
    if(b.length>=128&&b[0]===78&&b[1]===69&&b[2]===83&&b[3]===77&&b[4]===26&&
       (b[123]&4)&&((b[8]|b[9]<<8)<0x8000))
      return {id:'gme-plus',format,worker:'gme-plus-worker.js'};
  }
  for(const [id,formats] of Object.entries(DECODERS)){
    if(formats.includes(format))return {id,format,worker:['broad','gsf'].includes(id)?'codec-worker.js':id+'-worker.js'};
  }
  throw Error('Для формата '+(format||'без расширения')+' нужен совместимый декодер');
}
export function virtualPath(value){
  if(typeof value!=='string'||!value||value.startsWith('/')||/[\\\u0000-\u001f|]/.test(value)||value.split('/').some(p=>!p||p==='.'||p==='..'))throw Error('Недопустимое имя музыкального файла');
  return value;
}
export function inputName(track,url){
  return virtualPath(track.asset.path||track.original_filename||decodeURIComponent(new URL(url).pathname.split('/').pop()));
}
