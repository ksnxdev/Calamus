# Calamus

Android-клиент на базе [Cherrygram](https://github.com/arslan4k1390/Cherrygram) и [Telegram](https://github.com/DrKLO/Telegram).
## Сборка

Нужны JDK 21, Android SDK 36, Build Tools 36.0.0, NDK 27.2.12479018 и 29.0.14206865, CMake 3.22.1 и Python 3.11. Команда `python3.11` должна быть доступна в `PATH`.

1. После клонирования загрузите зависимости: `git submodule update --init --recursive`. Полный архив исходников содержит подмодули.
2. Скопируйте `local.properties.example` в `local.properties`, укажите `sdk.dir` и собственные `TELEGRAM_API_ID` / `TELEGRAM_API_HASH`.
3. Для релизной подписи заполните отдельный файл по образцу `calamus-signing.properties.example` и задайте `CALAMUS_SIGNING_PROPERTIES` — путь к этому файлу.
4. Укажите `JAVA_HOME` для JDK 21 и выполните:

```bash
./tools/build-beta.sh --console=plain
```

APK: `TMessagesProj_App/build/outputs/apk/afat/standalone/Calamus-<версия>-standalone-universal.apk`.

Поддержка классических Python-плагинов `.plugin` использует SDK [exteraGram](https://github.com/exteraSquad/exteraGram). Эти плагины выполняются в основном процессе приложения; предупреждение показывается при первом входе в их раздел. Изолированные плагины Calamus `.plug` используют отдельный движок. Плагины, зависящие от внутренних классов или функций exteraGram, которых нет в Calamus, могут требовать адаптации.

Условия заимствованных компонентов: [лицензия exteraGram](EXTERAGRAM_LICENSE) и [дополнительные условия](ADDITIONAL_TERMS.md). Версия исходников SDK и локальные зависимости зафиксированы в `ExteraDependencies/UPSTREAM.json`.
