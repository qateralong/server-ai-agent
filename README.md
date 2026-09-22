# Server AI Agent — персональный AI-агент для рабочего стола Linux

Один JVM-процесс, в котором живут окно управления (JavaFX), Telegram-бот,
голосовой ввод по клавише и голосовые сообщения в Telegram (whisper.cpp),
озвучка ответов (Piper), напоминания, заметки,
долговременная память о людях и выполнение Python-скриптов, которые пишет модель.
Модель — Ollama (облако или локальный демон) либо Claude (Anthropic API),
переключается на лету.

Это **личный однопользовательский** агент под конкретную машину: Arch Linux,
Wayland, GNOME. Переносимость не цель. Подробное устройство, решения и их
причины разобраны в комментариях к коду и в `config/agent.example.toml`;
этот файл — про то, как поставить, поднять с нуля и как жить с ним потом.

---

## 1. Установка готовым пакетом

У каждого [релиза](https://github.com/qateralong/server-ai-agent/releases)
три сборки — **агент с окном**, **сервер** (headless, раздел 7) и **тонкий
клиент** (раздел 8) — в форматах их систем. JVM лежит внутри пакета, **Java
ставить не нужно**.

| Файл | Что это |
|---|---|
| `server-ai-agent-<v>-1-x86_64.pkg.tar.zst` | Arch: агент с окном |
| `server-ai-agent-client-<v>-1-x86_64.pkg.tar.zst` | Arch: тонкий клиент |
| `server-ai-agent_<v>_amd64.deb` | Ubuntu/Debian: агент с окном |
| `server-ai-agent-server_<v>_amd64.deb` | Ubuntu/Debian: сервер |
| `server-ai-agent-client_<v>_amd64.deb` | Ubuntu/Debian: тонкий клиент |
| `*-linux-x64.tar.gz` (×3) | Любой glibc-Linux без пакетного менеджера: распаковать и запустить `bin/…` |
| `server-ai-agent-<v>-windows-x64.exe`, `…-client-…exe` | Windows: установщики в профиль пользователя, без прав администратора |
| `*-windows-x64.zip` (×2) | Windows: те же две сборки, переносимые |
| `standalone-agent.jar`, `server.jar`, `client.jar` | `java -jar …` на любой ОС с JDK 25 |

```bash
# Arch
sudo pacman -U server-ai-agent-0.2.2-1-x86_64.pkg.tar.zst

# Ubuntu/Debian
sudo dpkg -i server-ai-agent_0.2.2_amd64.deb

# без пакетного менеджера
tar xzf server-ai-agent-0.2.2-linux-x64.tar.gz
./server-ai-agent/bin/server-ai-agent
```

Arch-пакет кладёт сборку в `/opt/server-ai-agent`, ссылку в `/usr/bin` и ярлык
в меню приложений; `.deb` от jpackage ставит туда же. Запуск — командой
`server-ai-agent` или из меню приложений.

Два момента, о которых пакет не позаботится:

* **Конфига он не создаёт.** Автосоздание при первом запуске работает только
  в репозитории, где рядом лежит `config/agent.example.toml`; у установленной
  сборки рабочий каталог произвольный. Возьмите образец из репозитория
  и положите его по постоянному пути, а пути внутри задайте **абсолютными**:

  ```bash
  mkdir -p ~/.config/bebebe-agent
  curl -o ~/.config/bebebe-agent/config.toml \
      https://raw.githubusercontent.com/qateralong/server-ai-agent/main/config/agent.example.toml
  chmod 600 ~/.config/bebebe-agent/config.toml
  ```

* **Внешние программы в пакет не входят**: whisper.cpp (распознавание), Piper
  (озвучка), `ffmpeg`, `wl-clipboard`, evdev-хелпер для push-to-talk. Без них
  агент работает как текстовый бот с окном, а чего не хватает — пишет в лог
  на старте. Как их поставить — разделы 2.1 и 2.3–2.5.

**Windows** — не среда этого агента: клавиша через evdev, буфер через
`wl-paste` и уведомления через `notify-send` существуют только под Linux,
поэтому голосовой ввод, буфер обмена и всплывающие напоминания там не
работают. Окно, Telegram, модель, память и заметки — работают. Ручной проверки
на Windows не было, CI проверяет только сборку.

Если нужна правка кода или своя ветка — собирайте из исходников, раздел 2.

---

## 2. Сборка с нуля на чистом Arch

### 2.1 Пакеты

```bash
# обязательное: сборка и работа окна, Telegram, скриптов
sudo pacman -S --needed jdk-openjdk git base-devel python gtk3 libxtst

# голосовой ввод (push-to-talk): запись микрофона и чтение клавиши из /dev/input
sudo pacman -S --needed alsa-utils pipewire-alsa libevdev cmake

# озвучка ответов и голосовые в Telegram (Piper ставится отдельно, см. 2.5)
sudo pacman -S --needed ffmpeg

# буфер обмена под Wayland и всплывающие напоминания
sudo pacman -S --needed wl-clipboard libnotify

# локальная модель вместо облака (необязательно)
sudo pacman -S --needed ollama
```

Что зачем:

| Пакет | Кому нужен |
|---|---|
| `jdk-openjdk` (25) | Сборка и запуск. Gradle ставить не нужно — в репозитории wrapper |
| `base-devel`, `libevdev` | Нативный хелпер `native/evdev-hotkey` (`gcc`, `make`) |
| `python` | venv для скриптов модели: `~/.local/share/bebebe-agent/venv` |
| `gtk3`, `libxtst` | JavaFX под Linux (на GNOME обычно уже стоят) |
| `alsa-utils` + `pipewire-alsa` | `arecord` пишет микрофон поверх PipeWire |
| `cmake` | Сборка whisper.cpp из исходников |
| `ffmpeg` | WAV от Piper → OGG/Opus для Telegram |
| `wl-clipboard` | `wl-paste` — чтение буфера по явной просьбе |
| `libnotify` | `notify-send` для напоминаний на рабочем столе |
| `ollama` | Только для локальных моделей; для `https://ollama.com` не нужен |

JavaFX 27 и AtlantaFX **системными пакетами не ставятся**: Gradle-плагин
`org.openjfx.javafxplugin` скачивает платформенные jar сам, AtlantaFX — обычная
зависимость из Maven Central. `prlimit` (лимиты скриптов) — из `util-linux`,
он в базовой системе. SQLite — встроен в `sqlite-jdbc`.

Из AUR ничего обязательного нет. Есть пакеты `whisper.cpp` и `piper-tts-bin`,
но ниже описан путь без AUR: whisper.cpp собирается из исходников, Piper
ставится `pip`-ом в отдельный venv — так версии совпадают с тем, что проверено.

### 2.2 Клонировать и собрать

```bash
git clone git@github.com:qateralong/server-ai-agent.git ~/server-ai-agent
cd ~/server-ai-agent
./gradlew build          # ~3 минуты: компиляция всех модулей и 742 офлайн-теста
```

Тесты ходят только в локальные заглушки; интернет для сборки нужен один раз —
Gradle скачает зависимости. Часть тестов `script-runtime` поднимает настоящий
Python-venv во временном каталоге — это ожидаемые секунды.

Путь `~/server-ai-agent` зашит в `systemd/server-ai-agent.service` (`%h/server-ai-agent`) — если
клонируете в другое место, поправьте `WorkingDirectory` и `ExecStart` там.

### 2.3 Голосовой ввод: evdev-хелпер и группа `input`

В Wayland нет глобальных горячих клавиш, поэтому нажатие читается напрямую
из `/dev/input` маленькой C-программой:

```bash
cd ~/server-ai-agent/native/evdev-hotkey && make      # → build/evdev-hotkey
sudo usermod -aG input $USER                 # доступ к /dev/input/event*
```

**После `usermod` — перелогиниться**, членство в группе применяется при входе.
Проверить: `groups | grep input`, затем `./build/evdev-hotkey` — нажатие
и отпускание Home печатают `DOWN` / `UP`.

> Группа `input` даёт возможность читать любой ввод в системе. Для личной
> машины это принятый способ; более узкая альтернатива — правило udev на одно
> устройство.

### 2.4 whisper.cpp и модель

```bash
git clone https://github.com/ggml-org/whisper.cpp ~/src/whisper.cpp
cd ~/src/whisper.cpp
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j --config Release
sh ./models/download-ggml-model.sh large-v3-turbo     # ~1.6 ГБ
```

Бинарник — `build/bin/whisper-cli` (не `main`, проект его переименовал).
Для русского берите `large-v3-turbo`; `tiny`/`base` практически бесполезны.

### 2.5 Piper (озвучка)

```bash
python3 -m venv ~/.local/share/bebebe-agent/piper-venv
~/.local/share/bebebe-agent/piper-venv/bin/pip install piper-tts
~/.local/share/bebebe-agent/piper-venv/bin/python -m piper.download_voices \
    --data-dir ~/.local/share/bebebe-agent/piper-voices ru_RU-irina-medium
```

Проверить: `echo "Проверка." | ~/.local/share/bebebe-agent/piper-venv/bin/piper \
-m ~/.local/share/bebebe-agent/piper-voices/ru_RU-irina-medium.onnx -f /tmp/t.wav`.

Голосовой ввод и озвучка **необязательны**: без них агент работает как
текстовый Telegram-бот с окном. Чего не хватает, видно в логе на старте
(`STT не запущен: …`, `TTS не запущен: …`) — с командой, что сделать.

---

## 3. Первый запуск и настройка через окно

```bash
cd ~/server-ai-agent
./gradlew :supervisor-app:run
```

При первом запуске конфига ещё нет — процесс сам копирует
`config/agent.example.toml` в `config/agent.toml` (права `600`) и пишет об этом
в лог. Дальше всё заполняется **в окне**, файл руками править не обязательно:

1. **Настройки** → блок «Провайдер модели»:
   * Провайдер — `Ollama` или `Claude`.
   * API key — ключ выбранного провайдера (`https://ollama.com/settings/keys`
     либо `sk-ant-…`). Для локального Ollama — пусто.
   * Модель — «Загрузить список» подтянет доступные; для Ollama имя
     **без суффикса `:cloud`** (`gpt-oss:120b`, не `gpt-oss:120b:cloud`).
   * «Для продвинутых» → Endpoint URL: пусто = стандартный адрес провайдера;
     `http://localhost:11434` — локальный демон Ollama.
2. Там же — **Telegram**: токен от `@BotFather` и разрешённые username
   (через запятую, без `@`). Пустой список = бот **никому** не отвечает,
   это безопасный дефолт.
3. **Сохранить.** Провайдер, модель и белый список применяются сразу;
   смена токена бота переподключает поллинг за пару секунд.
4. **Тест агента** → «Проверить связь» → должно быть
   `Ollama · https://ollama.com — OK, моделей: N`. «Отправить» — ответ модели.
5. Верхняя панель → тумблер **Включить**. Агент выключен по умолчанию
   (`[agent] enabled_on_start`), меню в Telegram работает и в выключенном
   состоянии — им можно включить обратно.
6. В Telegram: `/start` → ⚡ Питание → включить → написать что угодно.

**Голосовые сообщения в Telegram.** Записанное в чате голосовое агент скачивает,
переводит `ffmpeg`-ом в 16 кГц WAV и распознаёт тем же whisper.cpp, что и
push-to-talk, — то есть работает это только при заполненной секции `[stt]`
(раздел 2.4). В ответ приходит «🎤 распознанный текст» и обычный ответ агента.
Выключается тумблером `telegram.voice_input`: окно → Настройки или ⚙️ Настройки
в чате; тогда бот прямо говорит, что голосовые не принимает.

Секреты (API key, токен бота) правятся **только в окне**: в Telegram-меню
они видны как «задан / не задан» и в чат не отправляются. Всё, чего нет в форме
(пути к whisper.cpp и Piper, клавиша push-to-talk, таймауты, лимиты скриптов),
живёт в `config/agent.toml` и читается **на старте**: у каждого ключа там есть
комментарий, что он делает и когда применяется.

Порядок поиска конфига: `-Dbebebe.config=…` → `$BEBEBE_CONFIG` →
`~/.config/bebebe-agent/config.toml` → `./config/agent.toml`.

---

## 4. Автозапуск: `systemd --user`

Падение всего процесса изнутри Java не ловится — того, кто ловил бы, уже нет.
Поэтому за перезапуск отвечает пользовательский юнит systemd; за зависание
внутри процесса — встроенный watchdog (перезапускает поток обработки, не процесс).

```bash
cd ~/server-ai-agent
./gradlew installDist                                   # стартовый скрипт без Gradle-демона
mkdir -p ~/.config/systemd/user
cp systemd/server-ai-agent.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now server-ai-agent              # автозапуск при входе в сессию + старт сейчас
```

Юнит привязан к `graphical-session.target` (окну нужен Wayland),
`Restart=on-failure` с паузой 5 с и потолком 5 перезапусков за 10 минут
(сломанный конфиг не должен крутить процесс вечно; сбросить —
`systemctl --user reset-failed server-ai-agent`). Штатный выход — «Завершить
агента» на экране «Статус», exit 0 — не перезапускается.

После обновления кода:

```bash
git pull && ./gradlew installDist && systemctl --user restart server-ai-agent
```

Ярлык в список приложений GNOME (повторный запуск при живом процессе не поднимает
второй экземпляр, а показывает окно первого — крестик окно только прячет):

```bash
sed "s|@REPO@|$PWD|g" desktop/server-ai-agent.desktop > ~/.local/share/applications/server-ai-agent.desktop
```

Окно можно вернуть и из Telegram: ⚡ Питание → «🪟 Показать окно».
Трея на GNOME без расширения AppIndicator нет — это ограничение оболочки.

---

## 5. Логи и статус

### В окне

* **Статус** — включён ли агент, последняя ошибка, доступность модели *по факту
  последнего вызова* (не пинг), счётчики вызовов и токенов, свободное место,
  версия сборки (коммит, ветка, «грязное» дерево), результат проверки
  обновлений, число перезапусков watchdog, кнопка «Экспорт/бэкап…».
* **Логи** — живая лента того же потока событий, что уходит в файлы, с фильтрами
  по уровню, подсистеме, `trace_id` и тексту. Двойной клик по строке — вся
  цепочка её запроса. Выпадающий список уровня применяется сразу и пишется
  в конфиг.

### В файлах

Каталог `logs/` (настраивается `[logging] dir`). Одна строка — один JSON-объект
с общим `trace_id` на весь запрос пользователя:

| Файл | Что в нём |
|---|---|
| `agent.log` | Всё подряд |
| `agent-core.log` | Решения модели, бюджет обращений, подтверждения |
| `script-runtime.log` | Каждый запуск скрипта: **код, stdout, stderr**, установленные пакеты |
| `telegram-bridge.log` | Поллинг, меню, доступ |
| `scheduler.log`, `watchdog.log` | Напоминания; зависания и перезапуски потока |

```bash
# найти trace_id по тексту вопроса
grep '"request.start"' logs/agent-core.log | jq -r 'select(.kv.text | test("файлов")) | .trace_id'

# вся история одного запроса из всех файлов, по времени
cat logs/*.log | grep e510c42a22f9 | jq -r '"\(.ts[11:23]) \(.subsystem) \(.kv.event // "") \(.msg)"' | sort

# что именно выполнялось
grep '"script.audit"' logs/script-runtime.log | grep e510c42a22f9 | jq -r '.kv.code'

# все упавшие скрипты
grep '"script.audit"' logs/script-runtime.log | jq 'select(.kv.exit_code != 0) | {ts, trace_id, stderr: .kv.stderr}'
```

Точечный `DEBUG` для одной подсистемы — `[logging.levels]` в конфиге
(`script-runtime = "DEBUG"` покажет venv и pip, `ollama-client = "DEBUG"` —
URL запросов). Ротация по 20 МБ и по дню, `.gz`, 14 дней.

### Процесс под systemd

```bash
systemctl --user status server-ai-agent
journalctl --user -u server-ai-agent -f          # stdout процесса: старт, «Провайдер модели: …», падения
```

### Типичная диагностика

| Симптом | Что смотреть |
|---|---|
| Бот молчит на всё | Пустой белый список или не тот ник. В `telegram-bridge.log` — `WARN Отклонено сообщение от …` с нужным id |
| `HTTP 401` | Неверный API key провайдера (Настройки в окне) |
| `HTTP 404` от Ollama | Имя модели с `:cloud` или опечатка |
| `Сеть недоступна` | Неверный Endpoint URL либо не запущен локальный Ollama |
| Клавиша нажата — тишина | Нет группы `input` / не перелогинились / агент выключен — см. лог на старте |
| Ответ пришёл от прежнего провайдера после переключения | Так задумано: текущий запрос доводится на старом, следующий идёт на новый |

---

## 6. Известные ограничения и риски

Два решения ниже — **осознанные компромиссы личного однопользовательского
агента**, а не недосмотр. Они дёшевы ровно до тех пор, пока агентом пользуется
один человек на своей машине; если это изменится, их придётся пересмотреть
первыми.

### 6.1 Сгенерированный код выполняется с правами пользователя, без строгой изоляции

Скрипты, которые пишет модель, запускаются обычным Python **от вашего
пользователя**: с вашим `$HOME`, дисплеем, сессией D-Bus, доступом к сети
и файлам. Песочницы (контейнер, `bubblewrap`, отдельный пользователь) нет.

Почему так: скрипты по замыслу управляют вашими GUI-приложениями и файлами —
изоляция отняла бы ровно то, ради чего они существуют. Что всё-таки стоит
на пути случайной прожорливости (не злого умысла):

* новый скрипт **всегда** требует подтверждения кнопкой, и снять этот флаг
  может только пользователь; починенная версия спрашивает заново;
* таймаут стены, `prlimit` на память / CPU / размер файла;
* отказ работать от root; отдельный venv, системные пакеты не трогаются;
* аудит каждого запуска (код, вывод, код возврата) в `script-runtime.log`;
* автоустановка недостающих пакетов — по имени из трассировки, то есть
  в конечном счёте из кода модели; отключается `scripts.auto_install_deps = false`.

Чего это **не** защищает: скрипт, которому вы нажали ✅, может делать с вашими
файлами и аккаунтами всё, что можете вы. Читайте объяснение в запросе
подтверждения. Текст веб-страниц и буфера обмена попадает в контекст модели —
это поверхность для prompt injection; ограничитель тот же — подтверждение.

### 6.2 Конфиг и секреты хранятся открытым текстом

`config/agent.toml` содержит API-ключи провайдеров, токен Telegram-бота и,
при наличии, ключ Brave и токен GitHub — обычным текстом. Keyring, шифрования
и Secret Service нет. Компенсация: файл в `.gitignore`, права `600`, секреты
не пишутся в логи и `toString()`, не отправляются в Telegram (в меню только
«задан / не задан»), вырезаются из бэкапа по маске имени ключа
(`*token*`, `*api_key*`, `*secret*`, `*password*`).

Чего это **не** защищает: любой процесс от вашего пользователя (и любой
скрипт из 5.1) может прочитать файл. Утечка домашнего каталога — утечка ключей.

### 6.3 Прочее, о чём стоит знать

* **Группа `input`** ради push-to-talk даёт чтение всего ввода в системе.
* **Белый список Telegram — единственная авторизация.** Проверка по username
  и chat id; кто в списке, тот управляет агентом целиком, включая запуск скриптов.
* **Бэкап без секретов** всё равно содержит личные данные: факты о людях,
  заметки, историю диалогов. Отправка его в Telegram — ваше решение.
* **Поиск через DuckDuckGo — разбор HTML**, сломается при смене вёрстки;
  Brave API платный.
* **Один поток обработки**: долгий скрипт задерживает и чат, и голос,
  и напоминания.
* **Ответ на голос уходит в последний активный чат Telegram** — при двух
  чатах поедет не туда.
* **Ollama Cloud и Anthropic — внешние сервисы:** текст запросов, лог сессии
  и факты из памяти уходят к ним. Локально остаётся только аудио (whisper.cpp,
  Piper). Полностью локальный режим — локальный Ollama.

### 6.4 Что пересмотреть, если агент станет доступен кому-то ещё

Как только появляется второй пользователь, второй чат или общая машина,
список выше превращается из компромиссов в дыры. Минимум:

1. **Изоляция скриптов.** `bubblewrap`/`firejail` с явно выданными путями
   и без сессии D-Bus, либо отдельный системный пользователь под скрипты.
   Часть сценариев с GUI при этом перестанет работать — это придётся решать
   заново, а не докручивать `prlimit`.
2. **Секреты — в Secret Service** (GNOME Keyring через D-Bus) или хотя бы
   в отдельный файл с другим владельцем; конфиг без ключей.
3. **Авторизация по пользователю, а не по списку.** Кто что может: разрешение
   на скрипты, на память, на настройки; подтверждение скрипта — от того,
   кто его попросил, и не переносится на других.
4. **Разделение памяти и заметок по пользователям.** Сейчас факты о людях
   и заметки — одно хранилище на всех; чужой чат увидит чужие факты.
5. **Отдельная защита от prompt injection** для контента извне (веб, буфер,
   пересланные сообщения): пометка источника в контексте, запрет действий
   по инструкциям из такого контента.
6. **Ограничение частоты и бюджета на пользователя** — сейчас бюджет
   обращений считается на запрос, денежного потолка нет.
7. **Аудит с привязкой к пользователю** и хранение логов вне досягаемости
   скриптов (сейчас `logs/` пишется тем же пользователем).

---

## 7. Сервер на удалённой машине (headless, Этап 2)

Второй способ запуска — `server-app`: тот же агент **без окна и без JavaFX**
на машине, где нет дисплея (VPS, домашний сервер, Ubuntu по SSH). Скрипты,
буфер обмена и голос при этом выполняются не на сервере, а на **тонком
клиенте** на вашем компьютере (клиент — следующий шаг; пока сервер честно
отвечает «нет связи с компьютером»). Модель, память, Telegram, напоминания,
заметки, персоны, TTS, watchdog — всё на сервере, как раньше.

### 7.1 Установка на Ubuntu

Готовый `server-ai-agent-server_<v>_amd64.deb` из релиза (раздел 1) избавляет
от сборки и от JDK; ниже — путь из исходников, если нужна своя ветка.

```bash
sudo apt install -y openjdk-25-jdk git python3-venv ffmpeg
# whisper.cpp -- как в разделе 2.4 (cmake, модель); Piper -- как в 2.5, если нужны голосовые ответы
git clone git@github.com:qateralong/server-ai-agent.git ~/server-ai-agent && cd ~/server-ai-agent
./gradlew :server-app:installDist
```

### 7.2 Конфигурация — только файл, и это намеренно

```bash
cp config/agent.example.toml config/agent.toml && chmod 600 config/agent.toml
$EDITOR config/agent.toml
```

Заполнить: `[llm.<провайдер>]` ключ и модель, `[telegram]` токен и
`allowed_usernames`, `[stt]` пути к `whisper-cli` и модели (расшифровка голоса
с клиента идёт на сервере), `[transport]` `port` (8765) и `bind`.

В серверном режиме конфиг читается **один раз при старте** и правится **только
в файле по SSH**, затем `systemctl --user restart server-ai-agent-server`. Окна нет,
а раздел «⚙️ Настройки» в Telegram показывает значения без кнопок правки.
Это осознанно: на удалённой машине нет второго интерфейса, который показал бы
расхождение файла и памяти; секреты в чат не уходят и так; а менять провайдера
или токен вслепую из чата на машине, до которой идти по SSH, — способ остаться
без связи. Один источник правды, один способ его менять.

### 7.3 Автозапуск

```bash
mkdir -p ~/.config/systemd/user
cp systemd/server-ai-agent-server.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now server-ai-agent-server
sudo loginctl enable-linger $USER     # иначе user-юнит остановится вместе с SSH-сессией
```

Юнит привязан к `default.target` (дисплея нет), перезапуск при падении тот же,
что у настольного. Порт транспорта должен быть доступен клиенту:
`sudo ufw allow 8765/tcp` либо SSH-туннель.

### 7.4 Паринг клиента

Окна нет, поэтому токены выпускаются из командной строки на сервере:

```bash
~/server-ai-agent/server-app/build/install/server-app/bin/server-app pair "ноутбук"
# печатает один раз: url, fingerprint, client_id, token -- для конфига клиента
~/server-ai-agent/server-app/build/install/server-app/bin/server-app clients
~/server-ai-agent/server-app/build/install/server-app/bin/server-app revoke <client_id>
```

На диске остаётся только хэш токена (`clients.json`, права 600); запущенный
сервер подхватывает новых клиентов без перезапуска.

### 7.5 Статус и логи

* **Telegram** → `/menu`: **📊 Статус** (агент, модель и её доступность,
  подключённые клиенты, watchdog, диск, версия, последняя ошибка, путь
  к конфигу) и **📜 Логи** (последние события с фильтром ERROR / WARN+ / все).
  В настольной сборке этих разделов в чате нет — там они в окне.
* **По SSH** — логи остаются файлами, как и раньше:

```bash
journalctl --user -u server-ai-agent-server -f                                   # процесс: старт, падения
tail -f ~/server-ai-agent/logs/agent.log | jq -r '"\(.ts[11:19]) \(.level) \(.subsystem) \(.msg)"'
tail -f ~/server-ai-agent/logs/transport.log | jq -r '.msg'                      # подключения клиентов, AUTH
grep '"remote.no_client"' ~/server-ai-agent/logs/agent-core.log | tail           # когда компьютер был не на связи
```


---

## 8. Тонкий клиент на вашем компьютере (Linux)

Пара к серверу из раздела 7: процесс без окна и без модели, который выполняет
скрипты, читает буфер и записывает голос по клавише — и всё это отдаёт серверу.
Ставится и готовым пакетом (`…-client-…pkg.tar.zst`, `…-client_…amd64.deb`,
`.exe` под Windows — раздел 1); ниже — из исходников.

```bash
cd ~/server-ai-agent && ./gradlew :client-app:installDist
cd native/evdev-hotkey && make && cd -                      # push-to-talk (раздел 2.3: группа input)
cp config/client.example.toml config/client.toml && chmod 600 config/client.toml
$EDITOR config/client.toml           # блок [server] -- из «server-app pair "ноутбук"» на сервере
cp systemd/server-ai-agent-client.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now server-ai-agent-client
```

Что умеет: скрипты (свой venv в `~/.local/share/bebebe-client/`, автоустановка
пакетов, таймаут), буфер обмена (`wl-paste`), push-to-talk (запись через
`javax.sound.sampled`, звук уходит на сервер, расшифровка там). Иконка в трее:
зелёная — подключён, серая — нет связи, красная — сервер отклонил (нужен
новый паринг); пункт «Выход». На GNOME без AppIndicator иконки нет — клиент
работает без неё. Настроек в UI нет — только `client.toml`.

Логи клиента — `~/.local/share/bebebe-client/logs/`, процесс —
`journalctl --user -u server-ai-agent-client -f`.

---

## 9. Где что лежит

```
config/agent.example.toml  все секции конфига с комментариями
config/agent.toml          ваш конфиг (создаётся при первом запуске, в git не попадает)
config/client.example.toml конфиг тонкого клиента (раздел 8)
systemd/                   юниты systemd --user: server-ai-agent (окно), server-ai-agent-server (headless), server-ai-agent-client
desktop/                   ярлык для GNOME
native/evdev-hotkey/       хелпер push-to-talk (C, libevdev)
logs/                      JSON-логи по подсистемам
~/.local/share/bebebe-agent/   venv скриптов, каталог скриптов, базы SQLite, статистика, бэкапы
~/.agent-data/notes/       заметки: markdown + собственный git-репозиторий (открывается в Obsidian)
```

Команды разработки: `./gradlew build` (всё + тесты), `./gradlew test`,
`./gradlew :telegram-bridge:test` (один модуль), `./gradlew :supervisor-app:run`.

Собрать то же, что кладётся в релиз: `./gradlew releaseArtifacts
-PreleaseVersion=0.2.2-local` → `build/release/{jars,native}`. Соберётся то,
для чего на машине есть инструменты: на Arch — `.pkg.tar.zst` (нужен `makepkg`),
на Ubuntu — `.deb` (`dpkg-deb`, `fakeroot`); Windows-сборку делает только CI.
Релиз выпускается тегом `v*` — его публикует
[`.github/workflows/release.yml`](.github/workflows/release.yml).
