const {createCipheriv, createHash, pbkdf2Sync, randomBytes, timingSafeEqual} = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const [codeFile, assets] = process.argv.slice(2);
const code = fs.readFileSync(codeFile, 'utf8').trim();
const verifier = fs.readFileSync(path.join(assets, 'personal-radio.sha256'), 'ascii').trim();
if (!/^[a-f0-9]{64}$/.test(verifier) ||
    !timingSafeEqual(createHash('sha256').update(code).digest(), Buffer.from(verifier, 'hex'))) {
    throw new Error('Код не совпадает с кодом личных станций Android');
}
const stations = JSON.parse(fs.readFileSync(path.join(assets, 'personal-radio.json'), 'utf8'));
if (!Array.isArray(stations) || !stations.length) throw new Error('Пустой список личных станций');
const records = stations.map(station => {
    let url;
    try { url = new URL(station.url); } catch { throw new Error('Некорректный адрес личной станции'); }
    const title = station.title?.trim();
    if (!title || url.protocol !== 'https:' || url.username || url.password || url.hash) {
        throw new Error('Некорректная запись личной станции');
    }
    return {title, url: url.href};
});
const salt = randomBytes(16), iv = randomBytes(12);
const cipher = createCipheriv('aes-256-gcm', pbkdf2Sync(code, salt, 600000, 32, 'sha256'), iv);
const data = Buffer.concat([cipher.update(JSON.stringify(records), 'utf8'), cipher.final(), cipher.getAuthTag()]);
process.stdout.write(JSON.stringify({
    version: 1, salt: salt.toString('base64'), iv: iv.toString('base64'), data: data.toString('base64')
}) + '\n');
