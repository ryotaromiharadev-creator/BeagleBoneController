/*
 * RC UDP server for BeagleBone Blue
 * Receives 2-byte packets: [throttle: int8][-128..127] [steering: int8][-128..127]
 * Port 9000, 500 Hz nominal rate
 *
 * ┌─ Build ──────────────────────────────────────────────────────────────────┐
 * │ 開発マシン (LED/Motor なし):                                              │
 * │   gcc -O2 -Wall -o rc_server rc_server.c -lpthread                       │
 * │                                                                           │
 * │ BeagleBone Blue 実機 (LED + Motor 有効):                                 │
 * │   gcc -O2 -Wall -DROBOTCONTROL -o rc_server rc_server.c \                │
 * │       -lrobotcontrol -lpthread                                            │
 * └───────────────────────────────────────────────────────────────────────────┘
 *
 * ┌─ LED 輝度制御の仕様 ──────────────────────────────────────────────────────┐
 * │ 軸ごとに 1 LED を割り当て:                                                │
 * │                                                                           │
 * │ RC_LED_GREEN: 横スティック (steering)  duty ∝ |steering|                 │
 * │ RC_LED_RED:   縦スティック (throttle)  duty ∝ |throttle|                 │
 * │                                                                           │
 * │ ソフトウェア PWM: 50 Hz / 20 ステップ (5% 刻み輝度, 1 ms/step)            │
 * └───────────────────────────────────────────────────────────────────────────┘
 */

#include <arpa/inet.h>
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
/* 開発マシン用スタブ — librobotcontrol なしでコンパイルできるようにする */
static void led_green(int on) { (void)on; }
static void led_red  (int on) { (void)on; }
#endif

#define RC_PORT     9000
#define DISPLAY_HZ  20           /* 端末再描画レート */
#define BAR_HALF    12           /* バー片側文字数 */

/* ── ソフトウェア PWM ────────────────────────────────────────────────────── */

#define PWM_STEPS   20           /* 1周期あたりのステップ数 (= 輝度解像度) */
#define PWM_STEP_NS 1000000L     /* 1 ms/step → 周期 20 ms = 50 Hz */

/*
 * メインループから書き込み、LED スレッドから読み出す。
 * ARM では 32bit float の単純な読み書きはアトミックなので volatile で十分。
 */
static volatile float g_fwd = 0.0f;   /* 0.0–1.0: 前進成分 → GREEN duty */
static volatile float g_rev = 0.0f;   /* 0.0–1.0: 後退成分 → RED   duty */

static volatile int running = 1;

static void sig_handler(int sig) { (void)sig; running = 0; }

/*
 * LED ソフトウェア PWM スレッド
 * 1 ms ごとに step カウンタを進め、duty 比以下なら LED ON、超えたら OFF にする。
 * GREEN と RED は独立して制御するため同時点灯が可能。
 */
static void *led_pwm_thread(void *arg) {
    (void)arg;
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    int step = 0;

    while (running) {
        /* duty をステップ数に変換 (0–20) */
        int f = (int)(g_fwd * PWM_STEPS + 0.5f);
        int r = (int)(g_rev * PWM_STEPS + 0.5f);
        if (f > PWM_STEPS) f = PWM_STEPS;
        if (r > PWM_STEPS) r = PWM_STEPS;

        led_green(step < f ? 1 : 0);
        led_red  (step < r ? 1 : 0);

        step = (step + 1) % PWM_STEPS;

        /* 絶対時刻で 1 ms 待機 (ジッターを蓄積しない) */
        t.tv_nsec += PWM_STEP_NS;
        if (t.tv_nsec >= 1000000000L) {
            t.tv_nsec -= 1000000000L;
            t.tv_sec++;
        }
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &t, NULL);
    }

    led_green(0);
    led_red(0);
    return NULL;
}

/*
 * 軸ごとに 1 LED を割り当て:
 *   GREEN (g_fwd): 横スティック (steering) の絶対値 → 右でも左でも同じ LED
 *   RED   (g_rev): 縦スティック (throttle) の絶対値 → 前進でも後退でも同じ LED
 */
static void update_leds(int8_t throttle, int8_t steering) {
    g_fwd = (steering < 0 ? -steering : steering) / 127.0f;  /* GREEN: 横軸 */
    g_rev = (throttle < 0 ? -throttle : throttle) / 127.0f;  /* RED:   縦軸 */
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
    if (!first) printf("\033[3A");
    first = 0;

    printf("\033[2K\rTHROTTLE %+4d  ", thr);
    draw_bar(thr);
    printf("  %-3s\n", thr > 0 ? "FWD" : thr < 0 ? "REV" : "---");

    printf("\033[2K\rSTEERING %+4d  ", str);
    draw_bar(str);
    printf("  %-3s\n", str > 0 ? "RGT" : str < 0 ? "LFT" : "---");

    printf("\033[2K\rpkts: %-8ld  uptime: %6.1f s\n", pkts, uptime);
    fflush(stdout);
}

/* ── main ────────────────────────────────────────────────────────────────── */

int main(void) {
    /* SA_RESTART を付けないことで recvfrom が EINTR で返り、
     * running=0 チェックが確実に実行される */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sig_handler;
    sa.sa_flags = 0;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGINT,  &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);

    /* LED PWM スレッド起動 */
    pthread_t led_tid;
    if (pthread_create(&led_tid, NULL, led_pwm_thread, NULL) != 0) {
        perror("pthread_create");
        return 1;
    }

    int sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) { perror("socket"); running = 0; pthread_join(led_tid, NULL); return 1; }

    int yes = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(RC_PORT);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind");
        close(sock);
        running = 0;
        pthread_join(led_tid, NULL);
        return 1;
    }

    printf("[rc_server] UDP port %d ready\n", RC_PORT);
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
        if (n != 2) continue;

        thr = (int8_t)buf[0];
        str = (int8_t)buf[1];
        pkts++;

        /* LED 輝度を更新 (PWM スレッドが非同期に反映する) */
        update_leds(thr, str);

        /* --- MOTOR: librobotcontrol でモーター・サーボを駆動 ---------------
         * int l = (int)thr + (int)str; if(l> 127)l= 127; if(l<-127)l=-127;
         * int r = (int)thr - (int)str; if(r> 127)r= 127; if(r<-127)r=-127;
         * rc_motor_set(1, l / 127.0);   // 左モーター ch1
         * rc_motor_set(2, r / 127.0);   // 右モーター ch2
         * ------------------------------------------------------------------ */

        /* 端末表示を 20 Hz で更新 */
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
    pthread_join(led_tid, NULL);   /* LED スレッド終了・消灯を待つ */
    puts("\n[rc_server] stopped");
    return 0;
}
