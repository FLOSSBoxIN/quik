/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.extensions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toFile

fun Uri.resourceExists(context: Context): Boolean {
    var retVal: Boolean? = null
    try {
        when (this.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                retVal = context.contentResolver.query(
                    this, null, null, null, null
                )?.use { it.moveToFirst() }
            }
            ContentResolver.SCHEME_FILE -> retVal = this.toFile().exists()
        }
    } catch (e: Exception) { /* nothing */
    }

    return retVal ?: false
}
