import {element, getJSON, remember} from './site.js';

export const trackers = [
    ['Чайная', 'Тихий эмбиент · время суток', 'clock'],
    ['Мастерская', 'Механический ритм', 'accents'],
    ['Погода', 'Затонувшая чайная', 'refresh'],
    ['Город', 'Чиптюн · местное время', 'clock'],
    ['Вояджер', 'Voyager 1 · плазменные волны', 'plasma', 'noise'],
    ['Звёзды', 'Kepler · яркость звезды', 'noise'],
    ['Солнечная система', 'Юпитер и Сатурн', 'noise'],
    ['Орбиты', 'Луна JPL, солнечный ветер NOAA и вспышки GOES'],
    ['Москва', 'Городской трекер · Sonic City', 'microphone']
];
const labels = {
    clock: 'Менять звук по времени суток', accents: 'Смещать ритмические акценты',
    refresh: 'Обновлять погоду каждые 10 минут', plasma: 'Архивный сигнал плазмы',
    noise: 'Шум и искажения', microphone: 'Уличный звук через микрофон'
};
const defaults = {clock: true, accents: true, refresh: true, plasma: true, noise: false, microphone: false};
const stored = key => {
    try { return JSON.parse(localStorage.getItem(key)); } catch { return null; }
};
export function options(mode) {
    return {...defaults, ...stored('tracker:' + mode)};
}

export function trackerPanel(target, onPlay, onChange) {
    const pick = element('select', '', {'aria-label': 'Трекер'});
    trackers.forEach(([title], mode) => pick.append(element('option', title, {value: mode})));
    pick.value = trackers[stored('tracker:mode')] ? stored('tracker:mode') : 0;
    const detail = element('p', '', {class: 'muted'});
    const settings = element('div', '', {class: 'stack'});
    const play = element('button', 'Играть трекер');
    function render() {
        const mode = +pick.value;
        const [, description, ...keys] = trackers[mode];
        detail.textContent = description;
        settings.replaceChildren();
        for (const key of keys) {
            const input = element('input', '', {type: 'checkbox'});
            input.checked = options(mode)[key];
            input.onchange = () => {
                localStorage.setItem('tracker:' + mode, JSON.stringify({...options(mode), [key]: input.checked}));
                onChange(mode, key);
            };
            const label = element('label', '', {class: 'option'});
            label.append(input, labels[key]);
            settings.append(label);
        }
    }
    pick.onchange = () => { localStorage.setItem('tracker:mode', pick.value); render(); };
    play.onclick = () => onPlay(+pick.value);
    target.append(element('h2', 'Трекеры'), pick, detail, settings, play);
    render();
}

export async function radioPanel(target, onPlay, onLock) {
    const [list, sealed] = await Promise.all([getJSON('./radio-stations.json'),
        getJSON('./personal-radio.sealed.json').catch(() => null)]);
    target.append(element('h2', 'Живое радио'), element('p', 'Для эфира нужна сеть.', {class: 'muted'}));
    function appendStation(parent, station) {
        const button = element('button', station.title);
        button.onclick = () => onPlay(station);
        parent.append(button);
        if (station.credit) parent.append(element('small', station.credit, {class: 'muted'}));
    }
    list.forEach(station => appendStation(target, station));
    if (sealed?.version !== 1) return;
    const privateList = element('section', '', {class: 'stack', hidden: ''});
    const form = element('form', '', {class: 'controls', hidden: ''});
    const personal = element('button', 'Личные станции', {type: 'button', 'aria-expanded': 'false'});
    const code = element('input', '', {type: 'password', inputmode: 'numeric', autocomplete: 'off',
        placeholder: 'Код', 'aria-label': 'Код личных станций', required: ''});
    const unlock = element('button', 'Открыть', {type: 'submit'});
    const state = element('p', '', {role: 'status'});
    form.append(code, unlock);
    target.append(personal, form, state, privateList);
    personal.onclick = () => {
        if (!privateList.hidden) {
            onLock();
            privateList.replaceChildren();
            privateList.hidden = true;
            personal.textContent = 'Личные станции';
        } else if (!unlock.disabled) {
            form.hidden = !form.hidden;
            if (!form.hidden) code.focus();
            else form.reset();
        }
        personal.setAttribute('aria-expanded', String(!privateList.hidden || !form.hidden));
        state.textContent = '';
    };
    form.onsubmit = async event => {
        event.preventDefault();
        if (unlock.disabled || !code.value.trim()) return;
        unlock.disabled = true;
        try {
            const bytes = value => Uint8Array.from(atob(value), character => character.charCodeAt(0));
            const password = await crypto.subtle.importKey('raw', new TextEncoder().encode(code.value.trim()), 'PBKDF2', false, ['deriveKey']);
            const key = await crypto.subtle.deriveKey({name: 'PBKDF2', salt: bytes(sealed.salt), iterations: 600000, hash: 'SHA-256'},
                password, {name: 'AES-GCM', length: 256}, false, ['decrypt']);
            const data = await crypto.subtle.decrypt({name: 'AES-GCM', iv: bytes(sealed.iv)}, key, bytes(sealed.data));
            const stations = JSON.parse(new TextDecoder().decode(data));
            stations.forEach(station => appendStation(privateList, {...station, personal: true}));
            privateList.hidden = false;
            personal.textContent = 'Закрыть личные станции';
            form.hidden = true;
            form.reset();
            state.textContent = '';
        } catch {
            state.textContent = 'Неверный код';
            code.select();
        } finally { unlock.disabled = false; }
    };
}

