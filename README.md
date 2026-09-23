# Server AI Agent

A personal AI agent that lives on your own machine and answers in Telegram.
The model is Ollama (cloud or a local daemon) or Claude, switchable on the fly.

---

## What it can do

* **Telegram bot** with a menu: text, voice messages, buttons for every section.
  It answers only the people on the whitelist.
* **Voice** — a voice message you send is recognised locally (whisper.cpp), and
  the agent can answer with voice too (Piper).
* **Memory** — remembers people and facts about them, and takes them into
  account in later conversations. It can be asked to remember something on the
  spot, corrected when it got something wrong, and it keeps a few lines about
  every conversation that has ended, so "what did we discuss yesterday?" has an
  answer. Whatever it decided to remember on its own waits in a review queue,
  where one tap says "yes, that's right" -- and confirmed facts are trusted more.
* **Notes and lists** — plain markdown files, they open in Obsidian.
* **Reminders** — "remind me tomorrow at 9", with repeats; missed ones are
  caught up.
* **Scripts** — when needed it writes and runs Python code, but only after you
  confirm it with a button. Successful ones are saved and reused.
* **Personas** — named instructions that set the style of the answers.

Everything except the model itself runs locally: voice recordings and notes go
nowhere.

---

## Installing the server (Ubuntu)

The server is the build without a window, for a machine with no display: a VPS
or a home server. Download `server-ai-agent-server_<version>_amd64.deb` from the
[releases](https://github.com/qateralong/server-ai-agent/releases) — the JVM is
inside, Java does not have to be installed separately.

```bash
sudo apt install -y python3-venv ffmpeg
sudo dpkg -i server-ai-agent-server_0.2.2_amd64.deb
```

### Configuration

Download the sample and fill it in:

```bash
mkdir -p ~/.config/bebebe-agent
curl -o ~/.config/bebebe-agent/config.toml \
    https://raw.githubusercontent.com/qateralong/server-ai-agent/main/config/agent.example.toml
chmod 600 ~/.config/bebebe-agent/config.toml
nano ~/.config/bebebe-agent/config.toml
```

The minimum it will not start without:

| Section | What to put there |
|---|---|
| `[llm]` | `provider = "ollama"` or `"claude"` |
| `[llm.ollama]` / `[llm.claude]` | the key and the model name |
| `[telegram]` | the token from `@BotFather` and `allowed_usernames` — your name without `@` |

An empty `allowed_usernames` means the bot answers nobody — that is on purpose.
Voice messages additionally need the `[stt]` section filled in (the paths to
`whisper-cli` and to the model); without it the bot honestly says it does not
recognise voice.

On the server the config is read once at start and is edited in the file only:
after a change run `systemctl --user restart server-ai-agent-server`.

### Starting it automatically

```bash
mkdir -p ~/.config/systemd/user
curl -o ~/.config/systemd/user/server-ai-agent-server.service \
    https://raw.githubusercontent.com/qateralong/server-ai-agent/main/systemd/server-ai-agent-server.service
systemctl --user daemon-reload
systemctl --user enable --now server-ai-agent-server
sudo loginctl enable-linger $USER     # otherwise the service stops with your SSH session
```

In the unit, change `ExecStart` to
`/opt/server-ai-agent-server/bin/server-ai-agent-server`.

### Checking that it works

In Telegram: `/start` → **⚡ Power** → switch on → write anything.
The agent is off by default, and the menu works while it is off too.

What is going on on the server:

```bash
journalctl --user -u server-ai-agent-server -f      # startup and crashes
```

The status and the latest events are in the chat as well: `/menu` →
**📊 Status**, **📜 Logs**.

---

## The other builds

The same releases hold two more: the **agent with a window** (JavaFX, for a
Linux desktop) and the **thin client** — it gives the server hands on your own
computer: runs scripts, reads the clipboard, records voice on a key press.
Formats: `.deb`, `.pkg.tar.zst` for Arch, `.exe` for Windows and `tar.gz`.

Building from source: `./gradlew build`, and `./gradlew :supervisor-app:run`
to start the window.

---

## Limitations

This is a personal single-user agent. Secrets live in the config in plain text
and scripts run with the user's rights without a sandbox — both are deliberate
and acceptable while one person uses the agent on their own machine. Before
opening it up to anyone else, both have to change.
