package com.gitmemo.actions

import com.gitmemo.GitMemoBundle
import com.intellij.openapi.project.Project
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository

/** Pushes the notes refs to the remote, which a normal push never does. */
internal class PushGitNotesAction : GitNotesSyncAction() {

  override fun progressTitle(): String = GitMemoBundle.message("progress.pushingNotes")

  override fun failureTitle(): String = GitMemoBundle.message("notification.pushFailed")

  override fun successMessage(remoteName: String): String =
    GitMemoBundle.message("notification.pushSucceeded", NOTES_REFS, remoteName)

  override fun createHandler(project: Project, repository: GitRepository, remote: GitRemote): GitLineHandler =
    GitLineHandler(project, repository.root, GitCommand.PUSH).apply {
      addParameters(remote.name, notesRefspec)
    }
}