export const clock = () => new Date().getHours() * 60 + new Date().getMinutes();
const limit = x => Math.max(0, Math.min(1, Number(x) || 0));
const normalizeWeather = ({rain, cloud, wind}) => [rain, cloud, wind].map(limit);

async function weatherFromSite() {
    return parseWeather(await getJSON('/assets/arcade/views/dynamic/sunken-teahouse/devices/phone_1440x3120/current.json'));
}
function parseWeather(state) {
    const {weather, at} = state.inputs ?? {};
    const code = weather?.weather_code;
    if (state.theme_id !== 'sunken-teahouse' || !Number.isInteger(code) || code < 0 || code > 99
        || !Number.isFinite(Date.parse(at))) throw new Error('Срез погоды недоступен');
    const rain = code >= 95 ? 1 : code >= 80 && code <= 86 ? .55 : code >= 51 && code <= 77 ? .38 : 0;
    return {at, rain: Math.max(rain, limit(weather.precipitation / 4)),
        cloud: weather.cloud_cover == null ? (code < 3 ? code * .25 : .9) : limit(weather.cloud_cover / 100),
        wind: limit(weather.wind_speed_10m / 18)};
}
function orbitSignal(archive) {
    const now = Date.now() / 1000, points = archive.moon.points;
    const after = points.findIndex(([time]) => time >= now);
    const start = points[Math.max(0, after < 0 ? points.length - 1 : after - 1)];
    const end = points[after] ?? start;
    const fraction = start[0] === end[0] ? 0 : limit((now - start[0]) / (end[0] - start[0]));
    const vector = start.slice(1).map((value, index) => value + (end[index + 1] - value) * fraction);
    const distance = Math.hypot(...vector);
    const flare = archive.flare, peak = flare?.peak ?? 0;
    const level = now >= peak && now - peak <= 3600 ? ({X: 1, M: .75, C: .48}[flare.classification[0]] || .28) : 0;
    return [vector[1] / distance, limit((distance - 350000) / 60000), vector[2] / distance,
        limit((archive.wind.speed - 250) / 550), limit(Math.log1p(archive.wind.density) / 3.5),
        limit((Math.log10(archive.flux.value) + 8) / 4), level ? peak : 0, level];
}
const weatherTime = at => new Date(at).toLocaleString('ru', {dateStyle: 'short', timeStyle: 'short'});

export async function trackerData(mode, refresh) {
    if (mode === 2) {
        let data;
        if (refresh && options(mode).refresh && navigator.onLine) {
            try {
                data = await weatherFromSite();
                await remember('tracker-weather', data);
            } catch {}
        }
        data ??= await remember('tracker-weather') ?? parseWeather(await getJSON('./trackers/teahouse-weather.json'));
        return {weather: normalizeWeather(data), stamp: 'Погода · ' + weatherTime(data.at)};
    }
    if (mode === 7) {
        const data = await getJSON('./trackers/orbits.json');
        return {orbits: orbitSignal(data), stamp: 'Архив орбит · ' + weatherTime(data.wind.time * 1000)};
    }
    return {};
}
