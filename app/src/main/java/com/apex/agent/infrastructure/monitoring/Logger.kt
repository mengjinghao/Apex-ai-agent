package com.apex.agent.infrastructure.monitoring

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

interface Logger {

    fun v(tag: String, message: String)
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable)

    @Singleton
    class Default @Inject constructor() : Logger {

        override fun v(tag: String, message: String) = Log.v(tag, message)
        override fun d(tag: String, message: String) = Log.d(tag, message)
        override fun i(tag: String, message: String) = Log.i(tag, message)
        override fun w(tag: String, message: String) = Log.w(tag, message)
        override fun e(tag: String, message: String) = Log.e(tag, message)
        override fun e(tag: String, message: String, throwable: Throwable) = Log.e(tag, message, throwable)
    }
}
