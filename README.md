# Server AI Agent

Персональный AI-агент, который живёт на вашей машине и отвечает в Telegram.
Модель — Ollama (облако или локальный демон) либо Claude, переключается на лету.

---

## Что умеет

* **Telegram-бот** с меню: текст, голосовые сообщения, кнопки для всех разделов.
  Отвечает только тем, кто в белом списке.
* **Голос** — присланное голосовое распознаёт локально (whisper.cpp) и может
  отвечать голосом (Piper).
* **Память** — помнит людей и факты о них, учитывает их в следующих разговорах.
* **Заметки и списки** — обычные markdown-файлы, открываются в Obsidian.
* **Напоминания** — «напомни завтра в 9», с повторами; не сработавшие догоняет.
* **Скрипты** — при необходимости пишет и выполняет Python-код, но только
  с вашего подтверждения кнопкой. Удачные сохраняет и переиспользует.
* **Персоны** — именованные инструкции, задающие стиль ответов.

Всё, кроме самой модели, работает локально: записи голоса и заметки никуда
не уходят.

---

## Установка сервера (Ubuntu)

Сервер — сборка без окна, для машины без дисплея: VPS или домашний сервер.
Скачайте `server-ai-agent-server_<версия>_amd64.deb` из
[релизов](https://github.com/qateralong/server-ai-agent/releases) — Java внутри,
ставить её отдельно не нужно.

```bash
sudo apt install -y python3-venv ffmpeg
sudo dpkg -i server-ai-agent-server_0.2.2_amd64.deb
```

### Конфиг

Скачайте образец и заполните его:

```bash
mkdir -p ~/.config/bebebe-agent
curl -o ~/.config/bebebe-agent/config.toml \
    https://raw.githubusercontent.com/qateralong/server-ai-agent/main/config/agent.example.toml
chmod 600 ~/.config/bebebe-agent/config.toml
nano ~/.config/bebebe-agent/config.toml
```

Минимум, без которого не запустится:

| Секция | Что вписать |
|---|---|
| `[llm]` | `provider = "ollama"` или `"claude"` |
| `[llm.ollama]` / `[llm.claude]` | ключ и имя модели |
| `[telegram]` | токен от `@BotFather` и `allowed_usernames` — ваш ник без `@` |

Пустой `allowed_usernames` означает, что бот не отвечает никому — так и задумано.
Голосовые сообщения дополнительно требуют заполненной секции `[stt]` (пути
к `whisper-cli` и модели); без неё бот честно скажет, что не распознаёт голос.

На сервере конфиг читается один раз при старте и правится только в файле:
после изменений — `systemctl --user restart server-ai-agent-server`.

### Автозапуск

```bash
mkdir -p ~/.config/systemd/user
curl -o ~/.config/systemd/user/server-ai-agent-server.service \
    https://raw.githubusercontent.com/qateralong/server-ai-agent/main/systemd/server-ai-agent-server.service
systemctl --user daemon-reload
systemctl --user enable --now server-ai-agent-server
sudo loginctl enable-linger $USER     # иначе сервис остановится вместе с SSH-сессией
```

В юните поправьте `ExecStart` на `/opt/server-ai-agent-server/bin/server-ai-agent-server`.

### Проверка

В Telegram: `/start` → **⚡ Питание** → включить → напишите что угодно.
Агент по умолчанию выключен, меню работает и в выключенном состоянии.

Что происходит на сервере:

```bash
journalctl --user -u server-ai-agent-server -f      # запуск и падения
```

Статус и последние события видны и в чате: `/menu` → **📊 Статус**, **📜 Логи**.

---

## Остальные сборки

В тех же релизах лежат ещё две: **агент с окном** (JavaFX, для рабочего стола
Linux) и **тонкий клиент** — он даёт серверу руки на вашем компьютере: выполняет
скрипты, читает буфер обмена, записывает голос по клавише. Форматы — `.deb`,
`.pkg.tar.zst` для Arch, `.exe` для Windows и `tar.gz`.

Собрать из исходников: `./gradlew build`, запустить окно —
`./gradlew :supervisor-app:run`.

---

## Ограничения

Это личный однопользовательский агент. Секреты лежат в конфиге открытым
текстом, а скрипты выполняются с правами пользователя без песочницы — оба
решения осознанные и приемлемы, пока агентом пользуется один человек на своей
машине. Прежде чем открывать доступ кому-то ещё, и то и другое нужно менять.
