package com.harsraj.inprep

import android.app.Application
import com.harsraj.inprep.di.FakeApplicationContainer

class InPrepApplication : Application() {
    val container: FakeApplicationContainer by lazy { FakeApplicationContainer() }
}
