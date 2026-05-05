/*
 * C benchmark receiver
 * Receives 6-byte packets from bench_send.py and reports:
 *   - Per-packet parse latency (clock_gettime overhead included)
 *   - Inter-packet jitter relative to 2 ms (500 Hz)
 *   - CPU time via getrusage
 *
 * Build: gcc -O2 -Wall -o bench_recv bench_recv.c -lm
 * Run  : ./bench_recv [packet_count]
 */
#include <arpa/inet.h>
#include <math.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define BENCH_PORT    9001
#define EXPECTED_NS   2000000L   /* 2 ms = 500 Hz */
#define TIMEOUT_SEC   15

static inline long ts_diff_ns(const struct timespec *a, const struct timespec *b) {
    return (a->tv_sec - b->tv_sec) * 1000000000L + (a->tv_nsec - b->tv_nsec);
}

static long labs_l(long x) { return x < 0 ? -x : x; }

int main(int argc, char *argv[]) {
    int total = (argc > 1) ? atoi(argv[1]) : 5000;

    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) { perror("socket"); return 1; }

    /* receive timeout so we don't hang if sender dies */
    struct timeval tv = { .tv_sec = TIMEOUT_SEC, .tv_usec = 0 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(BENCH_PORT);
    addr.sin_addr.s_addr = INADDR_ANY;
    if (bind(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind"); close(sock); return 1;
    }

    printf("[bench_recv.c] waiting for %d packets on port %d...\n", total, BENCH_PORT);
    fflush(stdout);

    long *proc_ns    = malloc(total * sizeof(long));
    long *jitter_ns  = malloc(total * sizeof(long));

    uint8_t buf[6];
    struct timespec ts_recv, ts_done, ts_prev = {0, 0};
    int received = 0;

    struct rusage ru_start, ru_end;
    getrusage(RUSAGE_SELF, &ru_start);

    while (received < total) {
        ssize_t n = recvfrom(sock, buf, sizeof(buf), 0, NULL, NULL);
        clock_gettime(CLOCK_MONOTONIC, &ts_recv);
        if (n != 6) continue;

        /* parse packet (same work as production rc_server.c) */
        uint16_t seq   = ((uint16_t)buf[0] << 8) | buf[1];
        uint32_t ts_us = ((uint32_t)buf[2] << 24) | ((uint32_t)buf[3] << 16)
                       | ((uint32_t)buf[4] << 8)  |  buf[5];
        (void)seq; (void)ts_us;

        clock_gettime(CLOCK_MONOTONIC, &ts_done);
        proc_ns[received] = ts_diff_ns(&ts_done, &ts_recv);

        if (received > 0)
            jitter_ns[received] = labs_l(ts_diff_ns(&ts_recv, &ts_prev) - EXPECTED_NS);

        ts_prev = ts_recv;
        received++;
    }

    getrusage(RUSAGE_SELF, &ru_end);
    close(sock);

    /* --- statistics --- */
    double sum_proc = 0, sum_jitter = 0, sq_jitter = 0;
    long   max_proc = 0, max_jitter = 0;

    for (int i = 0; i < received; i++) {
        if (proc_ns[i] > max_proc) max_proc = proc_ns[i];
        sum_proc += proc_ns[i];
    }
    for (int i = 1; i < received; i++) {
        if (jitter_ns[i] > max_jitter) max_jitter = jitter_ns[i];
        sum_jitter += jitter_ns[i];
        sq_jitter  += (double)jitter_ns[i] * jitter_ns[i];
    }

    int n_jitter = received - 1;
    double mean_proc   = sum_proc   / received;
    double mean_jitter = sum_jitter / n_jitter;
    double stddev      = sqrt(sq_jitter / n_jitter - mean_jitter * mean_jitter);

    /* CPU time consumed */
    long cpu_us = (ru_end.ru_utime.tv_sec  - ru_start.ru_utime.tv_sec)  * 1000000L
                + (ru_end.ru_utime.tv_usec - ru_start.ru_utime.tv_usec)
                + (ru_end.ru_stime.tv_sec  - ru_start.ru_stime.tv_sec)  * 1000000L
                + (ru_end.ru_stime.tv_usec - ru_start.ru_stime.tv_usec);

    printf("\n┌─────────────────────────────────────────┐\n");
    printf("│  C receiver — %d packets received        │\n", received);
    printf("├─────────────────────────────────────────┤\n");
    printf("│ Parse latency (per packet)              │\n");
    printf("│   mean : %7.0f ns                    │\n", mean_proc);
    printf("│   max  : %7ld ns                    │\n", max_proc);
    printf("├─────────────────────────────────────────┤\n");
    printf("│ Inter-packet jitter  |actual − 2 ms|   │\n");
    printf("│   mean : %7.1f µs                    │\n", mean_jitter / 1e3);
    printf("│   max  : %7.1f µs                    │\n", max_jitter  / 1e3);
    printf("│   σ    : %7.1f µs                    │\n", stddev      / 1e3);
    printf("├─────────────────────────────────────────┤\n");
    printf("│ CPU time (user+sys)  : %6.1f ms        │\n", cpu_us / 1e3);
    printf("│ CPU/packet           : %6.1f µs        │\n", (double)cpu_us / received);
    printf("└─────────────────────────────────────────┘\n");

    free(proc_ns);
    free(jitter_ns);
    return 0;
}
