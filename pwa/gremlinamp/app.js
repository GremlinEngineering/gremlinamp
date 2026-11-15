import {clock, options, radioPanel, trackerData, trackerPanel, trackers} from './live.js';
import {app, element, getJSON, message, offline, remember, rememberFiles, sameOrigin} from './site.js';
import {DecoderPlayback, capsuleRates, inputName, routeFile, trackDuration, unpackCapsule, virtualPath} from './decoder.js';
import {readDecoder, readMedia} from './arrangement.js';

const catalogURL = new URL('/music/game-index.json', location.origin);
const localFormats = new Set(['wav', 'aac', 'mp3', 'm4a', 'flac', 'ogg', 'oga']);
const music = (value, base = catalogURL) => sameOrigin(value, base, '/music/');
const audio = new Audio();
audio.preload = 'metadata';
const queueStyles = {auto: 'Авто', drone: 'Дрон-эмбиент', dub: 'Даб-эмбиент', downtempo: 'Даунтемпо'};
const arrangementKey = 'gremlinamp.queue-arrangement';
let arrangeQueue = false, queueStyle = 'auto';
try {
    const preference = JSON.parse(localStorage.getItem(arrangementKey));
    arrangeQueue = preference?.enabled === true;
    if (Object.hasOwn(queueStyles, preference?.style)) queueStyle = preference.style;
} catch {}
let context, playback, catalog = [], saved = {}, game, list, playing, generation = 0;
let loading = null, trackLoad;
let available, trackerTimer, microphone, meterTimer, liveTimeout;

app.classList.add('stack');
app.innerHTML = `
<nav class="controls" aria-label="Разделы GremlinAmp">
  <button type="button" data-page="library" aria-pressed="true">Музыка</button>
  <button type="button" data-page="trackers" aria-pressed="false">Трекеры</button>
  <button type="button" data-page="radio" aria-pressed="false">Радио</button>
  <button type="button" id="sleep-toggle">Таймер сна</button>
</nav>
<dialog id="sleep" class="stack" aria-label="Таймер сна">
  <h2>Таймер сна</h2>
  <p id="sleep-state" class="muted">Остановить воспроизведение через</p>
  <div class="controls">
    <button type="button" id="sleep-less">−5 мин</button>
    <output id="sleep-minutes">15 мин</output>
    <button type="button" id="sleep-more">+5 мин</button>
  </div>
  <div class="controls">
    <button type="button" id="sleep-start">Запустить</button>
    <button type="button" id="sleep-cancel" hidden>Выключить таймер</button>
    <button type="button" id="sleep-close">Закрыть</button>
  </div>
</dialog>
<div class="controls" id="live-bar" hidden>
  <span id="live-title" role="status"></span>
  <button type="button" id="live-toggle">Пауза</button>
  <button type="button" id="live-stop">Стоп</button>
</div>
<div class="columns" id="library">
  <section class="stack">
    <h2>Каталог</h2>
    <div class="controls">
      <input type="search" id="search" placeholder="Найти игру" aria-label="Найти игру">
      <select id="platform" aria-label="Платформа"><option value="">Все платформы</option></select>
      <button type="button" id="saved" aria-pressed="false">Офлайн</button>
    </div>
    <ul class="list" id="catalog" aria-label="Игры"></ul>
  </section>
  <section class="stack">
    <h2 id="game">Выберите игру</h2>
    <button type="button" id="pin" hidden>Сохранить игру офлайн</button>
    <article class="stack" aria-label="Плеер">
      <h3 id="now">Выберите трек</h3>
      <input id="seek" type="range" min="0" max="1" value="0" step="0.1" aria-label="Позиция трека" disabled>
      <div class="controls">
        <button type="button" id="previous" aria-label="Предыдущий трек">⏮</button>
        <button type="button" id="backward" aria-label="Назад на 10 секунд">−10 с</button>
        <button type="button" id="play">Играть</button>
        <button type="button" id="forward" aria-label="Вперёд на 10 секунд">+10 с</button>
        <button type="button" id="next" aria-label="Следующий трек">⏭</button>
        <span id="time" class="muted">0:00 / —</span>
      </div>
      <div class="controls">
        <button type="button" id="queue-arrangement" aria-pressed="false">Трекерная аранжировка</button>
        <select id="queue-style" aria-label="Стиль аранжировки">
          ${Object.entries(queueStyles).map(([value, title]) => `<option value="${value}">${title}</option>`).join('')}
        </select>
      </div>
      <div class="muted" id="queue-status" role="status" hidden></div>
    </article>
    <ul class="list" id="track-list" aria-label="Треки"></ul>
  </section>
</div>
<section id="trackers" class="stack" hidden></section>
<section id="radio" class="stack" hidden></section>`;
const $ = id => document.getElementById(id);
const fail = error => message(error.message || String(error));
const progress = (done, total) => message(`Сохранение ${done} / ${total}`);
const time = seconds => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;

