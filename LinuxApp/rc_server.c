/*
 * RC UDP server for BeagleBone Blue
 * Receives 2-byte packets: [throttle: int8][-128..127] [steering: int8][-128..127]
 * Port 9000, 500 Hz nominal rate
 *
 * ┌─ Build ──────────────────────────────────────────────────────────────────┐
 * │ 開発マシン:                                                               │
 * │   gcc -O2 -Wall -o rc_server rc_server.c -lpthread                       │
 * │                                                                           │
 * │ BeagleBone Blue 実機 (LED + Motor 有効):                                 │
 * │   gcc -O2 -Wall -DROBOTCONTROL -o rc_server rc_server.c \                │
 * │       -lrobotcontrol -lpthread                                            │
 * └───────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ Heartbeat & Watchdog ────────────────────────────────────────────────────┐
 * │ Heartbeat:  1 Hz で Android へ 0x01 を返送 → Android が生死を判定         │
 * │ Watchdog:   3 秒間パケット未受信 → モーター停止 + LED 消灯                │
 * │             (通信断でもロボットが暴走しないための安全機構)                  │
 * └───────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ LED 輝度制御 ─────────────────────────────────────────────────────────────┐
 * │ 差動2輪モデル: left = throttle+steering, right = throttle-steering         │
 * │ GREEN duty ∝ 前進成分の最大値 / RED duty ∝ 後退成分の最大値               │
 * │ ソフトウェア PWM: 50 Hz / 20 ステップ (5% 刻み輝度, 1 ms/step)            │
 * └───────────────────────────────────────────────────────────────────────────┘
 */

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#ifdef ROBOTCONTROL
#include <rc/led.h>
#include <rc/motor.h>
#include <rc/servo.h>
static void led_green(int on) { rc_led_set(RC_LED_GREEN, on); }
static void led_red  (int on) { rc_led_set(RC_LED_RED,   on); }
#else
static void led_green(int on) { (void)on; }
static void led_red  (int on) { (void)on; }
#endif

#define RC_PORT          9000
#define DISPLAY_HZ       20
#define BAR_HALF         12
#define WATCHDOG_SEC     3     /* この秒数パケット未受信でモーター停止 */
#define HEARTBEAT_HZ     1     /* Android へ送り返す heartbeat レート */

static volatile int running = 1;
static void sig_handler(int sig) { (void)sig; running = 0; }

/* ── クライアント管理 ────────────────────────────────────────────────────── */

static struct sockaddr_in g_client_addr;
static volatile int       g_client_known = 0;
static volatile time_t    g_last_recv_sec = 0;  /* 最終パケット受信時刻 */

/* ── ソフトウェア PWM ────────────────────────────────────────────────────── */

#define PWM_STEPS   20
#define PWM_STEP_NS 1000000L

static volatile float g_fwd = 0.0f;
static volatile float g_rev = 0.0f;

static void* led_pwm_thread(void *arg) {
    (void)arg;
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    int step = 0;

    while (running) {
        int f = (int)(g_fwd * PWM_STEPS + 0.5f);
        int r = (int)(g_rev * PWM_STEPS + 0.5f);
        if (f > PWM_STEPS) f = PWM_STEPS;
        if (r > PWM_STEPS) r = PWM_STEPS;

        led_green(step < f ? 1 : 0);
        led_red  (step < r ? 1 : 0);
        step = (step + 1) % PWM_STEPS;

        t.tv_nsec += PWM_STEP_NS;
        if (t.tv_nsec >= 1000000000L) { t.tv_nsec -= 1000000000L; t.tv_sec++; }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &t, NULL);
    }
    led_green(0);
    led_red(0);
    return NULL;
}

static void update_leds(int8_t throttle, int8_t steering) {
    int l = (int)throttle + (int)steering;
    int r = (int)throttle - (int)steering;
    if (l >  127) l =  127; else if (l < -127) l = -127;
    if (r >  127) r =  127; else if (r < -127) r = -127;

    int fwd = (l > 0 ? l : 0); if (r > fwd) fwd = r;
    int rev = (l < 0 ? -l : 0); if (r < 0 && -r > rev) rev = -r;

    g_fwd = fwd / 127.0f;
    g_rev = rev / 127.0f;
}

/* ── Heartbeat & Watchdog スレッド ──────────────────────────────────────── */

/*
 * 1 Hz で Android へ 0x01 (heartbeat) を返送する。
 * 同時に watchdog を監視し、3 秒間パケット未受信であれば
 * モーターと LED を安全停止させる。
 */
static void* heartbeat_thread(void *arg) {
    int sock = *(int *)arg;
    const uint8_t HB = 0x01;
    const long PERIOD_NS = 1000000000L / HEARTBEAT_HZ;

    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);

    while (running) {
        t.tv_nsec += PERIOD_NS;
        if (t.tv_nsec >= 1000000000L) { t.tv_nsec -= 1000000000L; t.tv_sec++; }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &t, NULL);

        if (!g_client_known) continue;

        /* Watchdog: 通信断でモーター暴走を防ぐ */
        if (time(NULL) - g_last_recv_sec > WATCHDOG_SEC) {
            g_fwd = 0.0f;
            g_rev = 0.0f;
            /* --- MOTOR 安全停止 (ROBOTCONTROL 有効時) ---
             * rc_motor_set(1, 0.0);
             * rc_motor_set(2, 0.0);
             * ------------------------------------------- */
        }

        /* Android へ heartbeat 返送 */
        sendto(sock, &HB, 1, 0,
               (struct sockaddr *)&g_client_addr, sizeof(g_client_addr));
    }
    return NULL;
}

