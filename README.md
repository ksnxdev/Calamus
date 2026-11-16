# Calamus

Android-клиент на базе [Cherrygram](https://github.com/arslan4k1390/Cherrygram) и [Telegram](https://github.com/DrKLO/Telegram). Лицензия — [GNU GPL](LICENSE); уведомления об авторстве сохранены в исходниках.

## Сборка

Нужны JDK 21, Android SDK 36, Build Tools 36.0.0, NDK 27.2.12479018 и CMake 3.22.1.

1. После клонирования загрузите зависимости: `git submodule update --init --recursive`. Полный архив исходников содержит подмодули.
2. Скопируйте `local.properties.example` в `local.properties`, укажите `sdk.dir` и собственные `TELEGRAM_API_ID` / `TELEGRAM_API_HASH`.
3. Для релизной подписи заполните отдельный файл по образцу `calamus-signing.properties.example` и задайте `CALAMUS_SIGNING_PROPERTIES` — путь к этому файлу.
4. Укажите `JAVA_HOME` для JDK 21 и выполните:

```bash
./tools/build-beta.sh --console=plain
```

APK: `TMessagesProj_App/build/outputs/apk/afat/standalone/Calamus-0.1.0-beta.1-standalone-universal.apk`.
Для отладочного APK: `./tools/build-debug.sh`.
