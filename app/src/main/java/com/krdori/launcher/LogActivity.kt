package com.krdori.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.krdori.launcher.databinding.ActivityLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows the tail of the server log, refreshing every second. */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private var shown: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val text = withContext(Dispatchers.IO) { ServerFiles.readLogTail(this@LogActivity) }
                    if (text != shown) show(text)
                    delay(1000)
                }
            }
        }
    }

    private fun show(text: String) {
        val scroll = binding.logScroll
        val atBottom = shown == null ||
            scroll.scrollY + scroll.height >= binding.logText.height - scroll.paddingBottom - 48
        shown = text
        binding.logText.text = text.ifEmpty { getString(R.string.log_empty) }
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        R.id.action_copy -> {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("krdori log", shown.orEmpty()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_clear -> {
            ServerFiles.clearLog(this)
            shown = null
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
