package com.mysafe.mysafe
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class StreamingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "📹 Caméra en cours de développement..."
        tv.gravity = android.view.Gravity.CENTER
        setContentView(tv)
    }
}
