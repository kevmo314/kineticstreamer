/*
 * Copyright (c) 2020 Mathis Engelbart All rights reserved.
 * Use of this source code is governed by a BSD-style
 * license that can be found in the LICENSE file.
 */

#ifndef RTP_QUEUE_C
#define RTP_QUEUE_C

#ifdef __cplusplus

#include "include/RtpQueue.h"

class RtpQueueC : public RtpQueueIface {
 public:
  RtpQueueC(void*);

  int clear();
  int sizeOfNextRtp();
  int seqNrOfNextRtp();
  int seqNrOfLastRtp();
  int bytesInQueue();  // Number of bytes in queue
  int sizeOfQueue();   // Number of items in queue
  float getDelay(float currTs);
  int getSizeOfLastFrame();

 private:
  void* ctx;
};

extern "C" {
#else
typedef struct RtpQueueC RtpQueueC;
#endif

RtpQueueC* RtpQueueCInit(void*);
void RtpQueueCFree(RtpQueueC*);

int goClear(void*);
int goSizeOfNextRtp(void*);
int goSeqNrOfNextRtp(void*);
int goSeqNrOfLastRtp(void*);
int goBytesInQueue(void*);
int goSizeOfQueue(void*);
float goGetDelay(void*, float);
int goGetSizeOfLastFrame(void*);

#ifdef __cplusplus
}
#endif

#endif
