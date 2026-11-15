export const app = document.querySelector('#app');
export const message = text => { document.querySelector('#status').textContent = text; };

export function element(tag, text, attributes = {}) {
    const node = document.createElement(tag);
    node.textContent = text ?? '';
    for (const [key, value] of Object.entries(attributes)) node.setAttribute(key, value);
    return node;
}

export function sameOrigin(value, base, prefix) {
    const url = new URL(value, base);
    if (url.origin !== location.origin || url.username || url.password
        || !url.pathname.startsWith(prefix) || url.hash) throw new Error('Неизвестный адрес данных');
    return url;
}

export async function getJSON(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error(`Нет данных (HTTP ${response.status})`);
    return response.json();
}

export async function remember(key, value) {
    const cache = await caches.open(new URL('./', location.href).pathname + ':saved');
    if (arguments.length === 2) {
        await cache.put(new URL(`saved/${encodeURIComponent(key)}`, location.href),
            new Response(JSON.stringify(value), {headers: {'Content-Type': 'application/json'}}));
        return value;
    }
    return (await cache.match(new URL(`saved/${encodeURIComponent(key)}`, location.href)))?.json();
}

export async function rememberFiles(urls, progress) {
    const cache = await caches.open(new URL('./', location.href).pathname + ':saved');
    for (let index = 0; index < urls.length; ++index) {
        const {url, bytes, sha256} = typeof urls[index] === 'object' && !(urls[index] instanceof URL) ? urls[index] : {url: urls[index]};
        const existing = await cache.match(url);
        if (!existing || sha256 && existing.headers.get('X-Offline-SHA256') !== sha256) {
            const response = await fetch(url, {cache: 'no-cache'});
            if (!response.ok || response.status !== 200) throw new Error(`Файл недоступен (HTTP ${response.status})`);
            const body = await response.blob();
            if (bytes != null && body.size !== bytes) throw new Error('Размер загруженного файла изменился');
            const headers = new Headers({'Content-Type': response.headers.get('Content-Type') || 'application/octet-stream'});
            if (sha256) {
                const digest = await crypto.subtle.digest('SHA-256', await body.arrayBuffer());
                const actual = Array.from(new Uint8Array(digest), x => x.toString(16).padStart(2, '0')).join('');
                if (actual !== sha256) throw new Error('Контрольная сумма файла изменилась');
                headers.set('X-Offline-SHA256', actual);
            }
            await cache.put(url, new Response(body, {headers}));
        }
        progress?.(index + 1, urls.length);
    }
}

async function showStorage() {
    const target = document.querySelector('#storage');
    const {usage, quota} = await navigator.storage?.estimate?.() ?? {};
    const persistent = await navigator.storage?.persisted?.();
    const count = bytes => `${Math.round(bytes / 1048576)} МБ`;
    target.textContent = [
        Number.isFinite(usage) && Number.isFinite(quota) ? `Сайт занимает ${count(usage)} из доступных ${count(quota)} для этого источника.` : '',
        persistent ? 'WebKit подтвердил постоянное хранилище.' : 'При нехватке места система может освободить память сайта.'
    ].filter(Boolean).join(' ');
}
document.querySelector('#persist').addEventListener('click', async () => {
    try {
        await navigator.storage?.persist?.();
        await showStorage();
    } catch (error) { message(error.message); }
});
function network() { document.querySelector('#connection').textContent = navigator.onLine ? 'Сеть' : 'Офлайн'; }
addEventListener('online', network);
addEventListener('offline', network);
network();
void showStorage().catch(() => {});

export async function offline() {
    if (!('serviceWorker' in navigator)) throw new Error('Офлайн требует HTTPS и поддержку Service Worker.');
    const active = await navigator.serviceWorker.register('./sw.js', {updateViaCache: 'none'});
    await navigator.serviceWorker.ready;
    if (!navigator.serviceWorker.controller) {
        await new Promise(resolve => navigator.serviceWorker.addEventListener('controllerchange', resolve, {once: true}));
    }
    await active.update().catch(() => {});
}
