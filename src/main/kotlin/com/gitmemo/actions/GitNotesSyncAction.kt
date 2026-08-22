package com.gitmemo.actions

import com.gitmemo.GitMemoBundle
import com.gitmemo.notes.GitNotesService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.vcs.log.VcsLogDataKeys
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/**
 * Syncs the notes refs with a remote.
 *
 * Notes are neither fetched nor pushed by git's default refspecs, so this has to be explicit. Unlike
 * the local read/write path, these are remote operations, so they go through git4idea's
 * [GitLineHandler] to inherit its authentication and progress handling — [GitCommand.FETCH] and
 * [GitCommand.PUSH] exist, unlike a `notes` command.
 */
internal abstract class GitNotesSyncAction : DumbAwareAction() {

  /** Refspec covering every notes namespace, not just the configured one. */
  protected val notesRefspec: String get() = "$NOTES_REFS:$NOTES_REFS"

  final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  final override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null && repositories(e).isNotEmpty()
  }

  final override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val repositories = repositories(e)
    if (repositories.isEmpty()) return

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, progressTitle(), true) {
      override fun run(indicator: ProgressIndicator) {
        for (repository in repositories) {
          indicator.checkCanceled()
          syncRepository(project, repository, indicator)
        }
        GitNotesService.getInstance(project).invalidateCaches()
      }
    })
  }

  protected abstract fun progressTitle(): String

  protected abstract fun failureTitle(): String

  protected abstract fun successMessage(remoteName: String): String

  /** Builds the fetch or push handler for [remote]. */
  protected abstract fun createHandler(project: Project, repository: GitRepository, remote: GitRemote): GitLineHandler

  private fun syncRepository(project: Project, repository: GitRepository, indicator: ProgressIndicator) {
    val notifier = VcsNotifier.getInstance(project)
    val remote = repository.remotes.firstOrNull { it.name == GitRemote.ORIGIN }
      ?: repository.remotes.firstOrNull()
    if (remote == null) {
      notifier.notifyError(
        "gitmemo.notes.no.remote",
        failureTitle(),
        GitMemoBundle.message("notification.noRemote", repository.root.presentableUrl),
      )
      return
    }

    val handler = createHandler(project, repository, remote)
    // Lets the authentication machinery pick the right credentials for this URL.
    handler.setUrls(remote.urls)

    val result = Git.getInstance().runCommand(handler)
    if (result.success()) {
      notifier.notifySuccess("gitmemo.notes.sync.succeeded", "", successMessage(remote.name))
    }
    else if (!indicator.isCanceled) {
      notifier.notifyError("gitmemo.notes.sync.failed", failureTitle(), result.errorOutputAsJoinedString)
    }
  }

  companion object {
    /** Glob matching every notes namespace. */
    const val NOTES_REFS: String = "refs/notes/*"
  }

  /** The repositories to sync: the selected commit's root, or every git repository in the project. */
  private fun repositories(e: AnActionEvent): List<GitRepository> {
    val project = e.project ?: return emptyList()
    val manager = GitRepositoryManager.getInstance(project)
    val selectedRoot = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits?.firstOrNull()?.root
    if (selectedRoot != null) {
      return listOfNotNull(manager.getRepositoryForRootQuick(selectedRoot))
    }
    return manager.repositories
  }
}
