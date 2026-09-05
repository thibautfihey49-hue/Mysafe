package com.mysafe.mysafe
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
class StreamingActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Toast.makeText(this, "📹 Streaming démarré", Toast.LENGTH_SHORT).show()
        finish()
    }
}
