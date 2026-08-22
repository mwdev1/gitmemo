package com.gitmemo.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.VcsLogDataKeys

/**
 * Base class for log actions that operate on the note of exactly one commit.
 *
 * Notes are per-commit and the editor shows a single body, so a multi-commit selection disables the
 * action rather than silently picking one.
 */
internal abstract class GitNoteCommitAction : DumbAwareAction() {

  final override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  final override fun update(e: AnActionEvent) {
    val project = e.project
    val commit = singleCommit(e)
    e.presentation.isEnabledAndVisible = project != null && commit != null
    if (project != null && commit != null) updatePresentation(e, project, commit)
  }

  final override fun actionPerformed(e: AnActionEvent) {
    val project = e.getData(CommonDataKeys.PROJECT) ?: return
    val commit = singleCommit(e) ?: return
    perform(project, commit)
  }

  /** Hook for subclasses that need to refine the presentation; called only when a commit is selected. */
  protected open fun updatePresentation(e: AnActionEvent, project: Project, commit: CommitId) = Unit

  protected abstract fun perform(project: Project, commit: CommitId)

  private fun singleCommit(e: AnActionEvent): CommitId? =
    e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits?.singleOrNull()
}
