# GremlinAmp PWA

Веб-приложение для музыки из игр, трекерных аранжировок и радио. [Открыть GremlinAmp](https://games.oopssre.ru/pwa/gremlinamp/)

## Возможности

- Каталог игр, очередь, перемотка и сохранение отдельных игр для офлайн-прослушивания.
- Трекерные аранжировки и девять генераторов музыки.
- Живое радио и таймер сна.

## Сборка и запуск

Нужны macOS, Python 3 и Emscripten. Добавьте `em++` в PATH или укажите его через переменную `EMXX`. Выполните команды из корня репозитория.

```sh
git clone https://github.com/GremlinEngineering/gremlinamp-obsidian.git
git -C gremlinamp-obsidian checkout 7e36eb48949969702317069f13d3c40ac4d8a240
GREMLINAMP_OBSIDIAN="$PWD/gremlinamp-obsidian" python3 pwa/tools/build.py
python3 pwa/tools/preview.py
```

Локальный адрес для проверки — `http://127.0.0.1:8766/pwa/gremlinamp/`.

Готовые файлы сборки находятся в `pwa/dist/gremlinamp/`.

## Лицензия

Код PWA распространяется по лицензии [MIT](LICENSE). Для сторонних декодеров действуют их лицензии. Сборка включает уведомления из репозитория декодеров. [Уведомления о сторонних компонентах](https://github.com/GremlinEngineering/gremlinamp-obsidian/blob/7e36eb48949969702317069f13d3c40ac4d8a240/THIRD_PARTY_NOTICES.md)
