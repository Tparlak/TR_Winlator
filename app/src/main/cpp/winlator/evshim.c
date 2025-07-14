/* ─────────── evshim.c (Multi-Controller Foundation) ─────────── */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <unistd.h>
#include <SDL.h>
#include <sys/mman.h>

#if defined(__GNUC__) || defined(__clang__)
#define memory_barrier() __sync_synchronize()
#else
#define memory_barrier()
#endif

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO , "EVSHIM_GUEST", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "EVSHIM_GUEST", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EVSHIM_GUEST", __VA_ARGS__)
#endif

#define MAX_GAMEPADS 4

struct gamepad_io {
    int16_t lx, ly, rx, ry, lt, rt;
    uint8_t btn[15];
    uint8_t hat;
    uint8_t _padding[4]; // Pad to 32 bytes for input section
    uint16_t low_freq_rumble;
    uint16_t high_freq_rumble;
};


// --- We now need arrays to hold data for each controller ---
static volatile struct gamepad_io *io_pads[MAX_GAMEPADS] = {NULL};
static int vjoy_ids[MAX_GAMEPADS] = {-1};

// The rumble callback now needs to know WHICH controller to vibrate
static int OnRumble(void *userdata, uint16_t low_frequency_rumble, uint16_t high_frequency_rumble) {
    int player_index = (int)(intptr_t)userdata;
    if (player_index < 0 || player_index >= MAX_GAMEPADS || !io_pads[player_index]) return -1;

    io_pads[player_index]->low_freq_rumble = low_frequency_rumble;
    io_pads[player_index]->high_freq_rumble = high_frequency_rumble;

    LOGD("Rumble P%d: Low=%u, High=%u", player_index, low_frequency_rumble, high_frequency_rumble);
    return 0;
}

// The updater thread now takes a player index to know which controller to manage
void *vjoy_updater(void *arg) {
    int player_index = (int)(intptr_t)arg;
    SDL_Joystick *js = SDL_JoystickOpen(vjoy_ids[player_index]);
    volatile struct gamepad_io *shared_pad = io_pads[player_index];

    if (!js || !shared_pad) { /* ... error handling ... */ }

    // Create a local struct to hold the last sent state
    struct gamepad_io last_state;
    memset(&last_state, 0, sizeof(struct gamepad_io));

    LOGI(">>> VJOY UPDATER for Player %d is LIVE in PID %d <<<", player_index, getpid());

    while (1) {
        memory_barrier(); // Ensure we read the latest from shared memory

        // Compare the current shared memory state with the last state we sent
        if (memcmp((void*)shared_pad, &last_state, sizeof(struct gamepad_io)) != 0) {
            // Data has changed! Update the virtual joystick.
            LOGD("P%d State Changed. Updating vjoy.", player_index);

            SDL_JoystickSetVirtualAxis(js, 0, shared_pad->lx);
            SDL_JoystickSetVirtualAxis(js, 1, shared_pad->ly);
            SDL_JoystickSetVirtualAxis(js, 2, shared_pad->rx);
            SDL_JoystickSetVirtualAxis(js, 3, shared_pad->ry);
            SDL_JoystickSetVirtualAxis(js, 4, shared_pad->lt);
            SDL_JoystickSetVirtualAxis(js, 5, shared_pad->rt);
            for (int i = 0; i < 15; ++i) {
                SDL_JoystickSetVirtualButton(js, i, shared_pad->btn[i]);
            }
            SDL_JoystickSetVirtualHat(js, 0, shared_pad->hat);

            // Update our local copy to reflect the new state
            memcpy(&last_state, (void*)shared_pad, sizeof(struct gamepad_io));
        }

        SDL_PumpEvents(); // Still need to pump events regardless
        SDL_Delay(5);     // A short delay is still good practice
    }
    return NULL;
}