/* ── 端末ビジュアライザ ──────────────────────────────────────────────────── */

static void draw_bar(int8_t val) {
    int pos = (int)(val * BAR_HALF / 127);
    if (pos < -BAR_HALF) pos = -BAR_HALF;
    if (pos >  BAR_HALF) pos =  BAR_HALF;

    putchar('[');
    for (int i = -BAR_HALF; i <= BAR_HALF; i++) {
        if (i == 0)                             putchar('|');
        else if (pos > 0 && i > 0 && i <= pos) putchar('#');
        else if (pos < 0 && i < 0 && i >= pos) putchar('#');
        else                                    putchar(' ');
    }
    putchar(']');
}

static void render(int8_t thr, int8_t str, long pkts, double uptime) {
    static int first = 1;
    if (!first) printf("\033[4A");   /* 4行上書き (STATUS 行が増えた分) */
    first = 0;

    printf("\033[2K\rTHROTTLE %+4d  ", thr);
    draw_bar(thr);
    printf("  %-3s\n", thr > 0 ? "FWD" : thr < 0 ? "REV" : "---");

    printf("\033[2K\rSTEERING %+4d  ", str);
    draw_bar(str);
    printf("  %-3s\n", str > 0 ? "RGT" : str < 0 ? "LFT" : "---");

    printf("\033[2K\rpkts: %-8ld  uptime: %6.1f s\n", pkts, uptime);

    /* STATUS 行: watchdog 発動中かどうかを表示 */
    int watchdog = g_client_known &&
                   (time(NULL) - g_last_recv_sec > WATCHDOG_SEC);
    if (watchdog)
        printf("\033[2K\r\033[31mSTATUS: *** WATCHDOG — 通信断, モーター停止 ***\033[0m\n");
    else if (g_client_known)
        printf("\033[2K\r\033[32mSTATUS: ONLINE  (last pkt %lus ago)\033[0m\n",
               (unsigned long)(time(NULL) - g_last_recv_sec));
    else
        printf("\033[2K\rSTATUS: waiting for Android...\n");

    fflush(stdout);
}

/* ── main ────────────────────────────────────────────────────────────────── */

int main(void) {
    signal(SIGINT,  sig_handler);
    signal(SIGTERM, sig_handler);

    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) { perror("socket"); return 1; }

    int yes = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    /* recvfrom を 50ms でタイムアウトさせ、通信断でも render/watchdog 表示が更新されるようにする */
    struct timeval tv = { .tv_sec = 0, .tv_usec = 50000 };
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(RC_PORT);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind"); close(sock); return 1;
    }

    /* スレッド起動 */
    pthread_t led_tid, hb_tid;
    if (pthread_create(&led_tid, NULL, led_pwm_thread, NULL) != 0) {
        perror("pthread_create led"); close(sock); return 1;
    }
    if (pthread_create(&hb_tid, NULL, heartbeat_thread, &sock) != 0) {
        perror("pthread_create hb"); running = 0;
        pthread_join(led_tid, NULL); close(sock); return 1;
    }

    printf("[rc_server] UDP port %d ready  (watchdog %ds, heartbeat %dHz)\n",
           RC_PORT, WATCHDOG_SEC, HEARTBEAT_HZ);
    fflush(stdout);

    uint8_t buf[2];
    struct sockaddr_in client;
    socklen_t clen = sizeof(client);

    int8_t thr = 0, str = 0;
    long   pkts = 0;
    const long RENDER_NS = 1000000000L / DISPLAY_HZ;

    struct timespec t_start, t_now, t_last_render;
    clock_gettime(CLOCK_MONOTONIC, &t_start);
    t_last_render = t_start;

    while (running) {
        ssize_t n = recvfrom(sock, buf, sizeof(buf), 0,
                             (struct sockaddr *)&client, &clen);
        if (n != 2) {
            /* SO_RCVTIMEO タイムアウト → パケットなしでも render を通す */
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) goto do_render;
            continue;
        }

        thr = (int8_t)buf[0];
        str = (int8_t)buf[1];
        pkts++;

        /* クライアント情報と受信時刻を更新 (watchdog / heartbeat 宛先) */
        g_client_addr   = client;
        g_client_known  = 1;
        g_last_recv_sec = time(NULL);

        update_leds(thr, str);

        /* --- MOTOR (ROBOTCONTROL 有効時) -----------------------------------
         * int l = (int)thr + (int)str; if(l> 127)l= 127; if(l<-127)l=-127;
         * int r = (int)thr - (int)str; if(r> 127)r= 127; if(r<-127)r=-127;
         * rc_motor_set(1, l / 127.0);
         * rc_motor_set(2, r / 127.0);
         * ------------------------------------------------------------------ */

        do_render:
        clock_gettime(CLOCK_MONOTONIC, &t_now);
        long elapsed = (t_now.tv_sec  - t_last_render.tv_sec)  * 1000000000L
                     + (t_now.tv_nsec - t_last_render.tv_nsec);
        if (elapsed >= RENDER_NS) {
            double uptime = (double)(t_now.tv_sec  - t_start.tv_sec)
                          + (t_now.tv_nsec - t_start.tv_nsec) * 1e-9;
            render(thr, str, pkts, uptime);
            t_last_render = t_now;
        }
    }

    close(sock);
    pthread_join(hb_tid,  NULL);
    pthread_join(led_tid, NULL);
    puts("\n[rc_server] stopped");
    return 0;
}
