# evdev-hotkey

A native push-to-talk helper: listens for one key via evdev and prints
`DOWN` / `UP` to stdout. The Java process reads this stream and uses it
to start and stop the microphone recording.

## Why it exists

Wayland has **no API for global hotkeys**. The compositor delivers keyboard
events only to the focused window; there is no equivalent of `XGrabKey`,
and this is not a limitation of a particular compositor but the protocol's
security model.

So the only way to learn about a key press when the application window is
not focused is to read input devices directly from `/dev/input`. This is not
available from the JVM without JNI, hence it is split out into a small
separate C process.

A side benefit: the helper does not depend on the compositor and works the
same way on GNOME, KDE, Sway and anything else.

## Building

```bash
sudo pacman -S libevdev      # Arch; on Debian -- libevdev-dev
make
```

The binary appears at `build/evdev-hotkey`. It can be installed into
`~/.local/bin`:

```bash
make install
```

## Access rights

`/dev/input/event*` belong to the `input` group, so a regular user cannot
read them. You need to add yourself to the group once:

```bash
sudo usermod -aG input $USER
```

**You must log out and back in** (or reboot) — group membership takes effect
at session login, not immediately. Check:

```bash
groups | grep input
```

Without this the helper will honestly say `no device found`.

> The `input` group grants access to all input devices, i.e. effectively the
> ability to read any input in the system. For a single-user machine this is
> acceptable and the usual way to set it up; the alternative is a udev rule
> for one specific device.

## Which device to listen on

There are usually several keyboards in the system: built-in, USB, virtual
compositor devices. That is why **by default the helper listens on all of
them at once** — specifying a device is not required.

See what it found:

```bash
make list
# or
./build/evdev-hotkey --list
```

The output is the path and the device name:

```
/dev/input/event6	Logitech USB Keyboard
/dev/input/event10	Logitech USB Keyboard Consumer Control
```

You can restrict it to one device — via `stt.hotkey_device` in the config
or as an argument:

```bash
./build/evdev-hotkey /dev/input/event6
```

An alternative way to find the keyboard is by the symlink name:

```bash
ls -l /dev/input/by-path/ | grep kbd
```

## Manual check

```bash
./build/evdev-hotkey
# press and release Home -- DOWN and UP will appear on stdout
```

## Usage

```
evdev-hotkey [OPTIONS] [DEVICE...]

  -k, --key NAME   which key to listen for (default KEY_HOME)
  -l, --list       show matching devices and exit
  -h, --help       this help
```

Key names are as in `linux/input-event-codes.h`: `KEY_HOME`, `KEY_F13`,
`KEY_RIGHTCTRL`, and so on.

## What it deliberately does not do

* It does not grab the key -- a Home key press **also reaches the active
  window**. Grabbing would require `EVIOCGRAB`, which would take the key away
  from the whole system. That is why it is better to pick a key that is not
  bound to anything else for push-to-talk (e.g. `KEY_F13`).
* It does not handle key repeat (`value == 2`) -- for push-to-talk only the
  press and release moments matter.
* It does not write or record anything -- only two words to stdout.
