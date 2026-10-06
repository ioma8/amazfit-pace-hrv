/* Android/MIPS platform layer for doomgeneric (Amazfit Pace, 320x300).
 *
 * Doom renders 320x200; DG_DrawFrame copies that straight into the Java bitmap
 * at 1:1 and the Java view letterboxes it between two 50 px control bars.
 */
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "doom_args.h"
#include "doomgeneric.h"

#define TAG "doom"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define KEYQUEUE_SIZE 64

static unsigned short s_KeyQueue[KEYQUEUE_SIZE];
static unsigned int s_KeyWrite = 0;
static unsigned int s_KeyRead = 0;
static pthread_mutex_t s_KeyLock = PTHREAD_MUTEX_INITIALIZER;

/* Valid for the whole nativeStart call (the Doom thread never returns). */
static JNIEnv *s_env;
static jobject s_fb;
static jobject s_view;
static jmethodID s_postInvalidate;
static uint32_t s_origin_ms;

/* Doom frames per second, for the overlay. DG_DrawFrame runs once per rendered
 * frame, so this is the engine's rate, not the UI thread's. Volatile because the
 * Java side reads it from another thread. */
static volatile int s_fps;
static uint32_t s_fps_count;
static uint32_t s_fps_window_ms;
#define FPS_WINDOW_MS 500

static uint32_t nowMs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)((uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

void DG_Init(void) {
    memset(s_KeyQueue, 0, sizeof(s_KeyQueue));
    s_origin_ms = nowMs();
    s_fps_window_ms = s_origin_ms;
}

void DG_DrawFrame(void) {
    AndroidBitmapInfo info;
    void *pixels;

    {
        /* Divide by the real elapsed time, so the reading is exact rather than
         * a multiple of the window. */
        uint32_t now = nowMs();
        uint32_t elapsed = now - s_fps_window_ms;
        s_fps_count++;
        if (elapsed >= FPS_WINDOW_MS) {
            s_fps = (int)(s_fps_count * 1000u / elapsed);
            s_fps_count = 0;
            s_fps_window_ms = now;
        }
    }

    if (AndroidBitmap_getInfo(s_env, s_fb, &info) == ANDROID_BITMAP_RESULT_SUCCESS
            && AndroidBitmap_lockPixels(s_env, s_fb, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
        const uint32_t *src = (const uint32_t *)DG_ScreenBuffer;
        int y;
        for (y = 0; y < DOOMGENERIC_RESY; y++) {
            uint32_t *row = (uint32_t *)((uint8_t *)pixels + (size_t)y * info.stride);
            int n = DOOMGENERIC_RESX;
            while (n--) {
                *row++ = *src++ | 0xFF000000u; /* doomes writes 0x00RRGGBB; force opaque */
            }
        }
        AndroidBitmap_unlockPixels(s_env, s_fb);
    }
    if (s_view) {
        (*s_env)->CallVoidMethod(s_env, s_view, s_postInvalidate);
    }
}

void DG_SleepMs(uint32_t ms) {
    usleep(ms * 1000);
}

uint32_t DG_GetTicksMs(void) {
    return nowMs() - s_origin_ms;
}

int DG_GetKey(int *pressed, unsigned char *doomKey) {
    int got = 0;
    pthread_mutex_lock(&s_KeyLock);
    if (s_KeyRead != s_KeyWrite) {
        unsigned short d = s_KeyQueue[s_KeyRead % KEYQUEUE_SIZE];
        s_KeyRead++;
        *pressed = d >> 8;
        *doomKey = d & 0xff;
        got = 1;
    }
    pthread_mutex_unlock(&s_KeyLock);
    return got;
}

void DG_SetWindowTitle(const char *title) {
    (void)title;
}

JNIEXPORT jint JNICALL
Java_com_doom_DoomView_nativeFps(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return s_fps;
}

JNIEXPORT void JNICALL
Java_com_doom_DoomView_nativeKey(JNIEnv *env, jclass cls, jint pressed, jint key) {
    (void)env;
    (void)cls;
    pthread_mutex_lock(&s_KeyLock);
    unsigned int next = (s_KeyWrite + 1) % KEYQUEUE_SIZE;
    if (next != s_KeyRead) { /* drop when full rather than block the UI thread */
        s_KeyQueue[s_KeyWrite] = (unsigned short)(((pressed & 1) << 8) | (key & 0xff));
        s_KeyWrite = next;
    }
    pthread_mutex_unlock(&s_KeyLock);
}

JNIEXPORT void JNICALL
Java_com_doom_DoomView_nativeStart(JNIEnv *env, jclass cls, jstring wadPath,
                                   jobject bitmap, jobject view) {
    (void)cls;
    const char *wad = (*env)->GetStringUTFChars(env, wadPath, NULL);

    s_env = env;
    s_fb = (*env)->NewGlobalRef(env, bitmap);
    s_view = (*env)->NewGlobalRef(env, view);
    jclass viewClass = (*env)->GetObjectClass(env, view);
    s_postInvalidate = (*env)->GetMethodID(env, viewClass, "postInvalidate", "()V");

    /* Doom writes default.cfg and savegames into the cwd; the app files dir
       (the IWAD's directory) is the only writable place we have. */
    {
        const char *slash = strrchr(wad, '/');
        if (slash != NULL) {
            char dir[512];
            size_t n = (size_t)(slash - wad);
            if (n < sizeof(dir)) {
                memcpy(dir, wad, n);
                dir[n] = '\0';
                if (chdir(dir) != 0) {
                    LOGI("chdir(%s) failed", dir);
                }
            }
        }
    }

    LOGI("doomgeneric starting, iwad=%s", wad);
    /* `wad` stays alive: myargv keeps the pointer for the whole session. */
    char *argv[DOOM_ARGV_MAX];
    int argc = doom_watch_args((char *)wad, argv);
    doomgeneric_Create(argc, argv);

    for (;;) {
        doomgeneric_Tick();
    }
}
