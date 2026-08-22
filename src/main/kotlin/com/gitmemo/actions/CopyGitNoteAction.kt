package com.gitmemo.actions

import com.gitmemo.GitMemoBundle
import com.gitmemo.notes.GitNotesService
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.vcs.log.CommitId
import java.awt.datatransfer.StringSelection

/** Copies the note of the selected commit to the clipboard. */
internal class CopyGitNoteAction : GitNoteCommitAction() {
  override fun perform(project: Project, commit: CommitId) {
    val title = GitMemoBundle.message("progress.loadingNote")
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
      override fun run(indicator: ProgressIndicator) {
        val note = try {
          GitNotesService.getInstance(project).readNote(commit.root, commit.hash.asString())
        }
        catch (e: VcsException) {
          VcsNotifier.getInstance(project)
            .notifyError("gitmemo.note.read.failed", GitMemoBundle.message("notification.readFailed"), e.message.orEmpty())
          return
        }
        if (note != null) CopyPasteManager.getInstance().setContents(StringSelection(note))
      }
    })
  }
}
