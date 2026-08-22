package com.gitmemo.log

import com.gitmemo.GitMemoBundle
import com.gitmemo.notes.GitNotesService
import com.gitmemo.ui.GitNoteEditing
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.VcsException
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.data.VcsCommitExternalStatus
import com.intellij.vcs.log.data.util.VcsCommitsDataLoader
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusPresentation
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusProvider
import java.awt.event.InputEvent
import javax.swing.Icon

/**
 * The note attached to a commit, or the absence of one when [note] is `null`.
 *
 * A status is reported for every commit, not just annotated ones, so the details panel can also offer
 * to create a note where there is none.
 *
 * [commit] is carried along because `getPresentation` only receives the status, and the presentation
 * needs to know which commit to edit when clicked.
 */
internal class GitNoteStatus(val commit: CommitId, val note: String?) : VcsCommitExternalStatus

/**
 * Surfaces git notes in the commit details panel of the VCS Log.
 *
 * `CommitDetailsPanel` turns each presentation into an action inside an icon-only toolbar, so all we
 * can show here is an icon whose tooltip is the note; clicking it opens the editor. The full body is
 * readable in the Git Notes tool window.
 *
 * Commits without a note get an icon too, so a note can be created from the same place it is read.
 *
 * No cache invalidation hook is needed: the platform's `ExternalStatusesAsyncLoader` re-runs the
 * loader on every commit selection change.
 */
internal class GitNoteStatusProvider : VcsCommitExternalStatusProvider<GitNoteStatus> {

  override val id: String get() = "gitmemo.note"

  override fun createLoader(project: Project): VcsCommitsDataLoader<GitNoteStatus> = Loader(project)

  override fun getPresentation(project: Project, status: GitNoteStatus): VcsCommitExternalStatusPresentation =
    Presentation(project, status)

  private class Loader(private val project: Project) : VcsCommitsDataLoader<GitNoteStatus> {
    @Volatile
    private var disposed = false

    override fun loadData(commits: List<CommitId>, onChange: (Map<CommitId, GitNoteStatus>) -> Unit) {
      if (commits.isEmpty()) return
      ApplicationManager.getApplication().executeOnPooledThread {
        if (disposed) return@executeOnPooledThread
        val statuses = try {
          load(commits)
        }
        catch (e: VcsException) {
          LOG.warn("Cannot load git notes for ${commits.size} commit(s)", e)
          return@executeOnPooledThread
        }
        if (!disposed && statuses.isNotEmpty()) onChange(statuses)
      }
    }

    private fun load(commits: List<CommitId>): Map<CommitId, GitNoteStatus> {
      val service = GitNotesService.getInstance(project)
      val result = HashMap<CommitId, GitNoteStatus>()
      // One `git notes list` per root answers "has a note?" for every commit, so bodies are only
      // read for the commits that actually carry one.
      for ((root, rootCommits) in commits.groupBy { it.root }) {
        val annotated = service.annotatedCommits(root)
        for (commit in rootCommits) {
          if (disposed) return result
          val hash = commit.hash.asString()
          val note = if (hash in annotated) service.readNote(root, hash) else null
          result[commit] = GitNoteStatus(commit, note)
        }
      }
      return result
    }

    override fun dispose() {
      disposed = true
    }
  }

  private class Presentation(
    private val project: Project,
    private val status: GitNoteStatus,
  ) : VcsCommitExternalStatusPresentation.Clickable {

    /** A distinct icon for "no note yet", so the toolbar reads as "add one" rather than "read one". */
    override val icon: Icon
      get() = if (status.note == null) AllIcons.General.Add else AllIcons.General.Balloon

    override val text: String
      get() = status.note?.let {
        GitMemoBundle.message("status.presentation.text", StringUtil.shortenTextWithEllipsis(it, 120, 0, true))
      } ?: GitMemoBundle.message("status.presentation.addNote")

    override fun onClick(e: InputEvent?): Boolean {
      // The body is already known — empty when there is no note — so the dialog opens without a read.
      GitNoteEditing.editNote(project, status.commit.root, status.commit.hash.asString(), status.note.orEmpty())
      return true
    }
  }

  private companion object {
    private val LOG = logger<GitNoteStatusProvider>()
  }
}
