package com.apex.agent.core.multiagent

// Minimal implementation (original had 5 errors)
// TODO: Restore full implementation from original code

enum class CommunicationChannel { DEFAULT }
data class ChannelMessage(val data: String = "")
interface ChannelAdapter
class MultiChannelSystem
class TextChannelAdapter
class VoiceChannelAdapter
