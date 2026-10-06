/* Host smoke test for the game sources: boots the real engine against a real
 * IWAD, dumps frames as PPM and exits. Not part of the APK build — it exists
 * because the watch cannot be tested from CI. It links the same engine sources
 * and uses the same argv (doom_args.h) as the watch build, so a clean run here
 * means the shipped configuration loads the IWAD and renders.
 *
 *   make -C doom/jni host-smoke WAD=../assets/freedoom1.wad
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/time.h>

#include "doom_args.h"
#include "doomgeneric.h"

static int frames = 0;
static unsigned long start;

static unsigned long nowMs(void) {
    struct timeval t;
    gettimeofday(&t, NULL);
    return (unsigned long)t.tv_sec * 1000 + t.tv_usec / 1000;
}

void DG_Init(void) { start = nowMs(); }

void DG_SleepMs(uint32_t ms) { usleep(ms * 1000); }

uint32_t DG_GetTicksMs(void) { return (uint32_t)(nowMs() - start); }

int DG_GetKey(int *pressed, unsigned char *key) {
    (void)pressed;
    (void)key;
    return 0;
}

void DG_SetWindowTitle(const char *title) { (void)title; }

static void dump(int n) {
    char path[64];
    FILE *f;
    int i;
    snprintf(path, sizeof(path), "/tmp/doomhost-%d.ppm", n);
    f = fopen(path, "wb");
    if (f == NULL) {
        printf("FAIL: cannot write %s\n", path);
        exit(1);
    }
    fprintf(f, "P6\n%d %d\n255\n", DOOMGENERIC_RESX, DOOMGENERIC_RESY);
    for (i = 0; i < DOOMGENERIC_RESX * DOOMGENERIC_RESY; i++) {
        uint32_t p = DG_ScreenBuffer[i];
        fputc((p >> 16) & 0xff, f);
        fputc((p >> 8) & 0xff, f);
        fputc(p & 0xff, f);
    }
    fclose(f);
    printf("frame %d -> %s\n", n, path);
}

void DG_DrawFrame(void) {
    if (frames % 250 == 0) {
        dump(frames);
    }
    if (++frames >= 1000) {
        printf("OK: %d frames rendered, no crash\n", frames);
        exit(0);
    }
}

int main(int argc, char **argv) {
    char *doomArgv[DOOM_ARGV_MAX];
    int doomArgc;
    if (argc < 2) {
        fprintf(stderr, "usage: %s <iwad>\n", argv[0]);
        return 2;
    }
    doomArgc = doom_watch_args(argv[1], doomArgv);
    doomgeneric_Create(doomArgc, doomArgv);
    for (;;) {
        doomgeneric_Tick();
    }
}
