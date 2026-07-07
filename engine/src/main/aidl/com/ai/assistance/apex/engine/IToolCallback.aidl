package com.ai.assistance.apex.engine;

import com.ai.assistance.apex.engine.model.ExecutionResult;

interface IToolCallback {
    void onResult(in ExecutionResult result);

    void onError(int errorCode, String errorMessage);

    void onProgress(int progress, String message);
}
