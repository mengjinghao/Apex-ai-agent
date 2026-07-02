package com.apex.agent.core.tools.skill

import com.apex.agent.core.tools.PackagePermission
import java.io.File

data class SkillPackage(
    val name: String,
    val description: String,
    val directory: File,
    val skillFile: File,
    val version: String = "1.0.0",
    val author: String = "",
    val dependencies: List<String> = emptyList(),
    val permissions: List<PackagePermission> = emptyList()
)