const sleepStep = 5, sleepMaximum = 720;
let sleepDeadline = 0, sleepTimeout, sleepMinutes = 15;
function drawSleep() {
    const remaining = Math.max(0, Math.ceil((sleepDeadline - Date.now()) / 1000));
    const label = remaining ? time(remaining) : '';
    $('sleep-toggle').textContent = label ? 'Таймер сна · ' + label : 'Таймер сна';
    $('sleep-state').textContent = label ? 'Осталось ' + label : 'Остановить воспроизведение через';
    $('sleep-minutes').textContent = sleepMinutes + ' мин';
    $('sleep-less').disabled = sleepMinutes === sleepStep;
    $('sleep-more').disabled = sleepMinutes === sleepMaximum;
    $('sleep-cancel').hidden = !remaining;
    $('sleep-start').textContent = remaining ? 'Перезапустить' : 'Запустить';
}
function checkSleep() {
    clearTimeout(sleepTimeout);
    if (sleepDeadline) {
        const remaining = sleepDeadline - Date.now();
        if (remaining > 0) sleepTimeout = setTimeout(checkSleep, Math.min(1000, remaining));
        else {
            sleepDeadline = 0;
            stopSound();
            message('Воспроизведение остановлено по таймеру сна');
        }
    }
    drawSleep();
}
$('sleep-toggle').onclick = () => {
    checkSleep();
    sleepMinutes = sleepDeadline ? Math.max(sleepStep, Math.ceil((sleepDeadline - Date.now()) / 300000) * sleepStep) : 15;
    drawSleep();
    $('sleep').showModal();
};
$('sleep-less').onclick = () => { sleepMinutes -= sleepStep; drawSleep(); };
$('sleep-more').onclick = () => { sleepMinutes += sleepStep; drawSleep(); };
$('sleep-start').onclick = () => { sleepDeadline = Date.now() + sleepMinutes * 60000; checkSleep(); $('sleep').close(); };
$('sleep-cancel').onclick = () => { sleepDeadline = 0; checkSleep(); $('sleep').close(); };
$('sleep-close').onclick = () => $('sleep').close();
document.addEventListener('visibilitychange', checkSleep);
window.addEventListener('pageshow', checkSleep);

function drawCatalog() {
    const search = $('search').value.trim().toLocaleLowerCase('ru');
    const platform = $('platform').value;
    const pinned = $('saved').getAttribute('aria-pressed') === 'true';
    const matches = catalog.filter(item => (!pinned || saved[item.id])
        && (!platform || item.platform === platform) && item.title.toLocaleLowerCase('ru').includes(search));
    $('catalog').replaceChildren(...matches.map(item => {
        const row = element('li'), button = element('button', item.title, {'aria-current': String(item.id === game?.id)});
        button.append(element('small', `${item.platform_label || item.platform} · ${item.track_count} · ${saved[item.id] ? 'офлайн' : 'каталог'}`));
        button.onclick = () => void openGame(item, pinned).catch(fail);
        row.append(button);
        return row;
    }));
    if (!matches.length) $('catalog').append(element('li', 'Игры не найдены', {class: 'muted'}));
}

