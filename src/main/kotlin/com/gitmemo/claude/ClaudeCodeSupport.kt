package com.gitmemo.claude

import com.gitmemo.GitMemoBundle
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsNotifier

/**
 * Entry point for handing a note to Claude Code.
 *
 * Deliberately free of any `org.jetbrains.plugins.terminal` import: the terminal plugin is only an
 * optional dependency, so its classes are absent from our classloader when a user disables it. All
 * terminal-touching code lives in [ClaudeCodeBridge], which is reached only from behind the
 * [isAvailable] guard and therefore never loaded when the terminal is gone.
 */
internal object ClaudeCodeSupport {
  private val LOG = logger<ClaudeCodeSupport>()

  private val TERMINAL_PLUGIN_ID = PluginId.getId("org.jetbrains.plugins.terminal")

  /**
   * Whether the button should be offered at all.
   *
   * Only the terminal plugin is required. The Claude Code plugin deliberately is not: what we need is
   * a `claude` process in a terminal, which the bridge can also start on its own, and requiring the
   * plugin would additionally hide the button in a `runIde` sandbox that does not have it installed.
   */
  fun isAvailable(): Boolean =
    PluginManagerCore.isPluginInstalled(TERMINAL_PLUGIN_ID) && !PluginManagerCore.isDisabled(TERMINAL_PLUGIN_ID)

  /**
   * Types the note into Claude Code's prompt without submitting it, so the user can add their own
   * instructions before pressing Enter. Starts a session if none is running, in which case the note
   * arrives once the CLI has finished booting.
   */
  fun sendNote(project: Project, shortHash: String, notesRef: String, body: String) {
    if (body.isBlank() || !isAvailable()) return
    try {
      ClaudeCodeBridge.sendNote(project, GitMemoBundle.message("claude.payload", shortHash, notesRef, body))
    }
    catch (e: Throwable) {
      // This integration reads the Claude Code plugin's internals (see ClaudeCodeBridge), so a future
      // version of it can break us. Degrade to a notification rather than an exception dialog.
      LOG.warn("Cannot send the note of $shortHash to Claude Code", e)
      notifyProblem(project, "gitmemo.claude.send.failed", e.message.orEmpty())
    }
  }

  /** Reports a failure that the user has to act on, without stealing focus from what they are doing. */
  fun notifyProblem(project: Project, displayId: String, detail: String) {
    VcsNotifier.getInstance(project).notifyMinorWarning(
      displayId,
      GitMemoBundle.message("notification.claudeSendFailed"),
      detail,
    )
  }
}
