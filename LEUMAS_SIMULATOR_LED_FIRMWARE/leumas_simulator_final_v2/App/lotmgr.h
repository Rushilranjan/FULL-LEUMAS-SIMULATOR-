/**
 * lotmgr.h — the production engine (implementation detail, invisible to the
 * audience). Runs lots through a recipe of stages using the per-module actors.
 *
 * A "lot" flows: {Macro, Gravimetric} -> Blender -> Packaging. Each module is
 * freed the instant it hands material downstream, so lots can overlap — that is
 * what lets Seq 2 raise throughput just by injecting more lots. Seq 1 injects 1.
 */
#ifndef APP_LOTMGR_H
#define APP_LOTMGR_H

#include <stdint.h>
#include <stdbool.h>

/* Start a sequence: activates the right modules and queues its lots. */
void lotmgr_start(uint8_t seq);

/* Advance every active lot. Call every scheduler tick. */
void lotmgr_tick(void);

/* True while any lot is still in progress (or queued). */
bool lotmgr_running(void);

#endif /* APP_LOTMGR_H */
