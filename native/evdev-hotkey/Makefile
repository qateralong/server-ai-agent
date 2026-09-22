CC      ?= gcc
CFLAGS  ?= -Wall -Wextra -O2
PREFIX  ?= $(HOME)/.local

BINARY   := build/evdev-hotkey
SOURCE   := hotkey.c

EVDEV_CFLAGS := $(shell pkg-config --cflags libevdev 2>/dev/null)
EVDEV_LIBS   := $(shell pkg-config --libs libevdev 2>/dev/null)

.PHONY: all clean install uninstall list check

all: check $(BINARY)

check:
	@pkg-config --exists libevdev || { \
		echo "libevdev not found."; \
		echo "  Arch:   sudo pacman -S libevdev"; \
		echo "  Debian: sudo apt install libevdev-dev"; \
		echo "  Fedora: sudo dnf install libevdev-devel"; \
		exit 1; }

$(BINARY): $(SOURCE) | build
	$(CC) $(CFLAGS) $(EVDEV_CFLAGS) -o $@ $< $(EVDEV_LIBS)
	@echo "Built: $@"

build:
	@mkdir -p build

install: all
	@mkdir -p $(PREFIX)/bin
	install -m 755 $(BINARY) $(PREFIX)/bin/evdev-hotkey
	@echo "Installed: $(PREFIX)/bin/evdev-hotkey"

uninstall:
	rm -f $(PREFIX)/bin/evdev-hotkey

list: all
	@./$(BINARY) --list || true

clean:
	rm -rf build
