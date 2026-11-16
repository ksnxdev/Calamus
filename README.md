# Calamus

Calamus — неофициальный Android-клиент Telegram с интерфейсом Material 3, основанный на [Cherrygram](https://github.com/arslan4k1390/Cherrygram) и [Telegram for Android](https://github.com/DrKLO/Telegram).

Текущий публичный выпуск: **0.1.0-beta.1**, `versionCode 1`, пакет `ksnxdev.calamus`.

Исходники и выпуски: [ksnxdev/Calamus](https://github.com/ksnxdev/Calamus). Обновления: [@CalamusGram](https://t.me/CalamusGram). APK и архив соответствующих исходников распространяются вместе. Код находится в стадии публичного тестирования; список проверок и ограничений — в [PUBLIC_BETA.md](PUBLIC_BETA.md).

В клиенте сохранены дополнительные функции Cherrygram, добавлены оформление Calamus, настройки цвета и размытия, меню сообщений в стиле iOS и изолированные Python-плагины формата `.plug`. Плагины требуют Android 10+, меню iOS — Android 11+. Минимальная версия клиента — Android 7. Private View распространяется отдельно.

Инструкции сборки и подписи: [PUBLIC_BETA.md](PUBLIC_BETA.md). Релизная команда — `./tools/build-beta.sh`. Свои Telegram API ID/Hash и ключ подписи нужно указать локально; в архив исходников они не включены.

Для сборки из Git требуется загрузить подмодули. Архив исходников включает их текущее содержимое, исходный commit и список ревизий. Внешние зависимости и версии указаны в Gradle; происхождение Python runtime и контрольные суммы — в `CalamusPlugins/vendor/python-runtime-verification.json`.

Исходные лицензии и авторство сохранены: [LICENSE](LICENSE), [оригинальный README Cherrygram](UPSTREAM_README.md). Лицензии сторонних компонентов находятся рядом с соответствующими исходниками, лицензия Python — в `CalamusPlugins/vendor/LICENSE.python`.
