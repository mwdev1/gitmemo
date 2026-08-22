package com.gitmemo.actions

import com.gitmemo.ui.GitNoteEditing
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.CommitId

/** Removes the note from the selected commit after confirmation. */
internal class DeleteGitNoteAction : GitNoteCommitAction() {
  override fun perform(project: Project, commit: CommitId) {
    GitNoteEditing.deleteNote(project, commit.root, commit.hash.asString())
  }
}
