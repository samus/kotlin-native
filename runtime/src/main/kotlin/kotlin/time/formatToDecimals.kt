/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package kotlin.time


@SymbolName("Kotlin_Double_formatToExactDecimals")
internal actual external fun formatToExactDecimals(value: Double, decimals: Int): String

internal actual fun formatUpToDecimals(value: Double, decimals: Int): String {
    return formatToExactDecimals(value, decimals).trimEnd('0')
}

@SymbolName("Kotlin_Double_formatScientificImpl")
internal external fun formatScientificImpl(value: Double): String

internal actual fun formatScientific(value: Double): String {
    return formatScientificImpl(value).replace("e+0", "e").replace("e+", "e")
}