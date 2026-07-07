package com.ai.assistance.apex.engine;

interface IContainerCallback {
    void onOutput(String output);

    void onStatusChanged(int status);

    void onError(String error);
}
