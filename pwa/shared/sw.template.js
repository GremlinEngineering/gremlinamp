const version = '@VERSION@';
const prefix = new URL('./', self.location).pathname;
const shell = prefix + ':shell:' + version;
const saved = prefix + ':saved';
const art = prefix + ':art';
const documents = prefix + ':documents';
const urls = @SHELL@;
const api = @API@;

self.addEventListener('install', event => event.waitUntil((async () => {
    const cache = await caches.open(shell);
    // Reload cached files when the app changes, including when a URL is reused.
    await cache.addAll(urls.map(path => new Request(new URL(path, self.location), {cache: 'reload'})));
    await self.skipWaiting();
})()));
self.addEventListener('activate', event => event.waitUntil((async () => {
    for (const key of await caches.keys()) {
        if (key.startsWith(prefix + ':shell:') && key !== shell) await caches.delete(key);
    }
    await self.clients.claim();
})()));

async function fromSaved(request, response) {
    const range = request.headers.get('Range');
    if (!range) return response;
    const match = /^bytes=(\d*)-(\d*)$/.exec(range);
    const blob = await response.blob();
    const size = blob.size;
    const start = match?.[1] ? Number(match[1]) : Math.max(0, size - Number(match?.[2]));
    const end = match?.[1] && match[2] ? Math.min(size - 1, Number(match[2])) : size - 1;
    if (!match || !Number.isSafeInteger(start) || start >= size || end < start)
        return new Response(null, {status: 416, headers: {'Content-Range': `bytes */${size}`}});
    return new Response(blob.slice(start, end + 1), {status: 206, headers: {
        'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${size}`,
        'Content-Length': String(end - start + 1),
        'Content-Type': response.headers.get('Content-Type') || 'application/octet-stream'
    }});
}

async function data(request, json) {
    const cached = await (await caches.open(saved)).match(request, {ignoreVary: true});
    if (!json) {
        const picture = api === '/game-worlds/orbita/' ? await (await caches.open(art)).match(request) : null;
        if (picture || cached) return fromSaved(request, picture || cached);
    }
    const cache = json ? await caches.open(documents) : null;
    try {
        const response = await fetch(request);
        if (cache && response.ok && response.status === 200) {
            await cache.put(request, response.clone()).catch(() => {});
        }
        if (response.ok) return response;
        return cached || await cache?.match(request) || response;
    } catch {
        return cached || await cache?.match(request) || new Response(null, {status: 503});
    }
}

self.addEventListener('fetch', event => {
    const request = event.request;
    const url = new URL(request.url);
    if (request.method !== 'GET' || url.origin !== self.location.origin || request.headers.has('X-Orbita-Art-Refresh')) return;
    if (url.pathname.startsWith(prefix)) {
        event.respondWith((async () => {
            const cache = await caches.open(shell);
            if (request.mode === 'navigate') return cache.match(new URL('index.html', self.location));
            return (await cache.match(request)) || fetch(request);
        })());
    } else if (url.pathname.startsWith(api)) {
        event.respondWith(data(request, url.pathname.endsWith('.json')));
    }
});
