/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */
package kotlin.time

import kotlin.system.*

public actual object MonoClock : LongReadingClock(), Clock { // TODO: interface should not be required here
    override fun reading(): Long = getTimeNanos()
    override val unit: DurationUnit = DurationUnit.NANOSECONDS
}