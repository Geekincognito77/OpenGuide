package com.openguide.tv

import android.app.Application
import com.openguide.tv.data.Repo

class OpenGuideApp : Application() {
    val repo: Repo by lazy { Repo(this) }
}