async function openGame(item, pinned) {
    const request = Symbol();
    loading = request;
    const local = saved[item.id];
    const url = music(local && pinned ? local.url : item.playlist);
    const data = local && (pinned || !navigator.onLine) ? local.data : await getJSON(url);
    if (request !== loading) return;
    if (!Array.isArray(data.tracks)) throw new Error('Треклист недоступен');
    game = item;
    list = {url: local && (pinned || !navigator.onLine) ? local.url : url.href, data};
    $('game').textContent = item.title;
    $('pin').hidden = false;
    $('pin').textContent = saved[item.id] ? 'Обновить офлайн-копию' : 'Сохранить игру офлайн';
    drawCatalog();
    drawTracks();
}

function drawTracks() {
    $('track-list').replaceChildren(...list.data.tracks.map((track, index) => {
        const row = element('li'), button = element('button', track.title || `Трек ${index + 1}`);
        const format = track.asset?.format || track.format || '';
        button.append(element('small', format.toUpperCase()));
        button.dataset.position = index;
        button.onclick = () => void playTrack(list, index).catch(fail);
        row.append(button);
        return row;
    }));
    drawPlayer();
}
function assets(track, source) {
    const fallback = ['psf2', 'minipsf2'].includes(track.asset?.format) && track.browser_render;
    const main = fallback ? (fallback.asset || fallback) : track.asset;
    if (!main?.url) throw new Error('У трека нет файла');
    return [main, ...(fallback ? [] : main.dependencies || [])].map(item => ({...item, url: music(item.url, source)}));
}

function currentTime() { return playback?.seconds ?? (playing?.media ? audio.currentTime : 0); }
function duration() { if (playing?.radio || playing?.tracker != null) return null; return playback?.duration ?? (playing?.media && Number.isFinite(audio.duration) ? audio.duration : null); }
function drawPlayer() {
    const current = currentTime(), total = duration();
    const isPlaying = playback?.state === 'playing' || playing?.media && !audio.paused;
    $('now').textContent = playing?.track.title || 'Выберите трек';
    $('play').textContent = isPlaying ? 'Пауза' : 'Играть';
    $('play').disabled = !!playing?.list && playback?.state === 'loading';
    $('seek').disabled = !total;
    $('seek').max = total || 1;
    if (document.activeElement !== $('seek')) $('seek').value = current;
    $('time').textContent = `${time(current || 0)} / ${total ? time(total) : '—'}`;
    $('previous').disabled = !playing?.list || playing.index === 0;
    $('next').disabled = !playing?.list || playing.index + 1 >= playing.list.data.tracks.length;
    $('forward').disabled = $('backward').disabled = !total;
    $('queue-arrangement').setAttribute('aria-pressed', String(arrangeQueue));
    $('queue-style').value = queueStyle;
    const style = playback?.metadata?.style;
    const status = arrangeQueue && playing?.list && (playing.fallback ? 'Оригинал · аранжировка недоступна' :
        playing.arranged && (playback?.state === 'loading' ? 'Подготовка аранжировки…' : queueStyles[style]));
    $('queue-status').hidden = !status;
    $('queue-status').textContent = status || '';
    $('live-bar').hidden = !playing || !!playing.list;
    $('live-title').textContent = playing?.list ? '' : [playing?.track.title, playing?.stamp].filter(Boolean).join(' · ');
    $('live-toggle').textContent = playing?.radio && audio.paused && playing.failed ? 'Повторить' : isPlaying ? 'Пауза' : 'Играть';
    $('live-toggle').disabled = !playing || playback?.state === 'loading';
    for (const button of $('track-list').querySelectorAll('button')) {
        button.setAttribute('aria-current', String(!!playing?.list && playing.list === list && +button.dataset.position === playing.index));
    }
}

