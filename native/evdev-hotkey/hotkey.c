#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <libevdev/libevdev.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define MAX_DEVICES 32
#define DEFAULT_KEY "KEY_HOME"
#define INPUT_DIR "/dev/input"

struct device {
    int fd;
    struct libevdev *dev;
    char path[512];
};

static volatile sig_atomic_t running = 1;

static void on_signal(int signum) {
    (void) signum;
    running = 0;
}

static bool supports_key(struct libevdev *dev, unsigned int key_code) {
    return libevdev_has_event_type(dev, EV_KEY) && libevdev_has_event_code(dev, EV_KEY, key_code);
}

static bool open_device(const char *path, unsigned int key_code, struct device *out, bool quiet) {
    int fd = open(path, O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        if (!quiet) {
            fprintf(stderr, "evdev-hotkey: cannot open %s: %s\n", path, strerror(errno));
            if (errno == EACCES) {
                fprintf(stderr, "  -> the input group is needed: sudo usermod -aG input $USER, then log out and back in\n");
            }
        }
        return false;
    }

    struct libevdev *dev = NULL;
    int rc = libevdev_new_from_fd(fd, &dev);
    if (rc < 0) {
        if (!quiet) {
            fprintf(stderr, "evdev-hotkey: %s is not an input device: %s\n", path, strerror(-rc));
        }
        close(fd);
        return false;
    }

    if (!supports_key(dev, key_code)) {
        libevdev_free(dev);
        close(fd);
        return false;
    }

    out->fd = fd;
    out->dev = dev;
    snprintf(out->path, sizeof(out->path), "%s", path);
    return true;
}

static void close_device(struct device *d) {
    if (d->dev) {
        libevdev_free(d->dev);
        d->dev = NULL;
    }
    if (d->fd >= 0) {
        close(d->fd);
        d->fd = -1;
    }
}

static int scan_devices(unsigned int key_code, struct device *devices, int max) {
    DIR *dir = opendir(INPUT_DIR);
    if (!dir) {
        fprintf(stderr, "evdev-hotkey: cannot open %s: %s\n", INPUT_DIR, strerror(errno));
        return 0;
    }

    int found = 0;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL && found < max) {
        if (strncmp(entry->d_name, "event", 5) != 0) {
            continue;
        }
        char path[512];
        snprintf(path, sizeof(path), "%s/%s", INPUT_DIR, entry->d_name);
        if (open_device(path, key_code, &devices[found], true)) {
            found++;
        }
    }
    closedir(dir);
    return found;
}

static void print_usage(const char *program) {
    fprintf(stderr,
            "evdev-hotkey -- prints DOWN/UP on key press\n"
            "\n"
            "Usage: %s [OPTIONS] [DEVICE...]\n"
            "\n"
            "  -k, --key NAME   which key to listen for (default %s)\n"
            "  -l, --list      show matching devices and exit\n"
            "  -h, --help      this help\n"
            "\n"
            "Without a device argument, all %s/event* are scanned.\n"
            "Example: %s --key KEY_HOME /dev/input/event6\n",
            program, DEFAULT_KEY, INPUT_DIR, program);
}

