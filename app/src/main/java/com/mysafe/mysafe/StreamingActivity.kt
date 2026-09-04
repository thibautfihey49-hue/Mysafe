package com.mysafe.mysafe
import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class StreamingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "📹 Caméra" })
    }
}
