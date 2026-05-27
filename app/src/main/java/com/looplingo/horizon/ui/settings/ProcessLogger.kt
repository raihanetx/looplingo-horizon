package com.looplingo.horizon.ui.settings

import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProcessLogger {
    private val logs = StringBuilder()
    private var logView: TextView? = null
    private var scrollView: ScrollView? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun attach(tv: TextView, sv: ScrollView) {
        logView = tv
        scrollView = sv
        logs.clear()
        tv.text = ""
    }

    fun detach() {
        logView = null
        scrollView = null
    }

    fun log(tag: String, msg: String) {
        val line = "${timeFmt.format(Date())} [$tag] $msg\n"
        logs.append(line)
        logView?.post {
            logView?.text = logs.toString()
            scrollView?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    fun getFullLog(): String = logs.toString()

    fun clear() {
        logs.clear()
        logView?.post { logView?.text = "" }
    }
}
