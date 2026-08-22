package com.gitmemo.actions

import com.gitmemo.GitMemoBundle
import com.intellij.openapi.project.Project
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository

/** Fetches the notes refs from the remote, which git's default refspec never does. */
internal class FetchGitNotesAction : GitNotesSyncAction() {

  override fun progressTitle(): String = GitMemoBundle.message("progress.fetchingNotes")

  override fun failureTitle(): String = GitMemoBundle.message("notification.fetchFailed")

  override fun successMessage(remoteName: String): String =
    GitMemoBundle.message("notification.fetchSucceeded", NOTES_REFS, remoteName)

  override fun createHandler(project: Project, repository: GitRepository, remote: GitRemote): GitLineHandler =
    GitLineHandler(project, repository.root, GitCommand.FETCH).apply {
      // Forced, since a note is mutable and its ref moves non-fast-forward on every edit.
      addParameters(remote.name, "+$notesRefspec")
    }
}