// The constructor now creates all controllers in a loop
__attribute__((constructor))
static void initialize_all_pads() {
    LOGI("EVSHIM Initializing for Multi-Controller support...");

    if (SDL_Init(SDL_INIT_JOYSTICK | SDL_INIT_HAPTIC) < 0) {
        LOGE("SDL_Init failed: %s", SDL_GetError());
        return;
    }

    const char* max_players_str = getenv("EVSHIM_MAX_PLAYERS");
    int num_players = max_players_str ? atoi(max_players_str) : 1;
    if (num_players > MAX_GAMEPADS) num_players = MAX_GAMEPADS;

    for (int i = 0; i < num_players; i++) {
        char mem_path[256];
        if (i == 0) {
            // Player 1 looks for the original, non-numbered path.
            snprintf(mem_path, sizeof(mem_path), "/data/data/com.winlator.cmod/files/imagefs/tmp/gamepad.mem");
        } else {
            // Players 2, 3, 4 look for the numbered path.
            snprintf(mem_path, sizeof(mem_path), "/data/data/com.winlator.cmod/files/imagefs/tmp/gamepad%d.mem", i);
        }

        int fd = open(mem_path, O_RDWR);
        if (fd < 0) {
            LOGE("P%d: Failed to open memory file '%s': %s", i, mem_path, strerror(errno));
            continue;
        }

        io_pads[i] = (volatile struct gamepad_io*) mmap(NULL, sizeof(struct gamepad_io), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        close(fd);

        if (io_pads[i] == MAP_FAILED) {
            LOGE("P%d: mmap failed for file '%s': %s", i, mem_path, strerror(errno));
            io_pads[i] = NULL;
            continue;
        }

        SDL_VirtualJoystickDesc desc;
        SDL_zero(desc);
        desc.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
        // All controllers are the proper GameController type.
        desc.type = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
        desc.naxes = 6;
        desc.nbuttons = 15;
        desc.nhats = 1;

        char device_name[64];

        // --- The empirically-proven "B/A" Naming Scheme ---
        // This is the only known naming that produces the correct 1,2,3,4 controller order.
        if (i < 2) {
            snprintf(device_name, sizeof(device_name), "B (Player %d)", i + 1);
        } else {
            snprintf(device_name, sizeof(device_name), "A (Player %d)", i + 1);
        }
        // --- End of Naming Scheme ---

        desc.name = device_name;

        // All controllers get rumble.
        desc.Rumble = &OnRumble;
        desc.userdata = (void*)(intptr_t)i;

        vjoy_ids[i] = SDL_JoystickAttachVirtualEx(&desc);
        if (vjoy_ids[i] < 0) {
            LOGE("P%d: Failed to attach virtual joystick: %s", i, SDL_GetError());
            continue;
        }

        LOGD("P%d: Successfully created vjoy instance (id=%d)", i, vjoy_ids[i]);

        pthread_t th;
        pthread_create(&th, NULL, vjoy_updater, (void*)(intptr_t)i);
        pthread_detach(th);
    }


}

/* ------------  “hide /dev/input/event*” hooks  -------------------- */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <unistd.h>
#include <linux/input.h>
#include <sys/ioctl.h>   /* declares the bionic-inline ioctl */
#include <errno.h>
#include <string.h>

#undef ioctl             /* drop the inline version so we can replace it */

static inline int is_event_node(const char *p)
{ return p && !strncmp(p, "/dev/input/event", 16); }

/* ---------- open()/open64() hooks ----------------------------------- */
typedef int (*open_f)(const char *, int, ...);
static open_f real_open;

static int open_common(const char *path, int flags, va_list ap)
{
    if (is_event_node(path)) { errno = ENOENT; return -1; }

    if (!real_open) real_open = (open_f)dlsym(RTLD_NEXT, "open");

    mode_t mode = 0;
    if (flags & O_CREAT) mode = va_arg(ap, mode_t);
    return real_open(path, flags, mode);
}

int open(const char *path, int flags, ...)
__attribute__((visibility("default")));
int open(const char *path, int flags, ...)
{
    va_list ap; va_start(ap, flags);
    int r = open_common(path, flags, ap);
    va_end(ap); return r;
}

int open64(const char *path, int flags, ...)
__attribute__((visibility("default")));
int open64(const char *path, int flags, ...)
{
    va_list ap; va_start(ap, flags);
    int r = open_common(path, flags, ap);
    va_end(ap); return r;
}

/* ---------------- ioctl wrapper ------------------- */
typedef int (*ioctl_f)(int, int, ...);
static ioctl_f real_ioctl;

__attribute__((visibility("default")))
int ioctl(int fd, int req, ...)
{
    if (!real_ioctl)
        real_ioctl = (ioctl_f)dlsym(RTLD_NEXT, "ioctl");

    /* reject any ioctl on /dev/input/event* ----------------------- */
    char linkbuf[64], path[64];
    snprintf(linkbuf, sizeof linkbuf, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(linkbuf, path, sizeof path - 1);
    if (n > 0) { path[n] = 0;
        if (is_event_node(path)) { errno = ENOTTY; return -1; }
    }

    va_list ap; va_start(ap, req);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    return real_ioctl(fd, req, arg);
}


/* ---------- read() hook (optional) ---------------------------------- */
typedef ssize_t (*read_f)(int, void *, size_t);
static read_f real_read;

ssize_t read(int fd, void *buf, size_t count)
__attribute__((visibility("default")));
ssize_t read(int fd, void *buf, size_t count)
{
    if (!real_read) real_read = (read_f)dlsym(RTLD_NEXT, "read");

    char linkbuf[64], path[64];
    snprintf(linkbuf, sizeof(linkbuf), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(linkbuf, path, sizeof(path) - 1);
    if (n > 0) { path[n] = 0; if (is_event_node(path)) { errno = EAGAIN; return -1; } }

    return real_read(fd, buf, count);
}
/* --------------------------------------------------------------------- */