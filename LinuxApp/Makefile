CC      = gcc
CFLAGS  = -O2 -Wall -Wextra
LDFLAGS = -lm

BINS = rc_server bench_recv

.PHONY: all bbl clean

# 開発マシン用 (librobotcontrol 不要)
all: $(BINS)

rc_server: rc_server.c
	$(CC) $(CFLAGS) -o $@ $< -lpthread

bench_recv: bench_recv.c
	$(CC) $(CFLAGS) -o $@ $< $(LDFLAGS)

# BeagleBone Blue 実機用 (LED + Motor 有効)
bbl: rc_server.c
	$(CC) $(CFLAGS) -DROBOTCONTROL -o rc_server $< -lrobotcontrol -lpthread

clean:
	rm -f $(BINS)
