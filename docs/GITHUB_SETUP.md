# Создание репозитория

Репозиторий: https://github.com/GremlinEngineering/gremlinamp

Организация: GremlinEngineering. Имя: gremlinamp.

Готовое описание для поля Description:

> Музыка Аркадного зала Школы Империи: Android-плеер игровых саундтреков, коллекции игр и серий, скачивание и офлайн-прослушивание без перекодирования.

Website: `https://games.oopssre.ru/gremlin.html`

Topics: `android`, `music-player`, `chiptune`, `game-music`, `retro-gaming`, `offline-first`, `java`, `jni`, `libgme`.

Создайте публичный репозиторий `gremlinamp`. Не добавляйте автоматически README, LICENSE или .gitignore: они уже подготовлены. Распакуйте пакет и загрузите **содержимое** папки gremlinamp, чтобы README.md и LICENSE оказались в корне репозитория.

Для первого коммита:

```bash
cd gremlinamp
git init -b main
git add .
git status
# Проверьте список: ключей подписи, APK и build-каталогов быть не должно.
git commit -m 'Initial release: GremlinAmp 0.1.7'
```

Затем добавьте URL именно созданного репозитория командой `git remote add origin`, которую покажет GitHub, и выполните `git push -u origin main`. Фактический remote: https://github.com/GremlinEngineering/gremlinamp.git

## Ссылка в плеере и на сайте

После публикации вставьте реальный адрес репозитория в поле `source_url` файла:

`/opt/gremlin-tg-publisher/games/app_releases/gremlinamp/release.json`

Выполните обычную сборку сайта. Карточка приложения покажет ссылку GitHub. В плеере откройте «Настройки → Ресурсы, права и лицензии → Исходники приложения · GitHub / ZIP»: он получает ту же ссылку с сайта. Пересборка APK для изменения адреса не нужна.

Готовые команды remote:

```bash
git remote add origin https://github.com/GremlinEngineering/gremlinamp.git
git push -u origin main
```
