package com.audiosplit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible stop between the Quick Settings tile and a running session. It exists only to
 * host the permission and consent dialogs, which need an activity, and finishes as soon as
 * they are answered, leaving the user where they were: in their video app.
 */
class StartMirrorActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (MirrorLauncher.canCapture(this)) {
            requestConsent()
        } else {
            MirrorState.error(MirrorLauncher.MIC_NEEDED)
            finish()
        }
    }

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            MirrorLauncher.start(this, result.resultCode, data)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated with a dialog already up: its result is still delivered here.
        if (savedInstanceState != null) return

        when {
            MirrorState.status.value.running -> finish()

            // Nothing to copy to, or never set up: that needs the screen, not a dialog.
            MirrorLauncher.resolveTarget(this) == null -> {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }

            else -> {
                val missing = MirrorLauncher.missingPermissions(this)
                if (missing.isEmpty()) {
                    requestConsent()
                } else {
                    // The consent dialog has to wait until this one is answered, or it never
                    // appears.
                    permissions.launch(missing.toTypedArray())
                }
            }
        }
    }

    private fun requestConsent() = consent.launch(MirrorLauncher.captureIntent(this))
}
