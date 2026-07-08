package com.ai.assistance.aiterminal.terminal.mascot

enum class AuraForm { IDLE, THINKING, TYPING, EXECUTING, BERSERK, SUCCESS, ERROR, SLEEPING, EVOLVING, COLLABORATING, LOADING, CELEBRATING, CURIOUS, SHIELDING, REMEMBERING, ANALYZING, LEARNING, NETWORKING, ROOT, PLANNING, COMPILING, CONNECTING, TOOLING, SKILLING, MCPING }
enum class AuraAccent { CYAN, AMBER, GREEN, RED, BLUE, VIOLET, WHITE }
object AuraMascot {
    fun getEmoji(form: AuraForm): String = "🪼"
    fun getIntervalMs(form: AuraForm): Long = 500L
    fun getAccent(form: AuraForm): AuraAccent = AuraAccent.CYAN
    fun getDrawableName(form: AuraForm): String = "aura_" + form.name.lowercase()
    fun getAnimationDrawableName(form: AuraForm): String = "aura_anim_" + form.name.lowercase()
}
object AuraFormConfig { fun fromName(name: String): AuraForm = AuraForm.IDLE }
