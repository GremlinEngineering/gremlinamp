# GremlinAmp PWA

Веб-клиент GremlinAmp для каталога игр, трекерных аранжировок и радио.

Для сборки нужны macOS, Python 3 и Emscripten. Укажите em++ через PATH или переменную EMXX.

```sh
git clone https://github.com/GremlinEngineering/gremlinamp-obsidian.git
git -C gremlinamp-obsidian checkout 7e36eb48949969702317069f13d3c40ac4d8a240
GREMLINAMP_OBSIDIAN="$PWD/gremlinamp-obsidian" python3 pwa/tools/build.py
python3 pwa/tools/preview.py
```

Сборка создаёт пакет в `pwa/dist/gremlinamp/`.
