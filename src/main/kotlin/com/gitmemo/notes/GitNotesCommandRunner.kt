package com.gitmemo.notes

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import git4idea.config.GitExecutableManager
import java.nio.charset.StandardCharsets

/** Outcome of a single `git notes` invocation. A non-zero [exitCode] is a value, not a failure. */
internal class GitNotesCommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
  val success: Boolean get() = exitCode == 0
}

/**
 * Runs `git notes` sub-commands.
 *
 * git4idea cannot be used here: [git4idea.commands.GitCommand] has no `NOTES` constant and its
 * constructors and `read`/`write` factories are all private, so a [git4idea.commands.GitLineHandler]
 * for `notes` cannot be constructed. Remote operations (fetch and push of the notes refs) do go
 * through git4idea — see `com.gitmemo.actions.GitNotesSyncAction`.
 *
 * Limitation: this builds the command line directly and therefore only supports a **local** git
 * executable. Non-local executables (WSL, Docker) need [git4idea.config.GitExecutable.patchCommandLine],
 * which requires a `GitHandler` instance we cannot create.
 */
internal object GitNotesCommandRunner {
  private val LOG = logger<GitNotesCommandRunner>()
  private const val TIMEOUT_MS = 30_000

  /**
   * Runs `git --no-pager notes <args>` in [root]. Must be called on a background thread.
   *
   * @throws VcsException if the process cannot be started or does not finish in time
   */
  @Throws(VcsException::class)
  fun run(project: Project, root: VirtualFile, vararg args: String): GitNotesCommandResult {
    val executableManager = GitExecutableManager.getInstance()
    val commandLine = GeneralCommandLine()
      .withExePath(executableManager.getPathToGit(project))
      .withWorkingDirectory(root.toNioPath())
      .withCharset(StandardCharsets.UTF_8)
      // Forces the C locale, so the stderr matching in GitNotesService is not language-dependent.
      .withEnvironment(executableManager.getExecutable(project).getLocaleEnv())
      // A repository with a credential-requiring notes ref must never block the IDE on a prompt.
      .withEnvironment("GIT_TERMINAL_PROMPT", "0")
    commandLine.addParameters("--no-pager", "notes")
    commandLine.addParameters(*args)

    LOG.debug { "Running: ${commandLine.commandLineString} in ${root.path}" }

    val output = try {
      CapturingProcessHandler(commandLine).runProcess(TIMEOUT_MS, true)
    }
    catch (e: ExecutionException) {
      throw VcsException("Cannot run git notes: ${e.message}", e)
    }

    if (output.isTimeout) {
      throw VcsException("git notes timed out after ${TIMEOUT_MS / 1000}s in ${root.presentableUrl}")
    }
    return GitNotesCommandResult(output.exitCode, output.stdout, output.stderr)
  }
}