async function fetchBytes(asset, signal) {
    const response = await fetch(asset.url, {signal});
    if (!response.ok) throw new Error(`Аудиофайл недоступен (HTTP ${response.status})`);
    return response.arrayBuffer();
}

async function nativeInput(track, format, asset, libs, signal) {
    let bytes = await fetchBytes(asset, signal);
    let route = routeFile(format, bytes);
    if (format === 'stm' || Object.hasOwn(capsuleRates, route.id) || ['a5', 'nf', 'dcs'].includes(route.id)) {
        bytes = await unpackCapsule(window, bytes);
        route = routeFile(format, bytes);
    }
    const subsong = track.subsong ?? 0;
    const dependencies = await Promise.all(libs.map(async item => ({path: virtualPath(item.path), bytes: await fetchBytes(item, signal)})));
    const limit = track.duration_known && track.play_ms > 0 ? (track.play_ms + (track.fade_ms || 0)) / 1000 : trackDuration(format, bytes, route, subsong);
    return {route, limit, input: {decoder: route.id, format: route.format,
        filename: inputName(track, asset.url), bytes, dependencies, subsong, renderMs: (limit ?? 120) * 1000}};
}

async function playTrack(source, index, {position = 0, progress = null, paused = false, fallback = false} = {}) {
    const track = source.data.tracks[index];
    if (!track) return;
    stopSound();
    const request = generation;
    trackLoad = new AbortController();
    const {signal} = trackLoad;
    const [asset, ...libs] = assets(track, source.url);
    const format = (asset.format || track.format || '').toLowerCase();
    const localMedia = localFormats.has(format);
    const arranged = arrangeQueue && !fallback;
    const media = localMedia && !arranged;
    const style = queueStyle;
    playing = {list: source, index, track, media, arranged, fallback, progress, paused};
    publishMediaTitle(track.title);
    message('');
    if (media) {
        if (progress !== null) {
            audio.addEventListener('loadedmetadata', () => {
                if (Number.isFinite(audio.duration)) audio.currentTime = progress * audio.duration;
                drawPlayer();
            }, {once: true, signal});
        }
        audio.src = asset.url;
        if (position > 0) audio.currentTime = position;
        if (!paused) {
            try { await audio.play(); }
            catch (error) { if (!signal.aborted) throw error; }
        }
        drawPlayer();
        return;
    }
    if (!context || context.state === 'closed') context = new AudioContext();
    const decoder = new DecoderPlayback(window, () => {
        if (request !== generation) return;
        drawPlayer();
        if (decoder.state === 'ended') skip(1);
        if (decoder.state === 'error') {
            if (arranged) void playTrack(source, index, {position, progress, paused, fallback: true}).catch(fail);
            else fail(decoder.error);
        }
    }, {position, progress, paused, context});
    playback = decoder;
    drawPlayer();
    try {
        if (arranged) {
            let samples;
            if (localMedia) {
                samples = await readMedia(await fetchBytes(asset, signal), signal);
            } else {
                const {route, input, limit} = await nativeInput(track, format, asset, libs, signal);
                samples = await readDecoder(route, input, limit, signal);
            }
            if (!signal.aborted) await decoder.start({worker: 'arrangement-worker.js'},
                {samples, style, id: String(track.id ?? `${asset.url.href}#${track.subsong ?? 0}`)});
        } else {
            const {route, input, limit} = await nativeInput(track, format, asset, libs, signal);
            if (!signal.aborted) await decoder.start(route, input, limit);
        }
    } catch (error) {
        if (request === generation) decoder.fail(error);
    }
}
function skip(step) {
    if (playing?.list) void playTrack(playing.list, playing.index + step).catch(fail);
}
function seek(value) {
    if (!playing?.list || !duration()) return;
    const position = Math.max(0, Math.min(value, Math.max(0, duration() - 0.1)));
    if (playing.media) audio.currentTime = position;
    else if (!playback?.seek(position)) {
        void playTrack(playing.list, playing.index, {position, paused: playback?.state === 'paused', fallback: playing.fallback}).catch(fail);
    }
}

