package com.gitmemo.notes

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic

/**
 * Notified after the plugin writes or removes a note, so every open surface can reload.
 *
 * The commit details panel does not need this: the platform's `ExternalStatusesAsyncLoader`
 * re-runs every status provider on each selection change.
 */
fun interface GitNotesChangedListener {
  fun notesChanged(root: VirtualFile, hash: String)

  companion object {
    @Topic.ProjectLevel
    @JvmField
    val TOPIC: Topic<GitNotesChangedListener> =
      Topic(GitNotesChangedListener::class.java, Topic.BroadcastDirection.NONE)
  }
}
