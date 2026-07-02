package com.ai.assistance.apex.engine.model;

parcelable ExecutionResult {
    int exitCode;
    String output;
    String error;
    long executionTime;
    boolean success;
}
