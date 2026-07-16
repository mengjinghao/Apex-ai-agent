package com.apex.plugins.skill

// Minimal implementation (original had 5 errors)
// TODO: Restore full implementation from original code

interface SkillPlugin
enum class SkillPluginCategory { DEFAULT }
interface SkillPluginContext
interface SkillPluginDescriptor
interface SkillPluginManager
interface SkillPluginMarketplace
interface SkillPluginListing
interface SkillPluginUpdate
data class PluginValidationResult(val data: String = "")
interface PluginEventListener
class SkillPluginAdapter
object SkillPluginConstants {
    fun init() { }
}
