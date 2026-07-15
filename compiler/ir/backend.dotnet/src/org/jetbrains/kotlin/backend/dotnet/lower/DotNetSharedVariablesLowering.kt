/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.dotnet.lower

import org.jetbrains.kotlin.backend.common.lower.SharedVariablesLowering
import org.jetbrains.kotlin.backend.dotnet.DotNetBackendContext

/** Rewrites mutable captures to runtime cells before common local-declaration closure conversion. */
internal class DotNetSharedVariablesLowering(context: DotNetBackendContext) :
    SharedVariablesLowering(context, skipRichCallables = false)
