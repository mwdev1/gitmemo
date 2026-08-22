package com.gitmemo.actions

import com.gitmemo.ui.GitNoteEditing
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.CommitId

/** Opens the note editor for the selected commit, creating the note if there is none. */
internal class EditGitNoteAction : GitNoteCommitAction() {
  override fun perform(project: Project, commit: CommitId) {
    GitNoteEditing.editNote(project, commit.root, commit.hash.asString())
  }
}
