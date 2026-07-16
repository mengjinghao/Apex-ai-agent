package com.apex.agent.core.normal.greeting

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

enum class GreetingType { DEFAULT }
enum class TimeOfDay { DEFAULT }
data class Greeting(val data: String = "")
data class DailyQuote(val data: String = "")
enum class QuoteCategory { DEFAULT }
class DailyGreetingSystem
