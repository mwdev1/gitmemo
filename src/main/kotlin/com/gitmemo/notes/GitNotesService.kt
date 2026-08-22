package com.gitmemo.notes

import com.gitmemo.settings.GitMemoSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.ThreadingAssertions
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads and writes git notes. The only entry point to `git notes` for the rest of the plugin.
 *
 * Every method blocks on a git process and must be called on a background thread.
 */
@Service(Service.Level.PROJECT)
class GitNotesService(private val project: Project) {

  private data class CacheKey(val rootPath: String, val notesRef: String)

  /** Per (root, ref) set of full commit hashes that carry a note. */
  private val annotatedCommits = ConcurrentHashMap<CacheKey, Set<String>>()

  /** The configured notes namespace, e.g. `refs/notes/commits`. */
  val notesRef: String get() = GitMemoSettings.getInstance(project).notesRef

  /**
   * Full hashes of every commit in [root] carrying a note in the configured ref.
   *
   * A single `git notes list` covers the whole repository, so callers can cheaply test many commits
   * without spawning a process per commit. Cached until a write or [invalidateCaches].
   *
   * Returns an empty set if the notes ref does not exist (git exits 0 with no output in that case).
   */
  @Throws(VcsException::class)
  fun annotatedCommits(root: VirtualFile): Set<String> {
    ThreadingAssertions.assertBackgroundThread()
    val key = CacheKey(root.path, notesRef)
    annotatedCommits[key]?.let { return it }

    val result = GitNotesCommandRunner.run(project, root, refArg(), "list")
    if (!result.success) {
      throw VcsException(errorMessage("list notes in ${root.presentableUrl}", result))
    }
    // Each line is "<noteBlobSha> <annotatedObjectSha>".
    val hashes = result.stdout.lineSequence()
      .mapNotNull { line -> line.trim().substringAfter(' ', "").trim().takeIf(String::isNotEmpty) }
      .toSet()
    annotatedCommits[key] = hashes
    return hashes
  }

  /** Whether [hash] carries a note, answered from the cached `git notes list` output. */
  @Throws(VcsException::class)
  fun hasNote(root: VirtualFile, hash: String): Boolean = hash in annotatedCommits(root)

  /**
   * The note attached to [hash], or `null` if there is none.
   *
   * One trailing newline is stripped, since git stores note bodies newline-terminated and no editor
   * should show that as an empty last line. [writeNote] restores it.
   */
  @Throws(VcsException::class)
  fun readNote(root: VirtualFile, hash: String): String? {
    ThreadingAssertions.assertBackgroundThread()
    val result = GitNotesCommandRunner.run(project, root, refArg(), "show", hash)
    if (result.success) return result.stdout.removeSuffix("\n")
    // `git notes show` exits 1 with "no note found for object <sha>." when the commit has no note.
    if (isMissingNote(result.stderr)) return null
    throw VcsException(errorMessage("read the note of $hash", result))
  }

  /** Creates or overwrites the note on [hash]. Blank [text] removes the note instead. */
  @Throws(VcsException::class)
  fun writeNote(root: VirtualFile, hash: String, text: String) {
    ThreadingAssertions.assertBackgroundThread()
    if (text.isBlank()) {
      deleteNote(root, hash)
      return
    }

    // Passed via a file rather than stdin so the encoding is unambiguous.
    val bodyFile = FileUtil.createTempFile("gitmemo-note-", ".txt", true)
    try {
      val body = if (text.endsWith("\n")) text else text + "\n"
      bodyFile.writeText(body, StandardCharsets.UTF_8)
      val result = GitNotesCommandRunner.run(
        project, root, refArg(), "add", "-f", "-F", bodyFile.absolutePath, hash
      )
      if (!result.success) throw VcsException(errorMessage("write the note of $hash", result))
    }
    finally {
      FileUtil.delete(bodyFile)
    }
    onNotesChanged(root, hash)
  }

  /** Removes the note on [hash]. A commit with no note is a no-op, reported as `false`. */
  @Throws(VcsException::class)
  fun deleteNote(root: VirtualFile, hash: String): Boolean {
    ThreadingAssertions.assertBackgroundThread()
    val result = GitNotesCommandRunner.run(project, root, refArg(), "remove", hash)
    if (!result.success) {
      // `git notes remove` exits 1 with "Object <sha> has no note" when there is nothing to remove.
      if (isMissingNote(result.stderr)) return false
      throw VcsException(errorMessage("remove the note of $hash", result))
    }
    onNotesChanged(root, hash)
    return true
  }

  /** Drops the cached `git notes list` results, e.g. after the notes ref setting changed. */
  fun invalidateCaches() {
    annotatedCommits.clear()
  }

  private fun onNotesChanged(root: VirtualFile, hash: String) {
    annotatedCommits.remove(CacheKey(root.path, notesRef))
    if (project.isDisposed) return
    project.messageBus.syncPublisher(GitNotesChangedListener.TOPIC).notesChanged(root, hash)
  }

  private fun refArg() = "--ref=$notesRef"

  private fun isMissingNote(stderr: String): Boolean =
    stderr.contains("no note found for object") || stderr.contains("has no note")

  private fun errorMessage(what: String, result: GitNotesCommandResult): String {
    val details = result.stderr.trim().ifEmpty { result.stdout.trim() }.ifEmpty { "exit code ${result.exitCode}" }
    LOG.warn("Failed to $what (exit ${result.exitCode}): $details")
    return "Cannot $what: $details"
  }

  companion object {
    private val LOG = logger<GitNotesService>()

    fun getInstance(project: Project): GitNotesService = project.service()
  }
}