function arrangementPreference(enabled, style) {
    const restart = enabled !== arrangeQueue || enabled && style !== queueStyle;
    arrangeQueue = enabled;
    queueStyle = style;
    try { localStorage.setItem(arrangementKey, JSON.stringify({enabled, style})); } catch {}
    if (!restart || !playing?.list) { drawPlayer(); return; }
    const total = duration();
    const progress = total ? Math.min(0.999, currentTime() / total) : playing.progress;
    const paused = playing.media ? audio.paused :
        playback?.state === 'loading' ? playing.paused : playback?.state === 'paused';
    void playTrack(playing.list, playing.index, {progress, paused}).catch(fail);
}

$('queue-arrangement').onclick = () => arrangementPreference(!arrangeQueue, queueStyle);
$('queue-style').onchange = () => arrangementPreference(arrangeQueue, $('queue-style').value);
function toggle() {
    if (playing?.radio) {
        if (audio.paused) void startRadio(playing.radio).catch(fail);
        else {
            clearTimeout(liveTimeout);
            audio.pause();
            audio.removeAttribute('src');
            audio.load();
            message('Эфир на паузе');
            drawPlayer();
        }
        return;
    }
    if (playing?.media) {
        if (audio.ended) audio.currentTime = 0;
        if (audio.paused) void audio.play().catch(fail);
        else audio.pause();
    } else if (['playing', 'paused'].includes(playback?.state)) void playback.toggle();
    else if (playing?.tracker != null) void startTracker(playing.tracker).catch(fail);
    else if (playing?.list) void playTrack(playing.list, playing.index).catch(fail);
    else if (list) void playTrack(list, 0).catch(fail);
}
$('play').onclick = $('live-toggle').onclick = toggle;
$('previous').onclick = () => skip(-1);
$('next').onclick = () => skip(1);
$('backward').onclick = () => seek(currentTime() - 10);
$('forward').onclick = () => seek(currentTime() + 10);
$('seek').onchange = () => seek(+$('seek').value);
for (const event of ['timeupdate', 'durationchange', 'play', 'pause']) audio.addEventListener(event, drawPlayer);
audio.addEventListener('ended', () => {
    if (playing?.radio) radioInterrupted('Эфир завершился. Нажмите «Повторить».');
    else skip(1);
});
audio.addEventListener('error', () => {
    if (!playing?.media || !audio.hasAttribute('src')) return;
    if (playing.radio) { radioInterrupted('Эфир недоступен. Нажмите «Повторить».'); return; }
    message('Не удалось открыть аудиофайл');
    drawPlayer();
});
audio.addEventListener('playing', () => {
    if (!playing?.radio || !audio.hasAttribute('src') || audio.paused) return;
    clearTimeout(liveTimeout);
    liveTimeout = undefined;
    message('Прямой эфир');
    drawPlayer();
});
audio.addEventListener('waiting', () => {
    if (!playing?.radio || !audio.hasAttribute('src') || audio.paused || liveTimeout !== undefined) return;
    message('Ожидание данных эфира…');
    watchRadio(generation);
});
$('search').oninput = $('platform').onchange = drawCatalog;
$('saved').onclick = () => {
    $('saved').setAttribute('aria-pressed', String($('saved').getAttribute('aria-pressed') !== 'true'));
    drawCatalog();
};
$('pin').onclick = async () => {
    const item = game;
    const button = $('pin');
    button.disabled = true;
    try {
        await available;
        const url = music(item.playlist);
        const data = await getJSON(url);
        if (!Array.isArray(data.tracks)) throw new Error('Треклист недоступен');
        const source = {url: url.href, data};
        const files = new Map(source.data.tracks.flatMap(track => assets(track, source.url)).map(asset => [asset.url.href, asset]));
        await rememberFiles([...files.values()], progress);
        saved = await remember('games', {...saved, [item.id]: {url: source.url, data: source.data, game: item}});
        drawCatalog();
        if (game.id === item.id) button.textContent = 'Обновить офлайн-копию';
        message(`${item.title} сохранена для офлайн-прослушивания`);
    } catch (error) { fail(error); }
    finally { button.disabled = false; }
};

