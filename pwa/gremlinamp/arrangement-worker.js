importScripts('arrangement-engine.js');

let arrangement, rate, position = 0;
self.onmessage = ({data}) => {
    const {type, revision} = data;
    try {
        if (type === 'load') {
            rate = data.sampleRate;
            arrangement = arrange(data.samples, data.style, data.id, rate);
            position = 0;
            postMessage({type: 'ready', duration: arrangement.frames / rate, seek: true, liveSeek: true, style: arrangement.style});
        } else if (type === 'seek') {
            position = Math.max(0, Math.min(arrangement.frames, Math.round(data.seconds * rate)));
            postMessage({type: 'positioned', seconds: position / rate, revision});
        } else if (type === 'render') {
            const count = Math.min(data.frames, arrangement.frames - position);
            const pcm = arrangement.render(position, count);
            position += count;
            postMessage({type: 'pcm', pcm: pcm.buffer, ended: position === arrangement.frames, revision}, [pcm.buffer]);
        }
    } catch (error) {
        postMessage({type: 'error', message: error.message, revision});
    }
};