int main(int argc, char **argv) {
    const char *key_name = DEFAULT_KEY;
    bool list_only = false;
    const char *explicit_paths[MAX_DEVICES];
    int explicit_count = 0;

    for (int i = 1; i < argc; i++) {
        const char *arg = argv[i];
        if ((strcmp(arg, "-k") == 0 || strcmp(arg, "--key") == 0) && i + 1 < argc) {
            key_name = argv[++i];
        } else if (strcmp(arg, "-l") == 0 || strcmp(arg, "--list") == 0) {
            list_only = true;
        } else if (strcmp(arg, "-h") == 0 || strcmp(arg, "--help") == 0) {
            print_usage(argv[0]);
            return 0;
        } else if (arg[0] == '-') {
            fprintf(stderr, "evdev-hotkey: unknown option %s\n", arg);
            print_usage(argv[0]);
            return 2;
        } else if (explicit_count < MAX_DEVICES) {
            explicit_paths[explicit_count++] = arg;
        }
    }

    int key_code = libevdev_event_code_from_name(EV_KEY, key_name);
    if (key_code < 0) {
        fprintf(stderr, "evdev-hotkey: unknown key '%s' (expected something like KEY_HOME)\n", key_name);
        return 2;
    }

    struct device devices[MAX_DEVICES];
    for (int i = 0; i < MAX_DEVICES; i++) {
        devices[i].fd = -1;
        devices[i].dev = NULL;
    }

    int count = 0;
    if (explicit_count > 0) {
        for (int i = 0; i < explicit_count; i++) {
            if (open_device(explicit_paths[i], (unsigned int) key_code, &devices[count], false)) {
                count++;
            } else {
                fprintf(stderr, "evdev-hotkey: %s does not match (no key %s or no access)\n",
                        explicit_paths[i], key_name);
            }
        }
    } else {
        count = scan_devices((unsigned int) key_code, devices, MAX_DEVICES);
    }

    if (count == 0) {
        fprintf(stderr, "evdev-hotkey: no device found with key %s.\n", key_name);
        fprintf(stderr, "  Check the group: sudo usermod -aG input $USER (then log out and back in)\n");
        return 1;
    }

    if (list_only) {
        for (int i = 0; i < count; i++) {
            printf("%s\t%s\n", devices[i].path, libevdev_get_name(devices[i].dev));
            close_device(&devices[i]);
        }
        return 0;
    }

    for (int i = 0; i < count; i++) {
        fprintf(stderr, "evdev-hotkey: listening on %s (%s)\n", devices[i].path, libevdev_get_name(devices[i].dev));
    }
    fprintf(stderr, "evdev-hotkey: key %s, devices: %d\n", key_name, count);

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);

    signal(SIGPIPE, SIG_IGN);

    struct pollfd fds[MAX_DEVICES];
    for (int i = 0; i < count; i++) {
        fds[i].fd = devices[i].fd;
        fds[i].events = POLLIN;
    }

    int alive = count;
    while (running && alive > 0) {
        int ready = poll(fds, (nfds_t) count, 500);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            fprintf(stderr, "evdev-hotkey: poll: %s\n", strerror(errno));
            break;
        }
        if (ready == 0) {
            continue;
        }

        for (int i = 0; i < count; i++) {
            if (fds[i].fd < 0 || !(fds[i].revents & (POLLIN | POLLERR | POLLHUP))) {
                continue;
            }
            if (fds[i].revents & (POLLERR | POLLHUP)) {

                fprintf(stderr, "evdev-hotkey: %s disconnected\n", devices[i].path);
                close_device(&devices[i]);
                fds[i].fd = -1;
                alive--;
                continue;
            }

            struct input_event ev;
            int rc;
            do {
                rc = libevdev_next_event(devices[i].dev, LIBEVDEV_READ_FLAG_NORMAL, &ev);
                if (rc == LIBEVDEV_READ_STATUS_SYNC) {

                    while (rc == LIBEVDEV_READ_STATUS_SYNC) {
                        rc = libevdev_next_event(devices[i].dev, LIBEVDEV_READ_FLAG_SYNC, &ev);
                    }
                    continue;
                }
                if (rc != LIBEVDEV_READ_STATUS_SUCCESS) {
                    break;
                }
                if (ev.type != EV_KEY || ev.code != (unsigned int) key_code) {
                    continue;
                }

                const char *line = NULL;
                if (ev.value == 1) {
                    line = "DOWN";
                } else if (ev.value == 0) {
                    line = "UP";
                }
                if (line && printf("%s\n", line) < 0) {
                    fprintf(stderr, "evdev-hotkey: stdout closed, exiting\n");
                    running = 0;
                    break;
                }

                fflush(stdout);
            } while (rc == LIBEVDEV_READ_STATUS_SUCCESS && running);

            if (rc == -ENODEV) {
                fprintf(stderr, "evdev-hotkey: %s disappeared\n", devices[i].path);
                close_device(&devices[i]);
                fds[i].fd = -1;
                alive--;
            }
        }
    }

    for (int i = 0; i < count; i++) {
        close_device(&devices[i]);
    }
    fprintf(stderr, "evdev-hotkey: shutting down\n");
    return 0;
}