for (const button of app.querySelectorAll('[data-page]')) button.onclick = () => {
    for (const tab of app.querySelectorAll('[data-page]')) {
        const active = tab === button;
        tab.setAttribute('aria-pressed', String(active));
        $(tab.dataset.page).hidden = !active;
    }
};
trackerPanel($('trackers'), mode => void startTracker(mode).catch(fail), (mode, key) => {
    if (playing?.tracker !== mode) return;
    sendTracker({options: options(mode)});
    if (key === 'microphone') {
        if (options(mode).microphone) void startMicrophone(generation).catch(fail);
        else stopMicrophone();
    }
});
void radioPanel($('radio'), station => void startRadio(station).catch(fail), () => {
    if (playing?.radio?.personal) { stopSound(); message(''); }
}).catch(fail);
$('live-stop').onclick = stopSound;

function stopMicrophone() {
    clearInterval(meterTimer);
    microphone?.getTracks().forEach(track => track.stop());
    microphone = null;
}
function stopSound() {
    ++generation;
    trackLoad?.abort();
    trackLoad = null;
    clearTimeout(liveTimeout);
    liveTimeout = undefined;
    clearInterval(trackerTimer);
    stopMicrophone();
    playback?.stop();
    playback = null;
    playing = null;
    audio.preload = 'metadata';
    audio.pause();
    audio.removeAttribute('src');
    audio.load();
    drawPlayer();
}
function publishMediaTitle(title) {
    if (navigator.mediaSession && typeof MediaMetadata === 'function')
        navigator.mediaSession.metadata = new MediaMetadata({title, artist: 'GremlinAmp'});
    if (navigator.audioSession) navigator.audioSession.type = 'playback';
}
function sendTracker(data) {
    playback?.decoder?.worker.postMessage({type: 'settings', ...data});
}
async function startMicrophone(request) {
    if (microphone) return;
    const stream = await navigator.mediaDevices.getUserMedia({audio: true});
    if (request !== generation || !options(8).microphone || microphone) {
        stream.getTracks().forEach(track => track.stop());
        return;
    }
    microphone = stream;
    const source = context.createMediaStreamSource(stream);
    const analyser = context.createAnalyser();
    analyser.fftSize = 2048;
    source.connect(analyser);
    const samples = new Float32Array(2048);
    meterTimer = setInterval(() => {
        analyser.getFloatTimeDomainData(samples);
        // The engine expects 16 kHz. Send short input blocks and keep them local.
        const factor = context.sampleRate / 16000;
        const street = Float32Array.from({length: Math.min(1280, Math.floor(samples.length / factor))},
            (_, index) => samples[Math.floor(index * factor)]);
        sendTracker({street});
    }, 90);
    stream.getAudioTracks()[0].onended = () => {
        source.disconnect();
        if (stream === microphone) stopMicrophone();
    };
}
async function startTracker(mode) {
    stopSound();
    const request = generation;
    const [title] = trackers[mode];
    playing = {tracker: mode, track: {title}};
    publishMediaTitle(title);
    if (!context || context.state === 'closed') context = new AudioContext();
    const decoder = new DecoderPlayback(window, () => {
        if (request !== generation) return;
        if (decoder.state === 'error') { const error = decoder.error; stopSound(); fail(error); return; }
        drawPlayer();
    }, {context});
    playback = decoder;
    drawPlayer();
    message('Запуск трекера…');
    if (mode === 8 && options(mode).microphone) void startMicrophone(request).catch(fail);
    const initial = await trackerData(mode, false);
    if (request !== generation) return;
    playing.stamp = initial.stamp;
    const seed = crypto.getRandomValues(new Uint32Array(1))[0];
    await decoder.start({worker: 'tracker-worker.js'}, {mode, seed, ...initial, minute: clock(), options: options(mode)}, undefined);
    if (request !== generation) return;
    message('');
    const update = async () => {
        const data = await trackerData(mode, true);
        if (request !== generation) return;
        playing.stamp = data.stamp;
        sendTracker({...data, minute: clock(), options: options(mode)});
        drawPlayer();
    };
    let nextRefresh = 0;
    trackerTimer = setInterval(() => {
        sendTracker({minute: clock()});
        if (Date.now() >= nextRefresh) {
            nextRefresh = Date.now() + 600000;
            void update().catch(fail);
        }
    }, 15000);
}
function radioInterrupted(text) {
    clearTimeout(liveTimeout);
    liveTimeout = undefined;
    playing.failed = true;
    audio.pause();
    audio.removeAttribute('src');
    audio.load();
    message(text);
    drawPlayer();
}
function watchRadio(request) {
    liveTimeout = setTimeout(() => {
        liveTimeout = undefined;
        if (request === generation && !audio.paused && audio.readyState < 3)
            radioInterrupted('Эфир долго не отвечает. Нажмите «Повторить».');
    }, 18000);
}
function streamType(url) {
    if (/\.m3u8$/i.test(url.pathname)) return 'application/vnd.apple.mpegurl';
    if (/\.(ogg|oga)$/i.test(url.pathname)) return 'audio/ogg';
    return '';
}
async function startRadio(station) {
    if (!navigator.onLine) throw new Error('Для радио нужна сеть');
    const url = new URL(station.url);
    const type = station.type || streamType(url);
    if (url.protocol !== 'https:' || url.username || url.password) throw new Error('Станция недоступна по HTTPS');
    if (type && !audio.canPlayType(type)) throw new Error('Этот формат радио недоступен в текущей версии браузера');
    stopSound();
    const request = generation;
    playing = {radio: station, media: true, track: {title: station.title}};
    publishMediaTitle(station.title);
    audio.preload = 'none';
    audio.src = url.href;
    message('Подключение к эфиру…');
    drawPlayer();
    watchRadio(request);
    try { await audio.play(); } catch (error) {
        if (request !== generation || !audio.hasAttribute('src') || error.name === 'AbortError') return;
        radioInterrupted('Не удалось запустить эфир. Нажмите «Повторить».');
    }
}
for (const [name, action] of [['play', () => { if (playing?.media ? audio.paused : playback?.state !== 'playing') toggle(); }],
    ['pause', () => { if (playing?.media ? !audio.paused : playback?.state === 'playing') toggle(); }],
    ['stop', stopSound], ['previoustrack', () => skip(-1)], ['nexttrack', () => skip(1)]]) {
    try { navigator.mediaSession?.setActionHandler(name, action); } catch {}
}

available = offline();
available.catch(fail);
saved = await remember('games') ?? {};
try {
    const data = await getJSON(catalogURL);
    if (data.schema_version !== 1 || !data.games) throw new Error('Версия каталога не поддерживается');
    catalog = Object.entries(data.games).map(([id, item]) => ({id, ...item}));
} catch (error) {
    catalog = Object.values(saved).map(item => item.game);
    if (!catalog.length) fail(error);
}
const platforms = new Map(catalog.map(item => [item.platform, item.platform_label || item.platform]));
for (const [platform, label] of [...platforms].sort((a, b) => a[1].localeCompare(b[1], 'ru'))) $('platform').append(element('option', label, {value: platform}));
drawCatalog();
drawPlayer();
