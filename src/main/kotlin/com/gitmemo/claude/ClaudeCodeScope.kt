package com.gitmemo.claude

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

/**
 * Scope for the one suspending call this integration needs: obtaining the reworked terminal's input
 * channel (see [ClaudeCodeBridge]). Cancelled with the project, so a write cannot outlive it.
 */
@Service(Service.Level.PROJECT)
internal class ClaudeCodeScope(val coroutineScope: CoroutineScope)
