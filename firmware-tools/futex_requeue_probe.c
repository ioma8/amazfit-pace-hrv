#define _GNU_SOURCE
#include <errno.h>
#include <linux/futex.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <unistd.h>

int main(void) {
    volatile uint32_t word = 0;
    long r = syscall(SYS_futex, &word, FUTEX_CMP_REQUEUE_PI, 0, 0, &word, 0);
    printf("futex_requeue_same_address return=%ld errno=%d\n", r, errno);
    if (r < 0 && errno == EINVAL) {
        puts("RESULT: patched/requeue validation present");
        return 0;
    }
    puts("RESULT: inconclusive; not an exploit");
    return 1;
}
