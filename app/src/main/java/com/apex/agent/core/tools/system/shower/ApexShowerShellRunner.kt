package com.apex.core.tools.system.shower

import com.apex.core.tools.system.AndroidShellExecutor
import com.apex.core.tools.system.ShellIdentity as AppShellIdentity
import com.ai.assistance.showerclient.ShellCommandResult
import com.ai.assistance.showerclient.ShellIdentity
import com.ai.assistance.showerclient.ShellRunner

/**
 * Bridge implementation of [ShellRunner] that delegates to the app's
 * [AndroidShellExecutor], mapping identities between the library and app.
 */
object ApexShowerShellRunner : ShellRunner {

    override suspend fun run(command: String, identity: ShellIdentity): ShellCommandResult {
        val appIdentity = when (identity) {
            ShellIdentity.DEFAULT -> AppShellIdentity.DEFAULT
            ShellIdentity.SHELL -> AppShellIdentity.SHELL
            ShellIdentity.ROOT -> AppShellIdentity.ROOT
        }

        val result = AndroidShellExecutor.executeShellCommand(command, appIdentity)
        return ShellCommandResult(
            success = result.success,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
        )
    }
}
