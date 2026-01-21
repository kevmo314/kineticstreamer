/*
 * Copyright (c) 2020 Mathis Engelbart All rights reserved.
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */

#ifndef SCREAM_TX_H
#define SCREAM_TX_H

#include "RtpQueueC.h"

#ifdef __cplusplus
#include "include/ScreamTx.h"
extern "C" {
#else
typedef struct ScreamV2Tx ScreamV2Tx;
#endif

#include <stdbool.h>
#include <stdint.h>

ScreamV2Tx* ScreamTxInit();
void ScreamTxFree(ScreamV2Tx*);

void ScreamTxRegisterNewStream(ScreamV2Tx*,
                               RtpQueueC*,
                               uint32_t,
                               float,
                               float,
                               float,
                               float);
void ScreamTxNewMediaFrame(ScreamV2Tx*, uint32_t, uint32_t, int, bool);
float ScreamTxIsOkToTransmit(ScreamV2Tx*, uint32_t, uint32_t);
float ScreamTxAddTransmitted(ScreamV2Tx*,
                             uint32_t,
                             uint32_t,
                             int,
                             uint16_t,
                             bool);
void ScreamTxIncomingStdFeedbackBuf(ScreamV2Tx*,
                                    uint32_t,
                                    unsigned char*,
                                    int size);
void ScreamTxIncomingStdFeedback(ScreamV2Tx*,
                                 uint32_t,
                                 int,
                                 uint32_t,
                                 uint16_t,
                                 uint8_t,
                                 bool);
float ScreamTxGetTargetBitrate(ScreamV2Tx*, uint32_t, uint32_t);

void ScreamTxGetStatistics(ScreamV2Tx*, float, char*);

#ifdef __cplusplus
}
#endif

#endif
