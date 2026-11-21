package com.flashskip.experimental;

interface ISkipHost {
    oneway void onReady(boolean connected);
    boolean beginAction(String packageName, int windowId);
    oneway void endAction();
    oneway void onResult(String outcome, long durationMs);
    oneway void onInput(long sessionId, long attemptId, boolean accepted, String reason);
}
